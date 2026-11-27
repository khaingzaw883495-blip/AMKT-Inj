package com.example

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.config.ConfigFileCodec
import com.example.config.SplitConfigImportResult
import com.example.data.AmktDatabase
import com.example.data.AppLogStore
import com.example.data.ConnectionMode
import com.example.data.EffectiveConnectionCombiner
import com.example.data.LogLevel
import com.example.data.NetworkConfigEntity
import com.example.data.NetworkServerRepository
import com.example.data.PayloadMode
import com.example.data.RemoteServerListRepository
import com.example.data.ServerAccountEntity
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
class Milestone7SplitArchitectureTest {

  private lateinit var context: Context
  private lateinit var db: AmktDatabase
  private lateinit var remoteRepo: RemoteServerListRepository
  private lateinit var splitRepo: NetworkServerRepository
  private lateinit var legacyRepo: ServerProfileRepository
  private val codec = ConfigFileCodec()

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    db = Room.inMemoryDatabaseBuilder(context, AmktDatabase::class.java).allowMainThreadQueries().build()
    val testPrefsFile =
      java.io.File(context.cacheDir, "m7_test_${java.util.UUID.randomUUID()}.preferences_pb")
    val testStore =
      androidx.datastore.preferences.core.PreferenceDataStoreFactory.create { testPrefsFile }
    remoteRepo = RemoteServerListRepository(context, dataStore = testStore)
    splitRepo = NetworkServerRepository(db.networkConfigDao(), db.serverAccountDao(), remoteRepo)
    legacyRepo = ServerProfileRepository(db.serverProfileDao(), remoteRepo)
  }

  @After
  fun tearDown() {
    db.close()
  }

  @Test
  fun migration3To4SplitsProfilesDedupesIdenticalNetworksAndPreservesAllFields() = runBlocking {
    val dbName = "test_amkt_migration_3_4.db"
    context.deleteDatabase(dbName)

    val dbFile = context.getDatabasePath(dbName)
    dbFile.parentFile?.mkdirs()
    val rawDb = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
    rawDb.execSQL(
      """
      CREATE TABLE IF NOT EXISTS `server_profiles` (
        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
        `name` TEXT NOT NULL,
        `mode` TEXT NOT NULL,
        `sshHost` TEXT NOT NULL,
        `sshPort` INTEGER NOT NULL,
        `username` TEXT NOT NULL,
        `password` TEXT NOT NULL,
        `payload` TEXT NOT NULL,
        `proxyHost` TEXT NOT NULL,
        `proxyPort` INTEGER NOT NULL,
        `sni` TEXT NOT NULL,
        `payloadMode` TEXT NOT NULL DEFAULT 'DIRECT',
        `extraHeaders` TEXT NOT NULL DEFAULT '',
        `dnsttNameserver` TEXT NOT NULL,
        `dnsttPublicKey` TEXT NOT NULL,
        `dnsttResolver` TEXT NOT NULL DEFAULT '8.8.8.8:53',
        `vlessLink` TEXT NOT NULL,
        `expiryDate` TEXT NOT NULL,
        `locked` INTEGER NOT NULL,
        `notes` TEXT NOT NULL,
        `isSelected` INTEGER NOT NULL,
        `createdAt` INTEGER NOT NULL,
        `updatedAt` INTEGER NOT NULL
      )
      """.trimIndent()
    )

    // Profiles 1 & 2 share identical normalized WS network settings (only case/trailing spaces differ).
    // Profile 3 is DNSTT with Direct network; Profile 4 is VLESS WS with /vless-ws path.
    val sharedPayload = "GET / HTTP/1.1[crlf]Host: [host][crlf]Upgrade: websocket[crlf]Connection: Upgrade[crlf][crlf]"
    rawDb.execSQL(
      """
      INSERT INTO server_profiles
      (id, name, mode, sshHost, sshPort, username, password, payload, proxyHost, proxyPort, sni, payloadMode, extraHeaders, dnsttNameserver, dnsttPublicKey, dnsttResolver, vlessLink, expiryDate, locked, notes, isSelected, createdAt, updatedAt)
      VALUES
      (1, 'SSH Account A', 'SSH_WEBSOCKET', 'a.example.com', 80, 'userA', 'passA', '$sharedPayload', 'proxy.example.com', 8080, 'Bug.Example.Net', 'WEBSOCKET', 'X-Test: 1', '', '', '8.8.8.8:53', '', '2027-05-01', 0, 'note A', 0, 1000, 1000),
      (2, 'SSH Account B', 'SSH_WEBSOCKET', 'b.example.com', 80, 'userB', 'passB', '  $sharedPayload  ', 'PROXY.EXAMPLE.COM', 8080, 'bug.example.net', 'WEBSOCKET', 'X-Test: 1', '', '', '8.8.8.8:53', '', '2027-06-01', 1, 'note B', 1, 2000, 2000),
      (3, 'DNSTT Node', 'DNSTT', '', 22, 'dnsUser', 'dnsPass', '', '', 8080, '', 'DIRECT', '', 'ns.example.org', '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', '1.1.1.1:53', '', '', 0, 'slow dns', 0, 3000, 3000),
      (4, 'VLESS Node', 'VLESS_WS', 'edge.example.com', 80, '', '', '/vless-ws', '', 8080, '', 'WEBSOCKET', '', '', '', '8.8.8.8:53', 'vless://11111111-2222-3333-4444-555555555555@edge.example.com:80?encryption=none&security=none&type=ws&path=%2Fvless-ws#VLESS', '2099-12-31', 0, 'vless note', 0, 4000, 4000)
      """.trimIndent()
    )
    rawDb.version = 3
    rawDb.close()

    val migratedDb =
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
      val networks = migratedDb.networkConfigDao().getAllNetworksSnapshot()
      val servers = migratedDb.serverAccountDao().getAllServersSnapshot()

      // 4 profiles -> 3 deduplicated Networks (Account A & Account B MUST share 1 Network) + 4 Servers
      assertEquals("Identical normalized networks must dedupe into 3 total networks", 3, networks.size)
      assertEquals("All 4 servers must be preserved", 4, servers.size)

      val srvA = servers.first { it.name == "SSH Account A" }
      val srvB = servers.first { it.name == "SSH Account B" }
      val srvDnstt = servers.first { it.name == "DNSTT Node" }
      val srvVless = servers.first { it.name == "VLESS Node" }

      assertNotNull(srvA.defaultNetworkId)
      assertEquals("Account A & B must point to the SAME deduplicated Network", srvA.defaultNetworkId, srvB.defaultNetworkId)
      assertEquals("a.example.com", srvA.host)
      assertEquals("userA", srvA.username)
      assertEquals("passA", srvA.password)
      assertEquals("2027-05-01", srvA.expiryDate)
      assertEquals("note A", srvA.notes)

      assertEquals("b.example.com", srvB.host)
      assertEquals("userB", srvB.username)
      assertEquals("passB", srvB.password)
      assertTrue("Selected server flag must survive migration", srvB.wasSelectedOnMigration)
      assertTrue("Locked flag must survive migration", srvB.locked)

      assertEquals(ConnectionMode.DNSTT, srvDnstt.resolvedConnectionMode)
      assertEquals("ns.example.org", srvDnstt.dnsttNameserver)
      assertEquals("1.1.1.1:53", srvDnstt.dnsttResolver)
      assertEquals("dnsUser", srvDnstt.username)
      assertEquals("dnsPass", srvDnstt.password)

      assertEquals(ConnectionMode.VLESS_WS, srvVless.resolvedConnectionMode)
      assertEquals("11111111-2222-3333-4444-555555555555", srvVless.vlessUuid)
      assertEquals("2099-12-31", srvVless.expiryDate)
      assertTrue(srvVless.vlessLink.startsWith("vless://"))

      val sharedNet = networks.first { it.id == srvA.defaultNetworkId }
      assertEquals(PayloadMode.WEBSOCKET, sharedNet.resolvedPayloadMode)
      assertEquals("proxy.example.com", sharedNet.httpProxyHost)
      assertEquals(8080, sharedNet.httpProxyPort)
      assertEquals("X-Test: 1", sharedNet.extraHeaders)
    } finally {
      migratedDb.close()
      context.deleteDatabase(dbName)
    }
  }

  @Test
  fun freshInstallHasZeroSeededProfilesAndUpgradePreservesExistingData() = runBlocking {
    val (seededNets, seededSrvs) = splitRepo.initializeOnLaunch()
    assertEquals("Fresh install must seed ZERO networks", 0, seededNets)
    assertEquals("Fresh install must seed ZERO servers", 0, seededSrvs)
    assertTrue("Fresh install networks table must be empty", splitRepo.getAllNetworksSnapshot().isEmpty())
    assertTrue("Fresh install servers table must be empty", splitRepo.getAllServersSnapshot().isEmpty())

    // Simulate existing user data on upgrade: initializeOnLaunch must preserve it untouched
    val existingNetId =
      splitRepo.saveNetwork(
        NetworkConfigEntity(name = "Existing User Net", payloadMode = PayloadMode.DIRECT.code),
        selectAfterSave = false,
      )
    val existingSrvId =
      splitRepo.saveServer(
        ServerAccountEntity(
          name = "Existing User Server",
          connectionMode = ConnectionMode.SSH_DIRECT.code,
          host = "existing.example.com",
          port = 22,
          defaultNetworkId = existingNetId,
        ),
        selectAfterSave = false,
      )

    val (afterUpgradeNets, afterUpgradeSrvs) = splitRepo.initializeOnLaunch()
    assertEquals(0, afterUpgradeNets)
    assertEquals(0, afterUpgradeSrvs)
    assertEquals(1, splitRepo.getAllNetworksSnapshot().size)
    assertEquals(1, splitRepo.getAllServersSnapshot().size)

    val (lastNetId, lastSrvId) = remoteRepo.getLastSelection()
    assertEquals("Upgrade must restore valid Network selection", existingNetId, lastNetId)
    assertEquals("Upgrade must restore valid Server selection", existingSrvId, lastSrvId)
  }

  @Test
  fun effectiveConnectionCombinerProducesExactCombinedProfileAndReflectsNetworkEdits() {
    val network =
      NetworkConfigEntity(
        id = 7L,
        name = "Carrier WS Network",
        payloadMode = PayloadMode.WEBSOCKET.code,
        payloadTemplate = "GET / HTTP/1.1[crlf]Host: [sni][crlf][split]GET /ws HTTP/1.1[crlf]Host: [host][crlf]Upgrade: websocket[crlf]Connection: Upgrade[crlf][crlf]",
        sniHost = "bug-front.example.net",
        extraHeaders = "X-Custom: ok",
        httpProxyHost = "198.51.100.10",
        httpProxyPort = 8080,
      )
    val server1 =
      ServerAccountEntity(
        id = 11L,
        name = "Account One",
        connectionMode = ConnectionMode.SSH_DIRECT.code,
        host = "ssh1.example.com",
        port = 80,
        username = "alice",
        password = "pw1",
        expiryDate = "2099-01-01",
        defaultNetworkId = 7L,
      )
    val server2 =
      ServerAccountEntity(
        id = 12L,
        name = "Account Two",
        connectionMode = ConnectionMode.SSH_DIRECT.code,
        host = "ssh2.example.com",
        port = 80,
        username = "bob",
        password = "pw2",
        defaultNetworkId = 7L,
      )

    val combined1 = EffectiveConnectionCombiner.combine(network, server1)
    assertEquals(ConnectionMode.SSH_WEBSOCKET, combined1.connectionMode)
    assertEquals(PayloadMode.WEBSOCKET, combined1.resolvedPayloadMode)
    assertEquals("ssh1.example.com", combined1.sshHost)
    assertEquals(80, combined1.sshPort)
    assertEquals("alice", combined1.username)
    assertEquals("pw1", combined1.password)
    assertEquals("bug-front.example.net", combined1.sniHost)
    assertEquals("198.51.100.10", combined1.proxyHost)
    assertEquals(8080, combined1.proxyPort)
    assertEquals("X-Custom: ok", combined1.extraHeaders)
    assertEquals("2099-01-01", combined1.expiryDate)

    // Updating the shared Network immediately changes the combined config for ALL servers using it
    val updatedNetwork = network.copy(sniHost = "updated-bug.example.net", payloadMode = PayloadMode.SSL_TLS_SNI.code)
    val combined2 = EffectiveConnectionCombiner.combine(updatedNetwork, server2)
    assertEquals(ConnectionMode.SSH_SSL, combined2.connectionMode)
    assertEquals(PayloadMode.SSL_TLS_SNI, combined2.resolvedPayloadMode)
    assertEquals("updated-bug.example.net", combined2.sniHost)
    assertEquals("ssh2.example.com", combined2.sshHost)
    assertEquals("bob", combined2.username)
  }

  @Test
  fun splitMyVpnImportExportRoundTripAndLegacyBackwardCompatibility() = runBlocking {
    val net =
      NetworkConfigEntity(
        name = "Carrier Split WS",
        payloadMode = PayloadMode.WEBSOCKET.code,
        payloadTemplate = "GET / HTTP/1.1[crlf]Host: [host][crlf]Upgrade: websocket[crlf]Connection: Upgrade[crlf][crlf]",
        sniHost = "sni.example.org",
        extraHeaders = "User-Agent: TestClient",
        httpProxyHost = "198.51.100.20",
        httpProxyPort = 8080,
      )
    val srv =
      ServerAccountEntity(
        name = "JP Node 01",
        connectionMode = ConnectionMode.SSH_DIRECT.code,
        host = "jp1.example.org",
        port = 80,
        username = "tester1",
        password = "secret-password-1",
        expiryDate = "2099-12-31",
        region = "JP",
        tags = "VIP",
        notes = "Combined export test",
      )

    // 1. Combined export -> decode -> import
    val combinedBytes =
      codec.encodeSplitToMyVpnBytes(
        network = net,
        server = srv,
        lockOverride = true,
        expiryOverride = "2099-12-31",
        notesOverride = "Combined export test",
      )
    val decodedCombined = codec.decodeSplitImportedBytes(combinedBytes, "combined.myvpn")
    assertTrue(decodedCombined is SplitConfigImportResult.Success)
    val okCombined = decodedCombined as SplitConfigImportResult.Success
    assertEquals(1, okCombined.networks.size)
    assertEquals(1, okCombined.servers.size)

    val outcomeCombined = splitRepo.importSplitBundle(okCombined.networks, okCombined.servers)
    assertEquals(1, outcomeCombined.networksAdded)
    assertEquals(1, outcomeCombined.serversAdded)
    val storedSrv = splitRepo.getAllServersSnapshot().first { it.name == "JP Node 01" }
    assertNotNull("Combined import links server to imported network", storedSrv.defaultNetworkId)
    assertEquals("JP", storedSrv.region)
    assertEquals("VIP", storedSrv.tags)
    assertTrue(storedSrv.locked)

    // 2. Network-only export -> import
    val netOnly = net.copy(name = "SSL Only Net", payloadMode = PayloadMode.SSL_TLS_SNI.code, sniHost = "ssl.example.org")
    val netOnlyBytes = codec.encodeSplitToMyVpnBytes(network = netOnly, server = null)
    val decodedNetOnly = codec.decodeSplitImportedBytes(netOnlyBytes, "net_only.myvpn") as SplitConfigImportResult.Success
    assertEquals(1, decodedNetOnly.networks.size)
    assertEquals(0, decodedNetOnly.servers.size)
    val outcomeNetOnly = splitRepo.importSplitBundle(decodedNetOnly.networks, decodedNetOnly.servers)
    assertEquals(1, outcomeNetOnly.networksAdded)
    assertEquals(0, outcomeNetOnly.serversAdded)

    // 3. Server-only export -> import (saves cleanly even with no matching network)
    val srvOnly = srv.copy(name = "Standalone Server", host = "solo.example.org", username = "solo")
    val srvOnlyBytes = codec.encodeSplitToMyVpnBytes(network = null, server = srvOnly)
    val decodedSrvOnly = codec.decodeSplitImportedBytes(srvOnlyBytes, "srv_only.myvpn") as SplitConfigImportResult.Success
    assertEquals(0, decodedSrvOnly.networks.size)
    assertEquals(1, decodedSrvOnly.servers.size)
    val outcomeSrvOnly =
      splitRepo.importSplitBundle(
        networks = decodedSrvOnly.networks,
        servers = decodedSrvOnly.servers,
        fallbackSelectedNetworkId = null,
      )
    assertEquals(0, outcomeSrvOnly.networksAdded)
    assertEquals(1, outcomeSrvOnly.serversAdded)
    assertTrue(splitRepo.getAllServersSnapshot().any { it.name == "Standalone Server" })

    // 4. Legacy single-profile .myvpn -> auto-split + dedupe identical Network!
    val legacyProfile =
      ServerProfileEntity(
        name = "Legacy Shared WS",
        mode = ConnectionMode.SSH_WEBSOCKET.code,
        sshHost = "legacy.example.org",
        sshPort = 80,
        username = "legacyUser",
        password = "legacyPassword",
        payload = net.payloadTemplate,
        payloadMode = PayloadMode.WEBSOCKET.code,
        extraHeaders = net.extraHeaders,
        proxyHost = net.httpProxyHost,
        proxyPort = net.httpProxyPort,
        sni = net.sniHost,
      )
    val legacyBytes = codec.encodeToMyVpnBytes(legacyProfile)
    val decodedLegacy = codec.decodeSplitImportedBytes(legacyBytes, "old_v1.myvpn") as SplitConfigImportResult.Success
    val outcomeLegacy = splitRepo.importSplitBundle(decodedLegacy.networks, decodedLegacy.servers)
    assertEquals("Identical network from legacy .myvpn must reuse existing network", 0, outcomeLegacy.networksAdded)
    assertEquals(1, outcomeLegacy.networksReused)
    assertEquals(1, outcomeLegacy.serversAdded)
    val legacyImportedSrv = splitRepo.getAllServersSnapshot().first { it.name == "Legacy Shared WS" }
    assertEquals(storedSrv.defaultNetworkId, legacyImportedSrv.defaultNetworkId)
  }

  @Test
  fun remoteJsonMergesNetworksAndServersSeparatelyPreservingLocalEdits() = runBlocking {
    // Seed local Network + Server with local edits
    val localNetId =
      splitRepo.saveNetwork(
        NetworkConfigEntity(
          name = "True WS",
          payloadMode = PayloadMode.WEBSOCKET.code,
          payloadTemplate = "GET /local HTTP/1.1[crlf]Host: [host][crlf][crlf]",
          sniHost = "local-sni.example.net",
          extraHeaders = "X-Local: keep",
        )
      )
    splitRepo.saveServer(
      ServerAccountEntity(
        name = "Tokyo 01",
        connectionMode = ConnectionMode.SSH_DIRECT.code,
        host = "old-tokyo.example.com",
        port = 22,
        username = "myLocalUser",
        password = "myLocalPassword",
        notes = "keep my note",
        defaultNetworkId = localNetId,
      )
    )

    val remoteJson =
      """
      {
        "networks": [
          {
            "name": "  true ws ",
            "payload_mode": "WEBSOCKET",
            "sni_host": "updated-sni.example.net"
          },
          {
            "name": "Direct Net",
            "payload_mode": "DIRECT"
          }
        ],
        "servers": [
          {
            "name": "tokyo 01",
            "connection_mode": "SSH_DIRECT",
            "host": "new-tokyo.example.com",
            "port": 80,
            "network_name": "True WS"
          },
          {
            "name": "SG VLESS",
            "connection_mode": "VLESS_WS",
            "host": "sg.example.com",
            "port": 80,
            "vless_link": "vless://11111111-2222-3333-4444-555555555555@sg.example.com:80?encryption=none&security=none&type=ws&path=%2Fvless-ws#SG",
            "expiry_date": "2099-12-31",
            "region": "SG",
            "network_name": "Direct Net"
          }
        ]
      }
      """.trimIndent()

    val server = OneShotHttpServer(statusCode = 200, body = remoteJson)
    val port = server.start()
    try {
      val result =
        splitRepo.fetchAndMergeSplitFromRemoteUrl(
          url = "http://127.0.0.1:$port/split.json",
          replaceExisting = false,
        )
      assertTrue("Split remote sync must succeed: ${result.exceptionOrNull()?.message}", result.isSuccess)
      val summary = result.getOrThrow()
      assertEquals(1, summary.networksAdded)
      assertEquals(1, summary.networksUpdated)
      assertEquals(1, summary.serversAdded)
      assertEquals(1, summary.serversUpdated)

      val allNets = splitRepo.getAllNetworksSnapshot()
      val allSrvs = splitRepo.getAllServersSnapshot()
      assertEquals("Must NOT duplicate True WS network", 2, allNets.size)
      assertEquals("Must NOT duplicate Tokyo 01 server", 2, allSrvs.size)

      val mergedNet = allNets.first { it.id == localNetId }
      assertEquals("updated-sni.example.net", mergedNet.sniHost)
      assertEquals("Local payload preserved when remote omits it", "GET /local HTTP/1.1[crlf]Host: [host][crlf][crlf]", mergedNet.payloadTemplate)
      assertEquals("X-Local: keep", mergedNet.extraHeaders)

      val mergedSrv = allSrvs.first { it.name.equals("Tokyo 01", ignoreCase = true) }
      assertEquals("new-tokyo.example.com", mergedSrv.host)
      assertEquals(80, mergedSrv.port)
      assertEquals("Local username preserved", "myLocalUser", mergedSrv.username)
      assertEquals("Local password preserved", "myLocalPassword", mergedSrv.password)
      assertEquals("Local notes preserved", "keep my note", mergedSrv.notes)

      val sgSrv = allSrvs.first { it.name == "SG VLESS" }
      assertEquals("SG", sgSrv.region)
      assertEquals("2099-12-31", sgSrv.expiryDate)
      val directNet = allNets.first { it.name == "Direct Net" }
      assertEquals(directNet.id, sgSrv.defaultNetworkId)
    } finally {
      server.stop()
    }
  }

  @Test
  fun viewModelValidatesMissingSelectionExpiryOnServerAndGuardsReferencedNetworkDeletion() = runBlocking {
    remoteRepo.markInitialTemplatesSeeded()
    val logStore = AppLogStore()
    val fakeVpn = FakeVpnEngineController()
    val viewModel =
      AmktViewModel(
        repository = legacyRepo,
        membershipRepository = MembershipRepository(context),
        vpnPreferencesRepository = VpnPreferencesRepository(context),
        vpnController = fakeVpn,
        configCodec = codec,
        logStore = logStore,
        networkServerRepository = splitRepo,
      )

    val netCollector = launch(Dispatchers.Unconfined) { viewModel.selectedNetwork.collect {} }
    val srvCollector = launch(Dispatchers.Unconfined) { viewModel.selectedServer.collect {} }
    try {
      // Allow AmktViewModel.init coroutine to complete cleanly before mutating selection
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      delay(50L)
      Shadows.shadowOf(Looper.getMainLooper()).idle()

      splitRepo.selectCombination(null, null)
      awaitSelection(viewModel, expectedNet = null, expectedSrv = null)

      // 1. Missing Network -> refused cleanly with Burmese message
      assertNull(viewModel.validateSelectionForVpnConnect())
      assertTrue(viewModel.snackbarMessage.value.orEmpty().contains("ကွန်ရက်"))

      val netId =
        splitRepo.saveNetwork(
          NetworkConfigEntity(name = "Test Direct Net", payloadMode = PayloadMode.DIRECT.code),
          selectAfterSave = true,
        )
      awaitSelection(viewModel, expectedNet = "Test Direct Net", expectedSrv = null)

      // 2. Missing Server -> refused cleanly with Burmese message
      assertNull(viewModel.validateSelectionForVpnConnect())
      assertTrue(viewModel.snackbarMessage.value.orEmpty().contains("ဆာဗာ"))

      // 3. Expired Server -> refused BEFORE VPN permission with EXPIRY log
      splitRepo.saveServer(
        ServerAccountEntity(
          name = "Expired Account",
          connectionMode = ConnectionMode.SSH_DIRECT.code,
          host = "expired.example.com",
          port = 22,
          username = "demo",
          password = "pw",
          expiryDate = "2020-01-01",
          defaultNetworkId = netId,
        ),
        selectAfterSave = true,
      )
      awaitSelection(viewModel, expectedNet = "Test Direct Net", expectedSrv = "Expired Account")

      assertNull("Expired Server must be refused before VPN start", viewModel.validateSelectionForVpnConnect())
      assertEquals(0, fakeVpn.startCalls)
      assertTrue(
        "Expected expiry snackbar, got: ${viewModel.snackbarMessage.value}",
        viewModel.snackbarMessage.value.orEmpty().contains("သက်တမ်းကုန်ဆုံးပါပြီ"),
      )
      assertTrue(
        logStore.logs.value.any {
          it.tag == "EXPIRY" && it.level == LogLevel.ERROR && it.message.contains("သက်တမ်းကုန်ဆုံးပါပြီ")
        }
      )

      // 4. Deleting a Network referenced by a Server fails without confirmation, succeeds with unlink
      val refused = splitRepo.deleteNetwork(netId, unlinkReferencingServers = false)
      assertFalse("Must refuse silent deletion when Servers reference the Network", refused)
      assertNotNull(splitRepo.getNetworkById(netId))

      val unlinkedAndDeleted = splitRepo.deleteNetwork(netId, unlinkReferencingServers = true)
      assertTrue("Confirmed deletion must unlink Servers and delete Network", unlinkedAndDeleted)
      assertNull(splitRepo.getNetworkById(netId))
      val unlinkedSrv = splitRepo.getAllServersSnapshot().first { it.name == "Expired Account" }
      assertNull("Referencing server must have defaultNetworkId cleared cleanly", unlinkedSrv.defaultNetworkId)

      // 5. Valid Network + valid Future-expiry Server -> succeeds and produces combined profile
      val goodNetId =
        splitRepo.saveNetwork(
          NetworkConfigEntity(name = "Good Direct Net", payloadMode = PayloadMode.DIRECT.code),
          selectAfterSave = true,
        )
      splitRepo.saveServer(
        ServerAccountEntity(
          name = "Active Account",
          connectionMode = ConnectionMode.SSH_DIRECT.code,
          host = "active.example.com",
          port = 22,
          username = "activeUser",
          password = "activePassword",
          expiryDate = "2099-12-31",
          defaultNetworkId = goodNetId,
        ),
        selectAfterSave = true,
      )
      awaitSelection(viewModel, expectedNet = "Good Direct Net", expectedSrv = "Active Account")

      val validated = viewModel.validateSelectionForVpnConnect()
      assertNotNull("Valid Network + Active Server must pass connect validation", validated)
      val (vNet, vSrv, combined) = validated!!
      assertNotNull(vNet)
      assertEquals("Good Direct Net", vNet!!.name)
      assertEquals("Active Account", vSrv.name)
      assertEquals("active.example.com", combined.sshHost)
      assertEquals("activeUser", combined.username)

      viewModel.startCombinedVpnAfterConsent(vNet, vSrv, combined)
      assertEquals(1, fakeVpn.startCalls)
    } finally {
      netCollector.cancel()
      srvCollector.cancel()
    }
  }

  private suspend fun awaitSelection(
    viewModel: AmktViewModel,
    expectedNet: String?,
    expectedSrv: String?,
  ) {
    val deadlineNanos = System.nanoTime() + 5_000_000_000L
    while (
      (viewModel.selectedNetwork.value?.name != expectedNet ||
        viewModel.selectedServer.value?.name != expectedSrv) &&
        System.nanoTime() < deadlineNanos
    ) {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      delay(20L)
    }
    Shadows.shadowOf(Looper.getMainLooper()).idle()
    val debugState =
      "nets=${viewModel.networks.value.map { it.id to it.name }}, " +
        "srvs=${viewModel.servers.value.map { it.id to it.name }}, " +
        "saved=${remoteRepo.getLastSelection()}"
    assertEquals("Network mismatch ($debugState)", expectedNet, viewModel.selectedNetwork.value?.name)
    assertEquals("Server mismatch ($debugState)", expectedSrv, viewModel.selectedServer.value?.name)
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
