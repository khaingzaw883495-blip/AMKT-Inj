package com.example

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
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
import com.example.redeem.RedeemRepository
import com.example.ui.AmktViewModel
import com.example.vpn.VpnEngineController
import com.example.vpn.VpnPreferencesRepository
import com.example.vpn.VpnTrafficStats
import com.example.vpn.VpnTunnelState
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateServersRefreshTest {
  private lateinit var context: Context
  private lateinit var db: AmktDatabase

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    db =
      Room.inMemoryDatabaseBuilder(context, AmktDatabase::class.java)
        .allowMainThreadQueries()
        .build()
  }

  @After
  fun tearDown() {
    db.close()
  }

  private data class TestStack(
    val remoteRepo: RemoteServerListRepository,
    val splitRepo: NetworkServerRepository,
    val viewModel: AmktViewModel,
    val logStore: AppLogStore,
  )

  private fun createStack(
    defaultRemoteUrl: String = RemoteServerListRepository.DEFAULT_REMOTE_SERVER_LIST_URL,
  ): TestStack {
    val serverPrefsFile = File(context.cacheDir, "refresh_srv_${UUID.randomUUID()}.preferences_pb")
    val redeemPrefsFile = File(context.cacheDir, "refresh_rdm_${UUID.randomUUID()}.preferences_pb")
    val remoteRepo =
      RemoteServerListRepository(
        context = context,
        dataStore = PreferenceDataStoreFactory.create { serverPrefsFile },
        defaultRemoteServerListUrl = defaultRemoteUrl,
      )
    val splitRepo =
      NetworkServerRepository(
        networkDao = db.networkConfigDao(),
        serverDao = db.serverAccountDao(),
        remoteServerListRepository = remoteRepo,
      )
    val legacyRepo = ServerProfileRepository(db.serverProfileDao(), remoteRepo)
    val redeemRepo =
      RedeemRepository(
        context = context,
        dataStore = PreferenceDataStoreFactory.create { redeemPrefsFile },
      )
    val logStore = AppLogStore()
    val vm =
      AmktViewModel(
        repository = legacyRepo,
        membershipRepository = MembershipRepository(context),
        vpnPreferencesRepository = VpnPreferencesRepository(context),
        vpnController = NoOpVpnController(),
        configCodec = ConfigFileCodec(),
        logStore = logStore,
        networkServerRepository = splitRepo,
        redeemRepository = redeemRepo,
      )
    return TestStack(remoteRepo, splitRepo, vm, logStore)
  }

  private fun waitUntil(timeoutMs: Long = 5_000L, condition: () -> Boolean) {
    val deadlineNanos = System.nanoTime() + timeoutMs * 1_000_000L
    while (System.nanoTime() < deadlineNanos) {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      if (condition()) return
      Thread.sleep(20L)
    }
    Shadows.shadowOf(Looper.getMainLooper()).idle()
    assertTrue("Timed out waiting for async ViewModel state", condition())
  }

  @Test
  fun obfuscatedDefaultRemoteServerListUrlReconstructsCleanly() {
    val expected = "https://n8n.marratethu.web.id/webhook/amkt-injector-servers"
    assertEquals(expected, com.example.config.NativeKeyBridge.resolveDefaultRemoteServerListUrl())
    assertEquals(expected, RemoteServerListRepository.DEFAULT_REMOTE_SERVER_LIST_URL)
  }

  @Test
  fun freshInstallHasZeroSeededProfilesAndEmbeddedDefaultListUrl() = runBlocking {
    val stack = createStack()
    Shadows.shadowOf(Looper.getMainLooper()).idle()
    Thread.sleep(40L)
    Shadows.shadowOf(Looper.getMainLooper()).idle()

    assertTrue("Fresh install must start with ZERO networks", stack.splitRepo.getAllNetworksSnapshot().isEmpty())
    assertTrue("Fresh install must start with ZERO servers", stack.splitRepo.getAllServersSnapshot().isEmpty())
    assertEquals(
      "https://n8n.marratethu.web.id/webhook/amkt-injector-servers",
      stack.remoteRepo.getSavedRemoteServerListUrl(),
    )
    assertEquals(
      "https://n8n.marratethu.web.id/webhook/amkt-injector-servers",
      stack.remoteRepo.remoteServerListUrlFlow.first(),
    )
    assertEquals(
      "https://n8n.marratethu.web.id/webhook/amkt-injector-servers",
      stack.viewModel.remoteServerListUrl.value,
    )
  }

  @Test
  fun updateWithNoSavedUrlUsesEmbeddedDefaultAutomatically() = runBlocking {
    val ownerJson =
      """
      {
        "format_version": 2,
        "networks": [
          {
            "name": "Owner WS Net",
            "payload_mode": "WEBSOCKET",
            "payload_template": "GET / HTTP/1.1[crlf]Host: [host][crlf]Upgrade: websocket[crlf][crlf]",
            "sni_host": "front.example.com"
          }
        ],
        "servers": [
          {
            "name": "Owner SG 01",
            "connection_mode": "SSH_DIRECT",
            "host": "sg-owner.example.com",
            "port": 443,
            "network_name": "Owner WS Net"
          }
        ]
      }
      """.trimIndent()

    val embeddedDefaultHttp = MultiRequestHttpServer(initialStatus = 200, initialBody = ownerJson)
    try {
      // Fresh install with NOTHING saved in DataStore, using embedded default URL
      val stack = createStack(defaultRemoteUrl = embeddedDefaultHttp.url)
      assertTrue(stack.splitRepo.getAllNetworksSnapshot().isEmpty())
      assertTrue(stack.splitRepo.getAllServersSnapshot().isEmpty())

      var missingUrlTriggered = false
      stack.viewModel.refreshSavedRemoteServers(onMissingUrl = { missingUrlTriggered = true })
      waitUntil {
        !stack.viewModel.isFetchingRemoteServers.value &&
          stack.viewModel.remoteSyncFeedback.value != null
      }

      assertFalse("Embedded default exists so missing-URL redirect must NOT fire", missingUrlTriggered)
      assertEquals("Embedded default URL must be fetched automatically", 1, embeddedDefaultHttp.requestCount.get())
      val fb = stack.viewModel.remoteSyncFeedback.value!!
      assertFalse("Expected successful fetch from embedded default: ${fb.message}", fb.isError)

      val nets = stack.splitRepo.getAllNetworksSnapshot()
      val srvs = stack.splitRepo.getAllServersSnapshot()
      assertEquals(1, nets.size)
      assertEquals("Owner WS Net", nets.first().name)
      assertEquals(1, srvs.size)
      assertEquals("Owner SG 01", srvs.first().name)

      // About -> Load with empty field also uses the embedded default automatically
      stack.viewModel.clearRemoteSyncFeedback()
      stack.viewModel.fetchServersFromRemoteUrl("", replaceExisting = false)
      waitUntil {
        !stack.viewModel.isFetchingRemoteServers.value &&
          stack.viewModel.remoteSyncFeedback.value != null
      }
      assertEquals(2, embeddedDefaultHttp.requestCount.get())
      assertEquals("Zero duplicates after second fetch", 1, stack.splitRepo.getAllNetworksSnapshot().size)
      assertEquals("Zero duplicates after second fetch", 1, stack.splitRepo.getAllServersSnapshot().size)

      // Embedded default URL (and any URL) must NEVER appear in app logs
      val remoteLogs =
        stack.logStore.logs.value.filter { it.tag == "REMOTE" }.joinToString("\n") { it.message }
      assertFalse("Embedded default URL must not appear in plain text logs", remoteLogs.contains(embeddedDefaultHttp.url))
      assertFalse("No http:// URL may appear in REMOTE logs", remoteLogs.contains("http://"))
      assertFalse("No https:// URL may appear in REMOTE logs", remoteLogs.contains("https://"))
      assertFalse("Default server list path must not appear in logs", remoteLogs.contains("amkt-injector-servers"))
    } finally {
      embeddedDefaultHttp.close()
    }
  }

  @Test
  fun updateWithChangedUrlUsesSavedCustomUrlAndResetReturnsToDefault() = runBlocking {
    val defaultJson =
      """
      {
        "format_version": 2,
        "networks": [{"name": "Default Net", "payload_mode": "DIRECT"}],
        "servers": [{"name": "Default Server", "connection_mode": "SSH_DIRECT", "host": "default.example.com", "port": 22, "network_name": "Default Net"}]
      }
      """.trimIndent()

    val customJson =
      """
      {
        "format_version": 2,
        "networks": [{"name": "Custom Net", "payload_mode": "DIRECT"}],
        "servers": [{"name": "Custom Server", "connection_mode": "SSH_DIRECT", "host": "custom.example.com", "port": 443, "network_name": "Custom Net"}]
      }
      """.trimIndent()

    val defaultHttp = MultiRequestHttpServer(initialStatus = 200, initialBody = defaultJson)
    val customHttp = MultiRequestHttpServer(initialStatus = 200, initialBody = customJson)
    try {
      val stack = createStack(defaultRemoteUrl = defaultHttp.url)

      // User changes URL in About/Settings and loads
      stack.viewModel.fetchServersFromRemoteUrl(customHttp.url, replaceExisting = false)
      waitUntil {
        !stack.viewModel.isFetchingRemoteServers.value &&
          stack.viewModel.remoteSyncFeedback.value != null
      }
      assertEquals(0, defaultHttp.requestCount.get())
      assertEquals(1, customHttp.requestCount.get())
      assertEquals(customHttp.url, stack.remoteRepo.getSavedRemoteServerListUrl())

      // Tapping Update now uses the changed/saved URL (NOT the embedded default)
      stack.viewModel.clearRemoteSyncFeedback()
      stack.viewModel.refreshSavedRemoteServers()
      waitUntil {
        !stack.viewModel.isFetchingRemoteServers.value &&
          stack.viewModel.remoteSyncFeedback.value != null
      }
      assertEquals("Must NOT hit default URL while custom URL is saved", 0, defaultHttp.requestCount.get())
      assertEquals("Must hit saved custom URL", 2, customHttp.requestCount.get())
      assertTrue(stack.splitRepo.getAllServersSnapshot().any { it.name == "Custom Server" })
      assertEquals(1, stack.splitRepo.getAllServersSnapshot().size)

      // Resetting URL in About returns to embedded default
      stack.viewModel.resetRemoteServerListUrl()
      waitUntil {
        runBlocking { stack.remoteRepo.getSavedRemoteServerListUrl() == defaultHttp.url }
      }
      stack.viewModel.clearRemoteSyncFeedback()
      stack.viewModel.refreshSavedRemoteServers()
      waitUntil {
        !stack.viewModel.isFetchingRemoteServers.value &&
          stack.viewModel.remoteSyncFeedback.value != null
      }
      assertEquals("After reset, Update must hit embedded default URL", 1, defaultHttp.requestCount.get())
      assertTrue(stack.splitRepo.getAllServersSnapshot().any { it.name == "Default Server" })
    } finally {
      defaultHttp.close()
      customHttp.close()
    }
  }

  @Test
  fun updateActionRefetchesSavedUrlAndUpsertsWithoutDuplicates() = runBlocking {
    val initialJson =
      """
      {
        "format_version": 2,
        "networks": [
          {
            "name": "Carrier WS",
            "payload_mode": "WEBSOCKET",
            "payload_template": "GET / HTTP/1.1[crlf]Host: [host][crlf]Upgrade: websocket[crlf][crlf]",
            "sni_host": "old-sni.example.com",
            "extra_headers": "X-Local: keep"
          }
        ],
        "servers": [
          {
            "name": "SG Node 01",
            "connection_mode": "SSH_DIRECT",
            "host": "sg01-old.example.com",
            "port": 443,
            "username": "user01",
            "network_name": "Carrier WS"
          }
        ]
      }
      """.trimIndent()

    val updatedJson =
      """
      {
        "format_version": 2,
        "networks": [
          {
            "name": "Carrier WS",
            "payload_mode": "WEBSOCKET",
            "sni_host": "new-sni.example.com"
          }
        ],
        "servers": [
          {
            "name": "SG Node 01",
            "connection_mode": "SSH_DIRECT",
            "host": "sg01-new.example.com",
            "port": 443,
            "network_name": "Carrier WS"
          },
          {
            "name": "JP Node 02",
            "connection_mode": "SSH_DIRECT",
            "host": "jp02.example.com",
            "port": 22,
            "network_name": "Carrier WS"
          }
        ]
      }
      """.trimIndent()

    val http = MultiRequestHttpServer(initialStatus = 200, initialBody = initialJson)
    try {
      val stack = createStack(defaultRemoteUrl = http.url)
      stack.viewModel.fetchServersFromRemoteUrl(http.url, replaceExisting = false)
      waitUntil { !stack.viewModel.isFetchingRemoteServers.value && stack.viewModel.remoteSyncFeedback.value != null }
      assertEquals(1, http.requestCount.get())
      assertEquals(1, stack.splitRepo.getAllNetworksSnapshot().size)
      assertEquals(1, stack.splitRepo.getAllServersSnapshot().size)

      http.setResponse(200, updatedJson)
      stack.viewModel.clearRemoteSyncFeedback()
      stack.viewModel.clearSnackbar()

      stack.viewModel.refreshSavedRemoteServers()
      waitUntil { !stack.viewModel.isFetchingRemoteServers.value && stack.viewModel.remoteSyncFeedback.value != null }

      assertEquals(2, http.requestCount.get())
      val fb1 = stack.viewModel.remoteSyncFeedback.value
      assertNotNull(fb1)
      assertFalse("Expected success feedback, got ${fb1?.message}", fb1!!.isError)
      assertEquals("Update Finished", fb1.message)
      assertEquals("Update Finished", stack.viewModel.snackbarMessage.value)

      val nets = stack.splitRepo.getAllNetworksSnapshot()
      val srvs = stack.splitRepo.getAllServersSnapshot()
      assertEquals("Zero duplicate networks", 1, nets.size)
      assertEquals("One updated + one added server", 2, srvs.size)
      assertEquals("new-sni.example.com", nets.first().sniHost)
      assertEquals("X-Local: keep", nets.first().extraHeaders)
      val sg = srvs.first { it.name == "SG Node 01" }
      assertEquals("sg01-new.example.com", sg.host)
      assertEquals("user01", sg.username)
      assertTrue(srvs.any { it.name == "JP Node 02" && it.host == "jp02.example.com" })

      // Tapping Update again with identical JSON keeps zero duplicates
      stack.viewModel.clearRemoteSyncFeedback()
      stack.viewModel.refreshSavedRemoteServers()
      waitUntil { !stack.viewModel.isFetchingRemoteServers.value && stack.viewModel.remoteSyncFeedback.value != null }

      assertEquals(3, http.requestCount.get())
      assertEquals(1, stack.splitRepo.getAllNetworksSnapshot().size)
      assertEquals(2, stack.splitRepo.getAllServersSnapshot().size)
    } finally {
      http.close()
    }
  }

  @Test
  fun updateActionWithFailingFetchShowsErrorAndLeavesLocalDataUntouched() = runBlocking {
    val failingHttp = MultiRequestHttpServer(initialStatus = 500, initialBody = "Internal Server Error")
    try {
      val stack = createStack(defaultRemoteUrl = failingHttp.url)
      stack.splitRepo.saveNetwork(
        NetworkConfigEntity(
          name = "Local Safe Net",
          payloadMode = PayloadMode.DIRECT.code,
        )
      )
      stack.splitRepo.saveServer(
        ServerAccountEntity(
          name = "Local Safe Server",
          connectionMode = ConnectionMode.SSH_DIRECT.code,
          host = "safe.example.com",
          port = 22,
        )
      )

      var missingUrlTriggered = false
      stack.viewModel.refreshSavedRemoteServers(onMissingUrl = { missingUrlTriggered = true })
      waitUntil { !stack.viewModel.isFetchingRemoteServers.value && stack.viewModel.remoteSyncFeedback.value != null }

      assertFalse(missingUrlTriggered)
      assertEquals(1, failingHttp.requestCount.get())
      val fb = stack.viewModel.remoteSyncFeedback.value!!
      assertTrue("Failing fetch must emit error feedback", fb.isError)
      assertTrue("Error message should surface HTTP 500: ${fb.message}", fb.message.contains("500"))
      assertEquals(fb.message, stack.viewModel.snackbarMessage.value)

      val nets = stack.splitRepo.getAllNetworksSnapshot()
      val srvs = stack.splitRepo.getAllServersSnapshot()
      assertEquals(1, nets.size)
      assertEquals("Local Safe Net", nets.first().name)
      assertEquals(1, srvs.size)
      assertEquals("Local Safe Server", srvs.first().name)
      assertEquals("safe.example.com", srvs.first().host)
    } finally {
      failingHttp.close()
    }
  }

  private class MultiRequestHttpServer(
    initialStatus: Int,
    initialBody: String,
  ) : AutoCloseable {
    private val responseRef = AtomicReference(initialStatus to initialBody)
    val requestCount = AtomicInteger(0)
    private val serverSocket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).apply {
      reuseAddress = true
      soTimeout = 500
    }
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var running = true
    val url: String = "http://127.0.0.1:${serverSocket.localPort}/servers.json"

    init {
      executor.submit {
        while (running && !serverSocket.isClosed) {
          try {
            serverSocket.accept().use { client ->
              client.soTimeout = 3_000
              val reader = client.getInputStream().bufferedReader(StandardCharsets.UTF_8)
              while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
              }
              requestCount.incrementAndGet()
              val (statusCode, body) = responseRef.get()
              val bytes = body.toByteArray(StandardCharsets.UTF_8)
              val reason = if (statusCode == 200) "OK" else "Error"
              val headers =
                "HTTP/1.1 $statusCode $reason\r\n" +
                  "Content-Type: application/json; charset=utf-8\r\n" +
                  "Content-Length: ${bytes.size}\r\n" +
                  "Connection: close\r\n\r\n"
              client.getOutputStream().apply {
                write(headers.toByteArray(StandardCharsets.UTF_8))
                write(bytes)
                flush()
              }
            }
          } catch (_: Exception) {
            // Timeout or shutdown loop check
          }
        }
      }
    }

    fun setResponse(status: Int, body: String) {
      responseRef.set(status to body)
    }

    override fun close() {
      running = false
      try {
        serverSocket.close()
      } catch (_: Exception) {}
      executor.shutdownNow()
      try {
        executor.awaitTermination(2, TimeUnit.SECONDS)
      } catch (_: Exception) {}
    }
  }

  private class NoOpVpnController : VpnEngineController {
    override val state: StateFlow<VpnTunnelState> = MutableStateFlow(VpnTunnelState.Disconnected)
    override val trafficStats: StateFlow<VpnTrafficStats> = MutableStateFlow(VpnTrafficStats())
    override val isImplemented: Boolean = true
    override fun prepareVpnPermissionIntent(context: Context): Intent? = null
    override fun startTunnel(profile: ServerProfileEntity, killSwitch: Boolean, autoReconnect: Boolean) {}
    override fun stopTunnel() {}
    override fun updateProtectionSettings(killSwitch: Boolean, autoReconnect: Boolean) {}
  }
}
