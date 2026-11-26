package com.example

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
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
import com.example.data.ServerPingChecker
import com.example.data.ServerProfileEntity
import com.example.data.ServerProfileRepository
import com.example.membership.MembershipRepository
import com.example.membership.OwnerBanner
import com.example.openvpn.OvpnSecretStore
import com.example.ui.AmktViewModel
import com.example.ui.home.HomeScreen
import com.example.ui.home.ServerPickerBottomSheet
import com.example.ui.profiles.ServersScreen
import com.example.ui.settings.SettingsScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.vpn.VpnEngineController
import com.example.vpn.VpnPreferencesRepository
import com.example.vpn.VpnProtectionSettings
import com.example.vpn.VpnTrafficStats
import com.example.vpn.VpnTunnelState
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
@Config(sdk = [36], qualifiers = "w412dp-h915dp")
class CompactBannerServerStatsAndMyVpnTest {

  @get:Rule val composeRule = createComposeRule()

  private lateinit var context: Context
  private lateinit var db: AmktDatabase

  private val sampleNetwork =
    NetworkConfigEntity(id = 1L, name = "DataNoPro", payloadMode = PayloadMode.DIRECT.code)

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
  fun homeHasZeroSpeedTestCompactOwnAdBannerAndSelectedServerPing() {
    val sg1 =
      ServerAccountEntity(
        id = 10L,
        name = "Singapore1",
        connectionMode = ConnectionMode.SSH_DIRECT.code,
        host = "sg1.example.com",
        port = 22,
        username = "u",
        password = "p",
        region = "SG",
        onlineCount = 18,
        userLimit = 50,
        isFromRemote = true,
      )
    var openedUrl: String? = null

    composeRule.setContent {
      MyApplicationTheme(darkTheme = true) {
        HomeScreen(
          networks = listOf(sampleNetwork),
          servers = listOf(sg1),
          selectedNetwork = sampleNetwork,
          selectedServer = sg1,
          vpnState = VpnTunnelState.Disconnected,
          trafficStats = VpnTrafficStats(),
          ownerBanner =
            OwnerBanner(
              imageUrl = "",
              linkUrl = "https://t.me/amktvpn",
              title = "AMKT Telegram",
            ),
          showAdsterraBanners = true,
          showOwnerBanner = true,
          serverPingsMs = mapOf(10L to 84),
          onOpenBannerLink = { openedUrl = it },
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

    // 1. Speed Test card/button must NOT exist anywhere
    composeRule.onAllNodesWithTag("home_speed_test_row", useUnmergedTree = true).assertCountEquals(0)
    composeRule.onAllNodesWithTag("home_speed_test_button", useUnmergedTree = true).assertCountEquals(0)
    composeRule.onAllNodesWithText("Speed Test", substring = true, ignoreCase = true, useUnmergedTree = true).assertCountEquals(0)
    composeRule.onAllNodesWithText("စပိစစ်", substring = true, useUnmergedTree = true).assertCountEquals(0)

    // 2. Home Server row shows Online/Limit + live ping pill in ms
    composeRule.onNodeWithTag("home_server_online_limit_line", useUnmergedTree = true).assertTextEquals("Online 18 · Limit 50")
    composeRule.onNodeWithTag("home_server_ping_badge", useUnmergedTree = true).assertTextEquals("84 ms")

    // 3. Own-ad banner is compact (much shorter than the old tall box) and still opens Telegram link
    val lazyList = composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
    lazyList.performScrollToNode(hasTestTag("home_owner_banner_card"))
    val bannerNode = composeRule.onNodeWithTag("home_owner_banner_card").performScrollTo()
    bannerNode.assertIsDisplayed()
    val bannerBounds = bannerNode.getBoundsInRoot()
    val bannerHeight = bannerBounds.bottom - bannerBounds.top
    assertTrue("Compact own-ad banner should stay <= 80.dp tall (was $bannerHeight)", bannerHeight <= 80.dp)
    bannerNode.performClick()
    assertEquals("https://t.me/amktvpn", openedUrl)
  }

  @Test
  fun perServerLiveOnlineLimitUsageBarAndPingUpdateInPickerAndServersScreen() {
    runBlocking {
    val remotePrefs = File(context.cacheDir, "live_stats_remote_${UUID.randomUUID()}.preferences_pb")
    val memberPrefs = File(context.cacheDir, "live_stats_member_${UUID.randomUUID()}.preferences_pb")
    val remoteRepo =
      RemoteServerListRepository(
        context = context,
        dataStore = PreferenceDataStoreFactory.create { remotePrefs },
      )
    val membershipRepo =
      MembershipRepository(
        context = context,
        dataStore = PreferenceDataStoreFactory.create { memberPrefs },
      )
    val splitRepo = NetworkServerRepository(db.networkConfigDao(), db.serverAccountDao(), remoteRepo)
    val legacyRepo = ServerProfileRepository(db.serverProfileDao(), remoteRepo)

    val sg1Id =
      splitRepo.saveServer(
        ServerAccountEntity(
          name = "Singapore1",
          host = "sg1.secret.example",
          port = 22,
          username = "secret_user",
          password = "secret_password",
          region = "SG",
          onlineCount = 12,
          userLimit = 50,
          isFromRemote = true,
        ),
        selectAfterSave = true,
      )
    val oldSrvId =
      splitRepo.saveServer(
        ServerAccountEntity(
          name = "Japan Classic",
          host = "jp.secret.example",
          port = 22,
          username = "secret_user2",
          password = "secret_password2",
          region = "JP",
          onlineCount = null,
          userLimit = 100,
          isFromRemote = true,
        ),
        selectAfterSave = false,
      )

    val pingPass = AtomicInteger(1)
    val viewModel =
      AmktViewModel(
        repository = legacyRepo,
        membershipRepository = membershipRepo,
        vpnPreferencesRepository = VpnPreferencesRepository(context),
        vpnController = DummyVpnController(),
        configCodec = ConfigFileCodec(),
        logStore = AppLogStore(),
        networkServerRepository = splitRepo,
        pingMeasurer = { srv ->
          val pass = pingPass.get()
          when (srv.id) {
            sg1Id -> if (pass == 1) 42 else 168
            oldSrvId -> null // unreachable
            else -> null
          }
        },
      )

    composeRule.setContent {
      MyApplicationTheme(darkTheme = true) {
        val srvList = db.serverAccountDao().observeAllServers()
        val stateServers = srvList.collectAsState(initial = emptyList()).value
        val pings = viewModel.serverPingsMs.collectAsState().value
        ServerPickerBottomSheet(
          servers = stateServers,
          selectedServerId = sg1Id,
          onSelect = {},
          onManageAll = {},
          onOpenRedeem = {},
          onDismiss = {},
          serverPingsMs = pings,
          onImportMyVpnClick = {},
        )
      }
    }

    awaitUiCondition("Initial ping map should populate Singapore1 -> 42 ms") {
      viewModel.serverPingsMs.value[sg1Id] == 42
    }
    composeRule.waitForIdle()

    composeRule.onNodeWithTag("server_sheet_online_limit_$sg1Id", useUnmergedTree = true).assertTextEquals("Online 12 · Limit 50")
    composeRule.onNodeWithTag("server_sheet_usage_bar_$sg1Id", useUnmergedTree = true).assertIsDisplayed()
    composeRule.onNodeWithTag("server_sheet_ping_$sg1Id", useUnmergedTree = true).assertTextEquals("42 ms")

    // Server without onlineCount shows Limit only, NO usage bar, and "—" when offline/unreachable
    composeRule.onNodeWithTag("server_sheet_online_limit_$oldSrvId", useUnmergedTree = true).assertTextEquals("Limit 100")
    composeRule.onAllNodesWithTag("server_sheet_usage_bar_$oldSrvId", useUnmergedTree = true).assertCountEquals(0)
    composeRule.onNodeWithTag("server_sheet_ping_$oldSrvId", useUnmergedTree = true).assertTextEquals("—")
    composeRule.onNodeWithTag("server_sheet_import_myvpn").assertIsDisplayed()

    // Live update: server online count + live ping both change -> UI updates live without fixed numbers!
    pingPass.set(2)
    val updatedSg1 = db.serverAccountDao().getServerById(sg1Id)!!.copy(onlineCount = 39)
    splitRepo.saveServer(updatedSg1, selectAfterSave = false)
    viewModel.refreshServerPings()

    awaitUiCondition("Live ping should update from 42 ms to 168 ms") {
      viewModel.serverPingsMs.value[sg1Id] == 168
    }
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("server_sheet_online_limit_$sg1Id", useUnmergedTree = true).assertTextEquals("Online 39 · Limit 50")
    composeRule.onNodeWithTag("server_sheet_ping_$sg1Id", useUnmergedTree = true).assertTextEquals("168 ms")

    // Zero hidden host/credential leak
    composeRule.onAllNodesWithText("sg1.secret.example", substring = true, useUnmergedTree = true).assertCountEquals(0)
    composeRule.onAllNodesWithText("secret_user", substring = true, useUnmergedTree = true).assertCountEquals(0)
    composeRule.onAllNodesWithTag("server_import_ovpn", useUnmergedTree = true).assertCountEquals(0)
    }
  }

  @Test
  fun serverPingCheckerMeasuresRealTcpPingForOpenVpnAndReturnsNullWhenOffline() {
    runBlocking {
    val loopbackSocket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    val openPort = loopbackSocket.localPort
    val closedPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    try {
      val ovpnConfig =
        """
        client
        dev tun
        proto tcp
        remote 127.0.0.1 $openPort
        """.trimIndent()
      val openVpnSrv =
        ServerAccountEntity(
          name = "Singapore OVPN",
          connectionMode = ConnectionMode.OPENVPN.code,
          host = "",
          port = 0,
          ovpnConfigEncrypted = OvpnSecretStore.encrypt(ovpnConfig),
        )
      val pingMs = ServerPingChecker.measureTcpPingMs(openVpnSrv, timeoutMs = 1_500)
      assertNotNull("OpenVPN server listening on local TCP port must report real ms ping", pingMs)
      assertTrue(pingMs!! >= 1)

      val offlineSrv =
        ServerAccountEntity(
          name = "Offline Node",
          connectionMode = ConnectionMode.SSH_DIRECT.code,
          host = "127.0.0.1",
          port = closedPort,
        )
      assertNull(ServerPingChecker.measureTcpPingMs(offlineSrv, timeoutMs = 400))
      assertEquals("42 ms", ServerPingChecker.formatPingBadge(42))
      assertEquals("—", ServerPingChecker.formatPingBadge(null))
    } finally {
      runCatching { loopbackSocket.close() }
    }
    }
  }

  @Test
  fun myvpnImportRestoresBundleAndRemoteSyncPreservesImportedWhileRemovingZeroOvpnButtons() {
    runBlocking {
    val remotePrefs = File(context.cacheDir, "myvpn_remote_${UUID.randomUUID()}.preferences_pb")
    val memberPrefs = File(context.cacheDir, "myvpn_member_${UUID.randomUUID()}.preferences_pb")
    val remoteRepo =
      RemoteServerListRepository(
        context = context,
        dataStore = PreferenceDataStoreFactory.create { remotePrefs },
      )
    val membershipRepo =
      MembershipRepository(
        context = context,
        dataStore = PreferenceDataStoreFactory.create { memberPrefs },
      )
    val splitRepo = NetworkServerRepository(db.networkConfigDao(), db.serverAccountDao(), remoteRepo)
    val legacyRepo = ServerProfileRepository(db.serverProfileDao(), remoteRepo)
    val codec = ConfigFileCodec()
    val viewModel =
      AmktViewModel(
        repository = legacyRepo,
        membershipRepository = membershipRepo,
        vpnPreferencesRepository = VpnPreferencesRepository(context),
        vpnController = DummyVpnController(),
        configCodec = codec,
        logStore = AppLogStore(),
        networkServerRepository = splitRepo,
        pingMeasurer = { null },
      )

    // Encode a locked .myvpn file and import via ViewModel
    val exportedBytes =
      codec.encodeSplitToMyVpnBytes(
        network =
          NetworkConfigEntity(
            name = "Custom Imported Net",
            payloadMode = PayloadMode.HTTP_PAYLOAD.code,
            payloadTemplate = "GET / HTTP/1.1[crlf][crlf]",
          ),
        server =
          ServerAccountEntity(
            name = "My .myvpn VIP",
            connectionMode = ConnectionMode.SSH_DIRECT.code,
            host = "vip.myvpn.example",
            port = 22,
            username = "u",
            password = "p",
            onlineCount = 10,
            userLimit = 50,
          ),
        lockOverride = true,
      )

    viewModel.importFromBytes(exportedBytes, "vip-bundle.myvpn")
    awaitUiCondition("Imported .myvpn server should save to Room DB") {
      runBlocking { splitRepo.getAllServersSnapshot().any { it.name == "My .myvpn VIP" } }
    }

    val importedSrv = splitRepo.getAllServersSnapshot().first { it.name == "My .myvpn VIP" }
    assertTrue(importedSrv.isUserImported)
    assertFalse(importedSrv.isFromRemote)

    // Subsequent remote sync must preserve imported .myvpn network + server
    val remoteJson =
      """
      {
        "networks": [{"name": "Atom Free", "payload_mode": "DIRECT"}],
        "servers": [{"name": "Singapore1", "host": "sg1.example.com", "port": 22, "username": "u", "password": "p", "online": 20, "limit": 50}]
      }
      """.trimIndent()
    splitRepo.mergeSplitRemoteList(ServerListLoader().parseSplitRemoteList(remoteJson).getOrThrow(), replaceExisting = false)

    val allNames = splitRepo.getAllServersSnapshot().map { it.name }.toSet()
    assertTrue("Imported .myvpn server must survive Remote Update", "My .myvpn VIP" in allNames)
    assertTrue("Singapore1" in allNames)

    // Verify ServersScreen displays empty-state or populated "Import .myvpn" and zero ".ovpn" UI
    var importTriggered = 0
    val serversSnapshot = splitRepo.getAllServersSnapshot()
    composeRule.setContent {
      MyApplicationTheme(darkTheme = true) {
        ServersScreen(
          servers = serversSnapshot,
          selectedServerId = importedSrv.id,
          onSelectServer = {},
          onSyncRemoteClick = {},
          onImportMyVpnClick = { importTriggered += 1 },
          serverPingsMs = mapOf(importedSrv.id to 55),
        )
      }
    }
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("servers_import_myvpn_button").assertIsDisplayed().performClick()
    assertEquals(1, importTriggered)
    composeRule.onNodeWithTag("server_card_ping_${importedSrv.id}", useUnmergedTree = true).assertTextEquals("55 ms")
    composeRule.onAllNodesWithText(".ovpn", substring = true, ignoreCase = true, useUnmergedTree = true).assertCountEquals(0)
    composeRule.onAllNodesWithTag("servers_import_ovpn_button", useUnmergedTree = true).assertCountEquals(0)
    }
  }

  @Test
  fun settingsThemeToggleSwitchesLightAndDarkAndPersistsPreference() {
    runBlocking {
    val prefsRepo = VpnPreferencesRepository(context)
    val remoteRepo = RemoteServerListRepository(
      context = context,
      dataStore = PreferenceDataStoreFactory.create { File(context.cacheDir, "theme_remote_${UUID.randomUUID()}.preferences_pb") },
    )
    val memberRepo = MembershipRepository(
      context = context,
      dataStore = PreferenceDataStoreFactory.create { File(context.cacheDir, "theme_member_${UUID.randomUUID()}.preferences_pb") },
    )
    val viewModel =
      AmktViewModel(
        repository = ServerProfileRepository(db.serverProfileDao(), remoteRepo),
        membershipRepository = memberRepo,
        vpnPreferencesRepository = prefsRepo,
        vpnController = DummyVpnController(),
        configCodec = ConfigFileCodec(),
        logStore = AppLogStore(),
        pingMeasurer = { null },
      )

    composeRule.setContent {
      val dark = viewModel.isDarkTheme.collectAsState().value
      MyApplicationTheme(darkTheme = dark) {
        SettingsScreen(
          vpnSettings = VpnProtectionSettings(),
          isDarkTheme = dark,
          onToggleKillSwitch = {},
          onToggleAutoReconnect = {},
          onToggleTheme = viewModel::toggleTheme,
          onOpenRedeemClick = {},
          onNavigateBack = {},
        )
      }
    }
    composeRule.waitForIdle()

    assertTrue(viewModel.isDarkTheme.value)
    composeRule.onNodeWithTag("settings_theme_toggle").performScrollTo().performClick()
    composeRule.waitForIdle()
    assertFalse(viewModel.isDarkTheme.value)
    awaitUiCondition("Light theme preference should persist to DataStore") {
      runBlocking { !prefsRepo.currentSettings().darkThemeEnabled }
    }
    }
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

  private class DummyVpnController : VpnEngineController {
    private val _state = MutableStateFlow<VpnTunnelState>(VpnTunnelState.Disconnected)
    override val state: StateFlow<VpnTunnelState> = _state
    override val trafficStats: StateFlow<VpnTrafficStats> = MutableStateFlow(VpnTrafficStats())
    override val isImplemented: Boolean = true

    override fun prepareVpnPermissionIntent(context: Context): Intent? = null

    override fun startTunnel(profile: ServerProfileEntity, killSwitch: Boolean, autoReconnect: Boolean) = Unit

    override fun stopTunnel() = Unit

    override fun updateProtectionSettings(killSwitch: Boolean, autoReconnect: Boolean) = Unit
  }
}
