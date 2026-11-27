package com.example

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
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
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
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
import com.example.data.ServerListLoader
import com.example.data.ServerProfileEntity
import com.example.data.ServerProfileRepository
import com.example.membership.MembershipRepository
import com.example.membership.OwnerBanner
import com.example.membership.RemotePriceItem
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
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
class ConsolidatedFixBatchM20Test {

  @get:Rule val composeRule = createComposeRule()

  private lateinit var context: Context
  private lateinit var db: AmktDatabase
  private lateinit var remoteRepo: RemoteServerListRepository
  private lateinit var splitRepo: NetworkServerRepository
  private val loader = ServerListLoader()

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    db = Room.inMemoryDatabaseBuilder(context, AmktDatabase::class.java).allowMainThreadQueries().build()
    remoteRepo =
      RemoteServerListRepository(
        context = context,
        dataStore =
          PreferenceDataStoreFactory.create {
            File(context.cacheDir, "m20_remote_${UUID.randomUUID()}.preferences_pb")
          },
      )
    splitRepo = NetworkServerRepository(db.networkConfigDao(), db.serverAccountDao(), remoteRepo)
  }

  @After
  fun tearDown() {
    Shadows.shadowOf(Looper.getMainLooper()).idle()
    db.close()
  }

  // ==================== Item 9: Dedicated "ဆက်သွယ်ရန်" Contact Us button ====================

  @Test
  fun homeShowsDedicatedContactUsButtonOpeningTelegramForBothFreeAndPaidUsers() {
    val openedUrls = mutableListOf<String>()
    val vipInj =
      ServerAccountEntity(
        id = 7L,
        name = "Singapore INJ",
        connectionMode = ConnectionMode.SSH_DIRECT.code,
        host = "vip.example.com",
        port = 22,
        expiryDate = "2099-12-31",
        tags = "VIP, REDEEM:INJ-7777-8888",
      )

    composeRule.setContent {
      MyApplicationTheme(darkTheme = true) {
        HomeScreen(
          networks = listOf(NetworkConfigEntity(id = 1L, name = "DataNoPro")),
          servers = listOf(vipInj),
          selectedNetwork = NetworkConfigEntity(id = 1L, name = "DataNoPro"),
          selectedServer = vipInj,
          vpnState = VpnTunnelState.Disconnected,
          trafficStats = VpnTrafficStats(),
          showAdsterraBanners = false,
          showOwnerBanner = true,
          hasActiveRedeemedServer = true,
          activeRedeemedServer = vipInj,
          onOpenBannerLink = { openedUrls.add(it) },
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
    composeRule.onNodeWithTag("home_emblem_internet_waves", useUnmergedTree = true).assertIsDisplayed()
    val lazyList = composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
    lazyList.performScrollToNode(hasTestTag("home_contact_us_button"))
    composeRule.onNodeWithTag("home_contact_us_button").performScrollTo().assertIsDisplayed()
    composeRule.onNodeWithText("ဆက်သွယ်ရန်", useUnmergedTree = true).assertIsDisplayed()
    composeRule.onNodeWithTag("home_contact_us_button").performClick()
    assertEquals(listOf(OwnerBanner.DEFAULT_TELEGRAM_URL), openedUrls)
  }

  // ==================== Item 10: Active INJ Expiry & Unlimited Fallback ====================

  @Test
  fun homeDisplaysUnlimitedWhenActiveInjServerHasNoExpiryDateAndOmitsFakeExpiry() {
    val now = 1_764_000_000_000L
    val vipNoExpiry =
      ServerAccountEntity(
        id = 2L,
        name = "VIP Unlimited",
        host = "vip.example.com",
        port = 22,
        expiryDate = "",
        tags = "REDEEM:INJ-UNLIM-01",
      )

    composeRule.setContent {
      MyApplicationTheme(darkTheme = true) {
        HomeScreen(
          networks = listOf(NetworkConfigEntity(id = 1L, name = "DataNoPro")),
          servers = listOf(vipNoExpiry),
          selectedNetwork = NetworkConfigEntity(id = 1L, name = "DataNoPro"),
          selectedServer = vipNoExpiry,
          vpnState = VpnTunnelState.Disconnected,
          trafficStats = VpnTrafficStats(),
          hasActiveRedeemedServer = true,
          activeRedeemedServer = vipNoExpiry,
          nowEpochMsProvider = { now },
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
    composeRule.onNodeWithTag("home_remaining_time_value", useUnmergedTree = true).assertTextEquals("Unlimited")
    composeRule.onAllNodesWithTag("home_server_expiry_line", useUnmergedTree = true).assertCountEquals(0)
  }

  @Test
  fun homeShowsInjVipExpiryLineWhenUserSwitchesToFreeServerWhileHoldingActiveTimedInjServer() {
    val now = 1_764_000_000_000L
    val freeSrv =
      ServerAccountEntity(
        id = 1L,
        name = "Japan Free",
        host = "free.example.com",
        port = 22,
        region = "JP",
      )
    val vipWithExpiry =
      ServerAccountEntity(
        id = 3L,
        name = "VIP Timed SG",
        host = "vip2.example.com",
        port = 22,
        expiryDate = "2099-12-31",
        tags = "REDEEM:INJ-TIMED-02",
      )

    composeRule.setContent {
      MyApplicationTheme(darkTheme = true) {
        HomeScreen(
          networks = listOf(NetworkConfigEntity(id = 1L, name = "DataNoPro")),
          servers = listOf(freeSrv, vipWithExpiry),
          selectedNetwork = NetworkConfigEntity(id = 1L, name = "DataNoPro"),
          selectedServer = freeSrv,
          vpnState = VpnTunnelState.Disconnected,
          trafficStats = VpnTrafficStats(),
          hasActiveRedeemedServer = true,
          activeRedeemedServer = vipWithExpiry,
          showAdsterraBanners = false,
          showOwnerBanner = true,
          nowEpochMsProvider = { now },
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
    composeRule.onNodeWithTag("home_inj_vip_expiry_line", useUnmergedTree = true).assertIsDisplayed()
    composeRule.onNodeWithText("INJ (VIP Timed SG):", substring = true, useUnmergedTree = true).assertIsDisplayed()
  }

  // ==================== Item 11: Room v6 -> v7 Migration + Distinct-Device Counts ====================

  @Test
  fun migration6To7PreservesAllTablesAndDefaultsLifetimeUsersToNull(): Unit = runBlocking {
    val dbName = "test_amkt_migration_6_7_${UUID.randomUUID()}.db"
    context.deleteDatabase(dbName)
    val dbFile = context.getDatabasePath(dbName)
    dbFile.parentFile?.mkdirs()

    val rawDb = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
    rawDb.execSQL(
      """
      CREATE TABLE IF NOT EXISTS `server_profiles` (
        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
        `name` TEXT NOT NULL, `mode` TEXT NOT NULL, `sshHost` TEXT NOT NULL, `sshPort` INTEGER NOT NULL,
        `username` TEXT NOT NULL, `password` TEXT NOT NULL, `payload` TEXT NOT NULL,
        `proxyHost` TEXT NOT NULL, `proxyPort` INTEGER NOT NULL, `sni` TEXT NOT NULL,
        `payloadMode` TEXT NOT NULL, `extraHeaders` TEXT NOT NULL,
        `dnsttNameserver` TEXT NOT NULL, `dnsttPublicKey` TEXT NOT NULL, `dnsttResolver` TEXT NOT NULL,
        `vlessLink` TEXT NOT NULL, `expiryDate` TEXT NOT NULL, `locked` INTEGER NOT NULL,
        `notes` TEXT NOT NULL, `isSelected` INTEGER NOT NULL,
        `ovpnConfigEncrypted` TEXT NOT NULL, `ovpnAuthRequired` INTEGER NOT NULL,
        `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL
      )
      """.trimIndent()
    )
    rawDb.execSQL(
      """
      CREATE TABLE IF NOT EXISTS `network_configs` (
        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
        `name` TEXT NOT NULL, `payloadMode` TEXT NOT NULL, `payloadTemplate` TEXT NOT NULL,
        `sniHost` TEXT NOT NULL, `extraHeaders` TEXT NOT NULL, `httpProxyHost` TEXT NOT NULL,
        `httpProxyPort` INTEGER NOT NULL, `locked` INTEGER NOT NULL,
        `isFromRemote` INTEGER NOT NULL DEFAULT 0, `isUserImported` INTEGER NOT NULL DEFAULT 0,
        `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL
      )
      """.trimIndent()
    )
    rawDb.execSQL(
      """
      CREATE TABLE IF NOT EXISTS `server_accounts` (
        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
        `name` TEXT NOT NULL, `connectionMode` TEXT NOT NULL, `host` TEXT NOT NULL, `port` INTEGER NOT NULL,
        `username` TEXT NOT NULL, `password` TEXT NOT NULL, `dnsttNameserver` TEXT NOT NULL,
        `dnsttPublicKey` TEXT NOT NULL, `dnsttResolver` TEXT NOT NULL, `vlessLink` TEXT NOT NULL,
        `vlessUuid` TEXT NOT NULL, `expiryDate` TEXT NOT NULL, `region` TEXT NOT NULL,
        `tags` TEXT NOT NULL, `notes` TEXT NOT NULL, `locked` INTEGER NOT NULL,
        `defaultNetworkId` INTEGER, `wasSelectedOnMigration` INTEGER NOT NULL,
        `ovpnConfigEncrypted` TEXT NOT NULL, `ovpnAuthRequired` INTEGER NOT NULL,
        `isFromRemote` INTEGER NOT NULL DEFAULT 0, `isUserImported` INTEGER NOT NULL,
        `onlineCount` INTEGER, `userLimit` INTEGER NOT NULL DEFAULT 100,
        `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL
      )
      """.trimIndent()
    )
    rawDb.execSQL(
      "INSERT INTO network_configs (id, name, payloadMode, payloadTemplate, sniHost, extraHeaders, httpProxyHost, httpProxyPort, locked, isFromRemote, isUserImported, createdAt, updatedAt) VALUES (1, 'Atom Free', 'DIRECT', '', '', '', '', 8080, 0, 1, 0, 1000, 1000)"
    )
    rawDb.execSQL(
      "INSERT INTO server_accounts (id, name, connectionMode, host, port, username, password, dnsttNameserver, dnsttPublicKey, dnsttResolver, vlessLink, vlessUuid, expiryDate, region, tags, notes, locked, defaultNetworkId, wasSelectedOnMigration, ovpnConfigEncrypted, ovpnAuthRequired, isFromRemote, isUserImported, onlineCount, userLimit, createdAt, updatedAt) VALUES (1, 'JP v6', 'SSH_DIRECT', 'jp.example.com', 22, 'u', 'p', '', '', '8.8.8.8:53', '', '', '', 'JP', '', '', 0, 1, 0, '', 0, 1, 0, 50, 100, 1000, 1000)"
    )
    rawDb.version = 6
    rawDb.close()

    val migrated =
      Room.databaseBuilder(context, AmktDatabase::class.java, dbName)
        .addMigrations(
          AmktDatabase.MIGRATION_1_2,
          AmktDatabase.MIGRATION_2_3,
          AmktDatabase.MIGRATION_3_4,
          AmktDatabase.MIGRATION_4_5,
          AmktDatabase.MIGRATION_5_6,
          AmktDatabase.MIGRATION_6_7,
        )
        .allowMainThreadQueries()
        .build()

    try {
      val srv = migrated.serverAccountDao().getServerById(1L)
      assertNotNull(srv)
      assertEquals("JP v6", srv!!.name)
      assertEquals(50, srv.onlineCount)
      assertEquals(100, srv.userLimit)
      assertNull("Migrated v6 row must default lifetimeUsers to NULL", srv.lifetimeUsers)
      assertNull(srv.formatLifetimeUsersLabel())

      migrated.serverAccountDao().updateLifetimeUsersByName("JP v6", 1240)
      val updated = migrated.serverAccountDao().getServerById(1L)!!
      assertEquals(1240, updated.lifetimeUsers)
      assertEquals("Used by: 1,240 users", updated.formatLifetimeUsersLabel())
    } finally {
      migrated.close()
      context.deleteDatabase(dbName)
    }
  }

  @Test
  fun lifetimeUsersAndAppUsersRenderOnHomeWhenPresentAndNeverInventWhenAbsent(): Unit = runBlocking {
    val json =
      """
      {
        "app_users": 5420,
        "networks": [{"name": "DataNoPro", "payload_mode": "DIRECT"}],
        "servers": [
          {"name": "Japan VIP", "host": "jp.example.com", "port": 22, "online": 40, "limit": 100, "lifetime_users": 1240},
          {"name": "Singapore NoCount", "host": "sg.example.com", "port": 22, "limit": 100}
        ]
      }
      """.trimIndent()
    val parsed = loader.parseSplitRemoteList(json).getOrThrow()
    val summary = splitRepo.mergeSplitRemoteList(parsed, replaceExisting = false)
    assertEquals(5420, summary.totalAppUsers)

    val srvs = splitRepo.getAllServersSnapshot().sortedBy { it.name }
    val jp = srvs.first { it.name == "Japan VIP" }
    val sg = srvs.first { it.name == "Singapore NoCount" }
    assertEquals(1240, jp.lifetimeUsers)
    assertNull(sg.lifetimeUsers)

    val nets = splitRepo.getAllNetworksSnapshot()
    var activeServer by mutableStateOf(jp)
    var activeAppUsers by mutableStateOf<Int?>(summary.totalAppUsers)

    composeRule.setContent {
      MyApplicationTheme(darkTheme = true) {
        HomeScreen(
          networks = nets,
          servers = srvs,
          selectedNetwork = nets.first(),
          selectedServer = activeServer,
          vpnState = VpnTunnelState.Disconnected,
          trafficStats = VpnTrafficStats(),
          totalAppUsers = activeAppUsers,
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
    composeRule.onNodeWithTag("home_server_lifetime_users_line", useUnmergedTree = true).assertTextEquals("Used by: 1,240 users")
    composeRule.onNodeWithTag("home_app_users_pill", useUnmergedTree = true).assertIsDisplayed()
    composeRule.onNodeWithText("App users: 5,420", useUnmergedTree = true).assertIsDisplayed()

    // Switch to server without lifetime_users & null totalAppUsers -> neither UI node is shown (zero invented counts)
    composeRule.runOnUiThread {
      activeServer = sg
      activeAppUsers = null
    }
    composeRule.waitForIdle()
    composeRule.onAllNodesWithTag("home_server_lifetime_users_line", useUnmergedTree = true).assertCountEquals(0)
    composeRule.onAllNodesWithTag("home_app_users_pill", useUnmergedTree = true).assertCountEquals(0)
  }

  @Test
  fun usagePingSendsDeviceAndServerToWebhookUpdatesCountsAndFailsSilentlyWhenOffline(): Unit = runBlocking {
    val receivedBodies = CopyOnWriteArrayList<String>()
    val webhook =
      MultiRequestHttpServer { _ ->
        200 to """{"app_users": 6012, "server_users": 891}"""
      }
    val port = webhook.start(receivedBodies)
    try {
      val memberRepo =
        MembershipRepository(
          context = context,
          dataStore =
            PreferenceDataStoreFactory.create {
              File(context.cacheDir, "m20_usage_${UUID.randomUUID()}.preferences_pb")
            },
          usageWebhookUrl = "http://127.0.0.1:$port/webhook/amkt-injector-usage",
        )
      val legacyRepo = ServerProfileRepository(db.serverProfileDao(), remoteRepo)
      val vpnController = RecordingVpnController()

      val net = NetworkConfigEntity(name = "Direct", payloadMode = PayloadMode.DIRECT.code)
      val srv = ServerAccountEntity(name = "Tokyo 01", connectionMode = ConnectionMode.SSH_DIRECT.code, host = "tokyo.example.com", port = 22)
      val nId = splitRepo.saveNetwork(net, selectAfterSave = true)
      val sId = splitRepo.saveServer(srv, selectAfterSave = true)
      splitRepo.selectCombination(nId, sId)

      val viewModelStore = ViewModelStore()
      val viewModel =
        ViewModelProvider(
          viewModelStore,
          AmktViewModel.Factory(
            repository = legacyRepo,
            membershipRepository = memberRepo,
            vpnPreferencesRepository = VpnPreferencesRepository(context),
            vpnController = vpnController,
            configCodec = ConfigFileCodec(),
            logStore = AppLogStore(),
            networkServerRepository = splitRepo,
          ),
        )[AmktViewModel::class.java]

      try {
        val deadlineLaunch = System.currentTimeMillis() + 3_000L
        while (receivedBodies.isEmpty() && System.currentTimeMillis() < deadlineLaunch) {
          Thread.sleep(25L)
        }
        assertTrue("First-launch ping should be sent", receivedBodies.isNotEmpty())
        assertTrue(receivedBodies.first().contains("\"event\":\"app_open\""))
        assertTrue(receivedBodies.first().contains("\"device_id\":"))

        assertNull(memberRepo.reportFirstLaunchUsageIfNeeded())

        val storedNet = splitRepo.getNetworkById(nId)!!
        val storedSrv = splitRepo.getServerById(sId)!!
        val combined = EffectiveConnectionCombiner.combine(storedNet, storedSrv)
        viewModel.startCombinedVpnAfterConsent(storedNet, storedSrv, combined)

        assertEquals("VPN connect must proceed immediately without waiting on webhook", 1, vpnController.startCount)

        val deadlineConnect = System.currentTimeMillis() + 3_000L
        while (receivedBodies.size < 2 && System.currentTimeMillis() < deadlineConnect) {
          Thread.sleep(25L)
        }
        assertTrue(receivedBodies.any { it.contains("\"event\":\"connect\"") && it.contains("\"server_name\":\"Tokyo 01\"") })

        val deadlineDb = System.currentTimeMillis() + 3_000L
        while ((splitRepo.getServerById(sId)?.lifetimeUsers == null || memberRepo.totalAppUsersFlow.first() != 6012) &&
          System.currentTimeMillis() < deadlineDb
        ) {
          Thread.sleep(25L)
        }
        assertEquals(6012, memberRepo.totalAppUsersFlow.first())
        assertEquals(891, splitRepo.getServerById(sId)?.lifetimeUsers)
      } finally {
        viewModelStore.clear()
      }
    } finally {
      webhook.stop()
    }

    // Offline / unreachable endpoint MUST return null silently and NEVER throw or block
    val offlineRepo =
      MembershipRepository(
        context = context,
        dataStore =
          PreferenceDataStoreFactory.create {
            File(context.cacheDir, "m20_offline_${UUID.randomUUID()}.preferences_pb")
          },
        usageWebhookUrl = "http://127.0.0.1:1/unreachable",
      )
    assertNull(offlineRepo.reportUsagePing(serverName = "Any", event = "connect"))
  }

  // ==================== Item 13: Dynamic Pricing from Remote JSON ====================

  @Test
  fun dynamicPricesDisplayOnRedeemScreenOnlyNeverOnHomeAndHideWhenRemoved() {
    val memberRepo =
      MembershipRepository(
        context = context,
        dataStore =
          PreferenceDataStoreFactory.create {
            File(context.cacheDir, "m20_prices_${UUID.randomUUID()}.preferences_pb")
          },
      )

    val json1 =
      """
      {
        "networks": [{"name": "DataNoPro", "payload_mode": "DIRECT"}],
        "servers": [{"name": "Japan VIP", "host": "jp.example.com", "port": 22}],
        "prices": [
          {"label": "1 Month VIP", "amount": 5000, "currency": "MMK", "duration": "30 days", "server": "All Servers"},
          {"label": "Incomplete Missing Currency", "amount": 999, "duration": "7 days"},
          {"label": "3 Months VIP", "amount": "12,000", "currency": "MMK", "duration": "90 days"}
        ]
      }
      """.trimIndent()

    val parsed1 = loader.parseSplitRemoteList(json1).getOrThrow()
    assertEquals("Incomplete entry missing currency must be ignored", 2, parsed1.prices.size)
    assertEquals("5000 MMK / 30 days", parsed1.prices[0].formattedPriceLine())
    assertEquals("All Servers", parsed1.prices[0].server)
    assertEquals("12,000 MMK / 90 days", parsed1.prices[1].formattedPriceLine())

    runBlocking { memberRepo.saveRemotePrices(parsed1.prices) }
    val savedPrices1 = runBlocking { memberRepo.remotePricesFlow.first() }
    assertEquals(2, savedPrices1.size)

    var showRedeemScreen by mutableStateOf(false)
    var currentPrices by mutableStateOf<List<RemotePriceItem>>(savedPrices1)

    composeRule.setContent {
      MyApplicationTheme(darkTheme = true) {
        if (!showRedeemScreen) {
          HomeScreen(
            networks = listOf(NetworkConfigEntity(id = 1L, name = "DataNoPro")),
            servers = listOf(ServerAccountEntity(id = 1L, name = "Japan VIP")),
            selectedNetwork = NetworkConfigEntity(id = 1L, name = "DataNoPro"),
            selectedServer = ServerAccountEntity(id = 1L, name = "Japan VIP"),
            vpnState = VpnTunnelState.Disconnected,
            trafficStats = VpnTrafficStats(),
            onConnectClick = {},
            onReconnectClick = {},
            onSelectNetwork = {},
            onSelectServer = {},
            onOpenNetworksClick = {},
            onOpenServersClick = {},
          )
        } else {
          RedeemCodeScreen(
            deviceId = "dev-123",
            isRedeeming = false,
            lastOutcome = null,
            remotePrices = currentPrices,
            onRedeemSubmit = {},
            onClearOutcome = {},
            onNavigateBack = {},
          )
        }
      }
    }
    composeRule.waitForIdle()
    // Home screen MUST NOT show any prices box
    composeRule.onAllNodesWithTag("home_prices_card", useUnmergedTree = true).assertCountEquals(0)
    composeRule.onAllNodesWithText("1 Month VIP", substring = true, useUnmergedTree = true).assertCountEquals(0)

    // Switch to RedeemCodeScreen -> dynamic prices render cleanly
    composeRule.runOnUiThread { showRedeemScreen = true }
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("redeem_prices_card").assertIsDisplayed()
    composeRule.onNodeWithText("1 Month VIP", useUnmergedTree = true).assertIsDisplayed()
    composeRule.onNodeWithText("5000 MMK / 30 days", useUnmergedTree = true).assertIsDisplayed()
    composeRule.onNodeWithText("12,000 MMK / 90 days", useUnmergedTree = true).assertIsDisplayed()
    composeRule.onAllNodesWithText("Incomplete Missing Currency", substring = true, useUnmergedTree = true).assertCountEquals(0)

    // Remote update removes prices -> Redeem hides prices with ZERO hardcoded fallback
    val jsonNoPrices =
      """
      {
        "networks": [{"name": "DataNoPro", "payload_mode": "DIRECT"}],
        "servers": [{"name": "Japan VIP", "host": "jp.example.com", "port": 22}]
      }
      """.trimIndent()
    val parsedEmpty = loader.parseSplitRemoteList(jsonNoPrices).getOrThrow()
    assertTrue(parsedEmpty.prices.isEmpty())
    runBlocking { memberRepo.saveRemotePrices(parsedEmpty.prices) }
    val emptyPrices = runBlocking { memberRepo.remotePricesFlow.first() }
    assertTrue(emptyPrices.isEmpty())

    composeRule.runOnUiThread { currentPrices = emptyPrices }
    composeRule.waitForIdle()
    composeRule.onAllNodesWithTag("redeem_prices_card", useUnmergedTree = true).assertCountEquals(0)
    composeRule.onAllNodesWithText("Ks", substring = true, useUnmergedTree = true).assertCountEquals(0)
    composeRule.onAllNodesWithText("2,500", substring = true, useUnmergedTree = true).assertCountEquals(0)

    val passDesc = context.getString(R.string.pass_dialog_desc)
    assertFalse("pass_dialog_desc must contain zero hardcoded prices", passDesc.contains("2,500"))
    assertFalse("pass_dialog_desc must contain zero hardcoded currency", passDesc.contains("Ks"))
  }

  // ==================== Item 16 & Item 12: Adsterra-Only on Networks/Servers + All 4 Tabs ====================

  @Test
  fun networksScreenPinsAdsterraBannerOutsideListForFreeUserAndNeverShowsOwnAd() {
    val sampleNet = NetworkConfigEntity(id = 1L, name = "Atom Free", payloadMode = PayloadMode.DIRECT.code)
    composeRule.setContent {
      MyApplicationTheme(darkTheme = true) {
        NetworksScreen(
          networks = listOf(sampleNet),
          selectedNetworkId = 1L,
          onSelectNetwork = {},
          onUpdateClick = {},
          showAdsterraBanners = true,
        )
      }
    }
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Atom Free").assertIsDisplayed()
    composeRule.onNodeWithTag("networks_banner_adsterra", useUnmergedTree = true).assertIsDisplayed()
    composeRule.onAllNodesWithTag("networks_banner_owner_ad", useUnmergedTree = true).assertCountEquals(0)
  }

  @Test
  fun serversScreenShowsZeroAdBannersForPaidInjUserAndDisplaysLifetimeUsers() {
    val sampleSrv = ServerAccountEntity(id = 1L, name = "Japan 01", onlineCount = 12, userLimit = 100, lifetimeUsers = 450)
    composeRule.setContent {
      MyApplicationTheme(darkTheme = true) {
        ServersScreen(
          servers = listOf(sampleSrv),
          selectedServerId = 1L,
          onSelectServer = {},
          onSyncRemoteClick = {},
          showAdsterraBanners = false,
        )
      }
    }
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("server_card_lifetime_users_1", useUnmergedTree = true).assertTextEquals("Used by: 450 users")
    composeRule.onAllNodesWithTag("servers_banner_adsterra", useUnmergedTree = true).assertCountEquals(0)
    composeRule.onAllNodesWithTag("servers_banner_owner_ad", useUnmergedTree = true).assertCountEquals(0)
  }

  @Test
  fun serverPickerSheetPinsAdsterraBannerForFreeUserAndDisplaysLifetimeUsers() {
    val sampleSrv = ServerAccountEntity(id = 1L, name = "Japan 01", onlineCount = 12, userLimit = 100, lifetimeUsers = 450)
    composeRule.setContent {
      MyApplicationTheme(darkTheme = true) {
        ServerPickerBottomSheet(
          servers = listOf(sampleSrv),
          selectedServerId = 1L,
          onSelect = {},
          onManageAll = {},
          onOpenRedeem = {},
          onDismiss = {},
          showAdsterraBanners = true,
        )
      }
    }
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("server_sheet_lifetime_users_1", useUnmergedTree = true).assertTextEquals("Used by: 450 users")
    composeRule.onNodeWithTag("server_sheet_banner_adsterra", useUnmergedTree = true).assertIsDisplayed()
    composeRule.onAllNodesWithTag("server_sheet_banner_owner_ad", useUnmergedTree = true).assertCountEquals(0)
  }

  @Test
  fun networkPickerSheetHidesAllBannersForPaidInjUser() {
    val sampleNet = NetworkConfigEntity(id = 1L, name = "Atom Free", payloadMode = PayloadMode.DIRECT.code)
    composeRule.setContent {
      MyApplicationTheme(darkTheme = true) {
        NetworkPickerBottomSheet(
          networks = listOf(sampleNet),
          selectedNetworkId = 1L,
          onSelect = {},
          onManageAll = {},
          onDismiss = {},
          showAdsterraBanners = false,
        )
      }
    }
    composeRule.waitForIdle()
    composeRule.onAllNodesWithTag("network_sheet_banner_owner_ad", useUnmergedTree = true).assertCountEquals(0)
    composeRule.onAllNodesWithTag("network_sheet_banner_adsterra", useUnmergedTree = true).assertCountEquals(0)
  }

  @Test
  fun settingsScreenHasZeroAdBanners() {
    composeRule.setContent {
      MyApplicationTheme(darkTheme = true) {
        SettingsScreen(
          vpnSettings = VpnProtectionSettings(),
          isDarkTheme = true,
          onToggleKillSwitch = {},
          onToggleAutoReconnect = {},
          onToggleTheme = {},
          onOpenRedeemClick = {},
          onNavigateBack = {},
        )
      }
    }
    composeRule.waitForIdle()
    listOf(
      "home_adsterra_320x50_banner",
      "home_owner_banner_card",
      "networks_banner_adsterra",
      "servers_banner_adsterra",
    ).forEach { tag ->
      composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).assertCountEquals(0)
    }
  }

  @Test
  fun allFourBottomNavigationTabsRemainPresent() {
    val memberRepo =
      MembershipRepository(
        context = context,
        dataStore =
          PreferenceDataStoreFactory.create {
            File(context.cacheDir, "m20_nav_member_${UUID.randomUUID()}.preferences_pb")
          },
      )
    val legacyRepo = ServerProfileRepository(db.serverProfileDao(), remoteRepo)
    val redeemRepo =
      RedeemRepository(
        context = context,
        dataStore =
          PreferenceDataStoreFactory.create {
            File(context.cacheDir, "m20_nav_redeem_${UUID.randomUUID()}.preferences_pb")
          },
      )
    val viewModelStore = ViewModelStore()
    val viewModel =
      ViewModelProvider(
        viewModelStore,
        AmktViewModel.Factory(
          repository = legacyRepo,
          membershipRepository = memberRepo,
          vpnPreferencesRepository = VpnPreferencesRepository(context),
          vpnController = RecordingVpnController(),
          configCodec = ConfigFileCodec(),
          logStore = AppLogStore(),
          networkServerRepository = splitRepo,
          redeemRepository = redeemRepo,
        ),
      )[AmktViewModel::class.java]

    try {
      composeRule.setContent {
        MyApplicationTheme(darkTheme = true) {
          AmktInjectorApp(viewModel = viewModel)
        }
      }
      composeRule.waitForIdle()
      composeRule.onNodeWithTag("nav_home").assertIsDisplayed()
      composeRule.onNodeWithTag("nav_networks").assertIsDisplayed()
      composeRule.onNodeWithTag("nav_profiles").assertIsDisplayed()
      composeRule.onNodeWithTag("nav_update").assertIsDisplayed()
    } finally {
      composeRule.waitForIdle()
      viewModelStore.clear()
      Shadows.shadowOf(Looper.getMainLooper()).idle()
    }
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
      _state.value = VpnTunnelState.Disconnected
    }

    override fun updateProtectionSettings(killSwitch: Boolean, autoReconnect: Boolean) = Unit
  }

  private class MultiRequestHttpServer(
    private val handler: (String) -> Pair<Int, String>,
  ) {
    private val executor = Executors.newCachedThreadPool()
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var running = true

    fun start(receivedBodies: MutableList<String>): Int {
      val ss = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
      ss.soTimeout = 5_000
      serverSocket = ss
      executor.submit {
        while (running && !ss.isClosed) {
          try {
            val client = ss.accept()
            executor.submit {
              client.use { sock ->
                sock.soTimeout = 2_000
                val inp = sock.getInputStream()
                val headerBuf = ByteArrayOutputStream()
                var prev4 = 0
                var b = inp.read()
                while (b != -1) {
                  headerBuf.write(b)
                  prev4 = (prev4 shl 8) or (b and 0xFF)
                  if (prev4 == 0x0D0A0D0A) break
                  b = inp.read()
                }
                val headersText = headerBuf.toString(StandardCharsets.ISO_8859_1.name())
                val contentLength =
                  headersText
                    .lines()
                    .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                    ?.substringAfter(':')
                    ?.trim()
                    ?.toIntOrNull()
                    ?: 0
                val bodyBytesRead = ByteArray(contentLength)
                var readTotal = 0
                while (readTotal < contentLength) {
                  val r = inp.read(bodyBytesRead, readTotal, contentLength - readTotal)
                  if (r <= 0) break
                  readTotal += r
                }
                val reqBody = String(bodyBytesRead, 0, readTotal, StandardCharsets.UTF_8)
                receivedBodies.add(reqBody)
                val (statusCode, respBody) = handler(reqBody)
                val respBytes = respBody.toByteArray(StandardCharsets.UTF_8)
                val reason = if (statusCode == 200) "OK" else "Error"
                val respHeaders =
                  "HTTP/1.1 $statusCode $reason\r\n" +
                    "Content-Type: application/json; charset=utf-8\r\n" +
                    "Content-Length: ${respBytes.size}\r\n" +
                    "Connection: close\r\n\r\n"
                val out = sock.getOutputStream()
                out.write(respHeaders.toByteArray(StandardCharsets.ISO_8859_1))
                out.write(respBytes)
                out.flush()
              }
            }
          } catch (_: IOException) {
            // Timeout or socket closed
          }
        }
      }
      return ss.localPort
    }

    fun stop() {
      running = false
      runCatching { serverSocket?.close() }
      executor.shutdownNow()
      executor.awaitTermination(2, TimeUnit.SECONDS)
    }
  }
}
