package com.example.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import com.example.data.NetworkConfigEntity
import com.example.data.ServerAccountEntity
import com.example.data.ServerProfileEntity
import com.example.ssh.LocalSocks5Proxy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class VpnTrafficStats(
  val bytesUp: Long = 0L,
  val bytesDown: Long = 0L,
  val activeStreams: Int = 0,
)

/**
 * System-wide VPN tunnel state (Android VpnService + hev-socks5-tunnel + SSH/DNSTT/VLESS SOCKS5).
 */
sealed interface VpnTunnelState {
  data object Disconnected : VpnTunnelState

  data class Connecting(
    val profileName: String,
    val targetHost: String,
    val targetPort: Int,
    val networkName: String = "",
    val serverName: String = "",
  ) : VpnTunnelState

  data class Connected(
    val profileName: String,
    val targetHost: String,
    val targetPort: Int,
    val localSocksHost: String = LocalSocks5Proxy.LOOPBACK_HOST,
    val localSocksPort: Int = LocalSocks5Proxy.DEFAULT_PORT,
    val killSwitchEnabled: Boolean = true,
    val autoReconnectEnabled: Boolean = true,
    val connectedAtMs: Long = System.currentTimeMillis(),
    val networkName: String = "",
    val serverName: String = "",
  ) : VpnTunnelState

  data class Reconnecting(
    val profileName: String,
    val targetHost: String,
    val targetPort: Int,
    val attempt: Int,
    val nextRetrySeconds: Int,
    val killSwitchBlocking: Boolean,
    val lastReason: String,
    val networkName: String = "",
    val serverName: String = "",
  ) : VpnTunnelState

  data class KillSwitchBlocking(
    val profileName: String,
    val targetHost: String,
    val targetPort: Int,
    val reason: String,
    val networkName: String = "",
    val serverName: String = "",
  ) : VpnTunnelState

  data class Error(val message: String) : VpnTunnelState
}

interface VpnEngineController {
  val state: StateFlow<VpnTunnelState>
  val trafficStats: StateFlow<VpnTrafficStats>
  val isImplemented: Boolean

  fun prepareVpnPermissionIntent(context: Context): Intent?

  fun startTunnel(
    profile: ServerProfileEntity,
    killSwitch: Boolean = true,
    autoReconnect: Boolean = true,
  )

  fun startCombinedTunnel(
    network: NetworkConfigEntity,
    server: ServerAccountEntity,
    effectiveProfile: ServerProfileEntity,
    killSwitch: Boolean = true,
    autoReconnect: Boolean = true,
  ) {
    startTunnel(effectiveProfile, killSwitch, autoReconnect)
  }

  fun stopTunnel()

  fun updateProtectionSettings(killSwitch: Boolean, autoReconnect: Boolean)
}

/**
 * Shared state hub between AmktVpnService (background foreground service) and UI ViewModel.
 */
object VpnRuntimeStateHolder {
  private val _state = MutableStateFlow<VpnTunnelState>(VpnTunnelState.Disconnected)
  val state: StateFlow<VpnTunnelState> = _state.asStateFlow()

  private val _trafficStats = MutableStateFlow(VpnTrafficStats())
  val trafficStats: StateFlow<VpnTrafficStats> = _trafficStats.asStateFlow()

  @Volatile var activeProfile: ServerProfileEntity? = null
  @Volatile var activeNetworkId: Long = -1L
  @Volatile var activeServerId: Long = -1L
  @Volatile var activeNetworkName: String = ""
  @Volatile var activeServerName: String = ""

  fun updateState(newState: VpnTunnelState) {
    _state.value = newState
  }

  fun updateTraffic(stats: VpnTrafficStats) {
    _trafficStats.value = stats
  }

  fun resetTraffic() {
    _trafficStats.value = VpnTrafficStats()
  }
}

class AndroidVpnEngineController(
  private val appContext: Context,
) : VpnEngineController {
  override val state: StateFlow<VpnTunnelState> = VpnRuntimeStateHolder.state
  override val trafficStats: StateFlow<VpnTrafficStats> = VpnRuntimeStateHolder.trafficStats
  override val isImplemented: Boolean = true

  override fun prepareVpnPermissionIntent(context: Context): Intent? {
    return VpnService.prepare(context)
  }

  override fun startTunnel(
    profile: ServerProfileEntity,
    killSwitch: Boolean,
    autoReconnect: Boolean,
  ) {
    VpnRuntimeStateHolder.activeProfile = profile
    VpnRuntimeStateHolder.activeNetworkId = -1L
    VpnRuntimeStateHolder.activeServerId = -1L
    VpnRuntimeStateHolder.activeNetworkName = ""
    VpnRuntimeStateHolder.activeServerName = profile.name
    val intent =
      Intent(appContext, AmktVpnService::class.java).apply {
        action = AmktVpnService.ACTION_START
        putExtra(AmktVpnService.EXTRA_PROFILE_ID, profile.id)
        putExtra(AmktVpnService.EXTRA_KILL_SWITCH, killSwitch)
        putExtra(AmktVpnService.EXTRA_AUTO_RECONNECT, autoReconnect)
      }
    ContextCompat.startForegroundService(appContext, intent)
  }

  override fun startCombinedTunnel(
    network: NetworkConfigEntity,
    server: ServerAccountEntity,
    effectiveProfile: ServerProfileEntity,
    killSwitch: Boolean,
    autoReconnect: Boolean,
  ) {
    VpnRuntimeStateHolder.activeProfile = effectiveProfile
    VpnRuntimeStateHolder.activeNetworkId = network.id
    VpnRuntimeStateHolder.activeServerId = server.id
    VpnRuntimeStateHolder.activeNetworkName = network.name
    VpnRuntimeStateHolder.activeServerName = server.name
    val intent =
      Intent(appContext, AmktVpnService::class.java).apply {
        action = AmktVpnService.ACTION_START
        putExtra(AmktVpnService.EXTRA_PROFILE_ID, effectiveProfile.id)
        putExtra(AmktVpnService.EXTRA_NETWORK_ID, network.id)
        putExtra(AmktVpnService.EXTRA_SERVER_ID, server.id)
        putExtra(AmktVpnService.EXTRA_NETWORK_NAME, network.name)
        putExtra(AmktVpnService.EXTRA_SERVER_NAME, server.name)
        putExtra(AmktVpnService.EXTRA_KILL_SWITCH, killSwitch)
        putExtra(AmktVpnService.EXTRA_AUTO_RECONNECT, autoReconnect)
      }
    ContextCompat.startForegroundService(appContext, intent)
  }

  override fun stopTunnel() {
    val intent =
      Intent(appContext, AmktVpnService::class.java).apply {
        action = AmktVpnService.ACTION_STOP
      }
    val sent = runCatching { appContext.startService(intent) }.isSuccess
    if (!sent) {
      runCatching { appContext.stopService(Intent(appContext, AmktVpnService::class.java)) }
      VpnRuntimeStateHolder.resetTraffic()
      VpnRuntimeStateHolder.updateState(VpnTunnelState.Disconnected)
    }
  }

  override fun updateProtectionSettings(killSwitch: Boolean, autoReconnect: Boolean) {
    val current = state.value
    if (
      current is VpnTunnelState.Connected ||
        current is VpnTunnelState.Reconnecting ||
        current is VpnTunnelState.KillSwitchBlocking
    ) {
      val intent =
        Intent(appContext, AmktVpnService::class.java).apply {
          action = AmktVpnService.ACTION_UPDATE_SETTINGS
          putExtra(AmktVpnService.EXTRA_KILL_SWITCH, killSwitch)
          putExtra(AmktVpnService.EXTRA_AUTO_RECONNECT, autoReconnect)
        }
      runCatching { appContext.startService(intent) }
    }
  }
}
