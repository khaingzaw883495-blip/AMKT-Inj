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
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.ads.AdsterraConfig
import com.example.ads.ConnectGateDecision
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
import com.example.membership.OwnerBanner
import com.example.redeem.RedeemRepository
import com.example.ui.AmktViewModel
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp")
class InjNoAdsTest {

  @get:Rule val composeRule = createComposeRule()

  private lateinit var context: Context
  private lateinit var db: AmktDatabase

  private val sampleNetwork =
    NetworkConfigEntity(
      id = 1L,
      name = "DataNoPro",
      payloadMode = PayloadMode.DIRECT.code,
    )

  private val freeServer =
    ServerAccountEntity(
      id = 10L,
      name = "Japan Free",
      connectionMode = ConnectionMode.SSH_DIRECT.code,
      host = "free-jp.example.com",
      port = 22,
      username = "free_user",
      password = "free_pass",
      region = "JP",
      tags = "FREE",
      isFromRemote = true,
    )

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    db = Room.inMemoryDatabaseBuilder(context, AmktDatabase::class.java).allowMainThreadQueries().build()
  }

  @After
  fun tearDown() {
    Shadows.shadowOf(Looper.getMainLooper()).idle()
    db.close()
  }

  @Test
  fun activeInjCodeUserSeesZeroAdsOnEveryScreenAndNoConnectGateThenAdsReturnWhenRemoved() {
    val currentDay = AtomicInteger(20261125)
    val clockMs = AtomicLong(1_764_000_000_000L)
    val memberPrefs = File(context.cacheDir, "inj_noads_member_${UUID.randomUUID()}.preferences_pb")
    val remotePrefs = File(context.cacheDir, "inj_noads_remote_${UUID.randomUUID()}.preferences_pb")
    val redeemPrefs = File(context.cacheDir, "inj_noads_redeem_${UUID.randomUUID()}.preferences_pb")

    val membershipRepo =
      MembershipRepository(
        context = context,
        dataStore = PreferenceDataStoreFactory.create { memberPrefs },
        todayYmdProvider = { currentDay.get() },
        nowMsProvider = { clockMs.get() },
      )
    val remoteRepo =
      RemoteServerListRepository(
        context = context,
        dataStore = PreferenceDataStoreFactory.create { remotePrefs },
      )
    val redeemRepo =
      RedeemRepository(
        context = context,
        dataStore = PreferenceDataStoreFactory.create { redeemPrefs },
      )
    val splitRepo = NetworkServerRepository(db.networkConfigDao(), db.serverAccountDao(), remoteRepo)
    val legacyRepo = ServerProfileRepository(db.serverProfileDao(), remoteRepo)
    val vpnController = RecordingVpnController()

    // Seed a Remote owner announcement banner ("ads" JSON), a network, and a free server
    val (netId, freeId) =
      runBlocking {
        membershipRepo.saveOwnerBanner(
          OwnerBanner(
            imageUrl = "https://cdn.example.org/promo-ad.png",
            linkUrl = "https://t.me/amktvpn_offer",
            title = "Owner Promo Banner",
          )
        )
        val nId = splitRepo.saveNetwork(sampleNetwork.copy(id = 0L), selectAfterSave = true)
        val fId = splitRepo.saveServer(freeServer.copy(id = 0L), selectAfterSave = true)
        splitRepo.selectCombination(nId, fId)
        nId to fId
      }

    // Start mock redeem webhook returning an active INJ paid server
    val webhookJson =
      """
      {
        "valid": true,
        "message": "VIP INJ server activated!",
        "server": {
          "mode": "ssh",
          "name": "VIP Singapore INJ",
          "host": "vip-sg.example.com",
          "port": 22,
          "username": "vip_u",
          "password": "vip_p",
          "expires_at": "2099-12-31",
          "region": "SG"
        }
      }
      """.trimIndent()
    val webhookServer = OneShotHttpServer(200, webhookJson)
    val webhookPort = webhookServer.start()
    try {
      runBlocking {
        redeemRepo.saveRedeemWebhookUrl("http://127.0.0.1:$webhookPort/webhook/amkt-injector-redeem")
      }

      val viewModel =
        AmktViewModel(
          repository = legacyRepo,
          membershipRepository = membershipRepo,
          vpnPreferencesRepository = VpnPreferencesRepository(context),
          vpnController = vpnController,
          configCodec = ConfigFileCodec(),
          logStore = AppLogStore(),
          networkServerRepository = splitRepo,
          redeemRepository = redeemRepo,
        )

      val openedBrowserUrls = mutableListOf<String>()

      composeRule.setContent {
        MyApplicationTheme(darkTheme = true) {
          AmktInjectorApp(
            viewModel = viewModel,
            adGateTickIntervalMs = 25L,
            adGateOpenBrowserOverride = { url ->
              openedBrowserUrls.add(url)
              true
            },
          )
        }
      }

      awaitUiCondition("Initial free selection ready") {
        viewModel.selectedNetwork.value?.id == netId && viewModel.selectedServer.value?.id == freeId
      }

      // Fresh install (no INJ code yet): ALL ads ARE present on Home
      composeRule.waitForIdle()
      assertAllHomeAdsPresent()

      // Navigate to Redeem screen and redeem valid INJ code
      composeRule.onNodeWithTag("home_redeem_button").performScrollTo().performClick()
      composeRule.waitForIdle()
      composeRule.onNodeWithTag("redeem_code_input").performTextInput("INJ-PAID-2026")
      composeRule.onNodeWithTag("redeem_submit_button").performClick()

      awaitUiCondition("INJ redeem should add active paid server") {
        viewModel.hasActiveRedeemedServer.value &&
          viewModel.servers.value.any { it.isRedeemedServer() && it.name == "VIP Singapore INJ" }
      }
      composeRule.waitForIdle()
      composeRule.onNodeWithTag("redeem_success_card").assertIsDisplayed()
      assertNoAdsAnywhereInTree()

      // Return Home from Redeem via Back -> ZERO ads on Home (no bottom banners, no own-ad banner, no Extend Time)
      composeRule.onNodeWithTag("redeem_go_home_button").performClick()
      composeRule.waitForIdle()
      assertNoAdsAnywhereInTree()

      // Tap Connect on the active INJ server -> NO 7s gate, NO external browser, VPN starts immediately
      composeRule.onNodeWithTag("connect_toggle_button").performScrollTo().performClick()
      awaitUiCondition("Paid INJ user must connect immediately without 7s gate") {
        vpnController.startCount == 1
      }
      assertEquals(0, openedBrowserUrls.size)
      composeRule.onAllNodesWithTag("ad_gate_dialog", useUnmergedTree = true).assertCountEquals(0)
      assertNoAdsAnywhereInTree()

      // Even if paid user picks another server while their active INJ server exists, STILL zero ads & no gate
      vpnController.setDisconnected()
      runBlocking { splitRepo.selectServer(freeId) }
      awaitUiCondition("Switched to free server") { viewModel.selectedServer.value?.id == freeId }
      composeRule.waitForIdle()
      assertNoAdsAnywhereInTree()
      composeRule.onNodeWithTag("connect_toggle_button").performScrollTo().performClick()
      awaitUiCondition("Paid INJ user remains ad-free across servers") {
        vpnController.startCount == 2
      }
      assertEquals(0, openedBrowserUrls.size)

      // Navigate through EVERY screen while paid: Networks -> Servers -> Home -> Settings -> Redeem -> Home
      composeRule.onNodeWithTag("nav_networks").performClick()
      composeRule.waitForIdle()
      assertNoAdsAnywhereInTree()

      composeRule.onNodeWithTag("nav_profiles").performClick()
      composeRule.waitForIdle()
      assertNoAdsAnywhereInTree()

      composeRule.onNodeWithTag("nav_home").performClick()
      composeRule.waitForIdle()
      assertNoAdsAnywhereInTree()

      composeRule.onNodeWithTag("home_settings_button").performClick()
      composeRule.waitForIdle()
      assertNoAdsAnywhereInTree()

      composeRule.onNodeWithTag("settings_redeem_button").performScrollTo().performClick()
      composeRule.waitForIdle()
      assertNoAdsAnywhereInTree()

      composeRule.onNodeWithTag("redeem_back_button").performClick()
      composeRule.waitForIdle()
      composeRule.onNodeWithTag("settings_back_button").performClick()
      composeRule.waitForIdle()
      assertNoAdsAnywhereInTree()

      // Now remove the INJ redeemed server from DB -> reverts to free mode and ALL ads return
      vpnController.setDisconnected()
      val redeemedId = viewModel.servers.value.first { it.isRedeemedServer() }.id
      runBlocking { splitRepo.deleteServer(redeemedId) }
      awaitUiCondition("Removing INJ server must revert hasActiveRedeemedServer to false") {
        !viewModel.hasActiveRedeemedServer.value
      }
      composeRule.waitForIdle()
      assertAllHomeAdsPresent()

      // Connecting now triggers the 7-second Smartlink_2 external-browser gate
      composeRule.onNodeWithTag("connect_toggle_button").performScrollTo().performClick()
      awaitUiCondition("Free mode after INJ removal must open 7s Smartlink_2 gate") {
        openedBrowserUrls.size == 1
      }
      assertEquals(AdsterraConfig.SMARTLINK_URL, openedBrowserUrls.first())
      awaitUiCondition("VPN should connect once 7s gate finishes") { vpnController.startCount == 3 }
    } finally {
      webhookServer.stop()
    }
  }

  @Test
  fun expiredInjCodeServerDoesNotHideAdsAndTriggersConnectGateWhenRevertingToFreeServer() {
    val currentDay = AtomicInteger(20261125)
    val clockMs = AtomicLong(1_764_000_000_000L)
    val memberPrefs = File(context.cacheDir, "inj_exp_member_${UUID.randomUUID()}.preferences_pb")
    val remotePrefs = File(context.cacheDir, "inj_exp_remote_${UUID.randomUUID()}.preferences_pb")

    val membershipRepo =
      MembershipRepository(
        context = context,
        dataStore = PreferenceDataStoreFactory.create { memberPrefs },
        todayYmdProvider = { currentDay.get() },
        nowMsProvider = { clockMs.get() },
      )
    val remoteRepo =
      RemoteServerListRepository(
        context = context,
        dataStore = PreferenceDataStoreFactory.create { remotePrefs },
      )
    val splitRepo = NetworkServerRepository(db.networkConfigDao(), db.serverAccountDao(), remoteRepo)
    val legacyRepo = ServerProfileRepository(db.serverProfileDao(), remoteRepo)
    val vpnController = RecordingVpnController()

    val expiredRedeemedServer =
      ServerAccountEntity(
        id = 0L,
        name = "Expired INJ VIP",
        connectionMode = ConnectionMode.SSH_DIRECT.code,
        host = "vip-exp.example.com",
        port = 22,
        username = "vip_exp_u",
        password = "vip_exp_p",
        expiryDate = "2020-01-01",
        tags = "REDEEM:INJ-EXPIRED-01",
        isFromRemote = false,
      )

    runBlocking {
      membershipRepo.saveOwnerBanner(
        OwnerBanner(
          imageUrl = "https://cdn.example.org/banner.png",
          linkUrl = "https://t.me/amktvpn",
          title = "Free Banner",
        )
      )
      val netId = splitRepo.saveNetwork(sampleNetwork.copy(id = 0L), selectAfterSave = true)
      val expVipId = splitRepo.saveServer(expiredRedeemedServer, selectAfterSave = false)
      val freeId = splitRepo.saveServer(freeServer.copy(id = 0L), selectAfterSave = true)
      splitRepo.selectCombination(netId, freeId)
      assertTrue(splitRepo.getServerById(expVipId)!!.isRedeemedServer())
    }

    val viewModel =
      AmktViewModel(
        repository = legacyRepo,
        membershipRepository = membershipRepo,
        vpnPreferencesRepository = VpnPreferencesRepository(context),
        vpnController = vpnController,
        configCodec = ConfigFileCodec(),
        logStore = AppLogStore(),
        networkServerRepository = splitRepo,
      )

    // ViewModel / AdsterraConfig must NOT treat an expired INJ server as active paid status
    runBlocking {
      assertTrue(viewModel.shouldShowConnectGateFor(freeServer))
      assertEquals(
        ConnectGateDecision.ShowGateAndGrantInitialFree,
        viewModel.evaluateConnectDecision(freeServer),
      )
      assertTrue(
        AdsterraConfig.shouldShowConnectGate(
          server = expiredRedeemedServer,
          passStatus = membershipRepo.passStatusFlow.valueIfInitialized(),
          todayYmd = 20261125,
          hasActiveRedeemedServer = false,
        )
      )
    }

    val openedUrls = mutableListOf<String>()
    composeRule.setContent {
      MyApplicationTheme(darkTheme = true) {
        AmktInjectorApp(
          viewModel = viewModel,
          adGateTickIntervalMs = 25L,
          adGateOpenBrowserOverride = { url ->
            openedUrls.add(url)
            true
          },
        )
      }
    }

    composeRule.waitForIdle()
    assertFalse(viewModel.hasActiveRedeemedServer.value)
    assertAllHomeAdsPresent()

    composeRule.onNodeWithTag("connect_toggle_button").performScrollTo().performClick()
    awaitUiCondition("Expired INJ server must NOT exempt user from Smartlink_2 gate") {
      openedUrls.size == 1
    }
    awaitUiCondition("VPN connects after gate") { vpnController.startCount == 1 }
  }

  private fun assertNoAdsAnywhereInTree() {
    listOf(
      "home_owner_banner_card",
      "home_owner_banner_remote_image",
      "home_owner_banner_fallback",
      "home_smartlink_bottom_banner",
      "home_adsterra_320x50_banner",
      "home_extend_time_button",
      "ad_gate_dialog",
    ).forEach { tag ->
      composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).assertCountEquals(0)
    }
  }

  private fun assertAllHomeAdsPresent() {
    val lazyList = composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
    lazyList.performScrollToNode(hasTestTag("home_extend_time_button"))
    composeRule.onNodeWithTag("home_extend_time_button").performScrollTo().assertIsDisplayed()

    lazyList.performScrollToNode(hasTestTag("home_owner_banner_card"))
    composeRule.onNodeWithTag("home_owner_banner_card").performScrollTo().assertIsDisplayed()

    lazyList.performScrollToNode(hasTestTag("home_smartlink_bottom_banner"))
    composeRule.onNodeWithTag("home_smartlink_bottom_banner").performScrollTo().assertIsDisplayed()

    lazyList.performScrollToNode(hasTestTag("home_adsterra_320x50_banner"))
    composeRule.onNodeWithTag("home_adsterra_320x50_banner").performScrollTo().assertIsDisplayed()
  }

  private fun awaitUiCondition(
    failureMessage: String,
    timeoutMs: Long = 5_000L,
    condition: () -> Boolean,
  ) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!condition() && System.currentTimeMillis() < deadline) {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      composeRule.mainClock.advanceTimeBy(40L)
      composeRule.waitForIdle()
      Thread.sleep(20L)
    }
    Shadows.shadowOf(Looper.getMainLooper()).idle()
    assertTrue(failureMessage, condition())
  }

  private fun kotlinx.coroutines.flow.Flow<com.example.membership.PassStatus>.valueIfInitialized():
    com.example.membership.PassStatus = com.example.membership.PassStatus()

  private class RecordingVpnController : VpnEngineController {
    private val _state = MutableStateFlow<VpnTunnelState>(VpnTunnelState.Disconnected)
    override val state: StateFlow<VpnTunnelState> = _state
    override val trafficStats: StateFlow<VpnTrafficStats> = MutableStateFlow(VpnTrafficStats())
    override val isImplemented: Boolean = true

    var startCount: Int = 0
      private set

    override fun prepareVpnPermissionIntent(context: Context): Intent? = null

    override fun startTunnel(
      profile: ServerProfileEntity,
      killSwitch: Boolean,
      autoReconnect: Boolean,
    ) {
      startCount += 1
      _state.value =
        VpnTunnelState.Connected(
          profileName = profile.name,
          targetHost = profile.sshHost,
          targetPort = profile.sshPort,
          connectedAtMs = System.currentTimeMillis(),
        )
    }

    override fun stopTunnel() {
      setDisconnected()
    }

    fun setDisconnected() {
      _state.value = VpnTunnelState.Disconnected
    }

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
          // Closed on teardown
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
