package com.example

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.ads.AdsterraConfig
import com.example.ads.ConnectGateDecision
import com.example.ads.FreeTimeStatus
import com.example.config.ImportedServerItem
import com.example.data.AmktDatabase
import com.example.data.ConnectionMode
import com.example.data.NetworkConfigEntity
import com.example.data.NetworkServerRepository
import com.example.data.PayloadMode
import com.example.data.RemoteServerListRepository
import com.example.data.ServerAccountEntity
import com.example.data.ServerListLoader
import com.example.membership.MembershipRepository
import com.example.membership.PassStatus
import com.example.ui.home.HomeScreen
import com.example.ui.home.NetworkPickerBottomSheet
import com.example.ui.home.ServerPickerBottomSheet
import com.example.ui.profiles.ServersScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.vpn.VpnTrafficStats
import com.example.vpn.VpnTunnelState
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * M14 Fix Batch regression tests covering all 6 fixes:
 * 1. Remote Update deletes removed remote entries while preserving `.myvpn`-imported & INJ-redeemed entries.
 * 2. Network picker shows strictly networks (zero leaked servers); Server picker shows strictly servers.
 * 3. Free-time model: 6h initial free, +9h per ad watch, shared max 2x/day counter, 3rd grant refused.
 * 4. Online / Limit per server (`Online: 102 | Limit: 150`, or `Limit: 100` when online count absent).
 * 5. Accurate Update summary counts (e.g., 14 stale -> 11 remote = 0 added, 11 updated, 3 removed).
 * 6. Room v5 -> v6 non-destructive migration preserving ownership & Online/Limit columns.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp")
class FixBatchM14Test {

  @get:Rule val composeRule = createComposeRule()

  private lateinit var context: Context
  private lateinit var db: AmktDatabase
  private lateinit var splitRepo: NetworkServerRepository
  private val loader = ServerListLoader()

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    db = Room.inMemoryDatabaseBuilder(context, AmktDatabase::class.java).allowMainThreadQueries().build()
    val remotePrefs = File(context.cacheDir, "m14_remote_${UUID.randomUUID()}.preferences_pb")
    val remoteRepo =
      RemoteServerListRepository(
        context = context,
        dataStore = PreferenceDataStoreFactory.create { remotePrefs },
      )
    splitRepo = NetworkServerRepository(db.networkConfigDao(), db.serverAccountDao(), remoteRepo)
  }

  @After
  fun tearDown() {
    Shadows.shadowOf(Looper.getMainLooper()).idle()
    db.close()
  }

  @Test
  fun updateRemovesDisappearedRemoteEntriesWhilePreservingMyvpnImportedAndRedeemedEntries(): Unit = runBlocking {
    // 1. First remote sync populates 3 remote networks and 2 remote servers
    val initialJson =
      """
      {
        "networks": [
          {"name": "Atom Free", "payload_mode": "HTTP_PAYLOAD", "payload_template": "GET / HTTP/1.1[crlf][crlf]"},
          {"name": "MPT Old", "payload_mode": "DIRECT"},
          {"name": "Mytel Fast", "payload_mode": "WEBSOCKET", "payload_template": "GET /ws HTTP/1.1[crlf][crlf]"}
        ],
        "servers": [
          {"name": "Japan 01", "host": "jp1.example.com", "port": 22, "username": "u1", "password": "p1"},
          {"name": "Old Singapore", "host": "sg-old.example.com", "port": 22, "username": "u2", "password": "p2"}
        ]
      }
      """.trimIndent()
    val firstParsed = loader.parseSplitRemoteList(initialJson).getOrThrow()
    val firstSummary = splitRepo.mergeSplitRemoteList(firstParsed, replaceExisting = false)
    assertEquals(3, firstSummary.networksAdded)
    assertEquals(2, firstSummary.serversAdded)
    assertEquals(0, firstSummary.networksRemoved)
    assertEquals(0, firstSummary.serversRemoved)

    // 2. User imports a custom .myvpn bundle (1 network + 1 server) AND redeems a paid INJ server
    splitRepo.importSplitBundle(
      networks =
        listOf(
          NetworkConfigEntity(
            name = "My Imported .myvpn Net",
            payloadMode = PayloadMode.SSL_TLS_SNI.code,
            sniHost = "custom.sni.example",
          )
        ),
      servers =
        listOf(
          ImportedServerItem(
            server =
              ServerAccountEntity(
                name = "My Imported .myvpn Server",
                connectionMode = ConnectionMode.SSH_DIRECT.code,
                host = "imp.example.com",
                port = 22,
                username = "imp",
                password = "pw",
              )
          )
        ),
    )
    splitRepo.upsertRedeemedServer(
      code = "INJ-9999-1111",
      incoming =
        ServerAccountEntity(
          name = "VIP Redeemed SG",
          connectionMode = ConnectionMode.SSH_DIRECT.code,
          host = "vip.example.com",
          port = 22,
          username = "vip",
          password = "vip",
        ),
    )

    // 3. Second remote sync removes "MPT Old" and "Old Singapore" from the owner's JSON
    val updatedJson =
      """
      {
        "networks": [
          {"name": "Atom Free", "payload_mode": "HTTP_PAYLOAD", "payload_template": "GET /v2 HTTP/1.1[crlf][crlf]"},
          {"name": "Mytel Fast", "payload_mode": "WEBSOCKET", "payload_template": "GET /ws2 HTTP/1.1[crlf][crlf]"}
        ],
        "servers": [
          {"name": "Japan 01", "host": "jp1.example.com", "port": 22, "username": "u1", "password": "p1"}
        ]
      }
      """.trimIndent()
    val secondParsed = loader.parseSplitRemoteList(updatedJson).getOrThrow()
    val secondSummary = splitRepo.mergeSplitRemoteList(secondParsed, replaceExisting = false)

    assertEquals(0, secondSummary.networksAdded)
    assertEquals(2, secondSummary.networksUpdated)
    assertEquals(1, secondSummary.networksRemoved)
    assertEquals(0, secondSummary.serversAdded)
    assertEquals(1, secondSummary.serversUpdated)
    assertEquals(1, secondSummary.serversRemoved)

    val remainingNetNames = splitRepo.getAllNetworksSnapshot().map { it.name }.toSet()
    val remainingSrvNames = splitRepo.getAllServersSnapshot().map { it.name }.toSet()

    // Removed remote entries are GONE
    assertFalse("Removed remote network must be deleted", "MPT Old" in remainingNetNames)
    assertFalse("Removed remote server must be deleted", "Old Singapore" in remainingSrvNames)

    // Remaining remote entries + .myvpn imported + INJ redeemed are ALL preserved
    assertTrue("Atom Free" in remainingNetNames)
    assertTrue("Mytel Fast" in remainingNetNames)
    assertTrue("Imported .myvpn network must NEVER be deleted", "My Imported .myvpn Net" in remainingNetNames)

    assertTrue("Japan 01" in remainingSrvNames)
    assertTrue("Imported .myvpn server must NEVER be deleted", "My Imported .myvpn Server" in remainingSrvNames)
    assertTrue("INJ redeemed server must NEVER be deleted", "VIP Redeemed SG" in remainingSrvNames)
  }

  @Test
  fun splitRemoteJsonWithLegacyStyleServerFieldsNeverLeaksServersIntoNetworksAndPickersStaySeparate() {
    runBlocking {
    // Reproduces the exact bug where remote JSON has both "networks" and "servers", and servers
    // include legacy keys like "ssh_host" / "mode" / "payload" -> MUST NOT fabricate fake networks!
    val json =
      """
      {
        "networks": [
          {"name": "DataNoPro", "payload_mode": "HTTP_PAYLOAD", "payload_template": "GET / HTTP/1.1[crlf][crlf]"},
          {"name": "Atom 0Balance", "payload_mode": "WEBSOCKET", "payload_template": "GET /ws HTTP/1.1[crlf][crlf]"}
        ],
        "servers": [
          {"name": "amktvpn02", "mode": "SSH_WEBSOCKET", "ssh_host": "s2.example.com", "ssh_port": 80, "username": "u", "password": "p", "online": 45, "limit": 100},
          {"name": "amktvpn03", "mode": "SSH_DIRECT", "ssh_host": "s3.example.com", "ssh_port": 22, "username": "u", "password": "p"}
        ]
      }
      """.trimIndent()

    val parsed = loader.parseSplitRemoteList(json).getOrThrow()
    assertEquals("Must not classify split servers as legacy profiles", 0, parsed.legacyProfileDtos.size)
    assertEquals(2, parsed.networkDtos.size)
    assertEquals(2, parsed.serverDtos.size)

    splitRepo.mergeSplitRemoteList(parsed, replaceExisting = false)
    val networks = splitRepo.getAllNetworksSnapshot()
    val servers = splitRepo.getAllServersSnapshot()

    assertEquals("Network table must contain ONLY the 2 networks", 2, networks.size)
    assertEquals(setOf("DataNoPro", "Atom 0Balance"), networks.map { it.name }.toSet())
    assertEquals("Server table must contain ONLY the 2 servers", 2, servers.size)
    assertEquals(setOf("amktvpn02", "amktvpn03"), servers.map { it.name }.toSet())

    // Verify NetworkPickerBottomSheet shows ONLY networks (count = 2, zero server names, zero Payload/Proxy details)
    composeRule.setContent {
      MyApplicationTheme(darkTheme = true) {
        NetworkPickerBottomSheet(
          networks = networks,
          selectedNetworkId = networks.first().id,
          onSelect = {},
          onManageAll = {},
          onDismiss = {},
        )
      }
    }
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("network_sheet_count").assertTextEquals("2")
    composeRule.onNodeWithText("DataNoPro").assertIsDisplayed()
    composeRule.onNodeWithText("Atom 0Balance").assertIsDisplayed()
    composeRule.onAllNodesWithText("amktvpn02", substring = true, useUnmergedTree = true).assertCountEquals(0)
    composeRule.onAllNodesWithText("amktvpn03", substring = true, useUnmergedTree = true).assertCountEquals(0)
    composeRule.onAllNodesWithText("Payload", substring = true, useUnmergedTree = true).assertCountEquals(0)
    }
  }

  @Test
  fun serverPickerShowsOnlyServersWithExactCountAndZeroNetworks() {
    val servers =
      listOf(
        ServerAccountEntity(
          id = 1L,
          name = "Japan VIP",
          region = "JP",
          onlineCount = 102,
          userLimit = 150,
        ),
        ServerAccountEntity(
          id = 2L,
          name = "Singapore Free",
          region = "SG",
          onlineCount = null,
          userLimit = 100,
        ),
      )

    composeRule.setContent {
      MyApplicationTheme(darkTheme = true) {
        ServerPickerBottomSheet(
          servers = servers,
          selectedServerId = 1L,
          onSelect = {},
          onManageAll = {},
          onOpenRedeem = {},
          onDismiss = {},
        )
      }
    }
    composeRule.waitForIdle()

    composeRule.onNodeWithTag("server_sheet_count").assertTextEquals("2")
    composeRule.onNodeWithText("Japan VIP").assertIsDisplayed()
    composeRule.onNodeWithText("Singapore Free").assertIsDisplayed()
    composeRule.onNodeWithTag("server_sheet_online_limit_1", useUnmergedTree = true).assertTextEquals("Online: 102 | Limit: 150")
    composeRule.onNodeWithTag("server_sheet_online_limit_2", useUnmergedTree = true).assertTextEquals("Limit: 100")
    composeRule.onAllNodesWithText("DataNoPro", substring = true, useUnmergedTree = true).assertCountEquals(0)
  }

  @Test
  fun freeTimeStartsAt6hOneAdWatchAdds9hAndThirdGrantSameDayIsRefused(): Unit = runBlocking {
    val currentDay = AtomicInteger(20261125)
    val clockMs = AtomicLong(1_764_000_000_000L)
    val prefsFile = File(context.cacheDir, "m14_freetime_${UUID.randomUUID()}.preferences_pb")
    val membershipRepo =
      MembershipRepository(
        context = context,
        dataStore = PreferenceDataStoreFactory.create { prefsFile },
        todayYmdProvider = { currentDay.get() },
        nowMsProvider = { clockMs.get() },
      )
    val freeServer = ServerAccountEntity(id = 1L, name = "Japan Free", host = "jp.example.com")
    val noPass = PassStatus(isActive = false)

    // 1. Fresh state -> first connect shows external-browser gate and grants 6h free
    val freshState = membershipRepo.getFreeTimeStatus()
    assertFalse(freshState.initialized)
    assertEquals(AdsterraConfig.INITIAL_FREE_MS, freshState.remainingMs(clockMs.get()))
    assertEquals("06:00:00", freshState.formatRemainingHms(clockMs.get()))
    assertEquals(
      ConnectGateDecision.ShowGateAndGrantInitialFree,
      AdsterraConfig.evaluateConnectGateDecision(freeServer, noPass, freshState, clockMs.get(), currentDay.get()),
    )

    val afterInit = membershipRepo.ensureInitialFreeTimeIfNeeded()
    assertTrue(afterInit.initialized)
    assertEquals(clockMs.get() + 6 * 3_600_000L, afterInit.expiresAtMs)
    assertEquals(0, afterInit.grantsUsedToday)

    // 2. While within 6h -> still shows external-browser gate EVERY connect (unlimited connects, 0 grant consumed)
    clockMs.addAndGet(2 * 3_600_000L)
    assertEquals("04:00:00", afterInit.formatRemainingHms(clockMs.get()))
    assertEquals(
      ConnectGateDecision.ShowGateAndConnect,
      AdsterraConfig.evaluateConnectGateDecision(freeServer, noPass, afterInit, clockMs.get(), currentDay.get()),
    )
    assertEquals(0, membershipRepo.getTimeExtendGrantsUsedToday())

    // 3. After 6h expires -> ShowGateAndGrantExtension; 1st grant adds +9h (own counter = 1)
    clockMs.addAndGet(4 * 3_600_000L + 1_000L)
    assertEquals(
      ConnectGateDecision.ShowGateAndGrantExtension,
      AdsterraConfig.evaluateConnectGateDecision(freeServer, noPass, afterInit, clockMs.get(), currentDay.get()),
    )
    val grant1 = membershipRepo.recordAdWatchAndGrantFreeTime().getOrThrow()
    assertEquals(1, grant1.grantsUsedToday)
    assertEquals(1, membershipRepo.getTimeExtendGrantsUsedToday())
    assertEquals(clockMs.get() + 9 * 3_600_000L, grant1.expiresAtMs)
    assertEquals("09:00:00", grant1.formatRemainingHms(clockMs.get()))

    // 4. After 9h expires same day -> 2nd grant adds +9h (own counter = 2)
    clockMs.addAndGet(9 * 3_600_000L + 1_000L)
    val grant2 = membershipRepo.recordAdWatchAndGrantFreeTime().getOrThrow()
    assertEquals(2, grant2.grantsUsedToday)
    assertEquals(2, membershipRepo.getTimeExtendGrantsUsedToday())
    assertEquals(clockMs.get() + 9 * 3_600_000L, grant2.expiresAtMs)

    // 5. After 2nd 9h expires same day -> 3rd grant MUST be refused!
    clockMs.addAndGet(9 * 3_600_000L + 1_000L)
    assertEquals(
      ConnectGateDecision.RefuseFreeTimeExpiredNoGrantsLeft,
      AdsterraConfig.evaluateConnectGateDecision(freeServer, noPass, grant2, clockMs.get(), currentDay.get()),
    )
    val grant3 = membershipRepo.recordAdWatchAndGrantFreeTime()
    assertTrue("3rd grant same day must be refused", grant3.isFailure)
    assertEquals(2, membershipRepo.getTimeExtendGrantsUsedToday())

    // 6. Midnight rollover resets dedicated grant counter -> new day allows +9h again
    currentDay.set(20261126)
    val nextDayState = membershipRepo.getFreeTimeStatus()
    assertEquals(0, nextDayState.grantsUsedToday)
    assertEquals(
      ConnectGateDecision.ShowGateAndGrantExtension,
      AdsterraConfig.evaluateConnectGateDecision(freeServer, noPass, nextDayState, clockMs.get(), currentDay.get()),
    )
    val nextDayGrant = membershipRepo.recordAdWatchAndGrantFreeTime().getOrThrow()
    assertEquals(1, nextDayGrant.grantsUsedToday)
  }

  @Test
  fun onlineAndLimitDisplayShowsExactCountWhenPresentAndNeverInventsCountWhenAbsent() {
    val withOnline =
      ServerAccountEntity(
        id = 1L,
        name = "Japan Fast",
        host = "secret-jp.example.com",
        port = 22,
        username = "hidden_u",
        password = "hidden_p",
        region = "JP",
        onlineCount = 102,
        userLimit = 150,
      )
    val withoutOnline =
      ServerAccountEntity(
        id = 2L,
        name = "Singapore Basic",
        host = "secret-sg.example.com",
        port = 22,
        username = "hidden_u2",
        password = "hidden_p2",
        region = "SG",
        onlineCount = null,
        userLimit = 100,
      )

    assertEquals("Online: 102 | Limit: 150", withOnline.formatOnlineLimitLabel())
    assertEquals("Limit: 100", withoutOnline.formatOnlineLimitLabel())
    assertFalse(withoutOnline.formatOnlineLimitLabel().contains("Online:"))

    // Verify rendering on HomeScreen (with free time displayed cleanly on the server row & remaining row)
    val now = 1_764_000_000_000L
    val freeStatus = FreeTimeStatus(initialized = true, expiresAtMs = now + 5 * 3_600_000L + 30 * 60_000L, grantsUsedToday = 0)
    composeRule.setContent {
      MyApplicationTheme(darkTheme = true) {
        HomeScreen(
          networks = listOf(NetworkConfigEntity(id = 1L, name = "DataNoPro")),
          servers = listOf(withOnline, withoutOnline),
          selectedNetwork = NetworkConfigEntity(id = 1L, name = "DataNoPro"),
          selectedServer = withOnline,
          vpnState = VpnTunnelState.Disconnected,
          trafficStats = VpnTrafficStats(),
          freeTimeStatus = freeStatus,
          hasValidNoAdsPass = false,
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
    composeRule.onNodeWithTag("home_server_online_limit_line", useUnmergedTree = true).assertTextEquals("Online: 102 | Limit: 150")
    composeRule.onNodeWithTag("home_remaining_time_value", useUnmergedTree = true).assertTextEquals("05:30:00")
    // Secrets remain strictly hidden
    composeRule.onAllNodesWithText("secret-jp.example.com", substring = true, useUnmergedTree = true).assertCountEquals(0)
    composeRule.onAllNodesWithText("hidden_u", substring = true, useUnmergedTree = true).assertCountEquals(0)
  }

  @Test
  fun serversScreenShowsLimitOnlyWhenOnlineCountIsNullAndOnlinePlusLimitWhenAvailable() {
    val withOnline = ServerAccountEntity(id = 1L, name = "Node A", onlineCount = 42, userLimit = 100)
    val withoutOnline = ServerAccountEntity(id = 2L, name = "Node B", onlineCount = null, userLimit = 100)

    composeRule.setContent {
      MyApplicationTheme(darkTheme = true) {
        ServersScreen(
          servers = listOf(withOnline, withoutOnline),
          selectedServerId = 1L,
          onSelectServer = {},
          onSyncRemoteClick = {},
        )
      }
    }
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("server_card_online_limit_1", useUnmergedTree = true).assertTextEquals("Online: 42 | Limit: 100")
    composeRule.onNodeWithTag("server_card_online_limit_2", useUnmergedTree = true).assertTextEquals("Limit: 100")
  }

  @Test
  fun homeScreenExtendTimeButtonShowsRemainingGrantsDisablesAtLimitAndHidesForPaidOrNoAds() {
    val now = 1_764_000_000_000L
    val freeSrv = ServerAccountEntity(id = 1L, name = "Japan Free", host = "jp.example.com", region = "JP")
    val paidSrv = ServerAccountEntity(id = 2L, name = "VIP SG", host = "vip.example.com", tags = "VIP, REDEEM:INJ-1234-5678")
    var extendClicks = 0

    // Case A: Free user with 1/2 grants used -> button visible, enabled, shows "1 left today"
    composeRule.setContent {
      MyApplicationTheme(darkTheme = true) {
        HomeScreen(
          networks = listOf(NetworkConfigEntity(id = 1L, name = "DataNoPro")),
          servers = listOf(freeSrv, paidSrv),
          selectedNetwork = NetworkConfigEntity(id = 1L, name = "DataNoPro"),
          selectedServer = freeSrv,
          vpnState = VpnTunnelState.Disconnected,
          trafficStats = VpnTrafficStats(),
          freeTimeStatus = FreeTimeStatus(initialized = true, expiresAtMs = now + 3_600_000L, grantsUsedToday = 1),
          hasValidNoAdsPass = false,
          nowEpochMsProvider = { now },
          onExtendTimeClick = { extendClicks += 1 },
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
    composeRule.onNodeWithTag("home_extend_time_button").assertIsDisplayed().assertIsEnabled().performClick()
    assertEquals(1, extendClicks)
    composeRule.onNodeWithText("1 left today", useUnmergedTree = true).assertIsDisplayed()
    composeRule.onAllNodesWithTag("home_extend_limit_message", useUnmergedTree = true).assertCountEquals(0)
  }

  @Test
  fun homeScreenDisablesExtendTimeButtonWhenTwoGrantsUsedAndHidesForRedeemedServer() {
    val now = 1_764_000_000_000L
    val freeSrv = ServerAccountEntity(id = 1L, name = "Japan Free", host = "jp.example.com", region = "JP")
    composeRule.setContent {
      MyApplicationTheme(darkTheme = true) {
        HomeScreen(
          networks = listOf(NetworkConfigEntity(id = 1L, name = "DataNoPro")),
          servers = listOf(freeSrv),
          selectedNetwork = NetworkConfigEntity(id = 1L, name = "DataNoPro"),
          selectedServer = freeSrv,
          vpnState = VpnTunnelState.Disconnected,
          trafficStats = VpnTrafficStats(),
          freeTimeStatus = FreeTimeStatus(initialized = true, expiresAtMs = now + 3_600_000L, grantsUsedToday = 2),
          hasValidNoAdsPass = false,
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
    composeRule.onNodeWithTag("home_extend_time_button").assertIsDisplayed().assertIsNotEnabled()
    composeRule.onNodeWithText("0 left today", useUnmergedTree = true).assertIsDisplayed()
    composeRule.onNodeWithTag("home_extend_limit_message").assertIsDisplayed()
  }

  @Test
  fun homeScreenHidesExtendTimeButtonForRedeemedServerAndValidatedNoAdsUser() {
    val now = 1_764_000_000_000L
    val paidSrv = ServerAccountEntity(id = 2L, name = "VIP SG", host = "vip.example.com", tags = "VIP, REDEEM:INJ-1234-5678")
    composeRule.setContent {
      MyApplicationTheme(darkTheme = true) {
        HomeScreen(
          networks = listOf(NetworkConfigEntity(id = 1L, name = "DataNoPro")),
          servers = listOf(paidSrv),
          selectedNetwork = NetworkConfigEntity(id = 1L, name = "DataNoPro"),
          selectedServer = paidSrv,
          vpnState = VpnTunnelState.Disconnected,
          trafficStats = VpnTrafficStats(),
          freeTimeStatus = FreeTimeStatus(initialized = true, expiresAtMs = now + 3_600_000L, grantsUsedToday = 0),
          hasValidNoAdsPass = true,
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
    composeRule.onAllNodesWithTag("home_extend_time_button", useUnmergedTree = true).assertCountEquals(0)
  }

  @Test
  fun updateSummaryCountsMatchRemoteJsonExactlyAndNeverCountStaleLocalEntriesAsUpdated(): Unit = runBlocking {
    // Seed 14 stale remote networks and 6 remote servers
    val initialNetJson =
      (1..14).joinToString(",") { i ->
        """{"name": "Net $i", "payload_mode": "DIRECT"}"""
      }
    val initialSrvJson =
      (1..6).joinToString(",") { i ->
        """{"name": "Server $i", "host": "s$i.example.com", "port": 22, "username": "u", "password": "p"}"""
      }
    val initialPayload = """{"networks": [$initialNetJson], "servers": [$initialSrvJson]}"""
    val first = splitRepo.mergeSplitRemoteList(loader.parseSplitRemoteList(initialPayload).getOrThrow(), replaceExisting = false)
    assertEquals(14, first.networksAdded)
    assertEquals(6, first.serversAdded)

    // Remote JSON is trimmed to 11 networks (Net 1..Net 11) and 6 servers (Server 1..Server 6)
    val shrunkNetJson =
      (1..11).joinToString(",") { i ->
        """{"name": "Net $i", "payload_mode": "DIRECT"}"""
      }
    val shrunkPayload = """{"networks": [$shrunkNetJson], "servers": [$initialSrvJson]}"""
    val second = splitRepo.mergeSplitRemoteList(loader.parseSplitRemoteList(shrunkPayload).getOrThrow(), replaceExisting = false)

    assertEquals(0, second.networksAdded)
    assertEquals("Updated count must equal remote 11, NEVER stale 14", 11, second.networksUpdated)
    assertEquals("3 removed remote networks must be reported as removed", 3, second.networksRemoved)
    assertEquals(0, second.serversAdded)
    assertEquals(6, second.serversUpdated)
    assertEquals(0, second.serversRemoved)

    val msg = second.toBurmeseMessage()
    assertTrue("Summary must show updated 11: $msg", msg.contains("အပ်ဒိတ် 11"))
    assertTrue("Summary must show removed 3: $msg", msg.contains("ဖယ်ရှား 3"))
    assertTrue("Summary must show servers updated 6: $msg", msg.contains("အပ်ဒိတ် 6"))
    assertFalse("Summary must NOT report stale count 14: $msg", msg.contains("14"))

    assertEquals(11, splitRepo.getAllNetworksSnapshot().size)
    assertEquals(6, splitRepo.getAllServersSnapshot().size)
  }

  @Test
  fun migration5To6PreservesExistingDataSetsRemoteFlagsCorrectlyAndAddsOnlineLimitDefaults(): Unit = runBlocking {
    val dbName = "test_amkt_migration_5_6_${UUID.randomUUID()}.db"
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
        `isUserImported` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL
      )
      """.trimIndent()
    )
    rawDb.execSQL(
      "INSERT INTO network_configs (id, name, payloadMode, payloadTemplate, sniHost, extraHeaders, httpProxyHost, httpProxyPort, locked, createdAt, updatedAt) VALUES (1, 'Legacy Remote Net', 'DIRECT', '', '', '', '', 8080, 0, 1000, 1000)"
    )
    rawDb.execSQL(
      """
      INSERT INTO server_accounts (id, name, connectionMode, host, port, username, password, dnsttNameserver, dnsttPublicKey, dnsttResolver, vlessLink, vlessUuid, expiryDate, region, tags, notes, locked, defaultNetworkId, wasSelectedOnMigration, ovpnConfigEncrypted, ovpnAuthRequired, isUserImported, createdAt, updatedAt)
      VALUES
      (1, 'Remote SSH v5', 'SSH_DIRECT', 'r.example.com', 22, 'u', 'p', '', '', '8.8.8.8:53', '', '', '', 'JP', '', '', 0, 1, 0, '', 0, 0, 1000, 1000),
      (2, 'Imported OVPN v5', 'OPENVPN', '', 0, '', '', '', '', '8.8.8.8:53', '', '', '', '', '', '', 0, NULL, 0, 'enc', 0, 1, 1000, 1000),
      (3, 'Redeemed VIP v5', 'SSH_DIRECT', 'v.example.com', 22, 'u', 'p', '', '', '8.8.8.8:53', '', '', '2099-01-01', 'SG', 'VIP, REDEEM:INJ-1111-2222', '', 0, 1, 0, '', 0, 0, 1000, 1000)
      """.trimIndent()
    )
    rawDb.version = 5
    rawDb.close()

    val migrated =
      Room.databaseBuilder(context, AmktDatabase::class.java, dbName)
        .addMigrations(
          AmktDatabase.MIGRATION_1_2,
          AmktDatabase.MIGRATION_2_3,
          AmktDatabase.MIGRATION_3_4,
          AmktDatabase.MIGRATION_4_5,
          AmktDatabase.MIGRATION_5_6,
        )
        .allowMainThreadQueries()
        .build()

    try {
      val nets = migrated.networkConfigDao().getAllNetworksSnapshot()
      val srvs = migrated.serverAccountDao().getAllServersSnapshot().associateBy { it.name }

      assertEquals(1, nets.size)
      assertTrue(nets.first().isFromRemote)
      assertFalse(nets.first().isUserImported)

      val remoteSrv = srvs.getValue("Remote SSH v5")
      assertTrue(remoteSrv.isFromRemote)
      assertFalse(remoteSrv.isUserImported)
      assertNull(remoteSrv.onlineCount)
      assertEquals(100, remoteSrv.userLimit)

      val importedSrv = srvs.getValue("Imported OVPN v5")
      assertFalse("Imported server must have isFromRemote = false", importedSrv.isFromRemote)
      assertTrue(importedSrv.isUserImported)

      val redeemedSrv = srvs.getValue("Redeemed VIP v5")
      assertFalse("Redeemed server must have isFromRemote = false", redeemedSrv.isFromRemote)
      assertTrue(redeemedSrv.isRedeemedServer())
    } finally {
      migrated.close()
      context.deleteDatabase(dbName)
    }
  }
}
