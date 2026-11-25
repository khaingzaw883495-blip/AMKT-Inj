package com.example

import android.content.Context
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.config.ConfigFileCodec
import com.example.data.AmktDatabase
import com.example.data.AppLogStore
import com.example.data.ConnectionMode
import com.example.data.NetworkConfigEntity
import com.example.data.NetworkServerRepository
import com.example.data.PayloadMode
import com.example.data.RemoteServerListRepository
import com.example.data.ServerAccountEntity
import com.example.data.ServerProfileEntity
import com.example.data.ServerProfileRepository
import com.example.membership.MembershipRepository
import com.example.redeem.RedeemCodeMapper
import com.example.redeem.RedeemOutcome
import com.example.redeem.RedeemRepository
import com.example.ui.AmktViewModel
import com.example.vpn.VpnEngineController
import com.example.vpn.VpnPreferencesRepository
import com.example.vpn.VpnTrafficStats
import com.example.vpn.VpnTunnelState
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RedeemCodeFlowTest {
  private lateinit var context: Context
  private lateinit var db: AmktDatabase
  private lateinit var remoteRepo: RemoteServerListRepository
  private lateinit var splitRepo: NetworkServerRepository
  private lateinit var redeemRepo: RedeemRepository

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    db =
      Room.inMemoryDatabaseBuilder(context, AmktDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    val serverPrefsFile =
      java.io.File(context.cacheDir, "redeem_srv_${java.util.UUID.randomUUID()}.preferences_pb")
    val redeemPrefsFile =
      java.io.File(context.cacheDir, "redeem_hook_${java.util.UUID.randomUUID()}.preferences_pb")
    remoteRepo =
      RemoteServerListRepository(
        context,
        dataStore = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create { serverPrefsFile },
      )
    splitRepo =
      NetworkServerRepository(
        networkDao = db.networkConfigDao(),
        serverDao = db.serverAccountDao(),
        remoteServerListRepository = remoteRepo,
      )
    redeemRepo =
      RedeemRepository(
        context,
        dataStore = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create { redeemPrefsFile },
      )
  }

  @After
  fun tearDown() {
    db.close()
  }

  @Test
  fun redeemPostsNormalizedJsonWithDeviceIdAndMapsSshServer() = runBlocking {
    val replyJson =
      """
      {
        "valid": true,
        "message": "ကုတ် အတည်ပြုပြီးပါပြီ (30 ရက်)",
        "server": {
          "mode": "ssh",
          "name": "SG VIP 01",
          "host": "sg-vip.example.com",
          "port": 443,
          "username": "buyer_01",
          "password": "secret-pass-example",
          "nameserver": "",
          "public_key": "",
          "vless_link": "",
          "expires_at": "2027-08-31T23:59:59Z"
        }
      }
      """.trimIndent()

    val server = LocalPostWebhookServer(statusLine = "200 OK", responseBody = replyJson)
    try {
      val outcome =
        redeemRepo.redeemCode(
          rawCode = "  inj-ab12-cd34  ",
          deviceId = "android-device-99",
          webhookUrlOverride = server.url,
        )

      assertEquals("POST", server.capturedMethod.get())
      assertTrue(
        "Expected application/json content-type, got ${server.capturedContentType.get()}",
        server.capturedContentType.get().orEmpty().contains("application/json", ignoreCase = true),
      )
      val sentBody = server.capturedBody.get().orEmpty()
      assertTrue("Expected normalized uppercase code in body: $sentBody", sentBody.contains("\"code\":\"INJ-AB12-CD34\""))
      assertTrue("Expected device_id in body: $sentBody", sentBody.contains("\"device_id\":\"android-device-99\""))

      assertTrue("Expected RedeemOutcome.Success, got $outcome", outcome is RedeemOutcome.Success)
      val success = outcome as RedeemOutcome.Success
      assertEquals("ကုတ် အတည်ပြုပြီးပါပြီ (30 ရက်)", success.message)
      assertEquals(ConnectionMode.SSH_DIRECT, success.server.resolvedConnectionMode)
      assertEquals("SG VIP 01", success.server.name)
      assertEquals("sg-vip.example.com", success.server.host)
      assertEquals(443, success.server.port)
      assertEquals("buyer_01", success.server.username)
      assertEquals("secret-pass-example", success.server.password)
      assertEquals("2027-08-31", success.server.expiryDate)
      assertNull("Redeemed server must not hardcode a network", success.server.defaultNetworkId)
    } finally {
      server.close()
    }
  }

  @Test
  fun redeemMapsDnsttAndVlessModesCleanly() = runBlocking {
    val dnsttReply =
      """
      {
        "valid": true,
        "message": "DNSTT ဆာဗာ ရရှိပြီး",
        "server": {
          "mode": "dnstt",
          "name": "JP SlowDNS 01",
          "host": "",
          "port": 22,
          "username": "dns_user",
          "password": "dns_pass_example",
          "nameserver": "tns.example.org",
          "public_key": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
          "vless_link": "",
          "expires_at": "2027-09-15"
        }
      }
      """.trimIndent()
    val dnsttServer = LocalPostWebhookServer("200 OK", dnsttReply)
    try {
      val outcome = redeemRepo.redeemCode("INJ-DNST-0001", "dev-1", dnsttServer.url)
      assertTrue(outcome is RedeemOutcome.Success)
      val srv = (outcome as RedeemOutcome.Success).server
      assertEquals(ConnectionMode.DNSTT, srv.resolvedConnectionMode)
      assertEquals("tns.example.org", srv.dnsttNameserver)
      assertEquals("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", srv.dnsttPublicKey)
      assertEquals("dns_user", srv.username)
      assertEquals("2027-09-15", srv.expiryDate)
    } finally {
      dnsttServer.close()
    }

    val vlessReply =
      """
      {
        "valid": true,
        "message": "VLESS ဆာဗာ ရရှိပြီး",
        "server": {
          "mode": "vless",
          "name": "",
          "host": "",
          "port": 0,
          "username": "",
          "password": "",
          "nameserver": "",
          "public_key": "",
          "vless_link": "vless://11111111-2222-3333-4444-555555555555@cdn.example.net:443?type=ws&encryption=none&security=tls&host=cdn.example.net&path=%2Fvless-ws#SG%20VLESS%20VIP",
          "expires_at": null
        }
      }
      """.trimIndent()
    val vlessServer = LocalPostWebhookServer("200 OK", vlessReply)
    try {
      val outcome = redeemRepo.redeemCode("INJ-VLES-0002", "dev-1", vlessServer.url)
      assertTrue(outcome is RedeemOutcome.Success)
      val srv = (outcome as RedeemOutcome.Success).server
      assertEquals(ConnectionMode.VLESS_WS, srv.resolvedConnectionMode)
      assertEquals("SG VLESS VIP", srv.name)
      assertEquals("cdn.example.net", srv.host)
      assertEquals(443, srv.port)
      assertEquals("11111111-2222-3333-4444-555555555555", srv.vlessUuid)
      assertEquals("", srv.expiryDate)
    } finally {
      vlessServer.close()
    }
  }

  @Test
  fun invalidEmptyMalformedAndHttpErrorRepliesCreateZeroServers() = runBlocking {
    // 1. valid: false -> surfaces backend Burmese message
    val invalidServer =
      LocalPostWebhookServer(
        "200 OK",
        """{"valid": false, "message": "ဤကုတ်သည် အခြားဖုန်းတွင် အသုံးပြုပြီးသားဖြစ်နေပါသည်", "server": null}""",
      )
    try {
      val outcome = redeemRepo.redeemCode("INJ-USED-0001", "dev-2", invalidServer.url)
      assertTrue(outcome is RedeemOutcome.Invalid)
      assertEquals(
        "ဤကုတ်သည် အခြားဖုန်းတွင် အသုံးပြုပြီးသားဖြစ်နေပါသည်",
        (outcome as RedeemOutcome.Invalid).message,
      )
    } finally {
      invalidServer.close()
    }

    // 2. Empty HTTP 200 body (n8n empty sheet case) -> Invalid with clear Burmese message, no crash
    val empty200Server = LocalPostWebhookServer("200 OK", "")
    try {
      val outcome = redeemRepo.redeemCode("INJ-EMPT-0002", "dev-2", empty200Server.url)
      assertTrue("Expected Invalid on empty HTTP 200, got $outcome", outcome is RedeemOutcome.Invalid)
      assertTrue((outcome as RedeemOutcome.Invalid).message.isNotBlank())
    } finally {
      empty200Server.close()
    }

    // 3. Non-JSON HTTP 200 body -> Invalid, no crash
    val htmlServer = LocalPostWebhookServer("200 OK", "<html>not json</html>")
    try {
      val outcome = redeemRepo.redeemCode("INJ-HTML-0003", "dev-2", htmlServer.url)
      assertTrue("Expected Invalid on HTML 200, got $outcome", outcome is RedeemOutcome.Invalid)
    } finally {
      htmlServer.close()
    }

    // 4. HTTP 500 -> Error, no crash
    val err500Server = LocalPostWebhookServer("500 Internal Server Error", "server crashed")
    try {
      val outcome = redeemRepo.redeemCode("INJ-ERR5-0004", "dev-2", err500Server.url)
      assertTrue("Expected Error on HTTP 500, got $outcome", outcome is RedeemOutcome.Error)
      assertTrue((outcome as RedeemOutcome.Error).message.contains("500"))
    } finally {
      err500Server.close()
    }

    assertEquals(0, splitRepo.getAllServersSnapshot().size)
  }

  @Test
  fun duplicateRedeemUpsertsWithoutDuplicatingAndSelectsFirstAvailableNetwork() = runBlocking {
    val netId =
      splitRepo.saveNetwork(
        NetworkConfigEntity(
          name = "WS Default Net",
          payloadMode = PayloadMode.WEBSOCKET.code,
          payloadTemplate = "GET / HTTP/1.1[crlf]Host: [host][crlf]Upgrade: websocket[crlf][crlf]",
        ),
        selectAfterSave = false,
      )

    val firstEntity =
      RedeemCodeMapper.mapServerDtoToEntity(
        "INJ-DUPL-9999",
        com.example.redeem.RedeemServerDto(
          mode = "ssh",
          name = "Buyer Server A",
          host = "old.example.com",
          port = 22,
          username = "u1",
          password = "p1",
          expiresAt = "2027-01-01",
        ),
      ).getOrThrow()

    val (stored1, updated1) = splitRepo.upsertRedeemedServer("INJ-DUPL-9999", firstEntity)
    assertFalse(updated1)
    assertEquals(1, splitRepo.getAllServersSnapshot().size)
    val (selNet, selSrv) = remoteRepo.getLastSelection()
    assertEquals("Should auto-pick first available Network when none selected", netId, selNet)
    assertEquals(stored1.id, selSrv)

    // Redeem same code again with updated host/password/expiry -> must update in-place, zero duplicates
    val secondEntity =
      RedeemCodeMapper.mapServerDtoToEntity(
        "INJ-DUPL-9999",
        com.example.redeem.RedeemServerDto(
          mode = "ssh",
          name = "Buyer Server A",
          host = "new.example.com",
          port = 443,
          username = "u1",
          password = "p2-updated",
          expiresAt = "2027-12-31T23:59:59Z",
        ),
      ).getOrThrow()

    val (stored2, updated2) = splitRepo.upsertRedeemedServer("INJ-DUPL-9999", secondEntity)
    assertTrue("Second redeem should update existing row", updated2)
    assertEquals(stored1.id, stored2.id)
    val all = splitRepo.getAllServersSnapshot()
    assertEquals("Must never duplicate redeemed server", 1, all.size)
    assertEquals("new.example.com", all.first().host)
    assertEquals(443, all.first().port)
    assertEquals("p2-updated", all.first().password)
    assertEquals("2027-12-31", all.first().expiryDate)
  }

  @Test
  fun expiredRedeemedServerIsRefusedBeforeVpnConsent() = runBlocking {
    val logStore = AppLogStore()
    val fakeVpn = NoOpVpnController()
    val vm =
      AmktViewModel(
        repository = ServerProfileRepository(db.serverProfileDao(), remoteRepo),
        membershipRepository = MembershipRepository(context),
        vpnPreferencesRepository = VpnPreferencesRepository(context),
        vpnController = fakeVpn,
        configCodec = ConfigFileCodec(),
        logStore = logStore,
        networkServerRepository = splitRepo,
        redeemRepository = redeemRepo,
      )

    val netCollector = launch(Dispatchers.Unconfined) {
      vm.selectedNetwork.collect {}
    }
    val srvCollector = launch(Dispatchers.Unconfined) {
      vm.selectedServer.collect {}
    }
    try {
      splitRepo.saveNetwork(
        NetworkConfigEntity(name = "Direct Net", payloadMode = PayloadMode.DIRECT.code),
        selectAfterSave = true,
      )
      val expiredServer =
        RedeemCodeMapper.mapServerDtoToEntity(
          "INJ-EXPD-1111",
          com.example.redeem.RedeemServerDto(
            mode = "ssh",
            name = "Expired Buyer Server",
            host = "expired.example.com",
            port = 22,
            username = "u",
            password = "p",
            expiresAt = "2020-01-01T00:00:00Z",
          ),
        ).getOrThrow()
      splitRepo.upsertRedeemedServer("INJ-EXPD-1111", expiredServer)

      // Pump main looper so StateFlows emit the newly saved Network + Server
      val deadline = System.currentTimeMillis() + 3_000L
      while (
        (vm.selectedNetwork.value?.name != "Direct Net" ||
          vm.selectedServer.value?.name != "Expired Buyer Server") &&
          System.currentTimeMillis() < deadline
      ) {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(25)
      }

      assertNotNull(vm.selectedNetwork.value)
      assertNotNull(vm.selectedServer.value)
      assertEquals("2020-01-01", vm.selectedServer.value?.expiryDate)

      val validated = vm.validateSelectionForVpnConnect()
      assertNull("Expired redeemed server must be refused before VPN permission", validated)
      assertTrue(
        "Expected [EXPIRY] log entry",
        logStore.logs.value.any { it.tag == "EXPIRY" && it.message.contains("သက်တမ်းကုန်") },
      )
    } finally {
      netCollector.cancel()
      srvCollector.cancel()
    }
  }

  @Test
  fun editableWebhookUrlPersistsValidatesAndResets() = runBlocking {
    assertEquals(RedeemRepository.DEFAULT_REDEEM_WEBHOOK_URL, redeemRepo.getRedeemWebhookUrl())

    val invalidSave = redeemRepo.saveRedeemWebhookUrl("ftp://invalid.example.com/hook")
    assertTrue(invalidSave.isFailure)

    val customUrl = "https://custom-hook.example.com/webhook/redeem"
    val validSave = redeemRepo.saveRedeemWebhookUrl(customUrl)
    assertTrue(validSave.isSuccess)
    assertEquals(customUrl, redeemRepo.getRedeemWebhookUrl())

    val resetUrl = redeemRepo.resetWebhookUrlToDefault()
    assertEquals(RedeemRepository.DEFAULT_REDEEM_WEBHOOK_URL, resetUrl)
    assertEquals(RedeemRepository.DEFAULT_REDEEM_WEBHOOK_URL, redeemRepo.getRedeemWebhookUrl())
  }

  private class NoOpVpnController : VpnEngineController {
    private val _state = MutableStateFlow<VpnTunnelState>(VpnTunnelState.Disconnected)
    override val state: StateFlow<VpnTunnelState> = _state
    private val _stats = MutableStateFlow(VpnTrafficStats())
    override val trafficStats: StateFlow<VpnTrafficStats> = _stats
    override val isImplemented: Boolean = true

    override fun prepareVpnPermissionIntent(context: Context): android.content.Intent? = null
    override fun startTunnel(profile: ServerProfileEntity, killSwitch: Boolean, autoReconnect: Boolean) = Unit
    override fun startCombinedTunnel(
      network: NetworkConfigEntity?,
      server: ServerAccountEntity,
      effectiveProfile: ServerProfileEntity,
      killSwitch: Boolean,
      autoReconnect: Boolean,
    ) = Unit
    override fun stopTunnel() = Unit
    override fun updateProtectionSettings(killSwitch: Boolean, autoReconnect: Boolean) = Unit
  }

  /**
   * Minimal bounded loopback HTTP server capturing a single POST request (method, Content-Type,
   * body) and returning a scripted response with strict timeouts and cleanup.
   */
  private class LocalPostWebhookServer(
    private val statusLine: String,
    private val responseBody: String,
  ) : AutoCloseable {
    private val serverSocket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).apply {
      soTimeout = 5_000
    }
    private val executor = Executors.newSingleThreadExecutor()
    val capturedMethod = AtomicReference<String>()
    val capturedContentType = AtomicReference<String>()
    val capturedBody = AtomicReference<String>()
    val url: String = "http://127.0.0.1:${serverSocket.localPort}/webhook/redeem"

    init {
      executor.submit {
        try {
          serverSocket.accept().use { client ->
            client.soTimeout = 3_000
            val input = BufferedInputStream(client.getInputStream())
            val headerBytes = ByteArrayOutputStream()
            var prev3 = 0
            var prev2 = 0
            var prev1 = 0
            while (true) {
              val b = input.read()
              if (b == -1) break
              headerBytes.write(b)
              if (prev3 == '\r'.code && prev2 == '\n'.code && prev1 == '\r'.code && b == '\n'.code) {
                break
              }
              prev3 = prev2
              prev2 = prev1
              prev1 = b
            }
            val headerText = headerBytes.toString(StandardCharsets.UTF_8.name())
            val lines = headerText.split("\r\n")
            val requestLine = lines.firstOrNull().orEmpty()
            capturedMethod.set(requestLine.substringBefore(' ').trim())
            var contentLength = 0
            for (line in lines.drop(1)) {
              val colon = line.indexOf(':')
              if (colon > 0) {
                val k = line.substring(0, colon).trim().lowercase()
                val v = line.substring(colon + 1).trim()
                if (k == "content-type") capturedContentType.set(v)
                if (k == "content-length") contentLength = v.toIntOrNull() ?: 0
              }
            }
            val bodyBuf = ByteArray(contentLength.coerceIn(0, 65_536))
            var readTotal = 0
            while (readTotal < bodyBuf.size) {
              val n = input.read(bodyBuf, readTotal, bodyBuf.size - readTotal)
              if (n <= 0) break
              readTotal += n
            }
            capturedBody.set(String(bodyBuf, 0, readTotal, StandardCharsets.UTF_8))

            val bodyBytes = responseBody.toByteArray(StandardCharsets.UTF_8)
            val httpResp =
              "HTTP/1.1 $statusLine\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${bodyBytes.size}\r\n" +
                "Connection: close\r\n\r\n"
            client.getOutputStream().apply {
              write(httpResp.toByteArray(StandardCharsets.UTF_8))
              write(bodyBytes)
              flush()
            }
          }
        } catch (_: Exception) {
          // Bounded test server exits cleanly
        }
      }
    }

    override fun close() {
      try {
        serverSocket.close()
      } catch (_: Exception) {}
      executor.shutdownNow()
      try {
        executor.awaitTermination(2, TimeUnit.SECONDS)
      } catch (_: Exception) {}
    }
  }
}
