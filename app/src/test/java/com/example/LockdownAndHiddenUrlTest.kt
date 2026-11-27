package com.example

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
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
import com.example.data.ServerPingChecker
import com.example.data.ServerProfileEntity
import com.example.data.ServerProfileRepository
import com.example.membership.MembershipRepository
import com.example.redeem.RedeemOutcome
import com.example.redeem.RedeemRepository
import com.example.ui.AmktViewModel
import com.example.ui.home.HomeScreen
import com.example.ui.home.NetworkPickerBottomSheet
import com.example.ui.home.ServerPickerBottomSheet
import com.example.ui.networks.NetworksScreen
import com.example.ui.profiles.ServersScreen
import com.example.ui.redeem.RedeemCodeScreen
import com.example.ui.settings.SettingsScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.vpn.VpnEngineController
import com.example.vpn.VpnPreferencesRepository
import com.example.vpn.VpnProtectionSettings
import com.example.vpn.VpnTrafficStats
import com.example.vpn.VpnTunnelState
import java.io.File
import java.util.Calendar
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LockdownAndHiddenUrlTest {
  @get:Rule val composeRule = createComposeRule()

  private val secretNetwork =
    NetworkConfigEntity(
      id = 10L,
      name = "DataNoPro",
      payloadMode = PayloadMode.WEBSOCKET.code,
      payloadTemplate = "GET /secret-ws-path HTTP/1.1[crlf]Host: secret-sni.example.net[crlf][crlf]",
      sniHost = "secret-sni.example.net",
      extraHeaders = "X-Secret-Header: top-secret-val",
      httpProxyHost = "secret-proxy.example.net",
      httpProxyPort = 8089,
    )

  private val secretServer =
    ServerAccountEntity(
      id = 20L,
      name = "Japan",
      connectionMode = ConnectionMode.DNSTT.code,
      host = "secret-host.example.org",
      port = 2299,
      username = "secret_user_99",
      password = "secret_pass_99",
      dnsttNameserver = "ns.secret-dnstt.example",
      dnsttPublicKey = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
      dnsttResolver = "8.8.4.4:53",
      vlessLink = "vless://11111111-2222-3333-4444-555555555555@secret-vless.example:443?type=ws&path=/secret-vless#MyVless",
      expiryDate = "2099-12-31",
      notes = "internal-secret-note",
      region = "JP",
      tags = "secret-tag",
      defaultNetworkId = 10L,
    )

  private fun assertZeroSecretLeaksOnScreen() {
    val forbidden =
      listOf(
        "amkt-injector-servers",
        "amkt-injector-redeem",
        "n8n.marratethu.web.id",
        "SATHU",
        "secret-sni.example.net",
        "secret-ws-path",
        "secret-proxy.example.net",
        "8089",
        "X-Secret-Header",
        "secret-host.example.org",
        "2299",
        "secret_user_99",
        "secret_pass_99",
        "ns.secret-dnstt.example",
        "0123456789abcdef0123456789abcdef",
        "8.8.4.4:53",
        "secret-vless.example",
        "11111111-2222-3333-4444-555555555555",
        "internal-secret-note",
      )
    forbidden.forEach { secret ->
      composeRule
        .onAllNodesWithText(secret, substring = true, ignoreCase = true, useUnmergedTree = true)
        .assertCountEquals(0)
    }
  }

  @Test
  fun homeScreenMatchesNewLayoutTogglesConnectAndHidesAllDetailsLogsAndImport() {
    val vpnStateHolder = mutableStateOf<VpnTunnelState>(VpnTunnelState.Disconnected)
    var connectClicks = 0

    composeRule.setContent {
      MyApplicationTheme(darkTheme = true) {
        HomeScreen(
          networks = listOf(secretNetwork),
          servers = listOf(secretServer),
          selectedNetwork = secretNetwork,
          selectedServer = secretServer,
          vpnState = vpnStateHolder.value,
          trafficStats = VpnTrafficStats(bytesDown = 2048L, bytesUp = 1024L),
          onConnectClick = {
            connectClicks += 1
            vpnStateHolder.value =
              if (vpnStateHolder.value is VpnTunnelState.Connected) {
                VpnTunnelState.Disconnected
              } else {
                VpnTunnelState.Connected(
                  profileName = secretServer.name,
                  targetHost = secretServer.host,
                  targetPort = secretServer.port,
                  connectedAtMs = System.currentTimeMillis(),
                )
              }
          },
          onReconnectClick = {},
          onSelectNetwork = {},
          onSelectServer = {},
          onOpenNetworksClick = {},
          onOpenServersClick = {},
          onOpenSettingsClick = {},
          onOpenRedeemClick = {},
        )
      }
    }
    composeRule.waitForIdle()

    // AMKT branding present, zero SATHU branding
    composeRule.onNodeWithTag("home_brand_title").assertIsDisplayed()
    composeRule.onNodeWithText("AMKT Injector", substring = false).assertIsDisplayed()

    // Network row: label + selected network name + privacy subtitle
    composeRule.onNodeWithTag("home_network_row").assertIsDisplayed()
    composeRule.onNodeWithText("Network").assertIsDisplayed()
    composeRule.onNodeWithText("DataNoPro").assertIsDisplayed()

    // Server row: country flag + label + selected server name + expiry line
    composeRule.onNodeWithTag("home_server_row").assertIsDisplayed()
    composeRule.onNodeWithText("Server").assertIsDisplayed()
    composeRule.onNodeWithText("🇯🇵").assertIsDisplayed()
    composeRule.onNodeWithText("Japan").assertIsDisplayed()
    composeRule.onNodeWithTag("home_server_expiry_line", useUnmergedTree = true).performScrollTo().assertIsDisplayed()

    // Remaining time row: read-only HH:MM:SS countdown, NO AddTime button
    val lazyList = composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
    val notClickable = SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick)
    val notEditable = SemanticsMatcher.keyNotDefined(SemanticsActions.SetText)
    lazyList.performScrollToNode(hasTestTag("home_remaining_time_value"))
    composeRule.onNodeWithText("Remaining time").assertIsDisplayed()
    composeRule
      .onNodeWithTag("home_remaining_time_value")
      .performScrollTo()
      .assertIsDisplayed()
      .assert(notClickable)
      .assert(notEditable)
    composeRule.onAllNodesWithText("AddTime", substring = true, ignoreCase = true, useUnmergedTree = true).assertCountEquals(0)

    // Status line + BIG CONNECT button toggles to DISCONNECT
    lazyList.performScrollToNode(hasTestTag("connect_toggle_button"))
    composeRule.onNodeWithText("AMKT Injector : Disconnected").assertIsDisplayed()
    composeRule.onNodeWithText("CONNECT").assertIsDisplayed()
    composeRule.onNodeWithTag("connect_toggle_button").performClick()
    composeRule.waitForIdle()
    assertEquals(1, connectClicks)
    composeRule.onNodeWithText("AMKT Injector : Connected").assertIsDisplayed()
    composeRule.onNodeWithText("DISCONNECT").assertIsDisplayed()

    // Duration + Data pills visible and read-only
    lazyList.performScrollToNode(hasTestTag("home_duration_pill"))
    composeRule.onNodeWithTag("home_duration_pill").performScrollTo().assertIsDisplayed().assert(notClickable).assert(notEditable)
    lazyList.performScrollToNode(hasTestTag("home_data_usage_pill"))
    composeRule.onNodeWithTag("home_data_usage_pill").performScrollTo().assertIsDisplayed().assert(notClickable).assert(notEditable)

    // Small Redeem button present
    lazyList.performScrollToNode(hasTestTag("home_redeem_button"))
    composeRule.onNodeWithTag("home_redeem_button").performScrollTo().assertIsDisplayed()

    // Zero Activity Log, zero Import/Add/Edit/Export UI, zero hidden details
    assertZeroSecretLeaksOnScreen()
    composeRule.onAllNodesWithText("DNSTT", substring = true, useUnmergedTree = true).assertCountEquals(0)
    composeRule.onAllNodesWithText("WebSocket", substring = true, useUnmergedTree = true).assertCountEquals(0)
    composeRule.onAllNodesWithTag("home_open_logs_button", useUnmergedTree = true).assertCountEquals(0)
    composeRule.onAllNodesWithTag("quick_import_button", useUnmergedTree = true).assertCountEquals(0)
    composeRule.onAllNodesWithTag("quick_add_server_button", useUnmergedTree = true).assertCountEquals(0)
    composeRule.onAllNodesWithTag("quick_export_button", useUnmergedTree = true).assertCountEquals(0)
    composeRule.onAllNodesWithTag("home_edit_network_button", useUnmergedTree = true).assertCountEquals(0)
    composeRule.onAllNodesWithTag("home_edit_server_button", useUnmergedTree = true).assertCountEquals(0)
    composeRule.onAllNodesWithText("Import", substring = true, ignoreCase = true, useUnmergedTree = true).assertCountEquals(0)
    composeRule.onAllNodesWithText("မှတ်တမ်း", substring = true, useUnmergedTree = true).assertCountEquals(0)
  }

  @Test
  fun appShellHasFourBottomNavItemsIncludingUpdateAndNoLogOrAboutTabs() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = Room.inMemoryDatabaseBuilder(context, AmktDatabase::class.java).allowMainThreadQueries().build()
    try {
      val prefsFile = File(context.cacheDir, "m9_shell_${UUID.randomUUID()}.preferences_pb")
      val remoteRepo =
        RemoteServerListRepository(
          context = context,
          dataStore = PreferenceDataStoreFactory.create { prefsFile },
        )
      val splitRepo = NetworkServerRepository(db.networkConfigDao(), db.serverAccountDao(), remoteRepo)
      val legacyRepo = ServerProfileRepository(db.serverProfileDao(), remoteRepo)
      val redeemPrefsFile = File(context.cacheDir, "m9_redeem_${UUID.randomUUID()}.preferences_pb")
      val redeemRepo =
        RedeemRepository(
          context = context,
          dataStore = PreferenceDataStoreFactory.create { redeemPrefsFile },
        )
      val viewModel =
        AmktViewModel(
          repository = legacyRepo,
          membershipRepository = MembershipRepository(context),
          vpnPreferencesRepository = VpnPreferencesRepository(context),
          vpnController = NoOpVpnController(),
          configCodec = ConfigFileCodec(),
          logStore = AppLogStore(),
          networkServerRepository = splitRepo,
          redeemRepository = redeemRepo,
        )

      composeRule.setContent {
        MyApplicationTheme(darkTheme = true) {
          AmktInjectorApp(viewModel = viewModel)
        }
      }
      composeRule.waitForIdle()

      // Strictly 4 bottom nav items: Home (ပင်မ), Network (ကွန်ရက်), Servers (ဆာဗာများ), Update
      composeRule.onNodeWithTag("nav_home").assertIsDisplayed()
      composeRule.onNodeWithTag("nav_networks").assertIsDisplayed()
      composeRule.onNodeWithTag("nav_profiles").assertIsDisplayed()
      composeRule.onNodeWithTag("nav_update").assertIsDisplayed()
      composeRule.onNodeWithText("Update").assertIsDisplayed()

      // Zero Log tab and zero About tab
      composeRule.onAllNodesWithTag("nav_logs", useUnmergedTree = true).assertCountEquals(0)
      composeRule.onAllNodesWithTag("nav_about", useUnmergedTree = true).assertCountEquals(0)
      composeRule.onAllNodesWithText("မှတ်တမ်း", substring = true, useUnmergedTree = true).assertCountEquals(0)
      composeRule.onAllNodesWithText("အကြောင်းအရာ", substring = true, useUnmergedTree = true).assertCountEquals(0)

      // Navigate to Settings from Home gear icon -> Kill Switch + Auto-Reconnect toggles reachable
      composeRule.onNodeWithTag("home_settings_button").performClick()
      composeRule.waitForIdle()
      composeRule.onNodeWithTag("kill_switch_toggle").assertIsDisplayed()
      composeRule.onNodeWithTag("auto_reconnect_toggle").assertIsDisplayed()
      // No About / Milestones / Privacy / URLs in Settings
      composeRule.onAllNodesWithText("Milestones", substring = true, ignoreCase = true, useUnmergedTree = true).assertCountEquals(0)
      composeRule.onAllNodesWithText("AES-256", substring = true, ignoreCase = true, useUnmergedTree = true).assertCountEquals(0)
      composeRule.onAllNodesWithText("Webhook", substring = true, ignoreCase = true, useUnmergedTree = true).assertCountEquals(0)
      assertZeroSecretLeaksOnScreen()
    } finally {
      db.close()
    }
  }

  @Test
  fun networksScreenShowsOnlyNameWithNoAddImportEditDeleteOrDetails() {
    var selected: NetworkConfigEntity? = null
    composeRule.setContent {
      MyApplicationTheme(darkTheme = true) {
        NetworksScreen(
          networks = listOf(secretNetwork),
          selectedNetworkId = secretNetwork.id,
          onSelectNetwork = { selected = it },
          onUpdateClick = {},
        )
      }
    }
    composeRule.waitForIdle()

    composeRule.onNodeWithText("DataNoPro").assertIsDisplayed()
    assertZeroSecretLeaksOnScreen()
    composeRule.onAllNodesWithText("Payload", substring = true, useUnmergedTree = true).assertCountEquals(0)
    composeRule.onAllNodesWithText("WebSocket", substring = true, useUnmergedTree = true).assertCountEquals(0)

    composeRule.onAllNodesWithTag("networks_add_fab", useUnmergedTree = true).assertCountEquals(0)
    composeRule.onAllNodesWithTag("networks_import_button", useUnmergedTree = true).assertCountEquals(0)
    composeRule.onAllNodesWithTag("empty_import_network_button", useUnmergedTree = true).assertCountEquals(0)
    composeRule.onAllNodesWithTag("network_edit_${secretNetwork.id}", useUnmergedTree = true).assertCountEquals(0)
    composeRule.onAllNodesWithTag("network_export_${secretNetwork.id}", useUnmergedTree = true).assertCountEquals(0)
    composeRule.onAllNodesWithTag("network_delete_${secretNetwork.id}", useUnmergedTree = true).assertCountEquals(0)

    composeRule.onNodeWithTag("network_card_${secretNetwork.id}").performClick()
    assertEquals(secretNetwork.id, selected?.id)
  }

  @Test
  fun emptyNetworksScreenShowsUpdateButtonAndNoImportButton() {
    var updateClicked = false
    composeRule.setContent {
      MyApplicationTheme(darkTheme = true) {
        NetworksScreen(
          networks = emptyList(),
          selectedNetworkId = null,
          onSelectNetwork = {},
          onUpdateClick = { updateClicked = true },
        )
      }
    }
    composeRule.waitForIdle()

    composeRule.onNodeWithTag("networks_empty_update_button").assertIsDisplayed().performClick()
    assertTrue(updateClicked)
    composeRule.onAllNodesWithTag("networks_import_button", useUnmergedTree = true).assertCountEquals(0)
    composeRule.onAllNodesWithTag("empty_import_network_button", useUnmergedTree = true).assertCountEquals(0)
    composeRule.onAllNodesWithText(".myvpn", substring = true, useUnmergedTree = true).assertCountEquals(0)
  }

  @Test
  fun serversScreenShowsFlagNameExpiryAndUpdateWithNoAddImportEditDeleteOrDetails() {
    var selected: ServerAccountEntity? = null
    var updateClicked = false
    composeRule.setContent {
      MyApplicationTheme(darkTheme = true) {
        ServersScreen(
          servers = listOf(secretServer),
          selectedServerId = secretServer.id,
          onSelectServer = { selected = it },
          onSyncRemoteClick = { updateClicked = true },
        )
      }
    }
    composeRule.waitForIdle()

    composeRule.onNodeWithText("🇯🇵").assertIsDisplayed()
    composeRule.onNodeWithText("Japan").assertIsDisplayed()
    assertZeroSecretLeaksOnScreen()
    composeRule.onAllNodesWithText("DNSTT", substring = true, useUnmergedTree = true).assertCountEquals(0)
    composeRule.onAllNodesWithText("VLESS", substring = true, useUnmergedTree = true).assertCountEquals(0)

    composeRule.onAllNodesWithTag("servers_add_fab", useUnmergedTree = true).assertCountEquals(0)
    composeRule.onAllNodesWithTag("servers_import_button", useUnmergedTree = true).assertCountEquals(0)
    composeRule.onAllNodesWithTag("empty_import_button", useUnmergedTree = true).assertCountEquals(0)
    composeRule.onAllNodesWithTag("filter_chip_dnstt", useUnmergedTree = true).assertCountEquals(0)
    composeRule.onAllNodesWithTag("server_edit_${secretServer.id}", useUnmergedTree = true).assertCountEquals(0)
    composeRule.onAllNodesWithTag("server_export_${secretServer.id}", useUnmergedTree = true).assertCountEquals(0)
    composeRule.onAllNodesWithTag("server_delete_${secretServer.id}", useUnmergedTree = true).assertCountEquals(0)

    composeRule.onNodeWithTag("servers_remote_sync_button").assertIsDisplayed().performClick()
    assertTrue(updateClicked)

    composeRule.onNodeWithTag("server_card_${secretServer.id}").performClick()
    assertEquals(secretServer.id, selected?.id)
  }

  @Test
  fun serverPickerSheetShowsFlagAndNameHidesAddAndFiltersOnlyByName() {
    composeRule.setContent {
      MyApplicationTheme(darkTheme = true) {
        ServerPickerBottomSheet(
          servers = listOf(secretServer),
          selectedServerId = secretServer.id,
          onSelect = {},
          onManageAll = {},
          onOpenRedeem = {},
          onDismiss = {},
        )
      }
    }
    composeRule.waitForIdle()

    composeRule.onNodeWithText("🇯🇵").assertIsDisplayed()
    composeRule.onNodeWithText("Japan").assertIsDisplayed()
    assertZeroSecretLeaksOnScreen()
    composeRule.onAllNodesWithTag("server_sheet_add", useUnmergedTree = true).assertCountEquals(0)
    composeRule.onNodeWithTag("server_sheet_refresh").assertIsDisplayed()

    // Searching by hidden host must NOT match
    composeRule.onNodeWithTag("server_sheet_search").performTextInput("secret-host")
    composeRule.waitForIdle()
    composeRule.onAllNodesWithTag("server_sheet_item_${secretServer.id}", useUnmergedTree = true).assertCountEquals(0)
  }

  @Test
  fun networkPickerSheetShowsOnlyNameHidesAddAndFiltersOnlyByName() {
    composeRule.setContent {
      MyApplicationTheme(darkTheme = true) {
        NetworkPickerBottomSheet(
          networks = listOf(secretNetwork),
          selectedNetworkId = secretNetwork.id,
          onSelect = {},
          onManageAll = {},
          onDismiss = {},
        )
      }
    }
    composeRule.waitForIdle()

    composeRule.onNodeWithText("DataNoPro").assertIsDisplayed()
    assertZeroSecretLeaksOnScreen()
    composeRule.onAllNodesWithTag("network_sheet_add", useUnmergedTree = true).assertCountEquals(0)

    // Searching by hidden SNI must NOT match
    composeRule.onNodeWithTag("network_sheet_search").performTextInput("secret-sni")
    composeRule.waitForIdle()
    composeRule.onAllNodesWithTag("network_sheet_item_${secretNetwork.id}", useUnmergedTree = true).assertCountEquals(0)
  }

  @Test
  fun redeemScreenHidesWebhookUrlAndServerTechnicalDetails() {
    composeRule.setContent {
      MyApplicationTheme(darkTheme = true) {
        RedeemCodeScreen(
          deviceId = "DEV-999",
          isRedeeming = false,
          lastOutcome =
            RedeemOutcome.Success(
              code = "INJ-TEST-1234",
              message = "Success",
              server = secretServer,
              wasUpdated = false,
            ),
          onRedeemSubmit = {},
          onClearOutcome = {},
          onNavigateBack = {},
        )
      }
    }
    composeRule.waitForIdle()

    val lazyList = composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
    lazyList.performScrollToNode(hasTestTag("redeem_success_card"))
    composeRule.onNodeWithText("Japan").performScrollTo().assertIsDisplayed()
    assertZeroSecretLeaksOnScreen()
    composeRule.onAllNodesWithTag("redeem_edit_webhook_button", useUnmergedTree = true).assertCountEquals(0)
    composeRule.onAllNodesWithText("Webhook", substring = true, ignoreCase = true, useUnmergedTree = true).assertCountEquals(0)
    composeRule.onAllNodesWithText("DNSTT", substring = true, useUnmergedTree = true).assertCountEquals(0)
  }

  @Test
  fun settingsScreenKeepsKillSwitchAndAutoReconnectReachableWithoutAboutOrUrls() {
    var killSwitch = false
    var autoReconnect = true
    composeRule.setContent {
      MyApplicationTheme(darkTheme = true) {
        SettingsScreen(
          vpnSettings = VpnProtectionSettings(killSwitchEnabled = killSwitch, autoReconnectEnabled = autoReconnect),
          isDarkTheme = true,
          onToggleKillSwitch = { killSwitch = it },
          onToggleAutoReconnect = { autoReconnect = it },
          onToggleTheme = {},
          onOpenRedeemClick = {},
          onNavigateBack = {},
        )
      }
    }
    composeRule.waitForIdle()

    composeRule.onNodeWithTag("kill_switch_toggle").assertIsDisplayed().performClick()
    composeRule.onNodeWithTag("auto_reconnect_toggle").assertIsDisplayed().performClick()
    assertTrue(killSwitch)
    assertFalse(autoReconnect)
    assertZeroSecretLeaksOnScreen()
  }

  @Test
  fun flagEmojiAndExpiryCountdownHmsFormatCorrectly() {
    assertEquals("🇯🇵", ServerPingChecker.resolveServerFlagEmoji(secretServer))
    assertEquals("🇸🇬", ServerPingChecker.resolveServerFlagEmoji(ServerAccountEntity(name = "Singapore 01")))
    assertEquals("🇹🇭", ServerPingChecker.resolveServerFlagEmoji(ServerAccountEntity(name = "Thailand-01")))
    assertEquals("🌐", ServerPingChecker.resolveServerFlagEmoji(ServerAccountEntity(name = "Custom Node")))
    assertEquals("🌐", ServerPingChecker.resolveServerFlagEmoji(null))

    val (blankHms, blankExpired) = ServerPingChecker.formatExpiryCountdownHms("")
    assertEquals("--:--:--", blankHms)
    assertFalse(blankExpired)

    val (pastHms, pastExpired) = ServerPingChecker.formatExpiryCountdownHms("2020-01-01")
    assertEquals("00:00:00", pastHms)
    assertTrue(pastExpired)

    val cal =
      Calendar.getInstance().apply {
        set(2026, Calendar.NOVEMBER, 22, 22, 30, 0)
        set(Calendar.MILLISECOND, 0)
      }
    val (futureHms, futureExpired) =
      ServerPingChecker.formatExpiryCountdownHms("2026-11-22", nowMs = cal.timeInMillis)
    assertEquals("01:29:59", futureHms)
    assertFalse(futureExpired)
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
}
