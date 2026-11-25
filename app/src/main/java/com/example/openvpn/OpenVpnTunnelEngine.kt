package com.example.openvpn

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import com.example.MainActivity
import com.example.data.AppLogStore
import com.example.data.LogLevel
import com.example.data.NetworkConfigEntity
import com.example.data.ServerProfileEntity
import com.example.ssh.SshTrafficStats
import com.tim.openvpn.model.CIDRIP
import com.tim.openvpn.utils.NetworkSpace
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.openvpn.ovpn3.ClientAPI_Config
import net.openvpn.ovpn3.ClientAPI_Event
import net.openvpn.ovpn3.ClientAPI_LogInfo
import net.openvpn.ovpn3.ClientAPI_OpenVPNClient
import net.openvpn.ovpn3.ClientAPI_ProvideCreds

sealed interface OpenVpnTunnelState {
  data object Disconnected : OpenVpnTunnelState

  data class Connecting(val profileName: String) : OpenVpnTunnelState

  data class Connected(
    val profileName: String,
    val connectedAtMs: Long = System.currentTimeMillis(),
  ) : OpenVpnTunnelState

  data class Error(val reason: String) : OpenVpnTunnelState
}

interface OpenVpnTunnelEngine {
  val state: StateFlow<OpenVpnTunnelState>
  val trafficStats: StateFlow<SshTrafficStats>

  suspend fun connect(
    vpnService: VpnService,
    profile: ServerProfileEntity,
    network: NetworkConfigEntity? = null,
    onTunEstablished: (() -> Unit)? = null,
  ): Result<Unit>

  suspend fun disconnect()

  fun abortActiveConnect()

  fun notifyNetworkChange()
}

/**
 * Internal handle abstracting a single OpenVPN3 client session so [IcsOpenVpn3TunnelEngine]
 * runs `libovpn3.so` in production while remaining fully unit-testable on the JVM.
 */
internal interface OpenVpnClientHandle {
  fun prepareConfig(): String?

  fun connectBlocking(): Pair<Boolean, String?>

  fun stop()

  fun reconnect(seconds: Int)

  fun readTrafficStats(): SshTrafficStats?

  fun release()
}

internal fun interface OpenVpnSessionFactory {
  fun create(
    vpnService: VpnService,
    profileName: String,
    effectiveOvpnConfig: String,
    username: String,
    password: String,
    onTunEstablished: (() -> Unit)?,
    onConnected: () -> Unit,
    onError: (String) -> Unit,
    onDisconnected: (String) -> Unit,
  ): OpenVpnClientHandle
}

/**
 * Production OpenVPN transport engine powered by the open-source ics-openvpn OpenVPN3 native core
 * (`io.github.tim06:openvpn` / `libovpn3.so` / `ClientAPI_OpenVPNClient`) running directly inside
 * [com.example.vpn.AmktVpnService] so Kill Switch, Auto-Reconnect, and live read-only traffic stats
 * behave identically across SSH, DNSTT, VLESS, and OpenVPN.
 *
 * When a [NetworkConfigEntity] is selected alongside a TCP OpenVPN server, this engine:
 * 1. Verifies the `.ovpn` uses TCP (refusing UDP with [OpenVpnPayloadForwarder.OVPN_TCP_ONLY_ERROR]).
 * 2. Establishes the selected network's payload tunnel FIRST via [OpenVpnPayloadForwarder] and
 *    forwards `127.0.0.1:11940` to the OpenVPN server's TCP port.
 * 3. Starts OpenVPN3 with a connect-time-only `.ovpn` string whose `remote` line points to
 *    `127.0.0.1 <boundPort>`, never modifying the stored profile.
 *
 * Security / Lock-down: Never logs raw `.ovpn` config lines, rewritten `remote` lines, local
 * forward port `11940`, remote host/IP, proxy host, certs, keys, or credentials.
 */
class IcsOpenVpn3TunnelEngine internal constructor(
  private val logStore: AppLogStore,
  private val forwarderProvider: () -> OpenVpnPayloadForwarder,
  private val nativeLibraryLoader: () -> Boolean,
  private val sessionFactory: OpenVpnSessionFactory,
  private val connectTimeoutMs: Long = CONNECT_TIMEOUT_MS,
) : OpenVpnTunnelEngine {

  constructor(logStore: AppLogStore) : this(
    logStore = logStore,
    forwarderProvider = { OpenVpnPayloadForwarder(logStore) },
    nativeLibraryLoader = { ensureNativeLibraryLoaded() },
    sessionFactory = defaultSessionFactory(),
    connectTimeoutMs = CONNECT_TIMEOUT_MS,
  )

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private val _state = MutableStateFlow<OpenVpnTunnelState>(OpenVpnTunnelState.Disconnected)
  override val state: StateFlow<OpenVpnTunnelState> = _state.asStateFlow()

  private val _trafficStats = MutableStateFlow(SshTrafficStats())
  override val trafficStats: StateFlow<SshTrafficStats> = _trafficStats.asStateFlow()

  @Volatile private var activeClient: OpenVpnClientHandle? = null
  @Volatile private var activeForwarder: OpenVpnPayloadForwarder? = null
  @Volatile private var runnerThread: Thread? = null
  @Volatile private var statsJob: Job? = null
  @Volatile private var userCancelled: Boolean = false

  override suspend fun connect(
    vpnService: VpnService,
    profile: ServerProfileEntity,
    network: NetworkConfigEntity?,
    onTunEstablished: (() -> Unit)?,
  ): Result<Unit> {
    val rawOvpn =
      runCatching { OvpnSecretStore.decrypt(profile.ovpnConfigEncrypted) }.getOrDefault("").trim()
    val parsedResult = OvpnConfigParser.parse(rawOvpn)
    if (parsedResult.isFailure) {
      val reason = parsedResult.exceptionOrNull()?.message ?: "OpenVPN config မမှန်ကန်ပါ"
      logStore.append(tag = "OVPN", message = reason, level = LogLevel.ERROR)
      _state.value = OpenVpnTunnelState.Error(reason)
      return Result.failure(IllegalArgumentException(reason))
    }
    val parsed = parsedResult.getOrThrow()

    // Combined mode (Network + OpenVPN) is strictly TCP-only; never silently rewrite udp -> tcp.
    if (network != null && !parsed.isTcp) {
      val reason = OpenVpnPayloadForwarder.OVPN_TCP_ONLY_ERROR
      logStore.append(tag = "OVPN", message = reason, level = LogLevel.ERROR)
      _state.value = OpenVpnTunnelState.Error(reason)
      return Result.failure(IllegalArgumentException(reason))
    }

    val decryptedUser = OvpnSecretStore.decryptOrBlank(profile.username).trim()
    val decryptedPass = OvpnSecretStore.decryptOrBlank(profile.password)
    if (parsed.requiresAuthUserPass && decryptedUser.isBlank()) {
      val reason = "OpenVPN Auth fail — Username/Password လိုအပ်ပါသည်"
      logStore.append(tag = "OVPN", message = reason, level = LogLevel.ERROR)
      _state.value = OpenVpnTunnelState.Error(reason)
      return Result.failure(IllegalStateException(reason))
    }

    disconnectInternal(emitLog = false)
    userCancelled = false

    _state.value = OpenVpnTunnelState.Connecting(profileName = profile.name)
    _trafficStats.value = SshTrafficStats()

    return withContext(Dispatchers.IO) {
      var localForwarder: OpenVpnPayloadForwarder? = null
      val effectiveConfig: String =
        if (network != null) {
          // Steps (a) & (b): Establish payload tunnel FIRST, then bind 127.0.0.1:11940.
          val forwarder = forwarderProvider()
          activeForwarder = forwarder
          val startRes =
            forwarder.start(
              network = network,
              targetHost = parsed.primaryRemoteHost,
              targetPort = parsed.primaryRemotePort,
              preferredPort = OpenVpnPayloadForwarder.DEFAULT_LOCAL_PORT,
              socketProtector = { sock -> runCatching { vpnService.protect(sock) }.getOrDefault(false) },
              onUpstreamClosed = {
                if (!userCancelled && _state.value is OpenVpnTunnelState.Connected) {
                  val dropMsg = "Payload Tunnel ချိတ်ဆက်မှု ပြတ်တောက်သွားပါသည်"
                  runCatching { activeClient?.stop() }
                  _state.value = OpenVpnTunnelState.Error(dropMsg)
                }
              },
            )
          if (startRes.isFailure || userCancelled) {
            forwarder.stop()
            if (activeForwarder === forwarder) activeForwarder = null
            if (userCancelled) {
              return@withContext Result.failure(CancellationException("ချိတ်ဆက်မှုကို ပယ်ဖျက်လိုက်ပါပြီ"))
            }
            val payloadReason =
              startRes.exceptionOrNull()?.message ?: "Payload Tunnel ချိတ်ဆက်မှု မအောင်မြင်ပါ"
            _state.value = OpenVpnTunnelState.Error(payloadReason)
            return@withContext Result.failure(IOException(payloadReason))
          }
          val boundPort = startRes.getOrThrow()
          localForwarder = forwarder
          // Step (c): Rewrite ONLY the remote line in memory at connect time; stored profile stays untouched.
          OvpnConfigParser.rewriteRemoteForLocalForward(
            rawOvpn = parsed.rawConfig,
            localHost = OpenVpnPayloadForwarder.LOOPBACK_HOST,
            localPort = boundPort,
          )
        } else {
          // Direct OpenVPN (no network selected): use original .ovpn as given.
          parsed.rawConfig
        }

      if (!nativeLibraryLoader()) {
        localForwarder?.stop()
        if (activeForwarder === localForwarder) activeForwarder = null
        val reason = "OpenVPN native library (libovpn3.so) ဖွင့်မရပါ"
        logStore.append(tag = "OVPN", message = reason, level = LogLevel.ERROR)
        _state.value = OpenVpnTunnelState.Error(reason)
        return@withContext Result.failure(UnsatisfiedLinkError(reason))
      }

      logStore.append(
        tag = "OVPN",
        message = "OpenVPN ချိတ်ဆက်နေသည် (${profile.name})...",
        level = LogLevel.INFO,
      )

      val connectSignal = CompletableDeferred<Result<Unit>>()
      val session =
        sessionFactory.create(
          vpnService = vpnService,
          profileName = profile.name,
          effectiveOvpnConfig = effectiveConfig,
          username = decryptedUser,
          password = decryptedPass,
          onTunEstablished = onTunEstablished,
          onConnected = {
            if (!userCancelled) {
              _state.value = OpenVpnTunnelState.Connected(profileName = profile.name)
              startStatsPolling()
              logStore.append(
                tag = "OVPN",
                message = "OpenVPN ချိတ်ဆက်မှု အောင်မြင်ပြီးပါပြီ (${profile.name})",
                level = LogLevel.SUCCESS,
              )
              connectSignal.complete(Result.success(Unit))
            }
          },
          onError = { reason ->
            if (!userCancelled) {
              if (!connectSignal.isCompleted) {
                connectSignal.complete(Result.failure(IOException(reason)))
              }
              _state.value = OpenVpnTunnelState.Error(reason)
              logStore.append(tag = "OVPN", message = reason, level = LogLevel.WARNING)
            }
          },
          onDisconnected = { reason ->
            if (!userCancelled) {
              val msg = reason.ifBlank { "OpenVPN ချိတ်ဆက်မှု ပြတ်တောက်သွားပါသည်" }
              if (!connectSignal.isCompleted) {
                connectSignal.complete(Result.failure(IOException(msg)))
              }
              if (_state.value is OpenVpnTunnelState.Connected || _state.value is OpenVpnTunnelState.Connecting) {
                _state.value = OpenVpnTunnelState.Error(msg)
              }
            }
          },
        )

      activeClient = session

      val evalError = session.prepareConfig()
      if (evalError != null) {
        activeClient = null
        localForwarder?.stop()
        if (activeForwarder === localForwarder) activeForwarder = null
        val msg = "OpenVPN config အတည်ပြုမရပါ ($evalError)"
        _state.value = OpenVpnTunnelState.Error(msg)
        logStore.append(tag = "OVPN", message = msg, level = LogLevel.ERROR)
        return@withContext Result.failure(IllegalArgumentException(msg))
      }

      val thread =
        Thread(
          {
            try {
              val (hadError, detailMsg) = session.connectBlocking()
              if (!userCancelled && hadError) {
                val mapped = detailMsg?.takeIf { it.isNotBlank() } ?: "OpenVPN ချိတ်ဆက်မှု မအောင်မြင်ပါ"
                if (!connectSignal.isCompleted) {
                  connectSignal.complete(Result.failure(IOException(mapped)))
                }
                if (_state.value !is OpenVpnTunnelState.Error) {
                  _state.value = OpenVpnTunnelState.Error(mapped)
                }
              } else if (!userCancelled && !connectSignal.isCompleted) {
                val msg = "OpenVPN ချိတ်ဆက်မှု ပြတ်တောက်သွားပါသည်"
                connectSignal.complete(Result.failure(IOException(msg)))
                _state.value = OpenVpnTunnelState.Error(msg)
              }
            } catch (t: Throwable) {
              if (!userCancelled) {
                val msg = "OpenVPN ချိတ်ဆက်မှု မအောင်မြင်ပါ (${t.javaClass.simpleName})"
                if (!connectSignal.isCompleted) {
                  connectSignal.complete(Result.failure(IOException(msg, t)))
                }
                _state.value = OpenVpnTunnelState.Error(msg)
              }
            } finally {
              statsJob?.cancel()
              statsJob = null
              localForwarder?.stop()
              if (activeForwarder === localForwarder) activeForwarder = null
              runCatching { session.release() }
            }
          },
          "amkt-ovpn3-runner",
        ).apply { isDaemon = true }

      runnerThread = thread
      thread.start()

      val outcome =
        withTimeoutOrNull(connectTimeoutMs) { connectSignal.await() }
          ?: run {
            runCatching { session.stop() }
            localForwarder?.stop()
            if (activeForwarder === localForwarder) activeForwarder = null
            val timeoutMsg = "OpenVPN ဆာဗာသို့ ချိတ်ဆက်ချိန် ကျော်လွန်သွားပါသည် (Timeout)"
            _state.value = OpenVpnTunnelState.Error(timeoutMsg)
            logStore.append(tag = "OVPN", message = timeoutMsg, level = LogLevel.ERROR)
            Result.failure(IOException(timeoutMsg))
          }

      if (userCancelled) {
        disconnectInternal(emitLog = false)
        return@withContext Result.failure(CancellationException("ချိတ်ဆက်မှုကို ပယ်ဖျက်လိုက်ပါပြီ"))
      }

      if (outcome.isFailure) {
        runCatching { session.stop() }
        localForwarder?.stop()
        if (activeForwarder === localForwarder) activeForwarder = null
        statsJob?.cancel()
        statsJob = null
        activeClient = null
      }
      outcome
    }
  }

  override fun abortActiveConnect() {
    userCancelled = true
    statsJob?.cancel()
    statsJob = null
    val fwd = activeForwarder
    activeForwarder = null
    runCatching { fwd?.abortActiveConnect() }
    val client = activeClient
    activeClient = null
    runCatching { client?.stop() }
  }

  override suspend fun disconnect() {
    abortActiveConnect()
    disconnectInternal(emitLog = true)
  }

  override fun notifyNetworkChange() {
    val client = activeClient ?: return
    if (userCancelled || _state.value !is OpenVpnTunnelState.Connected) return
    val fwd = activeForwarder
    if (fwd != null) {
      // Combined mode: physical network drop invalidates the upstream payload socket; tear down so
      // AmktVpnService engages Kill Switch and Auto-Reconnect (payload tunnel first, then OpenVPN).
      runCatching { fwd.stop() }
      runCatching { client.stop() }
      _state.value = OpenVpnTunnelState.Error("OpenVPN ကွန်ရက်ချိတ်ဆက်မှု ပြတ်တောက်သွားပါသည်")
    } else {
      runCatching { client.reconnect(0) }
    }
  }

  private suspend fun disconnectInternal(emitLog: Boolean) {
    val hadActive =
      activeClient != null ||
        activeForwarder != null ||
        _state.value is OpenVpnTunnelState.Connected ||
        _state.value is OpenVpnTunnelState.Connecting
    statsJob?.cancel()
    statsJob = null
    val fwd = activeForwarder
    activeForwarder = null
    val client = activeClient
    activeClient = null
    withContext(Dispatchers.IO) {
      runCatching { fwd?.stop() }
      runCatching { client?.stop() }
      val t = runnerThread
      runnerThread = null
      if (t != null && t.isAlive && t !== Thread.currentThread()) {
        runCatching { t.join(2_000L) }
      }
    }
    _trafficStats.value = SshTrafficStats()
    _state.value = OpenVpnTunnelState.Disconnected
    if (emitLog && hadActive) {
      logStore.append(
        tag = "OVPN",
        message = "OpenVPN ချိတ်ဆက်မှုကို ဖြတ်တောက်လိုက်ပါပြီ",
        level = LogLevel.INFO,
      )
    }
  }

  private fun startStatsPolling() {
    statsJob?.cancel()
    statsJob =
      scope.launch {
        while (isActive && !userCancelled) {
          val client = activeClient ?: break
          val stats = runCatching { client.readTrafficStats() }.getOrNull()
          if (stats != null) {
            _trafficStats.value = stats
          }
          delay(1_000L)
        }
      }
  }

  /**
   * Inner OpenVPN3 SWIG client binding OpenVPN3's TUN builder and socket protection directly to
   * [AmktVpnService].
   */
  private class AmktOpenVpn3Session(
    private val vpnService: VpnService,
    private val profileName: String,
    private val rawOvpnConfig: String,
    private val username: String,
    private val password: String,
    private val onTunEstablished: (() -> Unit)?,
    private val onConnected: () -> Unit,
    private val onError: (String) -> Unit,
    private val onDisconnected: (String) -> Unit,
  ) : ClientAPI_OpenVPNClient(), OpenVpnClientHandle {

    private val connectedNotified = AtomicBoolean(false)

    private var mtu: Int = DEFAULT_MTU
    private var sessionName: String = "AMKT Injector — $profileName"
    private val ipv4Addresses = mutableListOf<Pair<String, Int>>()
    private val ipv6Addresses = mutableListOf<Pair<String, Int>>()
    private val ipv4Routes = NetworkSpace()
    private val ipv6Routes = NetworkSpace()
    private val dnsServers = mutableListOf<String>()
    private val searchDomains = mutableListOf<String>()

    override fun prepareConfig(): String? {
      val config =
        ClientAPI_Config().apply {
          content = rawOvpnConfig
          guiVersion = "AMKT Injector"
          enableLegacyAlgorithms = true
          enableNonPreferredDCAlgorithms = true
          tunPersist = true
          retryOnAuthFailed = false
          googleDnsFallback = true
          allowLocalLanAccess = false
        }
      val eval = eval_config(config)
      if (eval.error) {
        return eval.message.orEmpty().ifBlank { "invalid config" }
      }
      if (!eval.autologin && (username.isNotBlank() || password.isNotBlank())) {
        val creds =
          ClientAPI_ProvideCreds().apply {
            this.username = this@AmktOpenVpn3Session.username
            this.password = this@AmktOpenVpn3Session.password
            cachePassword = true
          }
        val status = provide_creds(creds)
        if (status.error) {
          return status.message.orEmpty().ifBlank { "credentials rejected" }
        }
      }
      return null
    }

    override fun connectBlocking(): Pair<Boolean, String?> {
      val status = connect()
      return if (status != null && status.error) {
        true to mapOpenVpnError(status.status, status.message)
      } else {
        false to null
      }
    }

    override fun readTrafficStats(): SshTrafficStats? {
      val stats = transport_stats() ?: return null
      return SshTrafficStats(
        bytesUp = stats.bytesOut.coerceAtLeast(0L),
        bytesDown = stats.bytesIn.coerceAtLeast(0L),
        activeStreams = 1,
      )
    }

    override fun release() {
      delete()
    }

    override fun socket_protect(socket: Int, remote: String?, ipv6: Boolean): Boolean {
      return runCatching { vpnService.protect(socket) }.getOrDefault(false)
    }

    override fun tun_builder_new(): Boolean {
      mtu = DEFAULT_MTU
      sessionName = "AMKT Injector — $profileName"
      ipv4Addresses.clear()
      ipv6Addresses.clear()
      ipv4Routes.clear()
      ipv6Routes.clear()
      dnsServers.clear()
      searchDomains.clear()
      return true
    }

    override fun tun_builder_set_layer(layer: Int): Boolean = layer == 3

    override fun tun_builder_set_remote_address(address: String?, ipv6: Boolean): Boolean = true

    override fun tun_builder_set_session_name(name: String?): Boolean {
      if (!name.isNullOrBlank()) {
        sessionName = "AMKT Injector — $profileName"
      }
      return true
    }

    override fun tun_builder_set_mtu(mtu: Int): Boolean {
      if (mtu in 576..65535) {
        this.mtu = mtu
      }
      return true
    }

    override fun tun_builder_add_address(
      address: String?,
      prefixLength: Int,
      gateway: String?,
      ipv6: Boolean,
      net30: Boolean,
    ): Boolean {
      if (address.isNullOrBlank()) return false
      if (ipv6) {
        ipv6Addresses += address to prefixLength.coerceIn(0, 128)
      } else {
        ipv4Addresses += address to prefixLength.coerceIn(0, 32)
      }
      return true
    }

    override fun tun_builder_add_route(
      address: String?,
      prefixLength: Int,
      metric: Int,
      ipv6: Boolean,
    ): Boolean {
      if (address.isNullOrBlank()) return false
      return addNetworkRoute(address, prefixLength, ipv6, include = true)
    }

    override fun tun_builder_exclude_route(
      address: String?,
      prefixLength: Int,
      metric: Int,
      ipv6: Boolean,
    ): Boolean {
      if (address.isNullOrBlank()) return false
      return addNetworkRoute(address, prefixLength, ipv6, include = false)
    }

    override fun tun_builder_reroute_gw(ipv4: Boolean, ipv6: Boolean, flags: Long): Boolean {
      if (ipv4) {
        addNetworkRoute("0.0.0.0", 0, ipv6 = false, include = true)
      }
      if (ipv6) {
        addNetworkRoute("::", 0, ipv6 = true, include = true)
      }
      return true
    }

    override fun tun_builder_add_dns_server(address: String?, ipv6: Boolean): Boolean {
      if (!address.isNullOrBlank()) {
        dnsServers += address
      }
      return true
    }

    override fun tun_builder_add_search_domain(domain: String?): Boolean {
      if (!domain.isNullOrBlank()) {
        searchDomains += domain
      }
      return true
    }

    override fun tun_builder_establish(): Int {
      val configureIntent =
        PendingIntent.getActivity(
          vpnService,
          10,
          Intent(vpnService, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
          },
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

      val builder =
        vpnService.Builder()
          .setSession(sessionName)
          .setMtu(mtu)
          .setConfigureIntent(configureIntent)

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        builder.setMetered(false)
      }

      // Exclude our own app so upstream payload tunnel sockets (OpenVpnPayloadForwarder) reach the
      // physical network directly with zero routing loops while all other apps use the VPN tunnel.
      runCatching { builder.addDisallowedApplication(vpnService.packageName) }

      ipv4Addresses.forEach { (addr, prefix) ->
        runCatching { builder.addAddress(addr, prefix) }
      }
      ipv6Addresses.forEach { (addr, prefix) ->
        runCatching { builder.addAddress(addr, prefix) }
      }

      val positiveV4 = runCatching { ipv4Routes.positiveIPList }.getOrNull().orEmpty()
      if (positiveV4.isEmpty() && ipv4Addresses.isNotEmpty()) {
        runCatching { builder.addRoute("0.0.0.0", 0) }
      } else {
        positiveV4.forEach { ip ->
          runCatching { builder.addRoute(ip.iPv4Address, ip.networkMask) }
        }
      }

      val positiveV6 = runCatching { ipv6Routes.positiveIPList }.getOrNull().orEmpty()
      positiveV6.forEach { ip ->
        runCatching { builder.addRoute(ip.iPv6Address, ip.networkMask) }
      }

      if (dnsServers.isEmpty()) {
        runCatching { builder.addDnsServer("1.1.1.1") }
      } else {
        dnsServers.forEach { dns -> runCatching { builder.addDnsServer(dns) } }
      }
      searchDomains.forEach { domain -> runCatching { builder.addSearchDomain(domain) } }

      val pfd = runCatching { builder.establish() }.getOrNull() ?: return -1
      onTunEstablished?.invoke()
      return pfd.detachFd()
    }

    override fun pause_on_connection_timeout(): Boolean = false

    override fun log(info: ClientAPI_LogInfo?) {
      // Intentionally do NOT write OpenVPN internal log lines to AppLogStore so remote hosts,
      // IPs, certificates, local forward port, and credentials are never leaked.
    }

    override fun event(ev: ClientAPI_Event?) {
      if (ev == null) return
      val name = ev.name.orEmpty().uppercase()
      when {
        name == "CONNECTED" -> {
          if (connectedNotified.compareAndSet(false, true)) {
            onConnected()
          }
        }
        name == "AUTH_FAILED" -> {
          onError("OpenVPN Auth fail — အကောင့်အမည် သို့မဟုတ် စကားဝှက် မမှန်ကန်ပါ")
        }
        name == "DISCONNECTED" -> {
          onDisconnected("OpenVPN ချိတ်ဆက်မှု ပြတ်တောက်သွားပါသည်")
        }
        ev.fatal || ev.error || name == "TRANSPORT_ERROR" || name == "CONNECTION_TIMEOUT" -> {
          onError(mapOpenVpnError(name, ev.info.orEmpty()))
        }
      }
    }

    private fun addNetworkRoute(
      address: String,
      prefixLength: Int,
      ipv6: Boolean,
      include: Boolean,
    ): Boolean {
      return runCatching {
        if (ipv6) {
          val inet = InetAddress.getByName(address)
          if (inet is Inet6Address) {
            ipv6Routes.addIPv6(inet, prefixLength.coerceIn(0, 128), include)
          }
        } else {
          ipv4Routes.addIP(CIDRIP(address, prefixLength.coerceIn(0, 32)), include)
        }
        true
      }.getOrDefault(false)
    }
  }

  companion object {
    private const val DEFAULT_MTU = 1500
    private const val CONNECT_TIMEOUT_MS = 35_000L

    @Volatile private var nativeLoaded: Boolean? = null

    private fun defaultSessionFactory(): OpenVpnSessionFactory {
      return OpenVpnSessionFactory {
          vpnService,
          profileName,
          effectiveOvpnConfig,
          username,
          password,
          onTunEstablished,
          onConnected,
          onError,
          onDisconnected ->
        AmktOpenVpn3Session(
          vpnService = vpnService,
          profileName = profileName,
          rawOvpnConfig = effectiveOvpnConfig,
          username = username,
          password = password,
          onTunEstablished = onTunEstablished,
          onConnected = onConnected,
          onError = onError,
          onDisconnected = onDisconnected,
        )
      }
    }

    private fun ensureNativeLibraryLoaded(): Boolean {
      nativeLoaded?.let { return it }
      return synchronized(this) {
        nativeLoaded?.let { return@synchronized it }
        val loaded = runCatching { System.loadLibrary("ovpn3") }.isSuccess
        nativeLoaded = loaded
        loaded
      }
    }

    internal fun mapOpenVpnError(code: String?, detail: String?): String {
      val upper = "${code.orEmpty()} ${detail.orEmpty()}".uppercase()
      return when {
        upper.contains("AUTH") ->
          "OpenVPN Auth fail — အကောင့်အမည် သို့မဟုတ် စကားဝှက် မမှန်ကန်ပါ"
        upper.contains("TIMEOUT") ->
          "OpenVPN ဆာဗာသို့ ချိတ်ဆက်ချိန် ကျော်လွန်သွားပါသည် (Timeout)"
        upper.contains("RESOLVE") || upper.contains("DNS") ->
          "OpenVPN ဆာဗာလိပ်စာ ရှာမတွေ့ပါ — အင်တာနက်ချိတ်ဆက်မှုကို စစ်ဆေးပါ"
        upper.contains("TRANSPORT") || upper.contains("NETWORK") ->
          "OpenVPN ကွန်ရက်ချိတ်ဆက်မှု ပြတ်တောက်သွားပါသည်"
        else ->
          "OpenVPN ချိတ်ဆက်မှု မအောင်မြင်ပါ"
      }
    }
  }
}
