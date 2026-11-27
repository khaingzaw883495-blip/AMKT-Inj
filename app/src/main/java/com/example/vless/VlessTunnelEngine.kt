package com.example.vless

import com.example.data.AppLogStore
import com.example.data.LogLevel
import com.example.data.ServerProfileEntity
import com.example.ssh.LocalSocks5Proxy
import com.example.ssh.SshTrafficStats
import com.example.ssh.TunnelConnector
import java.io.IOException
import java.net.ConnectException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Runtime state of the Milestone 6 VLESS over WebSocket tunnel engine.
 */
sealed interface VlessTunnelState {
  data object Disconnected : VlessTunnelState

  data class Connecting(
    val profileName: String,
    val targetHost: String,
    val targetPort: Int,
  ) : VlessTunnelState

  data class Connected(
    val profileName: String,
    val targetHost: String,
    val targetPort: Int,
    val wsPath: String,
    val useTls: Boolean,
    val localSocksHost: String = LocalSocks5Proxy.LOOPBACK_HOST,
    val localSocksPort: Int = LocalSocks5Proxy.DEFAULT_PORT,
    val connectedAtMs: Long = System.currentTimeMillis(),
  ) : VlessTunnelState

  data class Error(val reason: String) : VlessTunnelState
}

interface VlessTunnelEngine {
  val state: StateFlow<VlessTunnelState>
  val trafficStats: StateFlow<SshTrafficStats>

  suspend fun connect(
    profile: ServerProfileEntity,
    localSocksPort: Int = LocalSocks5Proxy.DEFAULT_PORT,
  ): Result<Int>

  suspend fun disconnect()

  fun abortActiveConnect()

  suspend fun probeKeepAlive(): Boolean
}

/**
 * Real VLESS v0 over WebSocket client engine exposing a local SOCKS5 proxy on `127.0.0.1:1080`
 * for system-wide routing through [com.example.vpn.AmktVpnService] + `hev-socks5-tunnel`.
 */
class NativeVlessTunnelEngine(
  private val logStore: AppLogStore,
  private val transportFactory: () -> WebSocketTransport = { WebSocketTransport() },
) : VlessTunnelEngine {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val lifecycleMutex = Mutex()
  private val trackedSockets = ConcurrentHashMap.newKeySet<Socket>()
  private val consecutiveFailures = AtomicInteger(0)

  private val _state = MutableStateFlow<VlessTunnelState>(VlessTunnelState.Disconnected)
  override val state: StateFlow<VlessTunnelState> = _state.asStateFlow()

  private val _trafficStats = MutableStateFlow(SshTrafficStats())
  override val trafficStats: StateFlow<SshTrafficStats> = _trafficStats.asStateFlow()

  @Volatile private var activeConfig: VlessConfig? = null
  @Volatile private var activeProxy: LocalSocks5Proxy? = null
  @Volatile private var statsForwardJob: Job? = null
  @Volatile private var userCancelled: Boolean = false

  override suspend fun connect(
    profile: ServerProfileEntity,
    localSocksPort: Int,
  ): Result<Int> {
    val parsedConfig = VlessConfigParser.fromProfile(profile)
    if (parsedConfig.isFailure) {
      val reason = parsedConfig.exceptionOrNull()?.message ?: "VLESS ဆက်တင် မမှန်ကန်ပါ"
      logStore.append(tag = "VLESS", message = reason, level = LogLevel.WARNING)
      _state.value = VlessTunnelState.Error(reason)
      return Result.failure(IllegalArgumentException(reason))
    }

    val config = parsedConfig.getOrThrow()
    teardownInternal(emitLog = false)
    userCancelled = false
    consecutiveFailures.set(0)

    _state.value =
      VlessTunnelState.Connecting(
        profileName = profile.name,
        targetHost = config.serverHost,
        targetPort = config.serverPort,
      )

    logStore.append(
      tag = "VLESS",
      message =
        "VLESS ချိတ်ဆက်နေသည် (${config.serverHost}:${config.serverPort} • path=${config.wsPath} • security=${config.securityLabel})...",
      level = LogLevel.INFO,
    )

    return withContext(Dispatchers.IO) {
      var proxy: LocalSocks5Proxy? = null
      try {
        val transport = transportFactory()
        // 1. Perform a real WebSocket Upgrade handshake (and TLS+SNI if security=tls)
        //    to verify the VLESS endpoint is reachable before reporting Connected.
        val probeWs =
          transport.open(
            config = config,
            isAborted = { userCancelled },
            onSocketOpened = { sock -> trackedSockets.add(sock) },
          )
        probeWs.close()

        if (userCancelled) {
          _state.value = VlessTunnelState.Disconnected
          return@withContext Result.failure(CancellationException("ချိတ်ဆက်မှုကို ပယ်ဖျက်လိုက်ပါပြီ"))
        }

        if (config.useTls) {
          logStore.append(
            tag = "VLESS",
            message = "VLESS TLS/SNI Handshake အောင်မြင်ပြီးပါပြီ (SNI: ${config.effectiveSni})",
            level = LogLevel.SUCCESS,
          )
        }
        logStore.append(
          tag = "VLESS",
          message =
            "WebSocket တည်ဆောက်ပြီးပါပြီ (HTTP 101 Switching Protocols • ${config.effectiveWsHost}${config.wsPath})",
          level = LogLevel.SUCCESS,
        )

        // 2. Start local SOCKS5 proxy (127.0.0.1:1080) forwarding TCP and UDP streams through VLESS/WS
        val vlessTcpConnector = TunnelConnector { dstHost, dstPort, _ ->
          if (userCancelled) {
            throw IOException("VLESS tunnel stopped")
          }
          try {
            val channel =
              transport.openVlessChannel(
                config = config,
                targetHost = dstHost,
                targetPort = dstPort,
                isAborted = { userCancelled },
                onSocketOpened = { sock -> trackedSockets.add(sock) },
              )
            consecutiveFailures.set(0)
            channel
          } catch (e: IOException) {
            val fails = consecutiveFailures.incrementAndGet()
            if (!userCancelled && fails >= MAX_CONSECUTIVE_STREAM_FAILURES) {
              scope.launch { handleUpstreamDrop(config, e.message ?: "network error") }
            }
            throw e
          }
        }
        proxy =
          LocalSocks5Proxy(
            connector = vlessTcpConnector,
            onLog = { line ->
              logStore.append(tag = "VLESS", message = line, level = LogLevel.WARNING)
            },
            udpConnector = {
              VlessUdpTunnelSession(
                tcpConnector = vlessTcpConnector,
                udpStreamOpener = { dstHost, dstPort ->
                  if (userCancelled) throw IOException("VLESS tunnel stopped")
                  val channel =
                    transport.openVlessUdpChannel(
                      config = config,
                      targetHost = dstHost,
                      targetPort = dstPort,
                      isAborted = { userCancelled },
                      onSocketOpened = { sock -> trackedSockets.add(sock) },
                    )
                  VlessUdpTunnelSession.wrapVlessChannel(channel)
                },
              )
            },
          )

        val boundPort = proxy.start(localSocksPort)

        lifecycleMutex.withLock {
          if (userCancelled) {
            proxy.stop()
            activeConfig = null
            activeProxy = null
            _state.value = VlessTunnelState.Disconnected
            return@withContext Result.failure(CancellationException("ချိတ်ဆက်မှုကို ပယ်ဖျက်လိုက်ပါပြီ"))
          }
          activeConfig = config
          activeProxy = proxy
          startStatsForwarding(proxy)
          _state.value =
            VlessTunnelState.Connected(
              profileName = profile.name,
              targetHost = config.serverHost,
              targetPort = config.serverPort,
              wsPath = config.wsPath,
              useTls = config.useTls,
              localSocksHost = LocalSocks5Proxy.LOOPBACK_HOST,
              localSocksPort = boundPort,
            )
        }

        logStore.append(
          tag = "VLESS",
          message =
            "VLESS tunnel စတင်ပြီးပါပြီ — Local SOCKS5 Proxy (${LocalSocks5Proxy.LOOPBACK_HOST}:$boundPort) အဆင်သင့်ဖြစ်ပါပြီ",
          level = LogLevel.SUCCESS,
        )
        Result.success(boundPort)
      } catch (e: CancellationException) {
        proxy?.stop()
        teardownInternal(emitLog = false)
        Result.failure(e)
      } catch (e: Exception) {
        proxy?.stop()
        teardownInternal(emitLog = false)
        val friendlyReason = mapVlessErrorToBurmese(e, config.serverHost, config.serverPort)
        _state.value = VlessTunnelState.Error(friendlyReason)
        logStore.append(tag = "VLESS", message = friendlyReason, level = LogLevel.ERROR)
        Result.failure(IOException(friendlyReason, e))
      }
    }
  }

  override fun abortActiveConnect() {
    userCancelled = true
    trackedSockets.forEach { sock -> runCatching { sock.close() } }
    trackedSockets.clear()
  }

  override suspend fun disconnect() {
    abortActiveConnect()
    teardownInternal(emitLog = true)
  }

  override suspend fun probeKeepAlive(): Boolean =
    withContext(Dispatchers.IO) {
      val cfg = activeConfig ?: return@withContext false
      if (userCancelled || activeProxy == null) return@withContext false
      try {
        val probe =
          transportFactory().open(
            config = cfg,
            isAborted = { userCancelled },
            onSocketOpened = { sock -> trackedSockets.add(sock) },
          )
        probe.close()
        true
      } catch (e: Exception) {
        if (!userCancelled) {
          handleUpstreamDrop(cfg, e.message ?: "network lost")
        }
        false
      }
    }

  private suspend fun handleUpstreamDrop(config: VlessConfig, detail: String) {
    if (userCancelled || _state.value !is VlessTunnelState.Connected) return
    val dropMsg = "VLESS ချိတ်ဆက်မှု ပြတ်တောက်သွားပါသည် (${config.serverHost}:${config.serverPort} • $detail)"
    logStore.append(tag = "VLESS", message = dropMsg, level = LogLevel.WARNING)
    teardownInternal(emitLog = false)
    _state.value = VlessTunnelState.Error(dropMsg)
  }

  private suspend fun teardownInternal(emitLog: Boolean) {
    val hadActive =
      lifecycleMutex.withLock {
        val existed =
          activeProxy != null ||
            activeConfig != null ||
            _state.value is VlessTunnelState.Connected ||
            _state.value is VlessTunnelState.Connecting

        statsForwardJob?.cancel()
        statsForwardJob = null

        val proxyToStop = activeProxy
        activeProxy = null
        activeConfig = null

        withContext(Dispatchers.IO) {
          trackedSockets.forEach { sock -> runCatching { sock.close() } }
          trackedSockets.clear()
          runCatching { proxyToStop?.stop() }
        }

        _trafficStats.value = SshTrafficStats()
        _state.value = VlessTunnelState.Disconnected
        existed
      }

    if (emitLog && hadActive) {
      logStore.append(
        tag = "VLESS",
        message = "VLESS ချိတ်ဆက်မှု ဖြတ်တောက်ပြီး Local SOCKS5 Proxy ကို ပိတ်လိုက်ပါပြီ",
        level = LogLevel.INFO,
      )
    }
  }

  private fun startStatsForwarding(proxy: LocalSocks5Proxy) {
    statsForwardJob?.cancel()
    statsForwardJob =
      scope.launch {
        proxy.trafficStats.collect { stats -> _trafficStats.value = stats }
      }
  }

  private fun mapVlessErrorToBurmese(error: Exception, host: String, port: Int): String {
    val rawMessage = error.message.orEmpty()
    val cause = error.cause
    val causeMessage = cause?.message.orEmpty()
    if (containsMyanmarText(causeMessage)) return causeMessage
    if (containsMyanmarText(rawMessage)) return rawMessage

    return when {
      error is UnknownHostException || cause is UnknownHostException ->
        "VLESS ဆာဗာလိပ်စာ ($host) ရှာမတွေ့ပါ — အင်တာနက်ချိတ်ဆက်မှုကို စစ်ဆေးပါ"
      error is SocketTimeoutException || cause is SocketTimeoutException ->
        "VLESS ဆာဗာ ($host:$port) သို့ ချိတ်ဆက်ချိန် ကျော်လွန်သွားပါသည် (Timeout)"
      error is ConnectException || cause is ConnectException ->
        "VLESS ဆာဗာ ($host:$port) မှ ချိတ်ဆက်မှုကို လက်မခံပါ (Connection refused)"
      rawMessage.isNotBlank() ->
        "VLESS ချိတ်ဆက်မှု မအောင်မြင်ပါ: $rawMessage"
      else ->
        "VLESS ချိတ်ဆက်မှု မအောင်မြင်ပါ ($host:$port)"
    }
  }

  private fun containsMyanmarText(text: String): Boolean {
    return text.any { ch -> ch in '\u1000'..'\u109F' }
  }

  companion object {
    private const val MAX_CONSECUTIVE_STREAM_FAILURES = 8
  }
}
