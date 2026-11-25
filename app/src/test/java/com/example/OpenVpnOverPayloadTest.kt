package com.example

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Looper
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.config.ConfigFileCodec
import com.example.data.AmktDatabase
import com.example.data.AppLogStore
import com.example.data.ConnectionMode
import com.example.data.EffectiveConnectionCombiner
import com.example.data.NetworkConfigEntity
import com.example.data.NetworkServerRepository
import com.example.data.PayloadMode
import com.example.data.RemoteServerListRepository
import com.example.data.ServerAccountEntity
import com.example.data.ServerProfileEntity
import com.example.data.ServerProfileRepository
import com.example.membership.MembershipRepository
import com.example.openvpn.IcsOpenVpn3TunnelEngine
import com.example.openvpn.OpenVpnClientHandle
import com.example.openvpn.OpenVpnPayloadForwarder
import com.example.openvpn.OpenVpnSessionFactory
import com.example.openvpn.OpenVpnTunnelState
import com.example.openvpn.OvpnConfigParser
import com.example.openvpn.OvpnSecretStore
import com.example.ssh.LocalSocks5Proxy
import com.example.ssh.SshTrafficStats
import com.example.ui.AmktViewModel
import com.example.ui.home.HomeScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.vpn.VpnEngineController
import com.example.vpn.VpnPreferencesRepository
import com.example.vpn.VpnTrafficStats
import com.example.vpn.VpnTunnelState
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenVpnOverPayloadTest {

  @get:Rule val composeRule = createComposeRule()

  private lateinit var context: Context
  private lateinit var db: AmktDatabase
  private lateinit var remoteRepo: RemoteServerListRepository
  private lateinit var splitRepo: NetworkServerRepository
  private lateinit var legacyRepo: ServerProfileRepository

  private val tcpOvpn443 =
    """
    client
    dev tun
    proto tcp
    # remote commented.example.org 1194
    remote ovpn-tcp-443.example.net 443
    resolv-retry infinite
    nobind
    cipher AES-256-GCM
    auth SHA256
    <ca>
    -----BEGIN CERTIFICATE-----
    MIIFakeTcpCaSecret123456
    -----END CERTIFICATE-----
    </ca>
    """.trimIndent()

  private val udpOvpn1194 =
    """
    client
    dev tun
    proto udp
    remote ovpn-udp-direct.example.net 1194
    resolv-retry infinite
    nobind
    cipher AES-256-GCM
    <ca>
    -----BEGIN CERTIFICATE-----
    MIIFakeUdpCaSecret987654
    -----END CERTIFICATE-----
    </ca>
    """.trimIndent()

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    db = Room.inMemoryDatabaseBuilder(context, AmktDatabase::class.java).allowMainThreadQueries().build()
    val prefsFile = File(context.cacheDir, "ovpn_payload_test_${UUID.randomUUID()}.preferences_pb")
    val store = PreferenceDataStoreFactory.create { prefsFile }
    remoteRepo = RemoteServerListRepository(context, dataStore = store)
    splitRepo = NetworkServerRepository(db.networkConfigDao(), db.serverAccountDao(), remoteRepo)
    legacyRepo = ServerProfileRepository(db.serverProfileDao(), remoteRepo)
  }

  @After
  fun tearDown() {
    db.close()
  }

  @Test
  fun rewriteRemoteForLocalForwardRewritesOnlyRemoteLinesAndLeavesEverythingElseUntouched() {
    val rewritten =
      OvpnConfigParser.rewriteRemoteForLocalForward(
        rawOvpn = tcpOvpn443,
        localHost = "127.0.0.1",
        localPort = 11940,
      )

    assertTrue("Must rewrite active remote line to 127.0.0.1 11940", rewritten.contains("\nremote 127.0.0.1 11940\n"))
    assertFalse("Original remote host must not remain in rewritten active line", rewritten.contains("remote ovpn-tcp-443.example.net 443"))
    assertTrue("Commented remote line must stay untouched", rewritten.contains("# remote commented.example.org 1194"))
    assertTrue("proto tcp must remain untouched", rewritten.contains("\nproto tcp\n"))
    assertTrue("Inline CA block must stay untouched", rewritten.contains("MIIFakeTcpCaSecret123456"))

    // Per-remote proto token preserved when present (and tcp6 mapped safely to tcp for IPv4 loopback)
    val perRemoteProto =
      """
      client
      dev tun
      remote edge1.example.com 443 tcp-client
      remote edge2.example.com 443 tcp6
      """.trimIndent()
    val rewrittenPerRemote = OvpnConfigParser.rewriteRemoteForLocalForward(perRemoteProto, "127.0.0.1", 11940)
    assertTrue(rewrittenPerRemote.contains("remote 127.0.0.1 11940 tcp-client"))
    assertTrue(rewrittenPerRemote.contains("remote 127.0.0.1 11940 tcp"))
    assertFalse(rewrittenPerRemote.contains("edge1.example.com"))
    assertFalse(rewrittenPerRemote.contains("edge2.example.com"))
  }

  @Test
  fun localForwardPortDoesNotCollideWithSocks5OrHttpProxyListeners() {
    assertEquals(11940, OpenVpnPayloadForwarder.DEFAULT_LOCAL_PORT)
    assertNotEquals(LocalSocks5Proxy.DEFAULT_PORT, OpenVpnPayloadForwarder.DEFAULT_LOCAL_PORT)
    assertNotEquals(1080, OpenVpnPayloadForwarder.DEFAULT_LOCAL_PORT)
    assertNotEquals(8989, OpenVpnPayloadForwarder.DEFAULT_LOCAL_PORT)
    assertFalse(OpenVpnPayloadForwarder.DEFAULT_LOCAL_PORT in OpenVpnPayloadForwarder.RESERVED_PORTS)
    assertTrue(1080 in OpenVpnPayloadForwarder.RESERVED_PORTS)
    assertTrue(8989 in OpenVpnPayloadForwarder.RESERVED_PORTS)

    val forwarder = OpenVpnPayloadForwarder(AppLogStore())
    val dummyNet = NetworkConfigEntity(name = "DirectNet", payloadMode = PayloadMode.DIRECT.code)
    assertTrue(
      "Must refuse port 1080 collision",
      forwarder.start(dummyNet, "127.0.0.1", 443, preferredPort = 1080).isFailure,
    )
    assertTrue(
      "Must refuse port 8989 collision",
      forwarder.start(dummyNet, "127.0.0.1", 443, preferredPort = 8989).isFailure,
    )
  }

  @Test
  fun networkPlusTcpOvpnConnectsThroughLocalhostForwardAndLeavesStoredOvpnUnchanged() = runBlocking {
    val receivedPayload = AtomicReference("")
    val payloadProxy = MockPayloadRelayProxy(statusLine = "HTTP/1.1 101 Switching Protocols")
    val proxyPort = payloadProxy.start()

    val logStore = AppLogStore()
    val capturedEffectiveConfig = AtomicReference<String?>(null)
    val relayedEchoReply = AtomicReference<String?>(null)

    try {
      // Save Network (HTTP proxy + [split]/[host]/[ua] payload) and TCP OpenVPN server in Room
      val netId =
        splitRepo.saveNetwork(
          NetworkConfigEntity(
            name = "Atom-Zero-WS",
            payloadMode = PayloadMode.HTTP_PAYLOAD.code,
            payloadTemplate =
              "GET / HTTP/1.1[crlf]Host: bug.carrier.example[crlf][crlf]" +
                "[split]GET /ws HTTP/1.1[crlf]Host: [host]:[port][crlf]User-Agent: [ua][crlf]Upgrade: websocket[crlf]Connection: Upgrade[crlf][crlf]",
            httpProxyHost = "127.0.0.1",
            httpProxyPort = proxyPort,
            sniHost = "bug.carrier.example",
          )
        )
      val savedNet = splitRepo.getNetworkById(netId)!!

      val savedSrv =
        splitRepo.importOvpnProfile(
          rawOvpnText = tcpOvpn443,
          fileName = "Singapore-TCP443.ovpn",
          selectAfterImport = true,
        ).getOrThrow()

      val combinedProfile = EffectiveConnectionCombiner.combine(savedNet, savedSrv)

      val engine =
        IcsOpenVpn3TunnelEngine(
          logStore = logStore,
          forwarderProvider = { OpenVpnPayloadForwarder(logStore, splitDelayMs = 10L, handshakeTimeoutMs = 3_000) },
          nativeLibraryLoader = { true },
          sessionFactory =
            OpenVpnSessionFactory { _, _, effectiveOvpn, _, _, _, onConnected, onError, _ ->
              capturedEffectiveConfig.set(effectiveOvpn)
              val stopLatch = CountDownLatch(1)
              object : OpenVpnClientHandle {
                override fun prepareConfig(): String? = null

                override fun connectBlocking(): Pair<Boolean, String?> {
                  // Parse the connect-time rewritten remote line and actually connect TCP through
                  // our local 127.0.0.1:11940 forwarder to verify live payload-tunneled traffic!
                  val parsedRuntime = OvpnConfigParser.parse(effectiveOvpn).getOrThrow()
                  try {
                    Socket(parsedRuntime.primaryRemoteHost, parsedRuntime.primaryRemotePort).use { localSock ->
                      localSock.soTimeout = 3_000
                      val out = localSock.getOutputStream()
                      val inp = localSock.getInputStream()
                      out.write("OVPN-CLIENT-HELLO".toByteArray(StandardCharsets.ISO_8859_1))
                      out.flush()
                      val buf = ByteArray(64)
                      val n = inp.read(buf)
                      if (n > 0) {
                        relayedEchoReply.set(String(buf, 0, n, StandardCharsets.ISO_8859_1))
                      }
                      onConnected()
                      stopLatch.await(5, TimeUnit.SECONDS)
                    }
                    return false to null
                  } catch (e: IOException) {
                    onError(e.message ?: "io error")
                    return true to e.message
                  }
                }

                override fun stop() {
                  stopLatch.countDown()
                }

                override fun reconnect(seconds: Int) = Unit

                override fun readTrafficStats(): SshTrafficStats = SshTrafficStats(128L, 256L, 1)

                override fun release() = Unit
              }
            },
          connectTimeoutMs = 5_000L,
        )

      val result =
        engine.connect(
          vpnService = VpnService(),
          profile = combinedProfile,
          network = savedNet,
        )
      assertTrue("Combined Network + TCP OpenVPN must connect: ${result.exceptionOrNull()?.message}", result.isSuccess)
      assertTrue(engine.state.value is OpenVpnTunnelState.Connected)

      // 1. Verify OpenVPN engine received ONLY the remote line rewritten to 127.0.0.1:<port> (11940)
      val runtimeOvpn = capturedEffectiveConfig.get()
      assertNotNull(runtimeOvpn)
      val parsedRuntime = OvpnConfigParser.parse(runtimeOvpn!!).getOrThrow()
      assertEquals("127.0.0.1", parsedRuntime.primaryRemoteHost)
      assertEquals(OpenVpnPayloadForwarder.DEFAULT_LOCAL_PORT, parsedRuntime.primaryRemotePort)
      assertEquals("tcp", parsedRuntime.protocol)
      assertFalse("Original remote host must not appear in active runtime remote line", runtimeOvpn.contains("remote ovpn-tcp-443.example.net 443"))

      // 2. Verify payload proxy received substituted [host] = OpenVPN server host, [port] = 443, [ua]
      val capturedPayload = payloadProxy.capturedHandshake.get()
      receivedPayload.set(capturedPayload)
      assertTrue("Payload must substitute [host]=ovpn-tcp-443.example.net", capturedPayload.contains("Host: ovpn-tcp-443.example.net:443"))
      assertTrue("Payload must substitute [ua]", capturedPayload.contains("User-Agent: Mozilla/5.0"))
      assertTrue("Payload must send split request", capturedPayload.contains("Host: bug.carrier.example"))

      // 3. Verify live OpenVPN TCP bytes flowed end-to-end through 127.0.0.1:11940 -> payload proxy
      assertEquals("ECHO:OVPN-CLIENT-HELLO", relayedEchoReply.get())

      engine.disconnect()

      // 4. Verify stored .ovpn in Room is 100% unchanged afterwards!
      val reloadedSrv = splitRepo.getServerById(savedSrv.id)!!
      val storedOvpnAfter = OvpnSecretStore.decrypt(reloadedSrv.ovpnConfigEncrypted)
      assertEquals(tcpOvpn443, storedOvpnAfter)
      assertTrue(storedOvpnAfter.contains("remote ovpn-tcp-443.example.net 443"))
      assertFalse(storedOvpnAfter.contains("127.0.0.1"))
      assertFalse(storedOvpnAfter.contains("11940"))

      // 5. Verify ZERO sensitive details leaked into AppLogStore
      val allLogs = logStore.logs.value.joinToString("\n") { it.message }
      assertFalse("Logs must NOT leak local forward port 11940", allLogs.contains("11940"))
      assertFalse("Logs must NOT leak OpenVPN server host", allLogs.contains("ovpn-tcp-443.example.net"))
      assertFalse("Logs must NOT leak proxy host", allLogs.contains("127.0.0.1:$proxyPort"))
      assertFalse("Logs must NOT leak payload template", allLogs.contains("bug.carrier.example"))
      assertFalse("Logs must NOT leak inline CA cert", allLogs.contains("MIIFakeTcpCaSecret123456"))
    } finally {
      payloadProxy.stop()
    }
  }

  @Test
  fun networkPlusUdpOvpnShowsTcpOnlyMessageAndDoesNotConnect() = runBlocking {
    val logStore = AppLogStore()
    val fakeVpn = CapturingVpnController()
    val viewModel =
      AmktViewModel(
        repository = legacyRepo,
        membershipRepository = MembershipRepository(context),
        vpnPreferencesRepository = VpnPreferencesRepository(context),
        vpnController = fakeVpn,
        configCodec = ConfigFileCodec(),
        logStore = logStore,
        networkServerRepository = splitRepo,
      )

    val netCollector = launch(Dispatchers.Unconfined) { viewModel.selectedNetwork.collect {} }
    val srvCollector = launch(Dispatchers.Unconfined) { viewModel.selectedServer.collect {} }
    try {
      val netId =
        splitRepo.saveNetwork(
          NetworkConfigEntity(
            name = "Mytel-Payload",
            payloadMode = PayloadMode.HTTP_PAYLOAD.code,
            payloadTemplate = "GET / HTTP/1.1[crlf]Host: [host][crlf][crlf]",
            httpProxyHost = "10.0.0.2",
            httpProxyPort = 8080,
          ),
          selectAfterSave = true,
        )
      val udpSrv =
        splitRepo.importOvpnProfile(
          rawOvpnText = udpOvpn1194,
          fileName = "Japan-UDP.ovpn",
          selectAfterImport = true,
        ).getOrThrow()

      awaitCondition {
        viewModel.selectedNetwork.value?.id == netId &&
          viewModel.selectedServer.value?.id == udpSrv.id
      }

      // 1. Pre-flight in ViewModel refuses UDP + Network with exact user-facing message
      val validated = viewModel.validateSelectionForVpnConnect()
      assertNull("UDP OpenVPN with a selected Network must NOT connect", validated)
      assertEquals(OpenVpnPayloadForwarder.OVPN_TCP_ONLY_ERROR, viewModel.snackbarMessage.value)
      assertEquals(0, fakeVpn.startCalls)

      // 2. Engine-level defense-in-depth also refuses UDP + Network without starting OpenVPN
      val openVpnStarts = AtomicInteger(0)
      val engine =
        IcsOpenVpn3TunnelEngine(
          logStore = logStore,
          forwarderProvider = { OpenVpnPayloadForwarder(logStore) },
          nativeLibraryLoader = { true },
          sessionFactory =
            OpenVpnSessionFactory { _, _, _, _, _, _, _, _, _ ->
              openVpnStarts.incrementAndGet()
              error("OpenVPN engine must never start for UDP + Network")
            },
        )
      val net = splitRepo.getNetworkById(netId)!!
      val combined = EffectiveConnectionCombiner.combine(net, udpSrv)
      val engineRes = engine.connect(VpnService(), combined, network = net)
      assertTrue(engineRes.isFailure)
      assertEquals(OpenVpnPayloadForwarder.OVPN_TCP_ONLY_ERROR, engineRes.exceptionOrNull()?.message)
      assertEquals(0, openVpnStarts.get())

      // Stored .ovpn remains UDP (never silently rewritten to tcp)
      val decryptedAfter = OvpnSecretStore.decrypt(splitRepo.getServerById(udpSrv.id)!!.ovpnConfigEncrypted)
      assertTrue(decryptedAfter.contains("proto udp"))
      assertFalse(decryptedAfter.contains("proto tcp"))
    } finally {
      netCollector.cancel()
      srvCollector.cancel()
    }
  }

  @Test
  fun noNetworkPlusOvpnConnectsDirectAsBeforeForBothUdpAndTcp() = runBlocking {
    val logStore = AppLogStore()
    val forwarderCreated = AtomicInteger(0)
    val seenConfigs = CopyOnWriteArrayList<String>()

    val engine =
      IcsOpenVpn3TunnelEngine(
        logStore = logStore,
        forwarderProvider = {
          forwarderCreated.incrementAndGet()
          OpenVpnPayloadForwarder(logStore)
        },
        nativeLibraryLoader = { true },
        sessionFactory =
          OpenVpnSessionFactory { _, _, effectiveOvpn, _, _, _, onConnected, _, _ ->
            seenConfigs.add(effectiveOvpn)
            val latch = CountDownLatch(1)
            object : OpenVpnClientHandle {
              override fun prepareConfig(): String? = null
              override fun connectBlocking(): Pair<Boolean, String?> {
                onConnected()
                latch.await(5, TimeUnit.SECONDS)
                return false to null
              }
              override fun stop() = latch.countDown()
              override fun reconnect(seconds: Int) = Unit
              override fun readTrafficStats(): SshTrafficStats? = null
              override fun release() = Unit
            }
          },
      )

    // 1. UDP direct (no network)
    val udpProfile =
      ServerProfileEntity(
        id = 1L,
        name = "Direct UDP",
        mode = ConnectionMode.OPENVPN.code,
        ovpnConfigEncrypted = OvpnSecretStore.encrypt(udpOvpn1194),
      )
    val resUdp = engine.connect(VpnService(), udpProfile, network = null)
    assertTrue("Direct UDP OpenVPN must succeed without network", resUdp.isSuccess)
    engine.disconnect()

    // 2. TCP direct (no network)
    val tcpProfile =
      ServerProfileEntity(
        id = 2L,
        name = "Direct TCP",
        mode = ConnectionMode.OPENVPN.code,
        ovpnConfigEncrypted = OvpnSecretStore.encrypt(tcpOvpn443),
      )
    val resTcp = engine.connect(VpnService(), tcpProfile, network = null)
    assertTrue("Direct TCP OpenVPN must succeed without network", resTcp.isSuccess)
    engine.disconnect()

    assertEquals("No payload forwarder may be started when network == null", 0, forwarderCreated.get())
    assertEquals(2, seenConfigs.size)
    assertTrue(seenConfigs[0].contains("remote ovpn-udp-direct.example.net 1194"))
    assertTrue(seenConfigs[0].contains("proto udp"))
    assertTrue(seenConfigs[1].contains("remote ovpn-tcp-443.example.net 443"))
    assertTrue(seenConfigs[1].contains("proto tcp"))
  }

  @Test
  fun payloadFailureStopsBeforeStartingOpenVpnAndSurfacesPayloadError() = runBlocking {
    val failingProxy = MockPayloadRelayProxy(statusLine = "HTTP/1.1 403 Forbidden", closeImmediately = true)
    val proxyPort = failingProxy.start()

    val openVpnStarts = AtomicInteger(0)
    val logStore = AppLogStore()

    try {
      val net =
        NetworkConfigEntity(
          id = 9L,
          name = "Blocked-Proxy",
          payloadMode = PayloadMode.HTTP_PAYLOAD.code,
          payloadTemplate = "CONNECT [host]:[port] HTTP/1.1[crlf]Host: [host][crlf][crlf]",
          httpProxyHost = "127.0.0.1",
          httpProxyPort = proxyPort,
        )
      val srv =
        ServerAccountEntity(
          id = 10L,
          name = "TCP-443",
          connectionMode = ConnectionMode.OPENVPN.code,
          ovpnConfigEncrypted = OvpnSecretStore.encrypt(tcpOvpn443),
        )
      val combined = EffectiveConnectionCombiner.combine(net, srv)

      val engine =
        IcsOpenVpn3TunnelEngine(
          logStore = logStore,
          forwarderProvider = { OpenVpnPayloadForwarder(logStore, handshakeTimeoutMs = 2_000) },
          nativeLibraryLoader = { true },
          sessionFactory =
            OpenVpnSessionFactory { _, _, _, _, _, _, _, _, _ ->
              openVpnStarts.incrementAndGet()
              error("OpenVPN must NOT start when payload tunnel fails")
            },
        )

      val res = engine.connect(VpnService(), combined, network = net)
      assertTrue("Must fail when payload proxy returns 403", res.isFailure)
      assertEquals("OpenVPN engine must NOT be started on payload failure", 0, openVpnStarts.get())
      val state = engine.state.value
      assertTrue("Engine state must be Error", state is OpenVpnTunnelState.Error)
      val errMsg = (state as OpenVpnTunnelState.Error).reason
      assertTrue("Error should describe HTTP 403 payload rejection: $errMsg", errMsg.contains("403"))
    } finally {
      failingProxy.stop()
    }
  }

  @Test
  fun killSwitchAndAutoReconnectApplyToCombinedPayloadOpenVpnAndReconnectPayloadFirst() = runBlocking {
    val logStore = AppLogStore()
    val fakeVpn = CapturingVpnController()
    val vpnPrefs = VpnPreferencesRepository(context)
    vpnPrefs.setKillSwitchEnabled(true)
    vpnPrefs.setAutoReconnectEnabled(true)

    val viewModel =
      AmktViewModel(
        repository = legacyRepo,
        membershipRepository = MembershipRepository(context),
        vpnPreferencesRepository = vpnPrefs,
        vpnController = fakeVpn,
        configCodec = ConfigFileCodec(),
        logStore = logStore,
        networkServerRepository = splitRepo,
      )

    val netCollector = launch(Dispatchers.Unconfined) { viewModel.selectedNetwork.collect {} }
    val srvCollector = launch(Dispatchers.Unconfined) { viewModel.selectedServer.collect {} }
    val settingsCollector = launch(Dispatchers.Unconfined) { viewModel.vpnSettings.collect {} }

    val payloadProxy = MockPayloadRelayProxy(statusLine = "HTTP/1.1 200 Connection Established")
    val proxyPort = payloadProxy.start()

    try {
      val netId =
        splitRepo.saveNetwork(
          NetworkConfigEntity(
            name = "Ooredoo-TCP",
            payloadMode = PayloadMode.HTTP_PAYLOAD.code,
            payloadTemplate = "CONNECT [host]:[port] HTTP/1.1[crlf]Host: [host][crlf][crlf]",
            httpProxyHost = "127.0.0.1",
            httpProxyPort = proxyPort,
          ),
          selectAfterSave = true,
        )
      val srv =
        splitRepo.importOvpnProfile(
          rawOvpnText = tcpOvpn443,
          fileName = "SG-TCP443.ovpn",
          selectAfterImport = true,
        ).getOrThrow()

      awaitCondition {
        viewModel.selectedNetwork.value?.id == netId &&
          viewModel.selectedServer.value?.id == srv.id &&
          viewModel.vpnSettings.value.killSwitchEnabled &&
          viewModel.vpnSettings.value.autoReconnectEnabled
      }

      // 1. ViewModel validates combined mode and forwards Kill Switch + Auto-Reconnect flags
      val validated = viewModel.validateSelectionForVpnConnect()
      assertNotNull("Network + TCP OpenVPN must pass pre-flight validation", validated)
      val (vNet, vSrv, effective) = validated!!
      assertEquals(netId, vNet?.id)
      assertEquals(srv.id, vSrv.id)
      assertEquals(ConnectionMode.OPENVPN, effective.connectionMode)

      viewModel.startCombinedVpnAfterConsent(vNet, vSrv, effective)
      assertEquals(1, fakeVpn.startCalls)
      assertEquals(netId, fakeVpn.lastNetwork?.id)
      assertTrue("Kill Switch must apply to combined OpenVPN", fakeVpn.lastKillSwitch)
      assertTrue("Auto-Reconnect must apply to combined OpenVPN", fakeVpn.lastAutoReconnect)

      // 2. Verify drop detection + reconnect order: payload tunnel established FIRST, then OpenVPN
      val orderLog = CopyOnWriteArrayList<String>()
      val engine =
        IcsOpenVpn3TunnelEngine(
          logStore = logStore,
          forwarderProvider = {
            orderLog.add("payload_start")
            OpenVpnPayloadForwarder(logStore, handshakeTimeoutMs = 3_000)
          },
          nativeLibraryLoader = { true },
          sessionFactory =
            OpenVpnSessionFactory { _, _, effectiveOvpn, _, _, _, onConnected, _, _ ->
              orderLog.add("openvpn_start")
              val parsed = OvpnConfigParser.parse(effectiveOvpn).getOrThrow()
              assertEquals("127.0.0.1", parsed.primaryRemoteHost)
              assertEquals(11940, parsed.primaryRemotePort)
              val latch = CountDownLatch(1)
              object : OpenVpnClientHandle {
                override fun prepareConfig(): String? = null
                override fun connectBlocking(): Pair<Boolean, String?> {
                  onConnected()
                  latch.await(5, TimeUnit.SECONDS)
                  return false to null
                }
                override fun stop() = latch.countDown()
                override fun reconnect(seconds: Int) = Unit
                override fun readTrafficStats(): SshTrafficStats? = null
                override fun release() = Unit
              }
            },
        )

      val net = splitRepo.getNetworkById(netId)!!
      assertTrue(engine.connect(VpnService(), effective, network = net).isSuccess)
      assertTrue(engine.state.value is OpenVpnTunnelState.Connected)

      // Simulate physical network loss -> combined engine drops to Error so AmktVpnService engages
      // Kill Switch and runs Auto-Reconnect
      engine.notifyNetworkChange()
      assertTrue(
        "Network drop in combined mode must transition engine to Error for Kill Switch & Auto-Reconnect",
        engine.state.value is OpenVpnTunnelState.Error,
      )

      // Simulate AmktVpnService reconnect attempt -> must establish payload FIRST, then OpenVPN
      assertTrue(engine.connect(VpnService(), effective, network = net).isSuccess)
      assertTrue(engine.state.value is OpenVpnTunnelState.Connected)
      assertEquals(
        listOf("payload_start", "openvpn_start", "payload_start", "openvpn_start"),
        orderLog.toList(),
      )
      engine.disconnect()
    } finally {
      netCollector.cancel()
      srvCollector.cancel()
      settingsCollector.cancel()
      payloadProxy.stop()
    }
  }

  @Test
  fun homeScreenCombinedOpenVpnLockDownShowsNameOnlyWithZeroTechnicalLeak() {
    val net =
      NetworkConfigEntity(
        id = 11L,
        name = "DataNoPro",
        payloadMode = PayloadMode.HTTP_PAYLOAD.code,
        payloadTemplate = "GET /secret-path HTTP/1.1[crlf]Host: [host][crlf][crlf]",
        httpProxyHost = "proxy-hidden.example.org",
        httpProxyPort = 8080,
        sniHost = "sni-hidden.example.org",
      )
    val srv =
      ServerAccountEntity(
        id = 22L,
        name = "Singapore VIP",
        connectionMode = ConnectionMode.OPENVPN.code,
        expiryDate = "2099-12-31",
        region = "SG",
        ovpnConfigEncrypted = OvpnSecretStore.encrypt(tcpOvpn443),
      )

    composeRule.setContent {
      MyApplicationTheme(darkTheme = true) {
        HomeScreen(
          networks = listOf(net),
          servers = listOf(srv),
          selectedNetwork = net,
          selectedServer = srv,
          vpnState =
            VpnTunnelState.Connected(
              profileName = srv.name,
              targetHost = "",
              targetPort = 0,
              localSocksHost = "",
              localSocksPort = 0,
              networkName = net.name,
              serverName = srv.name,
            ),
          trafficStats = VpnTrafficStats(bytesUp = 512L, bytesDown = 2048L),
          onConnectClick = {},
          onReconnectClick = {},
          onSelectNetwork = {},
          onSelectServer = {},
          onOpenNetworksClick = {},
          onOpenServersClick = {},
        )
      }
    }
    composeRule.waitForIdle()

    composeRule.onNodeWithText("DataNoPro").assertIsDisplayed()
    composeRule.onNodeWithText("Singapore VIP").assertIsDisplayed()
    composeRule.onNodeWithTag("home_server_openvpn_badge", useUnmergedTree = true).assertIsDisplayed()

    val forbidden =
      listOf(
        "11940",
        "127.0.0.1",
        "ovpn-tcp-443.example.net",
        "proxy-hidden.example.org",
        "sni-hidden.example.org",
        "/secret-path",
        "MIIFakeTcpCaSecret123456",
        "proto tcp",
        "AES-256-GCM",
      )
    forbidden.forEach { secret ->
      composeRule
        .onAllNodesWithText(secret, substring = true, ignoreCase = true, useUnmergedTree = true)
        .assertCountEquals(0)
    }
  }

  private suspend fun awaitCondition(condition: suspend () -> Boolean) {
    val deadlineNanos = System.nanoTime() + 5_000_000_000L
    while (!condition() && System.nanoTime() < deadlineNanos) {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      delay(20L)
    }
    Shadows.shadowOf(Looper.getMainLooper()).idle()
    assertTrue("Timed out waiting for expected state", condition())
  }

  private class CapturingVpnController : VpnEngineController {
    override val state: StateFlow<VpnTunnelState> = MutableStateFlow(VpnTunnelState.Disconnected)
    override val trafficStats: StateFlow<VpnTrafficStats> = MutableStateFlow(VpnTrafficStats())
    override val isImplemented: Boolean = true
    var startCalls = 0
    var lastNetwork: NetworkConfigEntity? = null
    var lastProfile: ServerProfileEntity? = null
    var lastKillSwitch = false
    var lastAutoReconnect = false

    override fun prepareVpnPermissionIntent(context: Context): Intent? = null

    override fun startTunnel(
      profile: ServerProfileEntity,
      killSwitch: Boolean,
      autoReconnect: Boolean,
    ) {
      startCalls += 1
      lastNetwork = null
      lastProfile = profile
      lastKillSwitch = killSwitch
      lastAutoReconnect = autoReconnect
    }

    override fun startCombinedTunnel(
      network: NetworkConfigEntity?,
      server: ServerAccountEntity,
      effectiveProfile: ServerProfileEntity,
      killSwitch: Boolean,
      autoReconnect: Boolean,
    ) {
      startCalls += 1
      lastNetwork = network
      lastProfile = effectiveProfile
      lastKillSwitch = killSwitch
      lastAutoReconnect = autoReconnect
    }

    override fun stopTunnel() = Unit

    override fun updateProtectionSettings(killSwitch: Boolean, autoReconnect: Boolean) = Unit
  }

  /**
   * Minimal local mock HTTP/WebSocket payload proxy that reads incoming HTTP payload bytes
   * (supporting `[split]` multi-part payloads), replies with [statusLine], and then echoes any
   * subsequent tunnel bytes prefixed with `ECHO:` so tests can prove real end-to-end TCP relay.
   */
  private class MockPayloadRelayProxy(
    private val statusLine: String,
    private val closeImmediately: Boolean = false,
  ) {
    private val running = AtomicBoolean(false)
    private val executor = Executors.newCachedThreadPool()
    @Volatile private var serverSocket: ServerSocket? = null
    val capturedHandshake = AtomicReference("")

    fun start(): Int {
      val ss = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
      serverSocket = ss
      running.set(true)
      executor.execute {
        while (running.get() && !ss.isClosed) {
          val client =
            try {
              ss.accept()
            } catch (_: IOException) {
              break
            }
          executor.execute { handleClient(client) }
        }
      }
      return ss.localPort
    }

    private fun handleClient(client: Socket) {
      client.use { sock ->
        try {
          sock.soTimeout = 3_000
          val inp = sock.getInputStream()
          val out = sock.getOutputStream()

          val headerBytes = ByteArrayOutputStream()
          val buf = ByteArray(1024)
          // Read payload parts (including split write with tiny pause)
          val deadline = System.currentTimeMillis() + 600L
          while (System.currentTimeMillis() < deadline) {
            if (inp.available() > 0 || headerBytes.size() == 0) {
              val n = inp.read(buf)
              if (n <= 0) break
              headerBytes.write(buf, 0, n)
              val text = headerBytes.toString(StandardCharsets.ISO_8859_1.name())
              if (text.endsWith("\r\n\r\n") && (text.contains("Upgrade: websocket") || text.startsWith("CONNECT "))) {
                break
              }
            } else {
              Thread.sleep(15L)
            }
          }
          capturedHandshake.set(headerBytes.toString(StandardCharsets.ISO_8859_1.name()))

          val response = "$statusLine\r\nContent-Length: 0\r\n\r\n"
          out.write(response.toByteArray(StandardCharsets.ISO_8859_1))
          out.flush()

          if (closeImmediately) return

          // After payload handshake succeeds, echo relayed OpenVPN TCP stream bytes
          val dataBuf = ByteArray(1024)
          while (running.get() && !sock.isClosed) {
            val n = inp.read(dataBuf)
            if (n <= 0) break
            val msg = String(dataBuf, 0, n, StandardCharsets.ISO_8859_1)
            out.write(("ECHO:$msg").toByteArray(StandardCharsets.ISO_8859_1))
            out.flush()
          }
        } catch (_: IOException) {
          // Closed during disconnect or teardown
        }
      }
    }

    fun stop() {
      running.set(false)
      runCatching { serverSocket?.close() }
      executor.shutdownNow()
      executor.awaitTermination(2, TimeUnit.SECONDS)
    }
  }
}
