package com.example

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.config.ConfigFileCodec
import com.example.data.AmktDatabase
import com.example.data.AppLogStore
import com.example.data.ConnectionMode
import com.example.data.LogLevel
import com.example.data.PayloadMode
import com.example.data.ProfileExpiryChecker
import com.example.data.RemoteServerListRepository
import com.example.data.ServerProfileEntity
import com.example.data.ServerProfileRepository
import com.example.membership.MembershipRepository
import com.example.ui.AmktViewModel
import com.example.vpn.VpnEngineController
import com.example.vpn.VpnPreferencesRepository
import com.example.vpn.VpnTrafficStats
import com.example.vpn.VpnTunnelState
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
@Config(sdk = [36])
class RemoteSyncAndExpiryTest {

  private lateinit var context: Context
  private lateinit var db: AmktDatabase
  private lateinit var remoteRepo: RemoteServerListRepository
  private lateinit var profileRepo: ServerProfileRepository

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    db = Room.inMemoryDatabaseBuilder(context, AmktDatabase::class.java).allowMainThreadQueries().build()
    val testFile =
      java.io.File(context.cacheDir, "remote_sync_${java.util.UUID.randomUUID()}.preferences_pb")
    remoteRepo =
      RemoteServerListRepository(
        context,
        dataStore = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create { testFile },
      )
    profileRepo = ServerProfileRepository(db.serverProfileDao(), remoteRepo)
  }

  @After
  fun tearDown() {
    db.close()
  }

  @Test
  fun remoteJsonSyncAddsNewProfilesUpdatesExistingByNameWithoutDuplicationAndPreservesLocalEdits() =
    runBlocking {
      // Seed one locally edited profile in Room
      val existingId =
        profileRepo.saveProfile(
          ServerProfileEntity(
            name = "Node Alpha",
            mode = ConnectionMode.SSH_WEBSOCKET.code,
            sshHost = "old-alpha.example.com",
            sshPort = 22,
            username = "localuser",
            password = "local-secret-password",
            payload = "GET /custom HTTP/1.1[crlf]Host: [host][crlf][crlf]",
            payloadMode = PayloadMode.WEBSOCKET.code,
            sni = "custom-sni.example.com",
            notes = "My local note",
            isSelected = true,
          ),
          selectAfterSave = true,
        )

      // Remote JSON updates "Node Alpha" host/port (omitting password/payload/sni/notes)
      // and adds a brand-new "Node Beta" VLESS profile.
      val remoteJson =
        """
        {
          "servers": [
            {
              "name": "  node alpha ",
              "mode": "ssh_ws",
              "ssh_host": "new-alpha.example.com",
              "ssh_port": 8080
            },
            {
              "name": "Node Beta",
              "mode": "vless_ws",
              "ssh_host": "beta.example.com",
              "ssh_port": 80,
              "payload": "/vless-ws",
              "expiry_date": "2099-12-31",
              "notes": "Remote VLESS entry"
            }
          ]
        }
        """.trimIndent()

      val server = OneShotHttpServer(statusCode = 200, body = remoteJson)
      val port = server.start()
      try {
        val result =
          profileRepo.fetchAndImportFromRemoteUrl(
            url = "http://127.0.0.1:$port/servers.json",
            replaceExisting = false,
          )
        assertTrue("Remote fetch must succeed: ${result.exceptionOrNull()?.message}", result.isSuccess)
        val summary = result.getOrThrow()
        assertEquals(1, summary.addedCount)
        assertEquals(1, summary.updatedCount)
        assertEquals(2, summary.totalCount)
        assertFalse(summary.replacedAll)
        assertTrue(summary.toBurmeseMessage().contains("အသစ် 1 ခု"))
        assertTrue(summary.toBurmeseMessage().contains("ရှိပြီးသား 1 ခု"))

        val all = db.serverProfileDao().getAllProfilesSnapshot()
        assertEquals("Must NOT duplicate existing Node Alpha", 2, all.size)

        val alpha = db.serverProfileDao().getProfileById(existingId)
        assertNotNull(alpha)
        assertEquals("new-alpha.example.com", alpha!!.sshHost)
        assertEquals(8080, alpha.sshPort)
        // Locally edited fields omitted/blank in remote JSON MUST be preserved
        assertEquals("localuser", alpha.username)
        assertEquals("local-secret-password", alpha.password)
        assertEquals("GET /custom HTTP/1.1[crlf]Host: [host][crlf][crlf]", alpha.payload)
        assertEquals("custom-sni.example.com", alpha.sni)
        assertEquals("My local note", alpha.notes)
        assertTrue("Existing selection should remain intact", alpha.isSelected)

        val beta = all.firstOrNull { it.name == "Node Beta" }
        assertNotNull(beta)
        assertEquals(ConnectionMode.VLESS_WS, beta!!.connectionMode)
        assertEquals("beta.example.com", beta.sshHost)
        assertEquals(80, beta.sshPort)
        assertEquals("/vless-ws", beta.payload)
        assertEquals("2099-12-31", beta.expiryDate)
      } finally {
        server.stop()
      }
    }

  @Test
  fun remoteJsonErrorsReturnClearBurmeseMessagesAndNeverWipeLocalDatabase() = runBlocking {
    profileRepo.saveProfile(
      ServerProfileEntity(
        name = "Protected Local Server",
        mode = ConnectionMode.SSH_DIRECT.code,
        sshHost = "keep.example.com",
        sshPort = 22,
        username = "keepuser",
        password = "keeppassword",
      ),
      selectAfterSave = true,
    )

    // 1. Bad URL
    val badUrlResult =
      profileRepo.fetchAndImportFromRemoteUrl(url = "ftp://invalid.example.com/servers.json", replaceExisting = true)
    assertTrue(badUrlResult.isFailure)
    assertTrue(
      "Bad URL must return Burmese message",
      badUrlResult.exceptionOrNull()?.message.orEmpty().contains("URL ပုံစံ မမှန်ကန်ပါ"),
    )
    assertEquals(1, db.serverProfileDao().countProfiles())

    // 2. HTTP 500 error
    val errorServer = OneShotHttpServer(statusCode = 500, body = "Internal Server Error")
    val errorPort = errorServer.start()
    try {
      val httpFail =
        profileRepo.fetchAndImportFromRemoteUrl(
          url = "http://127.0.0.1:$errorPort/servers.json",
          replaceExisting = true,
        )
      assertTrue(httpFail.isFailure)
      assertTrue(httpFail.exceptionOrNull()?.message.orEmpty().contains("HTTP 500"))
      assertEquals("HTTP failure must never wipe local Room DB", 1, db.serverProfileDao().countProfiles())
    } finally {
      errorServer.stop()
    }

    // 3. Invalid JSON body
    val badJsonServer = OneShotHttpServer(statusCode = 200, body = "{not-valid-json")
    val badJsonPort = badJsonServer.start()
    try {
      val jsonFail =
        profileRepo.fetchAndImportFromRemoteUrl(
          url = "http://127.0.0.1:$badJsonPort/servers.json",
          replaceExisting = true,
        )
      assertTrue(jsonFail.isFailure)
      assertTrue(
        "Invalid JSON must return Burmese message (got: ${jsonFail.exceptionOrNull()?.message})",
        jsonFail.exceptionOrNull()?.message.orEmpty().contains("JSON ဖော်မတ် မမှန်ကန်ပါ"),
      )
      assertEquals("Invalid JSON must never wipe local Room DB", 1, db.serverProfileDao().countProfiles())
    } finally {
      badJsonServer.stop()
    }
  }

  @Test
  fun expiryCheckerAndViewModelRefuseExpiredProfilesAndAllowNoExpiryAndFutureProfiles() = runBlocking {
    val fixedToday = ProfileExpiryChecker.encodeYmd(2026, 6, 15)
    assertEquals(ProfileExpiryChecker.ExpiryState.NONE, ProfileExpiryChecker.evaluate("", fixedToday))
    assertEquals(ProfileExpiryChecker.ExpiryState.EXPIRED, ProfileExpiryChecker.evaluate("2026-06-14", fixedToday))
    assertEquals(ProfileExpiryChecker.ExpiryState.VALID, ProfileExpiryChecker.evaluate("2026-06-15", fixedToday))
    assertEquals(ProfileExpiryChecker.ExpiryState.VALID, ProfileExpiryChecker.evaluate("2026-06-16", fixedToday))
    assertTrue(ProfileExpiryChecker.isExpired("2020-01-01", fixedToday))
    assertFalse(ProfileExpiryChecker.isExpired("", fixedToday))
    assertFalse(ProfileExpiryChecker.isExpired("2099-12-31", fixedToday))

    val logStore = AppLogStore()
    val fakeVpnController = FakeVpnEngineController()
    val viewModel =
      AmktViewModel(
        repository = profileRepo,
        membershipRepository = MembershipRepository(context),
        vpnPreferencesRepository = VpnPreferencesRepository(context),
        vpnController = fakeVpnController,
        configCodec = ConfigFileCodec(),
        logStore = logStore,
      )

    // Keep StateFlow active just like Compose's collectAsStateWithLifecycle
    val collectorJob = launch(Dispatchers.Unconfined) { viewModel.selectedProfile.collect {} }
    try {
      // 1. Expired profile -> MUST refuse connect with clear Burmese message
      profileRepo.saveProfile(
        ServerProfileEntity(
          name = "Expired SG Node",
          mode = ConnectionMode.SSH_DIRECT.code,
          sshHost = "expired.example.com",
          sshPort = 22,
          username = "demo",
          password = "secret",
          expiryDate = "2020-01-01",
        ),
        selectAfterSave = true,
      )
      awaitSelectedProfileName(viewModel, "Expired SG Node")

      val expiredValidation = viewModel.validateProfileForVpnConnect()
      assertNull("Expired profile must be refused before VPN start", expiredValidation)
      assertEquals(0, fakeVpnController.startCalls)
      assertTrue(
        "Snackbar must display Burmese expiry warning",
        viewModel.snackbarMessage.value.orEmpty().contains("သက်တမ်းကုန်ဆုံးပါပြီ"),
      )
      assertTrue(
        "Activity logs must record EXPIRY ERROR",
        logStore.logs.value.any {
          it.tag == "EXPIRY" && it.level == LogLevel.ERROR && it.message.contains("သက်တမ်းကုန်ဆုံးပါပြီ")
        },
      )

      // 2. No-expiry profile -> MUST pass validation
      profileRepo.saveProfile(
        ServerProfileEntity(
          name = "No Expiry Node",
          mode = ConnectionMode.SSH_DIRECT.code,
          sshHost = "valid.example.com",
          sshPort = 22,
          username = "demo",
          password = "secret",
          expiryDate = "",
        ),
        selectAfterSave = true,
      )
      awaitSelectedProfileName(viewModel, "No Expiry Node")
      assertNotNull("No-expiry profile must be allowed to connect", viewModel.validateProfileForVpnConnect())

      // 3. Future-expiry VLESS WS profile -> MUST pass validation
      profileRepo.saveProfile(
        ServerProfileEntity(
          name = "Future VLESS Node",
          mode = ConnectionMode.VLESS_WS.code,
          sshHost = "edge.example.com",
          sshPort = 80,
          payload = "/vless-ws",
          vlessLink =
            "vless://11111111-2222-3333-4444-555555555555@edge.example.com:80?encryption=none&security=none&type=ws&path=%2Fvless-ws#Future",
          expiryDate = "2099-12-31",
        ),
        selectAfterSave = true,
      )
      awaitSelectedProfileName(viewModel, "Future VLESS Node")
      assertNotNull("Future-expiry VLESS profile must be allowed to connect", viewModel.validateProfileForVpnConnect())

      // 4. VLESS WS profile missing both vlessLink and UUID -> MUST be blocked with Burmese prompt
      profileRepo.saveProfile(
        ServerProfileEntity(
          name = "Blank VLESS Template",
          mode = ConnectionMode.VLESS_WS.code,
          sshHost = "edge.example.com",
          sshPort = 80,
          payload = "/vless-ws",
          vlessLink = "",
          username = "",
          expiryDate = "",
        ),
        selectAfterSave = true,
      )
      awaitSelectedProfileName(viewModel, "Blank VLESS Template")
      assertNull("VLESS profile without UUID/link must not start VPN", viewModel.validateProfileForVpnConnect())
      assertTrue(viewModel.snackbarMessage.value.orEmpty().contains("vless://"))
    } finally {
      collectorJob.cancel()
    }
  }

  private suspend fun awaitSelectedProfileName(viewModel: AmktViewModel, expectedName: String) {
    withTimeout(3_000L) {
      while (viewModel.selectedProfile.value?.name != expectedName) {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        delay(20L)
      }
    }
    Shadows.shadowOf(Looper.getMainLooper()).idle()
    assertEquals(expectedName, viewModel.selectedProfile.first()?.name)
  }

  private class FakeVpnEngineController : VpnEngineController {
    override val state: StateFlow<VpnTunnelState> = MutableStateFlow(VpnTunnelState.Disconnected)
    override val trafficStats: StateFlow<VpnTrafficStats> = MutableStateFlow(VpnTrafficStats())
    override val isImplemented: Boolean = true
    var startCalls = 0

    override fun prepareVpnPermissionIntent(context: Context): Intent? = null

    override fun startTunnel(profile: ServerProfileEntity, killSwitch: Boolean, autoReconnect: Boolean) {
      startCalls += 1
    }

    override fun stopTunnel() = Unit

    override fun updateProtectionSettings(killSwitch: Boolean, autoReconnect: Boolean) = Unit
  }

  private class OneShotHttpServer(
    private val statusCode: Int,
    private val body: String,
  ) {
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var serverSocket: ServerSocket? = null

    fun start(): Int {
      val ss = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
      ss.soTimeout = 4_000
      serverSocket = ss
      executor.submit {
        try {
          ss.accept().use { client ->
            client.soTimeout = 2_000
            val inp = client.getInputStream()
            val reqBuf = ByteArray(1024)
            runCatching { inp.read(reqBuf) }
            val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
            val reason = if (statusCode == 200) "OK" else "Error"
            val headers =
              "HTTP/1.1 $statusCode $reason\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${bodyBytes.size}\r\n" +
                "Connection: close\r\n\r\n"
            val out = client.getOutputStream()
            out.write(headers.toByteArray(StandardCharsets.ISO_8859_1))
            out.write(bodyBytes)
            out.flush()
          }
        } catch (_: IOException) {
          // Closed during teardown
        }
      }
      return ss.localPort
    }

    fun stop() {
      runCatching { serverSocket?.close() }
      executor.shutdownNow()
      executor.awaitTermination(2, TimeUnit.SECONDS)
    }
  }
}
