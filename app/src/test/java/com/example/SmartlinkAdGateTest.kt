package com.example

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
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
import com.example.membership.PassStatus
import com.example.ui.AmktViewModel
import com.example.ui.ads.ConnectAdGateDialog
import com.example.ui.home.HomeScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.vpn.VpnEngineController
import com.example.vpn.VpnPreferencesRepository
import com.example.vpn.VpnTrafficStats
import com.example.vpn.VpnTunnelState
import java.io.File
import java.util.UUID
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
@Config(sdk = [36], qualifiers = "w412dp-h915dp")
class SmartlinkAdGateTest {

  @get:Rule val composeRule = createComposeRule()

  private lateinit var context: Context
  private lateinit var db: AmktDatabase

  private val expectedNewSmartlink = "https://asiafilm.org/4/711d037be4e548a098cb6dfbc68b6edb"
  private val deprecatedOldSmartlinkToken = "e7bc65934a2fb9eef0e7b8c88cef974e"
  private val expectedBanner320x50Url = "https://bellnewyork.org/22/fe46d0afda2f021f7659b690e452eff4"

  private val sampleNetwork =
    NetworkConfigEntity(
      id = 1L,
      name = "DataNoPro",
      payloadMode = PayloadMode.DIRECT.code,
    )

  private val freeRemoteServer =
    ServerAccountEntity(
      id = 10L,
      name = "Japan Free",
      connectionMode = ConnectionMode.SSH_DIRECT.code,
      host = "secret-jp-host.example.com",
      port = 2222,
      username = "hidden_ssh_user",
      password = "hidden_ssh_pass",
      region = "JP",
      tags = "FREE",
      isUserImported = false,
      isFromRemote = true,
    )

  private val importedServer =
    ServerAccountEntity(
      id = 11L,
      name = "My Imported SG",
      connectionMode = ConnectionMode.SSH_DIRECT.code,
      host = "imported-sg.example.com",
      port = 22,
      username = "imp_user",
      password = "imp_pass",
      region = "SG",
      tags = "",
      isUserImported = true,
      isFromRemote = false,
    )

  private val redeemedServer =
    ServerAccountEntity(
      id = 12L,
      name = "VIP Japan",
      connectionMode = ConnectionMode.SSH_DIRECT.code,
      host = "vip-jp.example.com",
      port = 22,
      username = "vip_user",
      password = "vip_pass",
      region = "JP",
      tags = "VIP, REDEEM:INJ-7777-8888",
      isUserImported = false,
      isFromRemote = false,
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
  fun newSmartlinkUrlAndBanner320x50ConfigAreConfiguredAndUnsafeSchemesRejected() {
    assertEquals(expectedNewSmartlink, AdsterraConfig.SMARTLINK_URL)
    assertFalse(AdsterraConfig.SMARTLINK_URL.contains(deprecatedOldSmartlinkToken))
    assertEquals(expectedBanner320x50Url, AdsterraConfig.BANNER_320X50_SCRIPT_URL)
    assertEquals(7, AdsterraConfig.CONNECT_GATE_SECONDS)
    assertEquals(2, AdsterraConfig.MAX_GATE_SHOWS_PER_DAY)
    assertEquals(6, AdsterraConfig.INITIAL_FREE_HOURS)
    assertEquals(9, AdsterraConfig.AD_GRANT_HOURS)

    val bannerHtml = AdsterraConfig.buildBanner320x50Html()
    assertTrue(bannerHtml.contains("fe46d0afda2f021f7659b690e452eff4"))
    assertTrue(bannerHtml.contains(expectedBanner320x50Url))
    assertTrue(bannerHtml.contains("'width' : 320"))
    assertTrue(bannerHtml.contains("'height' : 50"))
    assertFalse(bannerHtml.contains(deprecatedOldSmartlinkToken))

    // URL scheme guard permits only http/https
    assertTrue(AdsterraConfig.isAllowedWebViewUrl(expectedNewSmartlink))
    assertTrue(AdsterraConfig.isAllowedWebViewUrl("http://example.com/ad"))
    assertFalse(AdsterraConfig.isAllowedWebViewUrl("javascript:alert(1)"))
    assertFalse(AdsterraConfig.isAllowedWebViewUrl("file:///etc/passwd"))
    assertFalse(AdsterraConfig.isAllowedWebViewUrl("content://com.example/secret"))
    assertFalse(AdsterraConfig.isAllowedWebViewUrl("intent://scan/#Intent;end"))
    assertFalse(AdsterraConfig.isAllowedWebViewUrl("data:text/html,<h1>x</h1>"))
    assertFalse(AdsterraConfig.isAllowedWebViewUrl(""))
    assertFalse(AdsterraConfig.isAllowedWebViewUrl(null))
  }

  @Test
  fun connectGateShowsOnEveryConnectWithNoDailyLimitWhileExtendGrantsCapAtTwoPerDay(): Unit = runBlocking {
    val currentDay = AtomicInteger(20261125)
    val prefsFile = File(context.cacheDir, "gate_cap_${UUID.randomUUID()}.preferences_pb")
    val membershipRepo =
      MembershipRepository(
        context = context,
        dataStore = PreferenceDataStoreFactory.create { prefsFile },
        todayYmdProvider = { currentDay.get() },
      )
    val viewModel = buildViewModel(membershipRepo = membershipRepo)

    // Connect gate shows EVERY time (1st, 2nd, 3rd, 4th consecutive connects) with NO daily limit
    assertTrue("1st connect must show gate", viewModel.evaluateAndConsumeConnectGate(freeRemoteServer))
    assertTrue("2nd connect must show gate", viewModel.evaluateAndConsumeConnectGate(freeRemoteServer))
    assertTrue("3rd connect in a row must STILL show gate", viewModel.evaluateAndConsumeConnectGate(freeRemoteServer))
    assertTrue("4th connect must STILL show gate", viewModel.evaluateAndConsumeConnectGate(importedServer))
    assertEquals(4, membershipRepo.getAdGateCountForDate())

    // Meanwhile, +9h time-extension grants remain untouched (0/2) and have their OWN 2x/day counter
    assertEquals(0, membershipRepo.getTimeExtendGrantsUsedToday())
    assertTrue(membershipRepo.recordAdWatchAndGrantFreeTime().isSuccess)
    assertTrue(membershipRepo.recordAdWatchAndGrantFreeTime().isSuccess)
    assertTrue("3rd +9h grant same day must be refused", membershipRepo.recordAdWatchAndGrantFreeTime().isFailure)
    assertEquals(2, membershipRepo.getTimeExtendGrantsUsedToday())

    // Midnight rollover resets the 2x/day grant counter
    currentDay.set(20261126)
    assertEquals(0, membershipRepo.getTimeExtendGrantsUsedToday())
    assertTrue(membershipRepo.recordAdWatchAndGrantFreeTime().isSuccess)
  }

  @Test
  fun redeemedServerAndValidNoAdsCodeSkipGateWithoutConsumingQuotaWhileExpiredCodeShowsGate(): Unit = runBlocking {
    val currentDay = AtomicInteger(20261125)
    val prefsFile = File(context.cacheDir, "gate_exempt_${UUID.randomUUID()}.preferences_pb")
    val dataStore = PreferenceDataStoreFactory.create { prefsFile }
    val membershipRepo =
      MembershipRepository(
        context = context,
        dataStore = dataStore,
        todayYmdProvider = { currentDay.get() },
      )
    val viewModel = buildViewModel(membershipRepo = membershipRepo)

    // 1. INJ-redeemed server skips gate
    assertFalse(viewModel.evaluateAndConsumeConnectGate(redeemedServer))
    assertEquals(0, membershipRepo.getAdGateCountForDate())

    // 2. Imported profile (non-redeemed) DOES show gate
    assertTrue(viewModel.evaluateAndConsumeConnectGate(importedServer))
    assertEquals(1, membershipRepo.getAdGateCountForDate())

    // 3. Valid stored no-ads code skips gate AND hides Adsterra banners
    dataStore.edit { prefs ->
      prefs[booleanPreferencesKey("pass_active")] = true
      prefs[stringPreferencesKey("pass_code")] = "NOADS-VALID-2026"
      prefs[stringPreferencesKey("pass_expires_at")] = "2099-12-31"
    }
    assertFalse(viewModel.evaluateAndConsumeConnectGate(freeRemoteServer))
    assertEquals(1, membershipRepo.getAdGateCountForDate())
    assertFalse(
      AdsterraConfig.shouldShowAdsterraBanners(
        PassStatus(isActive = true, expiresAt = "2099-12-31"),
        todayYmd = 20261125,
      )
    )

    // 4. Expired stored no-ads code resumes showing gate + Adsterra banners
    dataStore.edit { prefs ->
      prefs[booleanPreferencesKey("pass_active")] = true
      prefs[stringPreferencesKey("pass_code")] = "NOADS-EXPIRED"
      prefs[stringPreferencesKey("pass_expires_at")] = "2026-11-24"
    }
    assertTrue(viewModel.evaluateAndConsumeConnectGate(freeRemoteServer))
    assertEquals(2, membershipRepo.getAdGateCountForDate())
    assertTrue(
      AdsterraConfig.shouldShowAdsterraBanners(
        PassStatus(isActive = true, expiresAt = "2026-11-24"),
        todayYmd = 20261125,
      )
    )
  }

  @Test
  fun connectAdGateDialogOpensSmartlinkInExternalBrowserWithoutWebViewAndFinishesCountdown() {
    val openedExternalUrls = mutableListOf<String>()
    var finishCount = 0

    composeRule.setContent {
      MyApplicationTheme(darkTheme = true) {
        ConnectAdGateDialog(
          serverName = freeRemoteServer.name,
          countdownSeconds = 7,
          tickIntervalMs = 40L,
          onOpenExternalBrowser = { url ->
            openedExternalUrls.add(url)
            true
          },
          onCountdownFinished = { finishCount += 1 },
        )
      }
    }

    // Dialog opens immediately, launches NEW Smartlink_2 in external browser (NO in-app WebView), shows server name only
    composeRule.onNodeWithTag("ad_gate_dialog").assertIsDisplayed()
    composeRule.onNodeWithTag("ad_gate_server_name").assertIsDisplayed()
    composeRule.onNodeWithText("Japan Free").assertIsDisplayed()
    composeRule.onNodeWithTag("ad_gate_countdown_text").assertIsDisplayed()
    composeRule.onNodeWithTag("ad_gate_browser_info").assertIsDisplayed()
    composeRule.onAllNodesWithTag("ad_gate_webview_container", useUnmergedTree = true).assertCountEquals(0)
    assertEquals(listOf(expectedNewSmartlink), openedExternalUrls)

    // No skip/close buttons and zero technical details leaked
    composeRule.onAllNodesWithText("Skip", substring = true, ignoreCase = true, useUnmergedTree = true).assertCountEquals(0)
    composeRule.onAllNodesWithText("secret-jp-host.example.com", substring = true, useUnmergedTree = true).assertCountEquals(0)
    composeRule.onAllNodesWithText("hidden_ssh_user", substring = true, useUnmergedTree = true).assertCountEquals(0)
    composeRule.onAllNodesWithText("hidden_ssh_pass", substring = true, useUnmergedTree = true).assertCountEquals(0)

    // Wait for the 7-second countdown (7 * 40ms) to finish and trigger connection exactly once
    composeRule.waitUntil(timeoutMillis = 5_000L) { finishCount == 1 }
    assertEquals(1, finishCount)
  }

  @Test
  fun connectAdGateDialogContinuesCountdownAndConnectsEvenWhenExternalBrowserLaunchFails() {
    var finishCount = 0
    val attemptedUrls = mutableListOf<String>()

    composeRule.setContent {
      MyApplicationTheme(darkTheme = true) {
        ConnectAdGateDialog(
          serverName = "Singapore Node",
          countdownSeconds = 7,
          tickIntervalMs = 35L,
          onOpenExternalBrowser = { url ->
            attemptedUrls.add(url)
            false // Simulate no browser / launch failure
          },
          onCountdownFinished = { finishCount += 1 },
        )
      }
    }

    composeRule.waitForIdle()
    assertEquals(listOf(expectedNewSmartlink), attemptedUrls)
    composeRule.onNodeWithTag("ad_gate_error_fallback").assertIsDisplayed()
    composeRule.waitUntil(timeoutMillis = 5_000L) { finishCount == 1 }
    assertEquals(1, finishCount)
  }

  @Test
  fun homeScreenShowsBottomSmartlinkAnd320x50BannerAndHidesBothWhenNoAdsPassActive() {
    var openedUrl: String? = null
    var connectClicks = 0

    composeRule.setContent {
      MyApplicationTheme(darkTheme = true) {
        HomeScreen(
          networks = listOf(sampleNetwork),
          servers = listOf(freeRemoteServer),
          selectedNetwork = sampleNetwork,
          selectedServer = freeRemoteServer,
          vpnState = VpnTunnelState.Disconnected,
          trafficStats = VpnTrafficStats(),
          ownerBanner = null,
          showAdsterraBanners = true,
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

    // CONNECT button remains completely unobstructed
    lazyList.performScrollToNode(hasTestTag("connect_toggle_button"))
    composeRule.onNodeWithTag("connect_toggle_button").assertIsDisplayed().performClick()
    assertEquals(1, connectClicks)

    // Own-ad OwnerBannerCard stays present
    lazyList.performScrollToNode(hasTestTag("home_owner_banner_card"))
    composeRule.onNodeWithTag("home_owner_banner_card").performScrollTo().assertIsDisplayed()

    // Sponsored-link box ("Tap to support AMKT") must NOT be present anywhere on Home
    composeRule.onAllNodesWithTag("home_smartlink_bottom_banner", useUnmergedTree = true).assertCountEquals(0)

    // Adsterra 320x50 banner slot stays present on Home
    lazyList.performScrollToNode(hasTestTag("home_adsterra_320x50_banner"))
    composeRule.onNodeWithTag("home_adsterra_320x50_banner").performScrollTo().assertIsDisplayed()
  }

  @Test
  fun homeScreenHidesAdsterraBannersForValidNoAdsPassWhileKeepingOwnBannerVisible() {
    composeRule.setContent {
      MyApplicationTheme(darkTheme = true) {
        HomeScreen(
          networks = listOf(sampleNetwork),
          servers = listOf(freeRemoteServer),
          selectedNetwork = sampleNetwork,
          selectedServer = freeRemoteServer,
          vpnState = VpnTunnelState.Disconnected,
          trafficStats = VpnTrafficStats(),
          ownerBanner = null,
          showAdsterraBanners = false,
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

    val lazyList = composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
    lazyList.performScrollToNode(hasTestTag("home_owner_banner_card"))
    composeRule.onNodeWithTag("home_owner_banner_card").performScrollTo().assertIsDisplayed()

    composeRule.onAllNodesWithTag("home_smartlink_bottom_banner", useUnmergedTree = true).assertCountEquals(0)
    composeRule.onAllNodesWithTag("home_adsterra_320x50_banner", useUnmergedTree = true).assertCountEquals(0)
  }

  @Test
  fun appConnectFlowShowsGateEveryTimeAndExtendButtonGrants9hTwiceThenDisables() {
    val currentDay = AtomicInteger(20261125)
    val clockMs = AtomicLong(1_764_000_000_000L)
    val prefsFile = File(context.cacheDir, "e2e_gate_${UUID.randomUUID()}.preferences_pb")
    val membershipRepo =
      MembershipRepository(
        context = context,
        dataStore = PreferenceDataStoreFactory.create { prefsFile },
        todayYmdProvider = { currentDay.get() },
        nowMsProvider = { clockMs.get() },
      )
    val vpnController = RecordingVpnController()
    val remotePrefs = File(context.cacheDir, "e2e_remote_${UUID.randomUUID()}.preferences_pb")
    val remoteRepo =
      RemoteServerListRepository(
        context = context,
        dataStore = PreferenceDataStoreFactory.create { remotePrefs },
      )
    val splitRepo = NetworkServerRepository(db.networkConfigDao(), db.serverAccountDao(), remoteRepo)
    val legacyRepo = ServerProfileRepository(db.serverProfileDao(), remoteRepo)

    val (netId, freeId) =
      runBlocking {
        val nId = splitRepo.saveNetwork(sampleNetwork.copy(id = 0L), selectAfterSave = true)
        val fId = splitRepo.saveServer(freeRemoteServer.copy(id = 0L), selectAfterSave = true)
        splitRepo.selectCombination(nId, fId)
        nId to fId
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

    val openedBrowserUrls = mutableListOf<String>()

    composeRule.setContent {
      MyApplicationTheme(darkTheme = true) {
        AmktInjectorApp(
          viewModel = viewModel,
          adGateTickIntervalMs = 30L,
          adGateOpenBrowserOverride = { url ->
            openedBrowserUrls.add(url)
            true
          },
        )
      }
    }

    composeRule.waitUntil(timeoutMillis = 5_000L) {
      viewModel.selectedServer.value?.id == freeId && viewModel.selectedNetwork.value?.id == netId
    }
    composeRule.waitForIdle()

    val lazyList = composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
    lazyList.performScrollToNode(hasTestTag("home_duration_pill"))
    composeRule.onNodeWithTag("connect_toggle_button").performScrollTo().assertIsDisplayed()

    // 1. Connect #1 -> opens external-browser Smartlink_2 gate, grants initial 6h free (0 extend grants used), connects
    composeRule.onNodeWithTag("connect_toggle_button").performClick()
    awaitUiCondition("1st connect must open Smartlink_2 external-browser gate") { openedBrowserUrls.size == 1 }
    assertEquals(expectedNewSmartlink, openedBrowserUrls.last())
    awaitUiCondition("1st connect should start VPN after gate") { vpnController.startCount == 1 }
    val afterFirst = runBlocking { membershipRepo.getFreeTimeStatus() }
    assertTrue(afterFirst.initialized)
    assertEquals(clockMs.get() + AdsterraConfig.INITIAL_FREE_MS, afterFirst.expiresAtMs)
    assertEquals(0, afterFirst.grantsUsedToday)

    // 2. Connect #2 in a row -> STILL shows Smartlink_2 gate (no daily limit on gate!)
    vpnController.setDisconnected()
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("connect_toggle_button").performClick()
    awaitUiCondition("2nd connect in a row must show gate") { openedBrowserUrls.size == 2 }
    awaitUiCondition("2nd connect should start VPN after gate") { vpnController.startCount == 2 }

    // 3. Connect #3 in a row -> STILL shows Smartlink_2 gate (no daily limit on gate!)
    vpnController.setDisconnected()
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("connect_toggle_button").performClick()
    awaitUiCondition("3rd connect in a row must STILL show gate") { openedBrowserUrls.size == 3 }
    awaitUiCondition("3rd connect should start VPN after gate") { vpnController.startCount == 3 }
    assertEquals("Gate connects must NOT consume 2x/day Extend grants", 0, runBlocking { membershipRepo.getFreeTimeStatus() }.grantsUsedToday)

    // 4. Advance clock so 6h expires, then tap Home's Extend Time button twice (2 grants/day max)
    vpnController.setDisconnected()
    clockMs.addAndGet(AdsterraConfig.INITIAL_FREE_MS + 60_000L)
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("home_extend_time_button").assertIsDisplayed().assertIsEnabled()
    composeRule.onNodeWithText("2 left today", useUnmergedTree = true).assertIsDisplayed()

    // Grant #1 via Extend Time button -> opens Smartlink_2 gate, adds +9h, shows "1 left today"
    composeRule.onNodeWithTag("home_extend_time_button").performClick()
    awaitUiCondition("1st Extend Time tap opens Smartlink_2 gate") { openedBrowserUrls.size == 4 }
    awaitUiCondition("1st Extend Time grants +9h") { runBlocking { membershipRepo.getFreeTimeStatus() }.grantsUsedToday == 1 }
    val afterGrant1 = runBlocking { membershipRepo.getFreeTimeStatus() }
    assertEquals(clockMs.get() + AdsterraConfig.AD_GRANT_MS, afterGrant1.expiresAtMs)
    composeRule.onNodeWithText("1 left today", useUnmergedTree = true).assertIsDisplayed()

    // Grant #2 via Extend Time button -> opens Smartlink_2 gate, adds another +9h, shows "0 left today" & disables button
    composeRule.onNodeWithTag("home_extend_time_button").performClick()
    awaitUiCondition("2nd Extend Time tap opens Smartlink_2 gate") { openedBrowserUrls.size == 5 }
    awaitUiCondition("2nd Extend Time grants +9h (2/2)") { runBlocking { membershipRepo.getFreeTimeStatus() }.grantsUsedToday == 2 }
    composeRule.waitForIdle()
    composeRule.onNodeWithText("0 left today", useUnmergedTree = true).assertIsDisplayed()
    composeRule.onNodeWithTag("home_extend_time_button").assertIsNotEnabled()
    composeRule.onNodeWithTag("home_extend_limit_message").assertIsDisplayed()

    // 5. Redeem/select INJ-redeemed paid server -> hides Extend Time button AND skips connect gate completely
    val vipId = runBlocking { splitRepo.saveServer(redeemedServer.copy(id = 0L), selectAfterSave = true) }
    awaitUiCondition("VIP server selection should propagate") { viewModel.selectedServer.value?.id == vipId }
    composeRule.waitForIdle()
    composeRule.onAllNodesWithTag("home_extend_time_button", useUnmergedTree = true).assertCountEquals(0)
    composeRule.onNodeWithTag("connect_toggle_button").performScrollTo().performClick()
    awaitUiCondition("INJ-redeemed server should start VPN immediately") { vpnController.startCount == 4 }
    assertEquals("Redeemed INJ server must never open browser gate", 5, openedBrowserUrls.size)
  }

  private fun awaitUiCondition(
    failureMessage: String,
    timeoutMs: Long = 5_000L,
    condition: () -> Boolean,
  ) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!condition() && System.currentTimeMillis() < deadline) {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      composeRule.mainClock.advanceTimeBy(50L)
      composeRule.waitForIdle()
      Thread.sleep(20L)
    }
    Shadows.shadowOf(Looper.getMainLooper()).idle()
    assertTrue(failureMessage, condition())
  }

  private fun buildViewModel(membershipRepo: MembershipRepository): AmktViewModel {
    val remotePrefs = File(context.cacheDir, "gate_remote_${UUID.randomUUID()}.preferences_pb")
    val remoteRepo =
      RemoteServerListRepository(
        context = context,
        dataStore = PreferenceDataStoreFactory.create { remotePrefs },
      )
    val legacyRepo = ServerProfileRepository(db.serverProfileDao(), remoteRepo)
    return AmktViewModel(
      repository = legacyRepo,
      membershipRepository = membershipRepo,
      vpnPreferencesRepository = VpnPreferencesRepository(context),
      vpnController = RecordingVpnController(),
      configCodec = ConfigFileCodec(),
      logStore = AppLogStore(),
      networkServerRepository = null,
    )
  }

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
}
