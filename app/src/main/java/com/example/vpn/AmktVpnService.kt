package com.example.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.example.AmktApplication
import com.example.MainActivity
import com.example.R
import com.example.data.AppLogStore
import com.example.data.EffectiveConnectionCombiner
import com.example.data.LogLevel
import com.example.data.ProfileExpiryChecker
import com.example.data.ServerProfileEntity
import com.example.dnstt.DnsttConfig
import com.example.dnstt.DnsttEngine
import com.example.dnstt.DnsttState
import com.example.ssh.LocalSocks5Proxy
import com.example.ssh.SshTunnelEngine
import com.example.ssh.SshTunnelState
import com.example.vless.VlessTunnelEngine
import com.example.vless.VlessTunnelState
import java.io.IOException
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Milestone 3 Android VpnService routing all device traffic through the local SSH SOCKS5 proxy
 * using hev-socks5-tunnel (MIT / Apache-2.0), with Kill Switch leak protection and Auto-Reconnect.
 */
class AmktVpnService : VpnService() {
  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val hevTunnel: HevTunnelAdapter = NativeHevTunnelAdapter()

  private lateinit var logStore: AppLogStore
  private lateinit var sshEngine: SshTunnelEngine
  private lateinit var dnsttEngine: DnsttEngine
  private lateinit var vlessEngine: VlessTunnelEngine

  private val tunLock = Any()

  @Volatile private var vpnInterface: ParcelFileDescriptor? = null
  @Volatile private var hevTunnelRunning: Boolean = false
  @Volatile private var currentProfile: ServerProfileEntity? = null
  @Volatile private var currentNetworkName: String = ""
  @Volatile private var currentServerName: String = ""
  @Volatile private var killSwitchEnabled: Boolean = true
  @Volatile private var autoReconnectEnabled: Boolean = true
  @Volatile private var userRequestedStop: Boolean = false
  @Volatile private var sessionConnectedAtMs: Long = 0L

  private var tunnelSupervisorJob: Job? = null
  private var statsTickerJob: Job? = null
  private var networkCallback: ConnectivityManager.NetworkCallback? = null
  @Volatile private var networkRecoveredSignal: CompletableDeferred<Unit>? = null

  override fun onCreate() {
    super.onCreate()
    val app = application as AmktApplication
    logStore = app.logStore
    sshEngine = app.sshEngine
    dnsttEngine = app.dnsttEngine
    vlessEngine = app.vlessEngine
    ensureNotificationChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_STOP -> {
        handleStopRequest(reason = "အသုံးပြုသူမှ VPN ချိတ်ဆက်မှုကို ဖြတ်တောက်လိုက်ပါပြီ")
        return START_NOT_STICKY
      }
      ACTION_UPDATE_SETTINGS -> {
        killSwitchEnabled = intent.getBooleanExtra(EXTRA_KILL_SWITCH, killSwitchEnabled)
        autoReconnectEnabled = intent.getBooleanExtra(EXTRA_AUTO_RECONNECT, autoReconnectEnabled)
        applyRuntimeSettingChange()
        return START_STICKY
      }
      ACTION_START, null -> {
        killSwitchEnabled = intent?.getBooleanExtra(EXTRA_KILL_SWITCH, true) ?: true
        autoReconnectEnabled = intent?.getBooleanExtra(EXTRA_AUTO_RECONNECT, true) ?: true
        val profileId = intent?.getLongExtra(EXTRA_PROFILE_ID, -1L) ?: -1L
        val networkId = intent?.getLongExtra(EXTRA_NETWORK_ID, -1L) ?: -1L
        val serverId = intent?.getLongExtra(EXTRA_SERVER_ID, -1L) ?: -1L
        val networkNameExtra = intent?.getStringExtra(EXTRA_NETWORK_NAME).orEmpty()
        val serverNameExtra = intent?.getStringExtra(EXTRA_SERVER_NAME).orEmpty()
        startVpnWorkflow(
          profileId = profileId,
          networkId = networkId,
          serverId = serverId,
          networkNameExtra = networkNameExtra,
          serverNameExtra = serverNameExtra,
        )
        return START_STICKY
      }
      else -> return START_NOT_STICKY
    }
  }

  override fun onRevoke() {
    logStore.append(
      tag = "VPN",
      message = "Android စနစ် သို့မဟုတ် အခြား VPN တစ်ခုမှ VPN ခွင့်ပြုချက်ကို ရုပ်သိမ်းလိုက်ပါသည်",
      level = LogLevel.WARNING,
    )
    handleStopRequest(reason = "VPN ခွင့်ပြုချက် ရုပ်သိမ်းခံရပါသည်")
    super.onRevoke()
  }

  override fun onDestroy() {
    unregisterNetworkCallback()
    tunnelSupervisorJob?.cancel()
    statsTickerJob?.cancel()
    stopHevTunnelQuietly()
    closeTunInterfaceQuietly()
    if (::dnsttEngine.isInitialized) {
      runCatching { dnsttEngine.stopNow() }
    }
    if (::vlessEngine.isInitialized) {
      runCatching { vlessEngine.abortActiveConnect() }
    }
    serviceScope.cancel()
    super.onDestroy()
  }

  private fun startVpnWorkflow(
    profileId: Long,
    networkId: Long = -1L,
    serverId: Long = -1L,
    networkNameExtra: String = "",
    serverNameExtra: String = "",
  ) {
    userRequestedStop = false
    tunnelSupervisorJob?.cancel()
    statsTickerJob?.cancel()

    // Android 14+ requires startForeground immediately after startForegroundService()
    promoteToForeground(
      title = getString(R.string.app_name),
      text = getString(R.string.status_connecting),
    )

    tunnelSupervisorJob =
      serviceScope.launch {
        val resolved =
          resolveCombinedProfile(
            profileId = profileId,
            networkId = networkId,
            serverId = serverId,
            networkNameExtra = networkNameExtra,
            serverNameExtra = serverNameExtra,
          )
        if (resolved == null) {
          val msg = "ကွန်ရက် သို့မဟုတ် ဆာဗာ ရှာမတွေ့ပါ — ပြန်လည်ရွေးချယ်ပြီး ချိတ်ဆက်ပါ"
          logStore.append(tag = "VPN", message = msg, level = LogLevel.ERROR)
          VpnRuntimeStateHolder.updateState(VpnTunnelState.Error(msg))
          stopForegroundAndSelf()
          return@launch
        }
        val (profile, netName, srvName) = resolved

        if (profile.isExpired()) {
          val msg = ProfileExpiryChecker.buildExpiredRefusalMessage(srvName.ifBlank { profile.name }, profile.expiryDate)
          logStore.append(tag = "EXPIRY", message = msg, level = LogLevel.ERROR)
          VpnRuntimeStateHolder.updateState(VpnTunnelState.Error(msg))
          stopForegroundAndSelf()
          return@launch
        }

        currentProfile = profile
        currentNetworkName = netName
        currentServerName = srvName.ifBlank { profile.name }
        registerNetworkCallback()

        val initialOk = establishFullTunnel(profile, isReconnectAttempt = false, attemptNumber = 0)
        if (!initialOk) {
          if (userRequestedStop) {
            cleanupAllResources(clearStateToDisconnected = true)
            stopForegroundAndSelf()
            return@launch
          }
          val reason = currentErrorReason()
          val isAuthFail = reason.contains("Auth fail", ignoreCase = true)
          if (!userRequestedStop && autoReconnectEnabled && !isAuthFail) {
            // Initial connect never established a VPN tunnel yet, so do NOT open a blackhole
            // Kill Switch TUN during initial handshake retries (prevents trapping normal internet).
            runReconnectLoop(
              profile = profile,
              initialReason = reason,
              engageKillSwitchOnRetry = false,
            )
            if (!userRequestedStop && VpnRuntimeStateHolder.state.value is VpnTunnelState.Connected) {
              monitorSessionUntilStopped(profile)
            }
          } else if (!userRequestedStop && killSwitchEnabled && vpnInterface != null) {
            enterKillSwitchHold(profile, reason)
          } else {
            cleanupAllResources(clearStateToDisconnected = false)
            stopForegroundAndSelf()
          }
          return@launch
        }

        monitorSessionUntilStopped(profile)
      }
  }

  private suspend fun resolveCombinedProfile(
    profileId: Long,
    networkId: Long,
    serverId: Long,
    networkNameExtra: String,
    serverNameExtra: String,
  ): Triple<ServerProfileEntity, String, String>? {
    val app = application as AmktApplication
    val effectiveNetId = if (networkId > 0L) networkId else VpnRuntimeStateHolder.activeNetworkId
    val effectiveSrvId = if (serverId > 0L) serverId else VpnRuntimeStateHolder.activeServerId

    if (effectiveNetId > 0L && effectiveSrvId > 0L) {
      val net = app.networkServerRepository.getNetworkById(effectiveNetId)
      val srv = app.networkServerRepository.getServerById(effectiveSrvId)
      if (net != null && srv != null) {
        val combined = EffectiveConnectionCombiner.combine(net, srv)
        VpnRuntimeStateHolder.activeProfile = combined
        VpnRuntimeStateHolder.activeNetworkName = net.name
        VpnRuntimeStateHolder.activeServerName = srv.name
        return Triple(combined, net.name, srv.name)
      }
    }

    val cached = VpnRuntimeStateHolder.activeProfile
    val netLabel = networkNameExtra.ifBlank { VpnRuntimeStateHolder.activeNetworkName }
    val srvLabel = serverNameExtra.ifBlank { VpnRuntimeStateHolder.activeServerName }
    if (cached != null && (profileId <= 0L || cached.id == profileId)) {
      return Triple(cached, netLabel, srvLabel.ifBlank { cached.name })
    }
    if (profileId > 0L) {
      val legacy = app.repository.getById(profileId)
      if (legacy != null) {
        return Triple(legacy, netLabel, srvLabel.ifBlank { legacy.name })
      }
    }
    return cached?.let { Triple(it, netLabel, srvLabel.ifBlank { it.name }) }
  }

  private fun formatNotificationTitle(profile: ServerProfileEntity): String {
    val srv = currentServerName.ifBlank { profile.name }
    val net = currentNetworkName
    return if (net.isNotBlank()) {
      "${getString(R.string.app_name)} • $srv ($net)"
    } else {
      "${getString(R.string.app_name)} • $srv"
    }
  }

  private fun resolveDisplayTarget(profile: ServerProfileEntity): Pair<String, Int> {
    return if (profile.isDnsttMode()) {
      profile.dnsttNameserver.trim() to DnsttConfig.DEFAULT_RESOLVER_PORT
    } else {
      profile.sshHost.trim() to profile.sshPort.coerceIn(1, 65535)
    }
  }

  private suspend fun establishFullTunnel(
    profile: ServerProfileEntity,
    isReconnectAttempt: Boolean,
    attemptNumber: Int,
  ): Boolean {
    val (host, port) = resolveDisplayTarget(profile)

    if (!isReconnectAttempt) {
      VpnRuntimeStateHolder.resetTraffic()
      VpnRuntimeStateHolder.updateState(
        VpnTunnelState.Connecting(
          profileName = currentServerName.ifBlank { profile.name },
          targetHost = host,
          targetPort = port,
          networkName = currentNetworkName,
          serverName = currentServerName.ifBlank { profile.name },
        )
      )
      updateNotification(
        title = formatNotificationTitle(profile),
        text = getString(R.string.status_connecting),
      )
      val comboLabel =
        if (currentNetworkName.isNotBlank()) {
          "ကွန်ရက်: $currentNetworkName + ဆာဗာ: ${currentServerName.ifBlank { profile.name }}"
        } else {
          profile.name
        }
      logStore.append(
        tag = "VPN",
        message = "System-wide VPN စတင်နေသည် ($comboLabel [${profile.connectionMode.displayLabel}] • Kill Switch=${if (killSwitchEnabled) "ON" else "OFF"}, Auto-Reconnect=${if (autoReconnectEnabled) "ON" else "OFF"})",
        level = LogLevel.INFO,
      )
    } else {
      logStore.append(
        tag = "VPN",
        message = "အလိုအလျောက် ပြန်လည်ချိတ်ဆက်နေသည် (ကြိုးစားမှု #$attemptNumber)...",
        level = LogLevel.INFO,
      )
    }

    // 1 & 2. Start underlying tunnel engine (VLESS over WebSocket OR DNSTT+SSH OR SSH) -> Local SOCKS5 on 127.0.0.1:1080
    var dnsttLocalPort: Int? = null
    val boundSocksPort: Int
    if (profile.isVlessMode()) {
      runCatching { sshEngine.disconnect() }
      runCatching { dnsttEngine.stop() }
      val vlessResult = vlessEngine.connect(profile, LocalSocks5Proxy.DEFAULT_PORT)
      if (vlessResult.isFailure || userRequestedStop) {
        val reason = vlessResult.exceptionOrNull()?.message ?: "VLESS ချိတ်ဆက်မှု မအောင်မြင်ပါ"
        runCatching { vlessEngine.disconnect() }
        if (!userRequestedStop && !isReconnectAttempt) {
          VpnRuntimeStateHolder.updateState(VpnTunnelState.Error(reason))
        }
        return false
      }
      boundSocksPort = vlessResult.getOrDefault(LocalSocks5Proxy.DEFAULT_PORT)
    } else {
      runCatching { vlessEngine.disconnect() }
      // If DNSTT / SlowDNS mode: start DNSTT client and wait for local forward port
      if (profile.isDnsttMode()) {
        runCatching { dnsttEngine.stop() }
        val dnsttResult =
          dnsttEngine.start(
            DnsttConfig(
              nameserver = profile.dnsttNameserver,
              publicKeyHex = profile.dnsttPublicKey,
              resolverAddress = profile.effectiveDnsttResolver(),
            )
          )
        if (dnsttResult.isFailure || userRequestedStop) {
          val reason = dnsttResult.exceptionOrNull()?.message ?: "DNSTT DNS Tunnel စတင်မရပါ"
          runCatching { dnsttEngine.stop() }
          if (!userRequestedStop && !isReconnectAttempt) {
            VpnRuntimeStateHolder.updateState(VpnTunnelState.Error(reason))
          }
          return false
        }
        dnsttLocalPort = dnsttResult.getOrNull()
      } else {
        runCatching { dnsttEngine.stop() }
      }

      // Connect JSch SSH (over DNSTT loopback port OR direct/payload/SSL) + Local SOCKS5 on 127.0.0.1:1080
      val sshResult =
        if (profile.isDnsttMode() && dnsttLocalPort != null) {
          sshEngine.connect(
            profile = profile,
            localSocksPort = LocalSocks5Proxy.DEFAULT_PORT,
            overrideEndpoint = DnsttConfig.LOOPBACK_HOST to dnsttLocalPort,
            slowTransport = true,
          )
        } else {
          sshEngine.connect(profile, LocalSocks5Proxy.DEFAULT_PORT)
        }

      if (sshResult.isFailure || userRequestedStop) {
        val reason = sshResult.exceptionOrNull()?.message ?: "SSH ချိတ်ဆက်မှု မအောင်မြင်ပါ"
        if (profile.isDnsttMode()) {
          runCatching { dnsttEngine.stop() }
        }
        if (!userRequestedStop && !isReconnectAttempt) {
          VpnRuntimeStateHolder.updateState(VpnTunnelState.Error(reason))
        }
        return false
      }
      boundSocksPort = sshResult.getOrDefault(LocalSocks5Proxy.DEFAULT_PORT)
    }

    if (userRequestedStop) {
      runCatching { sshEngine.disconnect() }
      runCatching { vlessEngine.disconnect() }
      if (profile.isDnsttMode()) runCatching { dnsttEngine.stop() }
      return false
    }

    // 3. Establish fresh TUN interface (atomically replaces any Kill-Switch TUN with zero leak gap)
    stopHevTunnelQuietly()
    val newTun =
      synchronized(tunLock) {
        if (userRequestedStop) {
          null
        } else {
          val previousTun = vpnInterface
          val created =
            try {
              buildTunInterface(profile.name)
            } catch (e: Exception) {
              val msg = "Android TUN interface ဖန်တီးမရပါ: ${e.message ?: "unknown error"}"
              logStore.append(tag = "VPN", message = msg, level = LogLevel.ERROR)
              if (!isReconnectAttempt) {
                VpnRuntimeStateHolder.updateState(VpnTunnelState.Error(msg))
              }
              null
            }
          if (created != null) {
            vpnInterface = created
            runCatching { previousTun?.close() }
          }
          created
        }
      }

    if (newTun == null) {
      runCatching { sshEngine.disconnect() }
      runCatching { vlessEngine.disconnect() }
      if (profile.isDnsttMode()) runCatching { dnsttEngine.stop() }
      if (!userRequestedStop && VpnRuntimeStateHolder.state.value !is VpnTunnelState.Error) {
        val msg = "VPN ခွင့်ပြုချက် မရရှိပါ — VpnService.establish() မှ null ပြန်လာသည်"
        logStore.append(tag = "VPN", message = msg, level = LogLevel.ERROR)
        if (!isReconnectAttempt) {
          VpnRuntimeStateHolder.updateState(VpnTunnelState.Error(msg))
        }
      }
      return false
    }

    // 4. Write hev-socks5-tunnel YAML config & start native tun2socks engine
    try {
      val configFile =
        HevSocks5ConfigBuilder.writeConfigFile(
          directory = filesDir,
          socksHost = LocalSocks5Proxy.LOOPBACK_HOST,
          socksPort = boundSocksPort,
        )
      hevTunnel.start(configFile.absolutePath, newTun.fd)
      hevTunnelRunning = true
    } catch (t: Throwable) {
      val msg = "hev-socks5-tunnel စတင်မရပါ: ${t.message ?: t.javaClass.simpleName}"
      logStore.append(tag = "VPN", message = msg, level = LogLevel.ERROR)
      stopHevTunnelQuietly()
      if (!killSwitchEnabled) {
        closeTunInterfaceQuietly()
      }
      runCatching { sshEngine.disconnect() }
      runCatching { vlessEngine.disconnect() }
      if (profile.isDnsttMode()) runCatching { dnsttEngine.stop() }
      if (!isReconnectAttempt) {
        VpnRuntimeStateHolder.updateState(VpnTunnelState.Error(msg))
      }
      return false
    }

    sessionConnectedAtMs = System.currentTimeMillis()
    VpnRuntimeStateHolder.updateState(
      VpnTunnelState.Connected(
        profileName = currentServerName.ifBlank { profile.name },
        targetHost = host,
        targetPort = port,
        localSocksHost = LocalSocks5Proxy.LOOPBACK_HOST,
        localSocksPort = boundSocksPort,
        killSwitchEnabled = killSwitchEnabled,
        autoReconnectEnabled = autoReconnectEnabled,
        connectedAtMs = sessionConnectedAtMs,
        networkName = currentNetworkName,
        serverName = currentServerName.ifBlank { profile.name },
      )
    )

    val transportSummary =
      when {
        profile.isVlessMode() ->
          "VLESS WS -> SOCKS5 ${LocalSocks5Proxy.LOOPBACK_HOST}:$boundSocksPort • hev-socks5-tunnel"
        profile.isDnsttMode() && dnsttLocalPort != null ->
          "DNSTT 127.0.0.1:$dnsttLocalPort -> SSH -> SOCKS5 ${LocalSocks5Proxy.LOOPBACK_HOST}:$boundSocksPort"
        else ->
          "hev-socks5-tunnel -> ${LocalSocks5Proxy.LOOPBACK_HOST}:$boundSocksPort • DNS via mapdns"
      }
    val successMsg =
      if (isReconnectAttempt) {
        "System-wide VPN အလိုအလျောက် ပြန်လည်ချိတ်ဆက်မှု အောင်မြင်ပြီးပါပြီ (ကြိုးစားမှု #$attemptNumber • $transportSummary)"
      } else {
        "System-wide VPN ချိတ်ဆက်ပြီးပါပြီ ($transportSummary)"
      }
    logStore.append(tag = "VPN", message = successMsg, level = LogLevel.SUCCESS)

    startTrafficAndNotificationTicker(profile)
    return true
  }

  private suspend fun monitorSessionUntilStopped(profile: ServerProfileEntity) {
    while (!userRequestedStop && VpnRuntimeStateHolder.state.value is VpnTunnelState.Connected) {
      val dropReason = awaitUnexpectedDrop(profile) ?: break
      if (userRequestedStop) break
      handleUnexpectedSshDrop(profile, dropReason)
    }
  }

  private suspend fun awaitUnexpectedDrop(profile: ServerProfileEntity): String? {
    val dropSignal = CompletableDeferred<String>()
    val triggered = AtomicBoolean(false)
    fun triggerDrop(reason: String) {
      if (triggered.compareAndSet(false, true)) {
        dropSignal.complete(reason)
      }
    }

    return try {
      coroutineScope {
        val vlessWatcher =
          if (profile.isVlessMode()) {
            launch {
              vlessEngine.state.collect { vlessState ->
                if (userRequestedStop) return@collect
                when (vlessState) {
                  is VlessTunnelState.Error -> triggerDrop(vlessState.reason)
                  is VlessTunnelState.Disconnected -> {
                    if (VpnRuntimeStateHolder.state.value is VpnTunnelState.Connected) {
                      triggerDrop("VLESS ချိတ်ဆက်မှု ပြတ်တောက်သွားပါသည်")
                    }
                  }
                  else -> Unit
                }
              }
            }
          } else {
            null
          }

        val sshWatcher =
          if (!profile.isVlessMode()) {
            launch {
              sshEngine.state.collect { sshState ->
                if (userRequestedStop) return@collect
                when (sshState) {
                  is SshTunnelState.Error -> triggerDrop(sshState.reason)
                  is SshTunnelState.Disconnected -> {
                    if (VpnRuntimeStateHolder.state.value is VpnTunnelState.Connected) {
                      triggerDrop("SSH ချိတ်ဆက်မှု ပြတ်တောက်သွားပါသည်")
                    }
                  }
                  else -> Unit
                }
              }
            }
          } else {
            null
          }

        val dnsttWatcher =
          if (profile.isDnsttMode()) {
            launch {
              dnsttEngine.state.collect { dnsttState ->
                if (userRequestedStop) return@collect
                when (dnsttState) {
                  is DnsttState.Error -> triggerDrop(dnsttState.message)
                  is DnsttState.Stopped -> {
                    if (VpnRuntimeStateHolder.state.value is VpnTunnelState.Connected) {
                      triggerDrop("DNSTT DNS Tunnel ပြတ်တောက်သွားပါသည်")
                    }
                  }
                  else -> Unit
                }
              }
            }
          } else {
            null
          }

        val reason = dropSignal.await()
        vlessWatcher?.cancel()
        sshWatcher?.cancel()
        dnsttWatcher?.cancel()
        reason
      }
    } catch (_: CancellationException) {
      null
    }
  }

  private suspend fun handleUnexpectedSshDrop(profile: ServerProfileEntity, reason: String) {
    if (userRequestedStop) return
    statsTickerJob?.cancel()
    stopHevTunnelQuietly()
    runCatching { sshEngine.disconnect() }
    runCatching { vlessEngine.disconnect() }
    if (profile.isDnsttMode()) {
      runCatching { dnsttEngine.stop() }
    }

    if (killSwitchEnabled) {
      // Keep TUN open with 0.0.0.0/0 & ::/0 routed into blackhole -> ZERO traffic escapes
      ensureKillSwitchTunOpen(profile.name)
      logStore.append(
        tag = "KILLSW",
        message = "Kill Switch အသက်ဝင်နေသည် — Tunnel ပြန်လည်ချိတ်ဆက်ပြီးသည်အထိ ဖုန်း၏ Traffic အားလုံးကို ပိတ်ဆို့ထားပါသည် ($reason)",
        level = LogLevel.WARNING,
      )
    } else {
      closeTunInterfaceQuietly()
      logStore.append(
        tag = "VPN",
        message = "Tunnel ပြတ်တောက်သွားပါသည် (Kill Switch ပိတ်ထားသဖြင့် TUN ကို ခေတ္တပိတ်လိုက်သည်): $reason",
        level = LogLevel.WARNING,
      )
    }

    val isAuthFail = reason.contains("Auth fail", ignoreCase = true)
    if (autoReconnectEnabled && !isAuthFail) {
      runReconnectLoop(
        profile = profile,
        initialReason = reason,
        engageKillSwitchOnRetry = killSwitchEnabled,
      )
    } else if (killSwitchEnabled) {
      enterKillSwitchHold(profile, reason)
    } else {
      VpnRuntimeStateHolder.updateState(VpnTunnelState.Error(reason))
      stopForegroundAndSelf()
    }
  }

  private suspend fun runReconnectLoop(
    profile: ServerProfileEntity,
    initialReason: String,
    engageKillSwitchOnRetry: Boolean,
  ) {
    val (host, port) = resolveDisplayTarget(profile)
    var attempt = 0
    var lastReason = initialReason

    while (!userRequestedStop && autoReconnectEnabled && attempt < MAX_RECONNECT_ATTEMPTS) {
      attempt += 1
      val delaySec = calculateBackoffSeconds(attempt)

      VpnRuntimeStateHolder.updateState(
        VpnTunnelState.Reconnecting(
          profileName = currentServerName.ifBlank { profile.name },
          targetHost = host,
          targetPort = port,
          attempt = attempt,
          nextRetrySeconds = delaySec,
          killSwitchBlocking = killSwitchEnabled && vpnInterface != null,
          lastReason = lastReason,
          networkName = currentNetworkName,
          serverName = currentServerName.ifBlank { profile.name },
        )
      )
      val ksNote = if (killSwitchEnabled && vpnInterface != null) " • Kill Switch ပိတ်ဆို့ထားသည်" else ""
      updateNotification(
        title = "${getString(R.string.app_name)} • ပြန်ချိတ်နေသည် (#$attempt/$MAX_RECONNECT_ATTEMPTS)",
        text = "${delaySec}s အတွင်း ပြန်လည်ချိတ်ဆက်မည်$ksNote",
      )

      // Wait for backoff duration OR wake up immediately if Android reports network recovery
      val signal = CompletableDeferred<Unit>()
      networkRecoveredSignal = signal
      withTimeoutOrNull(delaySec * 1_000L) { signal.await() }
      networkRecoveredSignal = null

      if (userRequestedStop || !autoReconnectEnabled) break

      val reconnected =
        establishFullTunnel(profile, isReconnectAttempt = true, attemptNumber = attempt)
      if (reconnected) {
        return
      }
      if (userRequestedStop || !autoReconnectEnabled) break

      lastReason = currentErrorReason()
      if (lastReason.contains("Auth fail", ignoreCase = true)) {
        break
      }
      if (engageKillSwitchOnRetry && killSwitchEnabled && !userRequestedStop) {
        ensureKillSwitchTunOpen(profile.name)
      }
    }

    if (!userRequestedStop) {
      if (attempt >= MAX_RECONNECT_ATTEMPTS) {
        logStore.append(
          tag = "VPN",
          message = "အလိုအလျောက် ပြန်ချိတ်မှု ($MAX_RECONNECT_ATTEMPTS ကြိမ်) မအောင်မြင်သဖြင့် ရပ်တန့်လိုက်ပါပြီ ($lastReason)",
          level = LogLevel.WARNING,
        )
      }
      if (engageKillSwitchOnRetry && killSwitchEnabled && vpnInterface != null) {
        enterKillSwitchHold(profile, lastReason)
      } else {
        cleanupAllResources(clearStateToDisconnected = false)
        VpnRuntimeStateHolder.updateState(VpnTunnelState.Error(lastReason))
        stopForegroundAndSelf()
      }
    }
  }

  private fun enterKillSwitchHold(profile: ServerProfileEntity, reason: String) {
    if (userRequestedStop) return
    val (host, port) = resolveDisplayTarget(profile)
    ensureKillSwitchTunOpen(profile.name)
    if (userRequestedStop) return
    VpnRuntimeStateHolder.updateState(
      VpnTunnelState.KillSwitchBlocking(
        profileName = currentServerName.ifBlank { profile.name },
        targetHost = host,
        targetPort = port,
        reason = reason,
        networkName = currentNetworkName,
        serverName = currentServerName.ifBlank { profile.name },
      )
    )
    updateNotification(
      title = "${getString(R.string.app_name)} • Kill Switch Active",
      text = getString(R.string.vpn_kill_switch_blocked_msg),
    )
  }

  private fun ensureKillSwitchTunOpen(profileName: String) {
    synchronized(tunLock) {
      if (userRequestedStop || vpnInterface != null) return
      runCatching { vpnInterface = buildTunInterface("$profileName (Kill Switch)") }
    }
  }

  private fun applyRuntimeSettingChange() {
    val current = VpnRuntimeStateHolder.state.value
    logStore.append(
      tag = "VPN",
      message =
        "VPN ဆက်တင် ပြောင်းလဲပြီး: Kill Switch=${if (killSwitchEnabled) "ON" else "OFF"}, Auto-Reconnect=${if (autoReconnectEnabled) "ON" else "OFF"}",
      level = LogLevel.INFO,
    )
    when (current) {
      is VpnTunnelState.Connected -> {
        VpnRuntimeStateHolder.updateState(
          current.copy(
            killSwitchEnabled = killSwitchEnabled,
            autoReconnectEnabled = autoReconnectEnabled,
          )
        )
      }
      is VpnTunnelState.KillSwitchBlocking -> {
        if (!killSwitchEnabled) {
          handleStopRequest(reason = "Kill Switch ပိတ်လိုက်သဖြင့် Traffic ပြန်ဖွင့်ပေးလိုက်ပါပြီ")
        } else if (autoReconnectEnabled) {
          val profile = currentProfile ?: return
          tunnelSupervisorJob?.cancel()
          tunnelSupervisorJob =
            serviceScope.launch {
              runReconnectLoop(
                profile = profile,
                initialReason = current.reason,
                engageKillSwitchOnRetry = true,
              )
              if (!userRequestedStop && VpnRuntimeStateHolder.state.value is VpnTunnelState.Connected) {
                monitorSessionUntilStopped(profile)
              }
            }
        }
      }
      is VpnTunnelState.Reconnecting -> {
        if (!killSwitchEnabled) {
          closeTunInterfaceQuietly()
        } else {
          ensureKillSwitchTunOpen(current.profileName)
        }
        VpnRuntimeStateHolder.updateState(
          current.copy(killSwitchBlocking = killSwitchEnabled && vpnInterface != null)
        )
      }
      else -> Unit
    }
  }

  private fun handleStopRequest(reason: String) {
    // Synchronously release Kill Switch / TUN interface first so normal internet returns
    // instantaneously even if an SSH/Payload/DNSTT handshake or retry loop is currently active.
    synchronized(tunLock) {
      userRequestedStop = true
      stopHevTunnelQuietly()
      closeTunInterfaceQuietly()
    }
    networkRecoveredSignal?.complete(Unit)
    tunnelSupervisorJob?.cancel()
    tunnelSupervisorJob = null
    statsTickerJob?.cancel()
    statsTickerJob = null
    if (::sshEngine.isInitialized) {
      runCatching { sshEngine.abortActiveConnect() }
    }
    if (::vlessEngine.isInitialized) {
      runCatching { vlessEngine.abortActiveConnect() }
    }
    if (::dnsttEngine.isInitialized) {
      runCatching { dnsttEngine.stopNow() }
    }
    VpnRuntimeStateHolder.resetTraffic()
    VpnRuntimeStateHolder.updateState(VpnTunnelState.Disconnected)

    serviceScope.launch {
      cleanupAllResources(clearStateToDisconnected = true)
      logStore.append(tag = "VPN", message = reason, level = LogLevel.INFO)
      stopForegroundAndSelf()
    }
  }

  private suspend fun cleanupAllResources(clearStateToDisconnected: Boolean) {
    unregisterNetworkCallback()
    synchronized(tunLock) {
      stopHevTunnelQuietly()
      closeTunInterfaceQuietly()
    }
    runCatching { sshEngine.disconnect() }
    runCatching { vlessEngine.disconnect() }
    runCatching { dnsttEngine.stop() }
    VpnRuntimeStateHolder.resetTraffic()
    if (clearStateToDisconnected) {
      VpnRuntimeStateHolder.updateState(VpnTunnelState.Disconnected)
    }
  }

  private fun buildTunInterface(sessionName: String): ParcelFileDescriptor? {
    val configureIntent =
      PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).apply {
          flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    val builder =
      Builder()
        .setSession("AMKT Injector — $sessionName")
        .setMtu(HevSocks5ConfigBuilder.DEFAULT_MTU)
        .addAddress(HevSocks5ConfigBuilder.TUN_IPV4, HevSocks5ConfigBuilder.TUN_IPV4_PREFIX)
        .addRoute("0.0.0.0", 0)
        .addAddress(HevSocks5ConfigBuilder.TUN_IPV6, HevSocks5ConfigBuilder.TUN_IPV6_PREFIX)
        .addRoute("::", 0)
        .addDnsServer(HevSocks5ConfigBuilder.TUN_DNS_IPV4)
        .setConfigureIntent(configureIntent)

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      builder.setMetered(false)
    }

    // Critical: Exclude our own app so JSch SSH sockets reach the physical network directly
    // with zero routing loops while ALL other apps on the phone go through the VPN tunnel.
    runCatching { builder.addDisallowedApplication(packageName) }

    return builder.establish()
  }

  private fun startTrafficAndNotificationTicker(profile: ServerProfileEntity) {
    statsTickerJob?.cancel()
    statsTickerJob =
      serviceScope.launch {
        while (isActive && !userRequestedStop) {
          val vpnState = VpnRuntimeStateHolder.state.value
          if (vpnState !is VpnTunnelState.Connected) break

          val socksStats =
            if (profile.isVlessMode()) vlessEngine.trafficStats.value
            else sshEngine.trafficStats.value
          val tunStats = if (hevTunnelRunning) hevTunnel.getStats() else NativeTunStats()

          // Combine TUN counters and SOCKS5 counters safely (prefer higher live figure)
          val bytesUp = maxOf(socksStats.bytesUp, tunStats.txBytes)
          val bytesDown = maxOf(socksStats.bytesDown, tunStats.rxBytes)
          VpnRuntimeStateHolder.updateTraffic(
            VpnTrafficStats(
              bytesUp = bytesUp,
              bytesDown = bytesDown,
              activeStreams = socksStats.activeStreams,
            )
          )

          val elapsedSec = ((System.currentTimeMillis() - vpnState.connectedAtMs) / 1000L).coerceAtLeast(0L)
          val ksBadge = if (vpnState.killSwitchEnabled) " • Kill Switch" else ""
          updateNotification(
            title = formatNotificationTitle(profile),
            text =
              "${formatDuration(elapsedSec)} • ↑ ${formatBytes(bytesUp)} ↓ ${formatBytes(bytesDown)}$ksBadge",
          )
          delay(1_000L)
        }
      }
  }

  private fun registerNetworkCallback() {
    unregisterNetworkCallback()
    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
    val request =
      NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        .build()

    val callback =
      object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
          // Wake up auto-reconnect immediately when Wi-Fi or mobile data comes back
          networkRecoveredSignal?.complete(Unit)
        }

        override fun onLost(network: Network) {
          // Actively probe SSH/VLESS right away so Kill Switch engages fast on Wi-Fi / signal drop
          if (!userRequestedStop && VpnRuntimeStateHolder.state.value is VpnTunnelState.Connected) {
            serviceScope.launch {
              delay(750L)
              if (currentProfile?.isVlessMode() == true) {
                vlessEngine.probeKeepAlive()
              } else {
                sshEngine.probeKeepAlive()
              }
            }
          }
        }
      }

    runCatching {
      cm.registerNetworkCallback(request, callback)
      networkCallback = callback
    }
  }

  private fun unregisterNetworkCallback() {
    val cb = networkCallback ?: return
    networkCallback = null
    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
    runCatching { cm.unregisterNetworkCallback(cb) }
  }

  private fun stopHevTunnelQuietly() {
    if (!hevTunnelRunning) return
    hevTunnelRunning = false
    runCatching { hevTunnel.stop() }
  }

  private fun closeTunInterfaceQuietly() {
    synchronized(tunLock) {
      val tun = vpnInterface ?: return
      vpnInterface = null
      runCatching { tun.close() }
    }
  }

  private fun currentErrorReason(): String {
    return (vlessEngine.state.value as? VlessTunnelState.Error)?.reason
      ?: (sshEngine.state.value as? SshTunnelState.Error)?.reason
      ?: (dnsttEngine.state.value as? DnsttState.Error)?.message
      ?: "Tunnel ချိတ်ဆက်မှု ပြတ်တောက်နေပါသည်"
  }

  private fun ensureNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val nm = getSystemService(NotificationManager::class.java) ?: return
      val channel =
        NotificationChannel(
          NOTIFICATION_CHANNEL_ID,
          getString(R.string.vpn_notif_channel_name),
          NotificationManager.IMPORTANCE_LOW,
        ).apply {
          description = getString(R.string.vpn_notif_channel_desc)
          setShowBadge(false)
        }
      nm.createNotificationChannel(channel)
    }
  }

  private fun buildNotification(title: String, text: String): Notification {
    val openAppIntent =
      PendingIntent.getActivity(
        this,
        1,
        Intent(this, MainActivity::class.java).apply {
          flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    val disconnectIntent =
      PendingIntent.getService(
        this,
        2,
        Intent(this, AmktVpnService::class.java).apply { action = ACTION_STOP },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
      .setSmallIcon(R.mipmap.ic_launcher)
      .setContentTitle(title)
      .setContentText(text)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setCategory(NotificationCompat.CATEGORY_SERVICE)
      .setContentIntent(openAppIntent)
      .addAction(0, getString(R.string.action_disconnect), disconnectIntent)
      .build()
  }

  private fun promoteToForeground(title: String, text: String) {
    val notification = buildNotification(title, text)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      startForeground(
        NOTIFICATION_ID,
        notification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
      )
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }
  }

  private fun updateNotification(title: String, text: String) {
    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
    nm.notify(NOTIFICATION_ID, buildNotification(title, text))
  }

  private fun stopForegroundAndSelf() {
    runCatching {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        stopForeground(STOP_FOREGROUND_REMOVE)
      } else {
        @Suppress("DEPRECATION")
        stopForeground(true)
      }
    }
    stopSelf()
  }

  companion object {
    const val ACTION_START = "com.example.vpn.ACTION_START"
    const val ACTION_STOP = "com.example.vpn.ACTION_STOP"
    const val ACTION_UPDATE_SETTINGS = "com.example.vpn.ACTION_UPDATE_SETTINGS"

    const val EXTRA_PROFILE_ID = "extra_profile_id"
    const val EXTRA_NETWORK_ID = "extra_network_id"
    const val EXTRA_SERVER_ID = "extra_server_id"
    const val EXTRA_NETWORK_NAME = "extra_network_name"
    const val EXTRA_SERVER_NAME = "extra_server_name"
    const val EXTRA_KILL_SWITCH = "extra_kill_switch"
    const val EXTRA_AUTO_RECONNECT = "extra_auto_reconnect"

    const val MAX_RECONNECT_ATTEMPTS = 10

    private const val NOTIFICATION_CHANNEL_ID = "amkt_vpn_tunnel"
    private const val NOTIFICATION_ID = 3001

    internal fun calculateBackoffSeconds(attempt: Int): Int {
      return when {
        attempt <= 1 -> 2
        attempt == 2 -> 4
        attempt == 3 -> 6
        attempt == 4 -> 10
        else -> 15
      }
    }

    internal fun formatDuration(totalSeconds: Long): String {
      val hrs = totalSeconds / 3600L
      val mins = (totalSeconds % 3600L) / 60L
      val secs = totalSeconds % 60L
      return String.format(Locale.US, "%02d:%02d:%02d", hrs, mins, secs)
    }

    internal fun formatBytes(bytes: Long): String {
      return when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
      }
    }
  }
}
