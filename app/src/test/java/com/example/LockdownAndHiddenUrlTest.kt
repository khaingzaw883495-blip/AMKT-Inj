package com.example

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
import com.example.config.MyVpnCrypto
import com.example.data.ConnectionMode
import com.example.data.NetworkConfigEntity
import com.example.data.PayloadMode
import com.example.data.ServerAccountEntity
import com.example.membership.PassStatus
import com.example.redeem.RedeemOutcome
import com.example.ui.about.AboutScreen
import com.example.ui.home.HomeScreen
import com.example.ui.home.NetworkPickerBottomSheet
import com.example.ui.home.ServerPickerBottomSheet
import com.example.ui.networks.NetworksScreen
import com.example.ui.profiles.ServersScreen
import com.example.ui.redeem.RedeemCodeScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.vpn.VpnProtectionSettings
import com.example.vpn.VpnTrafficStats
import com.example.vpn.VpnTunnelState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LockdownAndHiddenUrlTest {
  @get:Rule val composeRule = createComposeRule()

  private val secretNetwork =
    NetworkConfigEntity(
      id = 10L,
      name = "My Clean Network",
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
      name = "My Clean Server",
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
      region = "ZZ",
      tags = "secret-tag",
      defaultNetworkId = 10L,
    )

  private fun assertZeroSecretLeaksOnScreen() {
    val forbidden =
      listOf(
        "amkt-injector-servers",
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
        .onAllNodesWithText(secret, substring = true, useUnmergedTree = true)
        .assertCountEquals(0)
    }
  }

  @Test
  fun aboutScreenHidesServerListUrlCompletelyAndLoadUsesEmbeddedDefault() {
    val fetchedCalls = mutableListOf<Pair<String, Boolean>>()

    composeRule.setContent {
      MyApplicationTheme {
        AboutScreen(
          isFetchingRemoteServers = false,
          bannerConfigUrl = "",
          isFetchingBanner = false,
          onFetchRemoteServers = { url, replace -> fetchedCalls.add(url to replace) },
          onFetchOwnerBanner = {},
        )
      }
    }
    composeRule.waitForIdle()

    // No URL text, no masked dots, no URL field, no reveal toggle, no reset button
    composeRule.onAllNodesWithText("amkt-injector-servers", substring = true, useUnmergedTree = true).assertCountEquals(0)
    composeRule.onAllNodesWithText("••••", substring = true, useUnmergedTree = true).assertCountEquals(0)
    composeRule.onAllNodesWithTag("remote_servers_url_input", useUnmergedTree = true).assertCountEquals(0)
    composeRule.onAllNodesWithTag("remote_servers_url_toggle_visibility", useUnmergedTree = true).assertCountEquals(0)
    composeRule.onAllNodesWithTag("remote_servers_url_masked_status", useUnmergedTree = true).assertCountEquals(0)
    composeRule.onAllNodesWithTag("remote_servers_reset_button", useUnmergedTree = true).assertCountEquals(0)

    // Load button still works and triggers embedded default fetch ("")
    composeRule.onNodeWithTag("remote_servers_fetch_button").performScrollTo().performClick()
    composeRule.waitForIdle()
    assertEquals(listOf("" to false), fetchedCalls)
  }

  @Test
  fun homeScreenShowsOnlyNamesAndReadOnlyUsageWithNoDetailsOrEditControls() {
    composeRule.setContent {
      MyApplicationTheme {
        HomeScreen(
          networks = listOf(secretNetwork),
          servers = listOf(secretServer),
          selectedNetwork = secretNetwork,
          selectedServer = secretServer,
          recentLogs = emptyList(),
          vpnState = VpnTunnelState.Disconnected,
          trafficStats = VpnTrafficStats(bytesDown = 2048L, bytesUp = 1024L),
          vpnSettings = VpnProtectionSettings(),
          passStatus = PassStatus(deviceId = "DEV-123"),
          ownerBanner = null,
          isValidatingCode = false,
          isDarkTheme = false,
          onToggleTheme = {},
          onConnectClick = {},
          onReconnectClick = {},
          onToggleKillSwitch = {},
          onToggleAutoReconnect = {},
          onSelectNetwork = {},
          onSelectServer = {},
          onImportClick = {},
          onOpenNetworksClick = {},
          onOpenServersClick = {},
          onOpenLogsClick = {},
          onValidateCode = {},
          onClearCode = {},
          onOpenBannerLink = {},
        )
      }
    }
    composeRule.waitForIdle()

    // Selected names are visible
    composeRule.onNodeWithText("My Clean Network").assertIsDisplayed()
    composeRule.onNodeWithText("My Clean Server").assertIsDisplayed()

    // No technical details anywhere on screen
    assertZeroSecretLeaksOnScreen()
    composeRule.onAllNodesWithText("DNSTT", substring = true, useUnmergedTree = true).assertCountEquals(0)
    composeRule.onAllNodesWithText("WebSocket", substring = true, useUnmergedTree = true).assertCountEquals(0)

    // No edit, add, or export buttons
    composeRule.onAllNodesWithTag("home_edit_network_button", useUnmergedTree = true).assertCountEquals(0)
    composeRule.onAllNodesWithTag("home_edit_server_button", useUnmergedTree = true).assertCountEquals(0)
    composeRule.onAllNodesWithTag("quick_add_server_button", useUnmergedTree = true).assertCountEquals(0)
    composeRule.onAllNodesWithTag("quick_export_button", useUnmergedTree = true).assertCountEquals(0)

    // Update refresh and .myvpn import remain visible
    val lazyList = composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
    composeRule.onNodeWithTag("home_refresh_servers_button").assertIsDisplayed()

    // Usage (remaining time, duration, data) stays visible AND strictly read-only (not clickable/editable)
    val notClickable = SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick)
    val notEditable = SemanticsMatcher.keyNotDefined(SemanticsActions.SetText)
    lazyList.performScrollToNode(hasTestTag("home_remaining_time_value"))
    composeRule.onNodeWithTag("home_remaining_time_value").performScrollTo().assertIsDisplayed().assert(notClickable).assert(notEditable)
    lazyList.performScrollToNode(hasTestTag("home_duration_pill"))
    composeRule.onNodeWithTag("home_duration_pill").performScrollTo().assertIsDisplayed().assert(notClickable).assert(notEditable)
    lazyList.performScrollToNode(hasTestTag("home_data_usage_pill"))
    composeRule.onNodeWithTag("home_data_usage_pill").performScrollTo().assertIsDisplayed().assert(notClickable).assert(notEditable)

    lazyList.performScrollToNode(hasTestTag("quick_import_button"))
    composeRule.onNodeWithTag("quick_import_button").performScrollTo().assertIsDisplayed()
    assertZeroSecretLeaksOnScreen()
  }

  @Test
  fun networksScreenShowsOnlyNetworkNameWithNoEditAddExportOrDetails() {
    var selected: NetworkConfigEntity? = null
    composeRule.setContent {
      MyApplicationTheme {
        NetworksScreen(
          networks = listOf(secretNetwork),
          selectedNetworkId = secretNetwork.id,
          onSelectNetwork = { selected = it },
          onImportClick = {},
        )
      }
    }
    composeRule.waitForIdle()

    composeRule.onNodeWithText("My Clean Network").assertIsDisplayed()
    assertZeroSecretLeaksOnScreen()
    composeRule.onAllNodesWithText("Payload", substring = true, useUnmergedTree = true).assertCountEquals(0)
    composeRule.onAllNodesWithText("WebSocket", substring = true, useUnmergedTree = true).assertCountEquals(0)

    composeRule.onAllNodesWithTag("networks_add_fab", useUnmergedTree = true).assertCountEquals(0)
    composeRule.onAllNodesWithTag("network_edit_${secretNetwork.id}", useUnmergedTree = true).assertCountEquals(0)
    composeRule.onAllNodesWithTag("network_export_${secretNetwork.id}", useUnmergedTree = true).assertCountEquals(0)
    composeRule.onAllNodesWithTag("network_delete_${secretNetwork.id}", useUnmergedTree = true).assertCountEquals(0)
    composeRule.onNodeWithTag("networks_import_button").assertIsDisplayed()

    composeRule.onNodeWithTag("network_card_${secretNetwork.id}").performClick()
    assertEquals(secretNetwork.id, selected?.id)
  }

  @Test
  fun serversScreenShowsOnlyServerNameWithNoEditAddExportFiltersOrDetails() {
    var selected: ServerAccountEntity? = null
    composeRule.setContent {
      MyApplicationTheme {
        ServersScreen(
          servers = listOf(secretServer),
          selectedServerId = secretServer.id,
          onSelectServer = { selected = it },
          onImportClick = {},
          onSyncRemoteClick = {},
        )
      }
    }
    composeRule.waitForIdle()

    composeRule.onNodeWithText("My Clean Server").assertIsDisplayed()
    assertZeroSecretLeaksOnScreen()
    composeRule.onAllNodesWithText("DNSTT", substring = true, useUnmergedTree = true).assertCountEquals(0)
    composeRule.onAllNodesWithText("VLESS", substring = true, useUnmergedTree = true).assertCountEquals(0)

    composeRule.onAllNodesWithTag("servers_add_fab", useUnmergedTree = true).assertCountEquals(0)
    composeRule.onAllNodesWithTag("filter_chip_dnstt", useUnmergedTree = true).assertCountEquals(0)
    composeRule.onAllNodesWithTag("server_edit_${secretServer.id}", useUnmergedTree = true).assertCountEquals(0)
    composeRule.onAllNodesWithTag("server_export_${secretServer.id}", useUnmergedTree = true).assertCountEquals(0)
    composeRule.onAllNodesWithTag("server_delete_${secretServer.id}", useUnmergedTree = true).assertCountEquals(0)
    composeRule.onNodeWithTag("servers_remote_sync_button").assertIsDisplayed()
    composeRule.onNodeWithTag("servers_import_button").assertIsDisplayed()

    composeRule.onNodeWithTag("server_card_${secretServer.id}").performClick()
    assertEquals(secretServer.id, selected?.id)
  }

  @Test
  fun serverPickerSheetShowsOnlyNameHidesAddAndFiltersOnlyByName() {
    composeRule.setContent {
      MyApplicationTheme {
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

    composeRule.onNodeWithText("My Clean Server").assertIsDisplayed()
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
      MyApplicationTheme {
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

    composeRule.onNodeWithText("My Clean Network").assertIsDisplayed()
    assertZeroSecretLeaksOnScreen()
    composeRule.onAllNodesWithTag("network_sheet_add", useUnmergedTree = true).assertCountEquals(0)

    // Searching by hidden SNI must NOT match
    composeRule.onNodeWithTag("network_sheet_search").performTextInput("secret-sni")
    composeRule.waitForIdle()
    composeRule.onAllNodesWithTag("network_sheet_item_${secretNetwork.id}", useUnmergedTree = true).assertCountEquals(0)
  }

  @Test
  fun redeemSuccessCardHidesServerHostAndModeDetails() {
    composeRule.setContent {
      MyApplicationTheme {
        RedeemCodeScreen(
          deviceId = "DEV-999",
          webhookUrl = "https://example.invalid/webhook",
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
          onOpenWebhookSettings = {},
        )
      }
    }
    composeRule.waitForIdle()

    val lazyList = composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
    lazyList.performScrollToNode(hasTestTag("redeem_success_card"))
    composeRule.onNodeWithText("My Clean Server").performScrollTo().assertIsDisplayed()
    assertZeroSecretLeaksOnScreen()
    composeRule.onAllNodesWithText("DNSTT", substring = true, useUnmergedTree = true).assertCountEquals(0)
  }

  @Test
  fun myVpnFileImportGuardAllowsMyVpnAndRejectsPlainTextLinks() {
    val encryptedBytes = MyVpnCrypto.encryptJson("""{"name":"test"}""")
    assertTrue(isAllowedMyVpnFileImport(encryptedBytes, "config.bin"))
    assertTrue(isAllowedMyVpnFileImport("{}".toByteArray(), "owner.myvpn"))
    assertFalse(isAllowedMyVpnFileImport("vless://uuid@host:443#test".toByteArray(), "links.txt"))
    assertFalse(isAllowedMyVpnFileImport("""{"servers":[]}""".toByteArray(), "servers.json"))
  }
}
