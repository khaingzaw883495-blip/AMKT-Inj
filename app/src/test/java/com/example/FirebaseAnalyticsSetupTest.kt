package com.example

import android.content.Context
import android.content.Intent
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.analytics.AppAnalyticsInitializer
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
import com.example.ui.AmktViewModel
import com.example.vpn.VpnEngineController
import com.example.vpn.VpnPreferencesRepository
import com.example.vpn.VpnTrafficStats
import com.example.vpn.VpnTunnelState
import com.google.firebase.FirebaseApp
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FirebaseAnalyticsSetupTest {

  private lateinit var context: Context
  private lateinit var db: AmktDatabase

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    db = Room.inMemoryDatabaseBuilder(context, AmktDatabase::class.java).allowMainThreadQueries().build()
  }

  @After
  fun tearDown() {
    db.close()
  }

  @Test
  fun googleServicesConfigAndVersionMatchAmktVpnProject() {
    assertEquals("com.aistudio.amktinjector.vkwmrx", BuildConfig.APPLICATION_ID)
    assertEquals(23, BuildConfig.VERSION_CODE)
    assertEquals("1.0.0-m17", BuildConfig.VERSION_NAME)

    val appIdResId = context.resources.getIdentifier("google_app_id", "string", context.packageName)
    assertTrue("google_app_id from google-services.json must be merged", appIdResId != 0)
    assertEquals("1:703262942189:android:b36bbe5f0d12192ceafde4", context.getString(appIdResId))

    val projectIdResId = context.resources.getIdentifier("project_id", "string", context.packageName)
    assertTrue("project_id from google-services.json must be merged", projectIdResId != 0)
    assertEquals("amkt-vpn", context.getString(projectIdResId))
  }

  @Test
  fun firebaseAnalyticsInitializesSafelyAndNeverBlocksVpnConnectionOnFailure() {
    // 1. Real initializer succeeds with merged google-services.json resources
    assertTrue(AppAnalyticsInitializer.initialize(context))
    assertTrue(FirebaseApp.getApps(context).isNotEmpty())

    // 2. Simulated failure (e.g. offline / missing GMS) is caught cleanly and never throws
    val failedGracefully =
      AppAnalyticsInitializer.initialize(context) {
        throw IllegalStateException("Simulated Firebase Analytics unavailable offline")
      }
    assertFalse(failedGracefully)

    // 3. VPN connection still validates and starts normally even when analytics init failed
    val remoteRepo =
      RemoteServerListRepository(
        context = context,
        dataStore =
          PreferenceDataStoreFactory.create {
            File(context.cacheDir, "fa_remote_${UUID.randomUUID()}.preferences_pb")
          },
      )
    val membershipRepo =
      MembershipRepository(
        context = context,
        dataStore =
          PreferenceDataStoreFactory.create {
            File(context.cacheDir, "fa_member_${UUID.randomUUID()}.preferences_pb")
          },
      )
    val splitRepo = NetworkServerRepository(db.networkConfigDao(), db.serverAccountDao(), remoteRepo)
    val legacyRepo = ServerProfileRepository(db.serverProfileDao(), remoteRepo)
    val vpnController = RecordingVpnController()

    val net = NetworkConfigEntity(name = "Direct Net", payloadMode = PayloadMode.DIRECT.code)
    val srv =
      ServerAccountEntity(
        name = "VIP Test",
        connectionMode = ConnectionMode.SSH_DIRECT.code,
        host = "vpn.example.com",
        port = 22,
        username = "u",
        password = "p",
      )

    runBlocking {
      val nId = splitRepo.saveNetwork(net, selectAfterSave = true)
      val sId = splitRepo.saveServer(srv, selectAfterSave = true)
      splitRepo.selectCombination(nId, sId)
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

    // Wait briefly or populate state via repository
    val storedNet = runBlocking { splitRepo.getAllNetworksSnapshot().first() }
    val storedSrv = runBlocking { splitRepo.getAllServersSnapshot().first() }
    val combined = com.example.data.EffectiveConnectionCombiner.combine(storedNet, storedSrv)
    viewModel.startCombinedVpnAfterConsent(storedNet, storedSrv, combined)

    assertEquals(1, vpnController.startCount)
    assertNotNull(vpnController.state.value as? VpnTunnelState.Connected)
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
}
