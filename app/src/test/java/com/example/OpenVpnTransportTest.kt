package com.example

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import android.util.Base64
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
import com.example.data.ServerProfileEntity
import com.example.data.ServerProfileRepository
import com.example.membership.MembershipRepository
import com.example.openvpn.OvpnConfigParser
import com.example.openvpn.OvpnSecretStore
import com.example.ui.AmktViewModel
import com.example.ui.home.HomeScreen
import com.example.ui.home.ServerPickerBottomSheet
import com.example.ui.profiles.ServersScreen
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
@Config(sdk = [36])
class OpenVpnTransportTest {

  @get:Rule val composeRule = createComposeRule()

  private lateinit var context: Context
  private lateinit var db: AmktDatabase
  private lateinit var remoteRepo: RemoteServerListRepository
  private lateinit var splitRepo: NetworkServerRepository
  private lateinit var legacyRepo: ServerProfileRepository

  private val sampleCertOnlyOvpn =
    """
    client
    dev tun
    proto udp
    remote ovpn-secret-jp.example.net 1194
    resolv-retry infinite
    nobind
    cipher AES-256-GCM
    auth SHA256
    <ca>
    -----BEGIN CERTIFICATE-----
    MIIFakeCertBodySecretCA123456
    -----END CERTIFICATE-----
    </ca>
    <cert>
    -----BEGIN CERTIFICATE-----
    MIIFakeClientCertSecret7890
    -----END CERTIFICATE-----
    </cert>
    <key>
    -----BEGIN PRIVATE KEY-----
    MIIFakePrivateKeySecretXYZ
    -----END PRIVATE KEY-----
    </key>
    """.trimIndent()

  private val sampleAuthRequiredOvpn =
    """
    client
    dev tun
    proto tcp
    remote ovpn-auth-sg.example.org 443
    cipher AES-128-GCM
    auth SHA512
    auth-user-pass
    <ca>
    -----BEGIN CERTIFICATE-----
    MIIFakeAuthCASecret999
    -----END CERTIFICATE-----
    </ca>
    """.trimIndent()

  private val sampleInlineAuthOvpn =
    """
    client
    dev tun
    proto tcp-client
    remote inline-auth.example.com 443
    <auth-user-pass>
    inlineUser
    inlinePass
    </auth-user-pass>
    <ca>
    -----BEGIN CERTIFICATE-----
    MIIFakeInlineCA
    -----END CERTIFICATE-----
    </ca>
    """.trimIndent()

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    db = Room.inMemoryDatabaseBuilder(context, AmktDatabase::class.java).allowMainThreadQueries().build()
    val prefsFile = File(context.cacheDir, "ovpn_test_${UUID.randomUUID()}.preferences_pb")
    val store = PreferenceDataStoreFactory.create { prefsFile }
    remoteRepo = RemoteServerListRepository(context, dataStore = store)
    splitRepo = NetworkServerRepository(db.networkConfigDao(), db.serverAccountDao(), remoteRepo)
    legacyRepo = ServerProfileRepository(db.serverProfileDao(), remoteRepo)
  }

  @After
  fun tearDown() {
    db.close()
  }

  @Test
  fun ovpnParserParsesUdpTcpInlineCertsCipherAndDetectsAuthUserPass() {
    val certOnly = OvpnConfigParser.parse(sampleCertOnlyOvpn).getOrThrow()
    assertEquals("ovpn-secret-jp.example.net", certOnly.primaryRemoteHost)
    assertEquals(1194, certOnly.primaryRemotePort)
    assertEquals("udp", certOnly.protocol)
    assertEquals("AES-256-GCM/SHA256", certOnly.cipherSummary)
    assertTrue(certOnly.hasInlineCertificates)
    assertFalse("Cert-only config must not prompt for auth-user-pass", certOnly.requiresAuthUserPass)
    assertEquals("Japan VIP", OvpnConfigParser.resolveProfileName("Japan VIP.ovpn", certOnly))
    assertEquals("ovpn-secret-jp.example.net", OvpnConfigParser.resolveProfileName(null, certOnly))

    val authReq = OvpnConfigParser.parse(sampleAuthRequiredOvpn).getOrThrow()
    assertEquals("ovpn-auth-sg.example.org", authReq.primaryRemoteHost)
    assertEquals(443, authReq.primaryRemotePort)
    assertEquals("tcp", authReq.protocol)
    assertEquals("AES-128-GCM/SHA512", authReq.cipherSummary)
    assertTrue("auth-user-pass without inline block must prompt", authReq.requiresAuthUserPass)

    val inlineAuth = OvpnConfigParser.parse(sampleInlineAuthOvpn).getOrThrow()
    assertFalse("Inline <auth-user-pass> must not prompt again", inlineAuth.requiresAuthUserPass)

    assertTrue("Blank config must fail cleanly", OvpnConfigParser.parse("   ").isFailure)
    assertTrue(
      "Config without remote directive must fail cleanly",
      OvpnConfigParser.parse("client\ndev tun\nproto udp\n").isFailure,
    )
    assertTrue(
      "Invalid base64 must fail cleanly",
      OvpnConfigParser.decodeBase64Ovpn("%%%not-valid-base64%%%").isFailure,
    )
  }

  @Test
  fun ovpnSecretStoreEncryptsAndDecryptsAtRestWithoutPlaintextLeak() {
    val encConfig = OvpnSecretStore.encrypt(sampleCertOnlyOvpn)
    assertTrue("Encrypted value must carry AMKTENC1 prefix", OvpnSecretStore.isEncrypted(encConfig))
    assertFalse("Ciphertext must NOT contain remote host", encConfig.contains("ovpn-secret-jp.example.net"))
    assertFalse("Ciphertext must NOT contain private key", encConfig.contains("MIIFakePrivateKeySecretXYZ"))
    assertEquals(sampleCertOnlyOvpn, OvpnSecretStore.decrypt(encConfig))

    val encPass = OvpnSecretStore.encrypt("super_secret_ovpn_pw")
    assertTrue(OvpnSecretStore.isEncrypted(encPass))
    assertFalse(encPass.contains("super_secret_ovpn_pw"))
    assertEquals("super_secret_ovpn_pw", OvpnSecretStore.decrypt(encPass))
  }

  @Test
  fun importOvpnCertOnlyAndAuthUserPassFlowsEncryptAtRestAndNeverLogSecrets() = runBlocking {
    val certSrv =
      splitRepo.importOvpnProfile(
        rawOvpnText = sampleCertOnlyOvpn,
        fileName = "Tokyo-OpenVPN.ovpn",
        selectAfterImport = true,
      ).getOrThrow()

    assertEquals(ConnectionMode.OPENVPN, certSrv.resolvedConnectionMode)
    assertFalse(certSrv.ovpnAuthRequired)
    assertEquals("Host column must remain blank for OpenVPN", "", certSrv.host)
    assertEquals("Port column must remain 0 for OpenVPN", 0, certSrv.port)
    assertTrue(OvpnSecretStore.isEncrypted(certSrv.ovpnConfigEncrypted))
    assertFalse(certSrv.ovpnConfigEncrypted.contains("ovpn-secret-jp.example.net"))
    assertEquals(sampleCertOnlyOvpn, OvpnSecretStore.decrypt(certSrv.ovpnConfigEncrypted))

    val authSrv =
      splitRepo.importOvpnProfile(
        rawOvpnText = sampleAuthRequiredOvpn,
        fileName = "Singapore-Auth.ovpn",
        username = "ovpn_user_77",
        password = "ovpn_secret_pass_77",
        selectAfterImport = true,
      ).getOrThrow()

    assertEquals(ConnectionMode.OPENVPN, authSrv.resolvedConnectionMode)
    assertTrue(authSrv.ovpnAuthRequired)
    assertTrue(OvpnSecretStore.isEncrypted(authSrv.username))
    assertTrue(OvpnSecretStore.isEncrypted(authSrv.password))
    assertFalse("Username must not be stored in plaintext", authSrv.username.contains("ovpn_user_77"))
    assertFalse("Password must not be stored in plaintext", authSrv.password.contains("ovpn_secret_pass_77"))
    assertEquals("ovpn_user_77", OvpnSecretStore.decrypt(authSrv.username))
    assertEquals("ovpn_secret_pass_77", OvpnSecretStore.decrypt(authSrv.password))
  }

  @Test
  fun remoteJsonSupportsOpenVpnBase64UpsertsByNameAndPreservesSshVless() = runBlocking {
    val ovpnB64V1 =
      Base64.encodeToString(sampleCertOnlyOvpn.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
    val updatedOvpnText = sampleCertOnlyOvpn.replace("1194", "1195")
    val ovpnB64V2 =
      Base64.encodeToString(updatedOvpnText.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)

    val remoteJsonV1 =
      """
      {
        "format_version": 2,
        "networks": [
          {
            "name": "Carrier Direct",
            "payload_mode": "DIRECT"
          }
        ],
        "servers": [
          {
            "name": "Japan SSH",
            "mode": "SSH_DIRECT",
            "host": "ssh-jp.example.com",
            "port": 22,
            "username": "u1",
            "password": "p1",
            "expiry_date": "2099-01-01"
          },
          {
            "name": "SG VLESS",
            "mode": "VLESS_WS",
            "host": "vless-sg.example.com",
            "port": 80,
            "vless_link": "vless://11111111-2222-3333-4444-555555555555@vless-sg.example.com:80?encryption=none&security=none&type=ws&path=%2Fws#SG",
            "expiry_date": "2099-02-01"
          },
          {
            "name": "Japan OpenVPN",
            "mode": "OPENVPN",
            "region": "JP",
            "expiry": "2099-09-30",
            "ovpn": "$ovpnB64V1"
          }
        ]
      }
      """.trimIndent()

    val server1 = OneShotHttpServer(200, remoteJsonV1)
    val port1 = server1.start()
    try {
      val res1 =
        splitRepo.fetchAndMergeSplitFromRemoteUrl(
          url = "http://127.0.0.1:$port1/servers.json",
          replaceExisting = false,
        )
      assertTrue("Remote JSON with OPENVPN must succeed: ${res1.exceptionOrNull()?.message}", res1.isSuccess)
      val summary1 = res1.getOrThrow()
      assertEquals(1, summary1.networksAdded)
      assertEquals(3, summary1.serversAdded)

      val servers1 = splitRepo.getAllServersSnapshot()
      assertEquals(3, servers1.size)
      val ovpnSrv = servers1.first { it.name == "Japan OpenVPN" }
      assertEquals(ConnectionMode.OPENVPN, ovpnSrv.resolvedConnectionMode)
      assertFalse("Remote OpenVPN server must NOT be isUserImported", ovpnSrv.isUserImported)
      assertEquals("2099-09-30", ovpnSrv.expiryDate)
      assertEquals("JP", ovpnSrv.region)
      assertEquals("", ovpnSrv.host)
      assertEquals(0, ovpnSrv.port)
      assertTrue(OvpnSecretStore.isEncrypted(ovpnSrv.ovpnConfigEncrypted))
      assertEquals(sampleCertOnlyOvpn, OvpnSecretStore.decrypt(ovpnSrv.ovpnConfigEncrypted))
    } finally {
      server1.stop()
    }

    // Second sync: upsert same OpenVPN server by name with new expiry & updated config while keeping SSH & VLESS in remote JSON
    val remoteJsonV2 =
      """
      {
        "format_version": 2,
        "networks": [
          {
            "name": "Carrier Direct",
            "payload_mode": "DIRECT"
          }
        ],
        "servers": [
          {
            "name": "Japan SSH",
            "mode": "SSH_DIRECT",
            "host": "ssh-jp.example.com",
            "port": 22,
            "username": "u1",
            "password": "p1",
            "expiry_date": "2099-01-01"
          },
          {
            "name": "SG VLESS",
            "mode": "VLESS_WS",
            "host": "vless-sg.example.com",
            "port": 80,
            "vless_link": "vless://11111111-2222-3333-4444-555555555555@vless-sg.example.com:80?encryption=none&security=none&type=ws&path=%2Fws#SG",
            "expiry_date": "2099-02-01"
          },
          {
            "name": "japan openvpn",
            "mode": "OPENVPN",
            "expiry": "2099-12-31",
            "ovpn": "$ovpnB64V2"
          }
        ]
      }
      """.trimIndent()
    val server2 = OneShotHttpServer(200, remoteJsonV2)
    val port2 = server2.start()
    try {
      val res2 =
        splitRepo.fetchAndMergeSplitFromRemoteUrl(
          url = "http://127.0.0.1:$port2/servers.json",
          replaceExisting = false,
        )
      assertTrue(res2.isSuccess)
      val summary2 = res2.getOrThrow()
      assertEquals(0, summary2.serversAdded)
      assertEquals(3, summary2.serversUpdated)
      assertEquals(0, summary2.serversRemoved)

      val servers2 = splitRepo.getAllServersSnapshot()
      assertEquals("Must upsert without duplicating", 3, servers2.size)
      val updated = servers2.first { it.name.equals("Japan OpenVPN", ignoreCase = true) }
      assertEquals("2099-12-31", updated.expiryDate)
      assertFalse(updated.isUserImported)
      assertEquals(updatedOvpnText, OvpnSecretStore.decrypt(updated.ovpnConfigEncrypted))
      // SSH & VLESS untouched
      assertTrue(servers2.any { it.name == "Japan SSH" && it.host == "ssh-jp.example.com" })
      assertTrue(servers2.any { it.name == "SG VLESS" && it.vlessLink.startsWith("vless://") })
    } finally {
      server2.stop()
    }

    // Third sync: invalid base64 .ovpn rejects cleanly WITHOUT wiping local Room DB!
    val badJson =
      """
      {
        "format_version": 2,
        "networks": [],
        "servers": [
          {
            "name": "Broken OpenVPN",
            "mode": "OPENVPN",
            "ovpn": "not_valid_ovpn_base64!!!"
          }
        ]
      }
      """.trimIndent()
    val server3 = OneShotHttpServer(200, badJson)
    val port3 = server3.start()
    try {
      val res3 =
        splitRepo.fetchAndMergeSplitFromRemoteUrl(
          url = "http://127.0.0.1:$port3/servers.json",
          replaceExisting = false,
        )
      assertTrue("Invalid base64 .ovpn must fail cleanly", res3.isFailure)
      assertEquals("Local servers must remain untouched on error", 3, splitRepo.getAllServersSnapshot().size)
      assertEquals("Local networks must remain untouched on error", 1, splitRepo.getAllNetworksSnapshot().size)
    } finally {
      server3.stop()
    }
  }

  @Test
  fun roomMigration4To5PreservesExistingDataAndAddsOpenVpnColumns() = runBlocking {
    val dbName = "test_amkt_migration_4_5.db"
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
    rawDb.execSQL(
      """
      CREATE TABLE IF NOT EXISTS `network_configs` (
        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
        `name` TEXT NOT NULL,
        `payloadMode` TEXT NOT NULL,
        `payloadTemplate` TEXT NOT NULL,
        `sniHost` TEXT NOT NULL,
        `extraHeaders` TEXT NOT NULL,
        `httpProxyHost` TEXT NOT NULL,
        `httpProxyPort` INTEGER NOT NULL,
        `locked` INTEGER NOT NULL,
        `createdAt` INTEGER NOT NULL,
        `updatedAt` INTEGER NOT NULL
      )
      """.trimIndent()
    )
    rawDb.execSQL(
      """
      CREATE TABLE IF NOT EXISTS `server_accounts` (
        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
        `name` TEXT NOT NULL,
        `connectionMode` TEXT NOT NULL,
        `host` TEXT NOT NULL,
        `port` INTEGER NOT NULL,
        `username` TEXT NOT NULL,
        `password` TEXT NOT NULL,
        `dnsttNameserver` TEXT NOT NULL,
        `dnsttPublicKey` TEXT NOT NULL,
        `dnsttResolver` TEXT NOT NULL,
        `vlessLink` TEXT NOT NULL,
        `vlessUuid` TEXT NOT NULL,
        `expiryDate` TEXT NOT NULL,
        `region` TEXT NOT NULL,
        `tags` TEXT NOT NULL,
        `notes` TEXT NOT NULL,
        `locked` INTEGER NOT NULL,
        `defaultNetworkId` INTEGER,
        `wasSelectedOnMigration` INTEGER NOT NULL,
        `createdAt` INTEGER NOT NULL,
        `updatedAt` INTEGER NOT NULL
      )
      """.trimIndent()
    )
    rawDb.execSQL(
      """
      INSERT INTO network_configs (id, name, payloadMode, payloadTemplate, sniHost, extraHeaders, httpProxyHost, httpProxyPort, locked, createdAt, updatedAt)
      VALUES (1, 'Saved Net v4', 'DIRECT', '', '', '', '', 8080, 0, 1000, 1000)
      """.trimIndent()
    )
    rawDb.execSQL(
      """
      INSERT INTO server_accounts (id, name, connectionMode, host, port, username, password, dnsttNameserver, dnsttPublicKey, dnsttResolver, vlessLink, vlessUuid, expiryDate, region, tags, notes, locked, defaultNetworkId, wasSelectedOnMigration, createdAt, updatedAt)
      VALUES (1, 'Saved SSH v4', 'SSH_DIRECT', 'v4.example.com', 22, 'u4', 'p4', '', '', '8.8.8.8:53', '', '', '2099-01-01', 'JP', '', '', 0, 1, 1, 1000, 1000)
      """.trimIndent()
    )
    rawDb.version = 4
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
      val srvs = migrated.serverAccountDao().getAllServersSnapshot()
      assertEquals(1, nets.size)
      assertEquals(1, srvs.size)
      val existing = srvs.first()
      assertEquals("Saved SSH v4", existing.name)
      assertEquals("", existing.ovpnConfigEncrypted)
      assertFalse(existing.ovpnAuthRequired)
      assertFalse(existing.isUserImported)

      // Insert a new OpenVPN row into migrated v5 DB
      val insertedId =
        migrated.serverAccountDao().insertServer(
          ServerAccountEntity(
            name = "Migrated OpenVPN",
            connectionMode = ConnectionMode.OPENVPN.code,
            ovpnConfigEncrypted = OvpnSecretStore.encrypt(sampleCertOnlyOvpn),
            isUserImported = true,
          )
        )
      val loaded = migrated.serverAccountDao().getServerById(insertedId)
      assertNotNull(loaded)
      assertTrue(loaded!!.isOpenVpnMode())
      assertTrue(loaded.isUserImported)
    } finally {
      migrated.close()
      context.deleteDatabase(dbName)
    }
  }

  @Test
  fun openVpnConnectWorksWithoutNetworkRespectsExpiryAndPassesKillSwitchAutoReconnect() = runBlocking {
    val logStore = AppLogStore()
    val fakeVpn = CapturingVpnController()
    val vpnPrefs = VpnPreferencesRepository(context)
    vpnPrefs.setKillSwitchEnabled(true)
    vpnPrefs.setAutoReconnectEnabled(true)

    val viewModel =
      AmktViewModel(
        repository = legacyRepo,
        membershipRepository = MembershipRepository(context),
        vpnPreferencesRepository = vpnPrefs,
        vpnController = fakeVpn,
        configCodec = ConfigFileCodec(),
        logStore = logStore,
        networkServerRepository = splitRepo,
      )

    val netCollector = launch(Dispatchers.Unconfined) { viewModel.selectedNetwork.collect {} }
    val srvCollector = launch(Dispatchers.Unconfined) { viewModel.selectedServer.collect {} }
    val settingsCollector = launch(Dispatchers.Unconfined) { viewModel.vpnSettings.collect {} }
    try {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      delay(40L)
      Shadows.shadowOf(Looper.getMainLooper()).idle()

      // Ensure NO network is selected
      splitRepo.selectCombination(null, null)
      val imported =
        splitRepo.importOvpnProfile(
          rawOvpnText = sampleCertOnlyOvpn,
          fileName = "NoNet-OpenVPN.ovpn",
          selectAfterImport = true,
        ).getOrThrow()

      awaitCondition {
        viewModel.selectedServer.value?.id == imported.id &&
          viewModel.selectedNetwork.value == null &&
          viewModel.vpnSettings.value.killSwitchEnabled &&
          viewModel.vpnSettings.value.autoReconnectEnabled
      }

      // OpenVPN must pass pre-flight validation even with selectedNetwork == null!
      val validated = viewModel.validateSelectionForVpnConnect()
      assertNotNull("OpenVPN must connect even when no Network is selected", validated)
      val (vNet, vSrv, effective) = validated!!
      assertNull(vNet)
      assertEquals("NoNet-OpenVPN", vSrv.name)
      assertEquals(ConnectionMode.OPENVPN, effective.connectionMode)
      assertTrue(effective.supportsVpnTunnel())

      viewModel.startCombinedVpnAfterConsent(vNet, vSrv, effective)
      assertEquals(1, fakeVpn.startCalls)
      assertEquals(ConnectionMode.OPENVPN, fakeVpn.lastProfile?.connectionMode)
      assertTrue("Kill Switch setting must apply to OpenVPN", fakeVpn.lastKillSwitch)
      assertTrue("Auto-Reconnect setting must apply to OpenVPN", fakeVpn.lastAutoReconnect)

      // Expired remote OpenVPN server must be refused before VPN start
      val expiredId =
        splitRepo.saveServer(
          ServerAccountEntity(
            name = "Expired OpenVPN",
            connectionMode = ConnectionMode.OPENVPN.code,
            expiryDate = "2020-01-01",
            ovpnConfigEncrypted = OvpnSecretStore.encrypt(sampleCertOnlyOvpn),
            isUserImported = false,
          ),
          selectAfterSave = true,
        )
      awaitCondition { viewModel.selectedServer.value?.id == expiredId }
      assertNull("Expired OpenVPN server must be refused", viewModel.validateSelectionForVpnConnect())
      assertTrue(viewModel.snackbarMessage.value.orEmpty().contains("သက်တမ်းကုန်ဆုံးပါပြီ"))
      assertEquals(1, fakeVpn.startCalls)
    } finally {
      netCollector.cancel()
      srvCollector.cancel()
      settingsCollector.cancel()
    }
  }

  @Test
  fun onlyUserImportedOpenVpnServersCanBeDeletedNotRemoteServers() = runBlocking {
    val imported =
      splitRepo.importOvpnProfile(
        rawOvpnText = sampleCertOnlyOvpn,
        fileName = "MyImported.ovpn",
        selectAfterImport = false,
      ).getOrThrow()
    val remoteOvpnId =
      splitRepo.saveServer(
        ServerAccountEntity(
          name = "Remote OpenVPN",
          connectionMode = ConnectionMode.OPENVPN.code,
          ovpnConfigEncrypted = OvpnSecretStore.encrypt(sampleCertOnlyOvpn),
          isUserImported = false,
        )
      )
    val remoteSshId =
      splitRepo.saveServer(
        ServerAccountEntity(
          name = "Remote SSH",
          connectionMode = ConnectionMode.SSH_DIRECT.code,
          host = "ssh.example.com",
          port = 22,
          isUserImported = false,
        )
      )

    assertFalse("Remote OpenVPN must NOT be deletable", splitRepo.deleteUserImportedServer(remoteOvpnId))
    assertFalse("Remote SSH must NOT be deletable", splitRepo.deleteUserImportedServer(remoteSshId))
    assertTrue("User-imported .ovpn MUST be deletable", splitRepo.deleteUserImportedServer(imported.id))

    val remaining = splitRepo.getAllServersSnapshot()
    assertEquals(2, remaining.size)
    assertTrue(remaining.any { it.id == remoteOvpnId })
    assertTrue(remaining.any { it.id == remoteSshId })
    assertFalse(remaining.any { it.id == imported.id })
  }

  @Test
  fun homeScreenShowsOpenVpnBadgeAndNameOnlyWithZeroConfigOrCredentialLeak() {
    val ovpnServer =
      ServerAccountEntity(
        id = 101L,
        name = "Japan OpenVPN",
        connectionMode = ConnectionMode.OPENVPN.code,
        host = "",
        port = 0,
        username = OvpnSecretStore.encrypt("ovpn_hidden_user"),
        password = OvpnSecretStore.encrypt("ovpn_hidden_pass"),
        expiryDate = "2099-12-31",
        region = "JP",
        ovpnConfigEncrypted = OvpnSecretStore.encrypt(sampleCertOnlyOvpn),
        ovpnAuthRequired = true,
        isUserImported = false,
      )

    composeRule.setContent {
      MyApplicationTheme(darkTheme = true) {
        HomeScreen(
          networks = emptyList(),
          servers = listOf(ovpnServer),
          selectedNetwork = null,
          selectedServer = ovpnServer,
          vpnState = VpnTunnelState.Disconnected,
          trafficStats = VpnTrafficStats(bytesDown = 1024L, bytesUp = 512L),
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

    composeRule.onNodeWithText("Japan OpenVPN").assertIsDisplayed()
    composeRule.onNodeWithTag("home_server_openvpn_badge", useUnmergedTree = true).assertIsDisplayed()
    composeRule.onNodeWithText("OpenVPN", useUnmergedTree = true).assertIsDisplayed()

    // Usage pills remain visible and strictly read-only
    val lazyList = composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
    val notClickable = SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick)
    val notEditable = SemanticsMatcher.keyNotDefined(SemanticsActions.SetText)
    lazyList.performScrollToNode(hasTestTag("home_duration_pill"))
    composeRule.onNodeWithTag("home_duration_pill").performScrollTo().assertIsDisplayed().assert(notClickable).assert(notEditable)
    lazyList.performScrollToNode(hasTestTag("home_data_usage_pill"))
    composeRule.onNodeWithTag("home_data_usage_pill").performScrollTo().assertIsDisplayed().assert(notClickable).assert(notEditable)

    assertZeroOvpnSecretLeaks()
  }

  @Test
  fun serversScreenShowsOpenVpnBadgeImportMyVpnAndZeroOvpnImportUi() {
    val remoteOvpn =
      ServerAccountEntity(
        id = 201L,
        name = "Remote JP Node",
        connectionMode = ConnectionMode.OPENVPN.code,
        region = "JP",
        expiryDate = "2099-12-31",
        ovpnConfigEncrypted = OvpnSecretStore.encrypt(sampleCertOnlyOvpn),
        isUserImported = false,
      )
    val secondOvpn =
      ServerAccountEntity(
        id = 202L,
        name = "Singapore OVPN 02",
        connectionMode = ConnectionMode.OPENVPN.code,
        ovpnConfigEncrypted = OvpnSecretStore.encrypt(sampleAuthRequiredOvpn),
        ovpnAuthRequired = true,
        isUserImported = false,
      )

    var myVpnImportClicked = false

    composeRule.setContent {
      MyApplicationTheme(darkTheme = true) {
        ServersScreen(
          servers = listOf(remoteOvpn, secondOvpn),
          selectedServerId = remoteOvpn.id,
          onSelectServer = {},
          onSyncRemoteClick = {},
          onImportMyVpnClick = { myVpnImportClicked = true },
        )
      }
    }
    composeRule.waitForIdle()

    // No .ovpn import button/text anywhere
    composeRule.onAllNodesWithTag("servers_import_ovpn_button", useUnmergedTree = true).assertCountEquals(0)
    composeRule.onAllNodesWithText(".ovpn", substring = true, ignoreCase = true, useUnmergedTree = true).assertCountEquals(0)

    // .myvpn import button IS displayed & clickable
    composeRule.onNodeWithTag("servers_import_myvpn_button").assertIsDisplayed().performClick()
    assertTrue(myVpnImportClicked)

    composeRule.onNodeWithText("Remote JP Node").assertIsDisplayed()
    composeRule.onNodeWithText("Singapore OVPN 02").assertIsDisplayed()
    composeRule.onNodeWithTag("server_openvpn_badge_${remoteOvpn.id}", useUnmergedTree = true).assertIsDisplayed()
    composeRule.onNodeWithTag("server_openvpn_badge_${secondOvpn.id}", useUnmergedTree = true).assertIsDisplayed()

    assertZeroOvpnSecretLeaks()
  }

  @Test
  fun serverPickerSheetShowsOpenVpnBadgeAndHidesAllConfigDetails() {
    val remoteOvpn =
      ServerAccountEntity(
        id = 301L,
        name = "Singapore OVPN",
        connectionMode = ConnectionMode.OPENVPN.code,
        region = "SG",
        expiryDate = "2099-12-31",
        ovpnConfigEncrypted = OvpnSecretStore.encrypt(sampleCertOnlyOvpn),
        isUserImported = false,
      )

    composeRule.setContent {
      MyApplicationTheme(darkTheme = true) {
        ServerPickerBottomSheet(
          servers = listOf(remoteOvpn),
          selectedServerId = remoteOvpn.id,
          onSelect = {},
          onManageAll = {},
          onOpenRedeem = {},
          onDismiss = {},
        )
      }
    }
    composeRule.waitForIdle()

    composeRule.onNodeWithText("Singapore OVPN").assertIsDisplayed()
    composeRule.onNodeWithTag("server_sheet_openvpn_badge_${remoteOvpn.id}", useUnmergedTree = true).assertIsDisplayed()
    assertZeroOvpnSecretLeaks()

    // Searching by hidden remote host must NOT match
    composeRule.onNodeWithTag("server_sheet_search").performTextInput("ovpn-secret-jp")
    composeRule.waitForIdle()
    composeRule.onAllNodesWithTag("server_sheet_item_${remoteOvpn.id}", useUnmergedTree = true).assertCountEquals(0)
  }

  private fun assertZeroOvpnSecretLeaks() {
    val forbidden =
      listOf(
        "ovpn-secret-jp.example.net",
        "ovpn-auth-sg.example.org",
        "1194",
        "AES-256-GCM",
        "AES-128-GCM",
        "SHA256",
        "MIIFakeCertBodySecretCA123456",
        "MIIFakePrivateKeySecretXYZ",
        "ovpn_hidden_user",
        "ovpn_hidden_pass",
        "AMKTENC1:",
        "dev tun",
        "auth-user-pass",
      )
    forbidden.forEach { secret ->
      composeRule
        .onAllNodesWithText(secret, substring = true, ignoreCase = true, useUnmergedTree = true)
        .assertCountEquals(0)
    }
  }

  private suspend fun awaitCondition(condition: suspend () -> Boolean) {
    val deadlineNanos = System.nanoTime() + 5_000_000_000L
    while (!condition() && System.nanoTime() < deadlineNanos) {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      delay(20L)
    }
    Shadows.shadowOf(Looper.getMainLooper()).idle()
    assertTrue("Timed out waiting for expected OpenVPN state", condition())
  }

  private class CapturingVpnController : VpnEngineController {
    override val state: StateFlow<VpnTunnelState> = MutableStateFlow(VpnTunnelState.Disconnected)
    override val trafficStats: StateFlow<VpnTrafficStats> = MutableStateFlow(VpnTrafficStats())
    override val isImplemented: Boolean = true
    var startCalls = 0
    var lastProfile: ServerProfileEntity? = null
    var lastKillSwitch = false
    var lastAutoReconnect = false

    override fun prepareVpnPermissionIntent(context: Context): Intent? = null

    override fun startTunnel(
      profile: ServerProfileEntity,
      killSwitch: Boolean,
      autoReconnect: Boolean,
    ) {
      startCalls += 1
      lastProfile = profile
      lastKillSwitch = killSwitch
      lastAutoReconnect = autoReconnect
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
