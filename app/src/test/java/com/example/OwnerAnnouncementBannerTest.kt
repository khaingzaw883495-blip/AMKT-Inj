package com.example

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
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
import com.example.data.ServerListLoader
import com.example.data.ServerProfileEntity
import com.example.data.ServerProfileRepository
import com.example.membership.MembershipRepository
import com.example.membership.OwnerBanner
import com.example.membership.PassStatus
import com.example.ui.AmktViewModel
import com.example.ui.home.HomeScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.vpn.VpnEngineController
import com.example.vpn.VpnPreferencesRepository
import com.example.vpn.VpnTrafficStats
import com.example.vpn.VpnTunnelState
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class OwnerAnnouncementBannerTest {

  @get:Rule val composeRule = createComposeRule()

  private lateinit var context: Context
  private lateinit var db: AmktDatabase
  private lateinit var remoteRepo: RemoteServerListRepository
  private lateinit var splitRepo: NetworkServerRepository
  private lateinit var legacyRepo: ServerProfileRepository
  private lateinit var membershipRepo: MembershipRepository

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    db = Room.inMemoryDatabaseBuilder(context, AmktDatabase::class.java).allowMainThreadQueries().build()
    val remotePrefs = File(context.cacheDir, "banner_remote_${UUID.randomUUID()}.preferences_pb")
    val memberPrefs = File(context.cacheDir, "banner_member_${UUID.randomUUID()}.preferences_pb")
    remoteRepo =
      RemoteServerListRepository(
        context = context,
        dataStore = PreferenceDataStoreFactory.create { remotePrefs },
      )
    splitRepo = NetworkServerRepository(db.networkConfigDao(), db.serverAccountDao(), remoteRepo)
    legacyRepo = ServerProfileRepository(db.serverProfileDao(), remoteRepo)
    membershipRepo =
      MembershipRepository(
        context = context,
        dataStore = PreferenceDataStoreFactory.create { memberPrefs },
      )
  }

  @After
  fun tearDown() {
    db.close()
  }

  @Test
  fun remoteJsonAdsSectionParsesSanitizesUrlsAndFallsBackWhenAbsent() {
    val loader = ServerListLoader()

    val withAdsJson =
      """
      {
        "format_version": 2,
        "networks": [{"name": "Direct Net", "payload_mode": "DIRECT"}],
        "servers": [{"name": "Japan 01", "mode": "SSH_DIRECT", "host": "jp.example.com", "port": 22}],
        "ads": {
          "image": "https://cdn.example.org/amkt-promo.png",
          "link": "https://t.me/amktvpn_updates",
          "title": "AMKT Notice"
        }
      }
      """.trimIndent()

    val parsedWithAds = loader.parseSplitRemoteList(withAdsJson).getOrThrow()
    assertNotNull(parsedWithAds.ownerBanner)
    assertEquals("https://cdn.example.org/amkt-promo.png", parsedWithAds.ownerBanner!!.imageUrl)
    assertEquals("https://t.me/amktvpn_updates", parsedWithAds.ownerBanner!!.linkUrl)
    assertEquals("AMKT Notice", parsedWithAds.ownerBanner!!.title)

    // Unsafe URL schemes must be stripped/rejected cleanly
    val unsafeJson =
      """
      {
        "format_version": 2,
        "networks": [{"name": "Direct Net", "payload_mode": "DIRECT"}],
        "servers": [],
        "ads": {
          "image": "javascript:alert(1)",
          "link": "data:text/html,<h1>xss</h1>"
        }
      }
      """.trimIndent()
    val parsedUnsafe = loader.parseSplitRemoteList(unsafeJson).getOrThrow()
    assertNull("Unsafe javascript:/data: URLs must be rejected", parsedUnsafe.ownerBanner)

    // Missing ads section yields null -> Home falls back to https://t.me/amktvpn
    val withoutAdsJson =
      """
      {
        "format_version": 2,
        "networks": [{"name": "Direct Net", "payload_mode": "DIRECT"}],
        "servers": []
      }
      """.trimIndent()
    val parsedNoAds = loader.parseSplitRemoteList(withoutAdsJson).getOrThrow()
    assertNull(parsedNoAds.ownerBanner)
  }

  @Test
  fun viewModelRemoteSyncPersistsOwnerBannerUpdatesWithoutRebuildAndPreservesOnFailure() = runBlocking {
    val viewModel =
      AmktViewModel(
        repository = legacyRepo,
        membershipRepository = membershipRepo,
        vpnPreferencesRepository = VpnPreferencesRepository(context),
        vpnController = NoOpVpnController(),
        configCodec = ConfigFileCodec(),
        logStore = AppLogStore(),
        networkServerRepository = splitRepo,
      )

    val bannerCollector = launch(Dispatchers.Unconfined) { viewModel.ownerBanner.collect {} }
    try {
      // 1. Remote JSON with "ads" saves remote banner image + link
      val jsonWithAds =
        """
        {
          "format_version": 2,
          "networks": [{"name": "Direct Net", "payload_mode": "DIRECT"}],
          "servers": [{"name": "JP 1", "mode": "SSH_DIRECT", "host": "jp1.example.com", "port": 22}],
          "ads": {
            "image": "https://cdn.example.org/banner1.png",
            "link": "https://example.org/amkt-offer"
          }
        }
        """.trimIndent()
      val server1 = OneShotHttpServer(200, jsonWithAds)
      val port1 = server1.start()
      try {
        viewModel.fetchServersFromRemoteUrl("http://127.0.0.1:$port1/list.json", replaceExisting = false)
        awaitCondition {
          membershipRepo.ownerBannerFlow.first()?.linkUrl == "https://example.org/amkt-offer"
        }
        val stored1 = membershipRepo.ownerBannerFlow.first()!!
        assertEquals("https://cdn.example.org/banner1.png", stored1.imageUrl)
        assertEquals("https://example.org/amkt-offer", stored1.linkUrl)
      } finally {
        server1.stop()
      }

      // 2. Failing Remote JSON fetch MUST keep existing banner + servers intact
      val serverFail = OneShotHttpServer(500, "server error")
      val portFail = serverFail.start()
      try {
        viewModel.fetchServersFromRemoteUrl("http://127.0.0.1:$portFail/list.json", replaceExisting = false)
        awaitCondition { viewModel.remoteSyncFeedback.value?.isError == true }
        val stillStored = membershipRepo.ownerBannerFlow.first()
        assertEquals("https://example.org/amkt-offer", stillStored?.linkUrl)
        assertEquals(1, splitRepo.getAllServersSnapshot().size)
      } finally {
        serverFail.stop()
      }

      // 3. Remote JSON without "ads" clears remote banner so Home uses built-in Telegram fallback
      val jsonWithoutAds =
        """
        {
          "format_version": 2,
          "networks": [{"name": "Direct Net", "payload_mode": "DIRECT"}],
          "servers": [{"name": "JP 1", "mode": "SSH_DIRECT", "host": "jp1.example.com", "port": 22}]
        }
        """.trimIndent()
      val server2 = OneShotHttpServer(200, jsonWithoutAds)
      val port2 = server2.start()
      try {
        viewModel.fetchServersFromRemoteUrl("http://127.0.0.1:$port2/list.json", replaceExisting = false)
        awaitCondition { membershipRepo.ownerBannerFlow.first() == null }
        assertNull(membershipRepo.ownerBannerFlow.first())
      } finally {
        server2.stop()
      }
    } finally {
      bannerCollector.cancel()
    }
  }

  @Test
  fun passStatusExpiryAndRedeemedServerHelpersWorkAccurately() {
    assertFalse(PassStatus(isActive = false, expiresAt = "2099-12-31").isCurrentlyValid())
    assertTrue(PassStatus(isActive = true, expiresAt = "2099-12-31").isCurrentlyValid())
    assertTrue(PassStatus(isActive = true, expiresAt = "").isCurrentlyValid())
    assertFalse(PassStatus(isActive = true, expiresAt = "2020-01-01").isCurrentlyValid())

    val redeemedServer =
      ServerAccountEntity(
        name = "VIP JP",
        connectionMode = ConnectionMode.SSH_DIRECT.code,
        tags = "VIP, REDEEM:INJ-1234-ABCD",
      )
    val remoteServer =
      ServerAccountEntity(
        name = "Free JP",
        connectionMode = ConnectionMode.SSH_DIRECT.code,
        tags = "FREE",
      )
    assertTrue(redeemedServer.isRedeemedServer())
    assertFalse(remoteServer.isRedeemedServer())
  }

  @Test
  fun homeScreenShowsTelegramFallbackWhenNoRemoteBannerAndOpensTelegramWithoutCoveringConnect() {
    var openedUrl: String? = null
    var connectClicks = 0

    val net = NetworkConfigEntity(id = 1L, name = "DataNoPro", payloadMode = PayloadMode.DIRECT.code)
    val srv =
      ServerAccountEntity(
        id = 2L,
        name = "Japan",
        connectionMode = ConnectionMode.SSH_DIRECT.code,
        host = "secret-host-99.example.com",
        port = 2222,
        username = "hidden_user_88",
        password = "hidden_pass_88",
        region = "JP",
      )

    composeRule.setContent {
      MyApplicationTheme(darkTheme = true) {
        HomeScreen(
          networks = listOf(net),
          servers = listOf(srv),
          selectedNetwork = net,
          selectedServer = srv,
          vpnState = VpnTunnelState.Disconnected,
          trafficStats = VpnTrafficStats(),
          ownerBanner = null,
          onConnectClick = { connectClicks += 1 },
          onReconnectClick = {},
          onSelectNetwork = {},
          onSelectServer = {},
          onOpenNetworksClick = {},
          onOpenServersClick = {},
          onOpenBannerLink = { openedUrl = it },
        )
      }
    }
    composeRule.waitForIdle()

    val lazyList = composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))

    // CONNECT button is unobstructed and responds immediately
    lazyList.performScrollToNode(hasTestTag("connect_toggle_button"))
    composeRule.onNodeWithTag("connect_toggle_button").assertIsDisplayed().performClick()
    assertEquals(1, connectClicks)

    // Built-in Telegram fallback card is shown at the bottom and opens https://t.me/amktvpn
    lazyList.performScrollToNode(hasTestTag("home_owner_banner_card"))
    composeRule.onNodeWithTag("home_owner_banner_fallback", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
    composeRule.onNodeWithText("AMKT VPN Telegram Channel", useUnmergedTree = true).assertIsDisplayed()
    composeRule.onNodeWithTag("home_owner_banner_card").performClick()
    assertEquals(OwnerBanner.DEFAULT_TELEGRAM_URL, openedUrl)

    // Zero server details leaked
    listOf("secret-host-99.example.com", "2222", "hidden_user_88", "hidden_pass_88").forEach { secret ->
      composeRule.onAllNodesWithText(secret, substring = true, useUnmergedTree = true).assertCountEquals(0)
    }
  }

  @Test
  fun homeScreenShowsRemoteOwnerBannerAndOpensConfiguredLink() {
    var openedUrl: String? = null
    val remoteBanner =
      OwnerBanner(
        imageUrl = "https://cdn.example.org/promo.png",
        linkUrl = "https://t.me/amktvpn_promo",
        title = "AMKT Promo",
      )

    composeRule.setContent {
      MyApplicationTheme(darkTheme = true) {
        HomeScreen(
          networks = emptyList(),
          servers = emptyList(),
          selectedNetwork = null,
          selectedServer = null,
          vpnState = VpnTunnelState.Disconnected,
          trafficStats = VpnTrafficStats(),
          ownerBanner = remoteBanner,
          onConnectClick = {},
          onReconnectClick = {},
          onSelectNetwork = {},
          onSelectServer = {},
          onOpenNetworksClick = {},
          onOpenServersClick = {},
          onOpenBannerLink = { openedUrl = it },
        )
      }
    }
    composeRule.waitForIdle()

    val lazyList = composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
    lazyList.performScrollToNode(hasTestTag("home_owner_banner_card"))
    composeRule.onNodeWithTag("home_owner_banner_card").performScrollTo().assertIsDisplayed().performClick()
    assertEquals("https://t.me/amktvpn_promo", openedUrl)
  }

  private suspend fun awaitCondition(condition: suspend () -> Boolean) {
    val deadlineNanos = System.nanoTime() + 5_000_000_000L
    while (!condition() && System.nanoTime() < deadlineNanos) {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      delay(20L)
    }
    Shadows.shadowOf(Looper.getMainLooper()).idle()
    assertTrue("Timed out waiting for expected owner banner state", condition())
  }

  private class NoOpVpnController : VpnEngineController {
    override val state: StateFlow<VpnTunnelState> = MutableStateFlow(VpnTunnelState.Disconnected)
    override val trafficStats: StateFlow<VpnTrafficStats> = MutableStateFlow(VpnTrafficStats())
    override val isImplemented: Boolean = true

    override fun prepareVpnPermissionIntent(context: Context): Intent? = null

    override fun startTunnel(profile: ServerProfileEntity, killSwitch: Boolean, autoReconnect: Boolean) = Unit

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
