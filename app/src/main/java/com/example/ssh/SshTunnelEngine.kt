package com.example.ssh

import com.example.data.AppLogStore
import com.example.data.ConnectionMode
import com.example.data.LogLevel
import com.example.data.PayloadMode
import com.example.data.ServerProfileEntity
import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Properties
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Milestone 2 SSH client engine (JSch — mwiede BSD-3-Clause fork) exposing a
 * real local SOCKS5 proxy on 127.0.0.1:1080 forwarding TCP traffic through SSH
 * direct-tcpip channels.
 */
sealed interface SshTunnelState {
  data object Disconnected : SshTunnelState

  data class Connecting(
    val profileName: String,
    val targetHost: String,
    val targetPort: Int,
  ) : SshTunnelState

  data class Connected(
    val profileName: String,
    val targetHost: String,
    val targetPort: Int,
    val localSocksHost: String = LocalSocks5Proxy.LOOPBACK_HOST,
    val localSocksPort: Int = LocalSocks5Proxy.DEFAULT_PORT,
    val serverBanner: String = "",
    val connectedAtMs: Long = System.currentTimeMillis(),
  ) : SshTunnelState

  data class Error(val reason: String) : SshTunnelState
}

interface SshTunnelEngine {
  val state: StateFlow<SshTunnelState>
  val trafficStats: StateFlow<SshTrafficStats>
  val isImplemented: Boolean

  suspend fun connect(
    profile: ServerProfileEntity,
    localSocksPort: Int = LocalSocks5Proxy.DEFAULT_PORT,
    overrideEndpoint: Pair<String, Int>? = null,
    slowTransport: Boolean = false,
  ): Result<Int>

  suspend fun disconnect()

  fun abortActiveConnect() {}

  suspend fun probeKeepAlive(): Boolean
}

class JschSshTunnelEngine(
  private val logStore: AppLogStore,
) : SshTunnelEngine {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val lifecycleMutex = Mutex()

  private val _state = MutableStateFlow<SshTunnelState>(SshTunnelState.Disconnected)
  override val state: StateFlow<SshTunnelState> = _state.asStateFlow()

  private val _trafficStats = MutableStateFlow(SshTrafficStats())
  override val trafficStats: StateFlow<SshTrafficStats> = _trafficStats.asStateFlow()

  override val isImplemented: Boolean = true

  @Volatile private var activeSession: Session? = null
  @Volatile private var activeProxy: LocalSocks5Proxy? = null
  @Volatile private var activeSocketFactory: InjectorSocketFactory? = null
  @Volatile private var watchdogJob: Job? = null
  @Volatile private var statsForwardJob: Job? = null
  @Volatile private var userCancelled: Boolean = false

  override suspend fun connect(
    profile: ServerProfileEntity,
    localSocksPort: Int,
    overrideEndpoint: Pair<String, Int>?,
    slowTransport: Boolean,
  ): Result<Int> {
    if (!profile.supportsSshTunnel()) {
      val modeLabel = profile.connectionMode.displayLabel
      val msg =
        "$modeLabel စနစ်ကို နောက်လာမည့် Milestone တွင် ထည့်သွင်းပါမည် — ယခု SSH (Direct / Payload / WebSocket / SSL+SNI) နှင့် DNSTT / SlowDNS ဖြင့် System-wide VPN ချိတ်ဆက်နိုင်ပါသည်"
      logStore.append(tag = "SSH", message = msg, level = LogLevel.WARNING)
      return Result.failure(UnsupportedOperationException(msg))
    }

    if (profile.isDnsttMode() && overrideEndpoint == null) {
      val msg = "DNSTT / SlowDNS ချိတ်ဆက်ရန် ဦးစွာ DNSTT Client Local Forward Port ကို စတင်ရပါမည်"
      logStore.append(tag = "SSH", message = msg, level = LogLevel.WARNING)
      return Result.failure(IllegalStateException(msg))
    }

    val host = (overrideEndpoint?.first ?: profile.sshHost).trim()
    val port = (overrideEndpoint?.second ?: profile.sshPort).coerceIn(1, 65535)
    val username = profile.username.trim()
    val password = profile.password
    val useDirectLocalForward = overrideEndpoint != null || profile.isDnsttMode()
    val connectTimeoutMs = if (slowTransport) SLOW_CONNECT_TIMEOUT_MS else CONNECT_TIMEOUT_MS
    val keepAliveIntervalMs =
      if (slowTransport) SLOW_SERVER_ALIVE_INTERVAL_MS else SERVER_ALIVE_INTERVAL_MS
    val keepAliveCountMax =
      if (slowTransport) SLOW_SERVER_ALIVE_COUNT_MAX else SERVER_ALIVE_COUNT_MAX
    val channelTimeoutMs =
      if (slowTransport) SLOW_CHANNEL_CONNECT_TIMEOUT_MS else CHANNEL_CONNECT_TIMEOUT_MS

    if (host.isBlank()) {
      val msg = "SSH Host မထည့်ရသေးပါ"
      logStore.append(tag = "SSH", message = msg, level = LogLevel.WARNING)
      return Result.failure(IllegalArgumentException(msg))
    }
    if (username.isBlank() || password.isBlank()) {
      val msg = "SSH Username သို့မဟုတ် Password မထည့်ရသေးပါ"
      logStore.append(tag = "SSH", message = msg, level = LogLevel.WARNING)
      return Result.failure(IllegalArgumentException(msg))
    }

    // Tear down any previous session cleanly before starting a new one
    teardownInternal(emitLog = false)
    userCancelled = false

    val displayTargetHost = if (profile.isDnsttMode()) profile.dnsttNameserver.trim().ifBlank { host } else host
    val displayTargetPort = if (profile.isDnsttMode()) 53 else port

    _state.value =
      SshTunnelState.Connecting(
        profileName = profile.name,
        targetHost = displayTargetHost,
        targetPort = displayTargetPort,
      )
    val payloadMode = profile.resolvedPayloadMode
    val sniInfo =
      if (!useDirectLocalForward && payloadMode == PayloadMode.SSL_TLS_SNI) {
        ", SNI=${profile.effectiveSniHost()}"
      } else {
        ""
      }
    val connectingMsg =
      if (useDirectLocalForward) {
        "DNSTT Local Port ($host:$port) မှတစ်ဆင့် SSH ချိတ်ဆက်နေသည် (user=$username, SlowDNS timeout=${connectTimeoutMs / 1000}s)..."
      } else {
        "SSH ဆာဗာသို့ ချိတ်ဆက်နေသည် ($host:$port, user=$username, mode=${payloadMode.displayLabel}$sniInfo)..."
      }
    logStore.append(tag = "SSH", message = connectingMsg, level = LogLevel.INFO)

    return withContext(Dispatchers.IO) {
      var session: Session? = null
      var proxy: LocalSocks5Proxy? = null
      try {
        val jsch = JSch()
        session =
          jsch.getSession(username, host, port).apply {
            setPassword(password)
            userInfo = PasswordOnlyUserInfo(password)
            if (!useDirectLocalForward && payloadMode != PayloadMode.DIRECT) {
              val injectorFactory =
                InjectorSocketFactory(
                  profile = profile,
                  logStore = logStore,
                  connectTimeoutMs = connectTimeoutMs,
                  handshakeTimeoutMs = connectTimeoutMs,
                )
              activeSocketFactory = injectorFactory
              setSocketFactory(injectorFactory)
            }
            setConfig(
              Properties().apply {
                put("StrictHostKeyChecking", "no")
                put("PreferredAuthentications", "password,keyboard-interactive")
                put("compression.s2c", "none")
                put("compression.c2s", "none")
              }
            )
            serverAliveInterval = keepAliveIntervalMs
            serverAliveCountMax = keepAliveCountMax
          }

        activeSession = session
        try {
          session.connect(connectTimeoutMs)
        } finally {
          activeSocketFactory = null
        }

        if (userCancelled || !session.isConnected) {
          session.disconnect()
          _state.value = SshTunnelState.Disconnected
          return@withContext Result.failure(CancellationException("ချိတ်ဆက်မှုကို ပယ်ဖျက်လိုက်ပါပြီ"))
        }

        val connectedSession = session
        val serverVersion = connectedSession.serverVersion.orEmpty()
        val hostKeyType = connectedSession.hostKey?.type.orEmpty()
        logStore.append(
          tag = "SSH",
          message =
            "SSH ချိတ်ဆက်မှု အောင်မြင်ပြီးပါပြီ ($host:$port${if (serverVersion.isNotBlank()) " • $serverVersion" else ""}${if (hostKeyType.isNotBlank()) " • $hostKeyType" else ""})",
          level = LogLevel.SUCCESS,
        )

        proxy =
          LocalSocks5Proxy(
            connector = { dstHost, dstPort, clientPort ->
              openSshDirectTcpChannel(connectedSession, dstHost, dstPort, clientPort, channelTimeoutMs)
            },
            onLog = { line ->
              logStore.append(tag = "SOCKS5", message = line, level = LogLevel.WARNING)
            },
          )
        val boundPort = proxy.start(localSocksPort)

        lifecycleMutex.withLock {
          if (userCancelled || !connectedSession.isConnected) {
            proxy.stop()
            connectedSession.disconnect()
            activeSession = null
            activeProxy = null
            _state.value = SshTunnelState.Disconnected
            return@withContext Result.failure(CancellationException("ချိတ်ဆက်မှုကို ပယ်ဖျက်လိုက်ပါပြီ"))
          }
          activeSession = connectedSession
          activeProxy = proxy
          startStatsForwarding(proxy)
          startWatchdog(connectedSession, displayTargetHost, displayTargetPort)
          _state.value =
            SshTunnelState.Connected(
              profileName = profile.name,
              targetHost = displayTargetHost,
              targetPort = displayTargetPort,
              localSocksHost = LocalSocks5Proxy.LOOPBACK_HOST,
              localSocksPort = boundPort,
              serverBanner = serverVersion,
            )
        }

        logStore.append(
          tag = "SOCKS5",
          message = "Local SOCKS5 Proxy စတင်ပြီးပါပြီ -> ${LocalSocks5Proxy.LOOPBACK_HOST}:$boundPort",
          level = LogLevel.SUCCESS,
        )
        Result.success(boundPort)
      } catch (e: CancellationException) {
        proxy?.stop()
        runCatching { session?.disconnect() }
        teardownInternal(emitLog = false)
        Result.failure(e)
      } catch (e: Exception) {
        proxy?.stop()
        runCatching { session?.disconnect() }
        teardownInternal(emitLog = false)
        val friendlyReason = mapSshErrorToBurmese(e, host, port)
        _state.value = SshTunnelState.Error(friendlyReason)
        logStore.append(tag = "SSH", message = friendlyReason, level = LogLevel.ERROR)
        Result.failure(IOException(friendlyReason, e))
      }
    }
  }

  override fun abortActiveConnect() {
    userCancelled = true
    activeSocketFactory?.abortActiveHandshake()
    activeSocketFactory = null
    runCatching { activeSession?.disconnect() }
  }

  override suspend fun disconnect() {
    abortActiveConnect()
    teardownInternal(emitLog = true)
  }

  override suspend fun probeKeepAlive(): Boolean =
    withContext(Dispatchers.IO) {
      val session = activeSession ?: return@withContext false
      if (!session.isConnected) return@withContext false
      try {
        session.sendKeepAliveMsg()
        session.isConnected
      } catch (_: Exception) {
        if (!userCancelled) {
          val target =
            (_state.value as? SshTunnelState.Connected)?.let { "${it.targetHost}:${it.targetPort}" }
              ?: "SSH server"
          val dropMsg = "SSH ချိတ်ဆက်မှု ပြတ်တောက်သွားပါသည် ($target)"
          logStore.append(tag = "SSH", message = dropMsg, level = LogLevel.WARNING)
          teardownInternal(emitLog = false)
          _state.value = SshTunnelState.Error(dropMsg)
        }
        false
      }
    }

  private suspend fun teardownInternal(emitLog: Boolean) {
    val hadActive =
      lifecycleMutex.withLock {
        val existed =
          activeSession != null ||
            activeProxy != null ||
            _state.value is SshTunnelState.Connected ||
            _state.value is SshTunnelState.Connecting

        watchdogJob?.cancel()
        watchdogJob = null
        statsForwardJob?.cancel()
        statsForwardJob = null

        val proxyToStop = activeProxy
        val sessionToClose = activeSession
        activeProxy = null
        activeSession = null

        withContext(Dispatchers.IO) {
          runCatching { proxyToStop?.stop() }
          runCatching { sessionToClose?.disconnect() }
        }

        _trafficStats.value = SshTrafficStats()
        _state.value = SshTunnelState.Disconnected
        existed
      }

    if (emitLog && hadActive) {
      logStore.append(
        tag = "SSH",
        message = "SSH ချိတ်ဆက်မှု ဖြတ်တောက်ပြီး Local SOCKS5 Proxy ကို ပိတ်လိုက်ပါပြီ",
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

  private fun startWatchdog(session: Session, host: String, port: Int) {
    watchdogJob?.cancel()
    watchdogJob =
      scope.launch {
        while (isActive) {
          delay(WATCHDOG_INTERVAL_MS)
          if (!session.isConnected) {
            if (!userCancelled) {
              val dropMsg = "SSH ချိတ်ဆက်မှု ပြတ်တောက်သွားပါသည် ($host:$port)"
              logStore.append(tag = "SSH", message = dropMsg, level = LogLevel.WARNING)
              teardownInternal(emitLog = false)
              _state.value = SshTunnelState.Error(dropMsg)
            }
            break
          }
        }
      }
  }

  @Throws(IOException::class)
  private fun openSshDirectTcpChannel(
    session: Session,
    targetHost: String,
    targetPort: Int,
    clientPort: Int,
    channelTimeoutMs: Int = CHANNEL_CONNECT_TIMEOUT_MS,
  ): TunnelChannel {
    if (!session.isConnected) {
      throw IOException("SSH session disconnected")
    }
    return try {
      val channel = session.openChannel("direct-tcpip") as ChannelDirectTCPIP
      channel.setHost(targetHost)
      channel.setPort(targetPort)
      channel.setOrgIPAddress(LocalSocks5Proxy.LOOPBACK_HOST)
      channel.setOrgPort(clientPort)
      val input = channel.inputStream
      val output = channel.outputStream
      channel.connect(channelTimeoutMs)
      JschTunnelChannel(channel, input, output)
    } catch (e: JSchException) {
      throw IOException(e.message ?: "SSH direct-tcpip failed", e)
    }
  }

  private fun mapSshErrorToBurmese(error: Exception, host: String, port: Int): String {
    val rawMessage = error.message.orEmpty()
    val cause = error.cause
    val causeMessage = cause?.message.orEmpty()
    val burmeseInjectorMessage =
      when {
        containsMyanmarText(causeMessage) -> causeMessage
        containsMyanmarText(rawMessage) -> rawMessage
        else -> null
      }
    if (burmeseInjectorMessage != null) {
      return burmeseInjectorMessage
    }
    return when {
      rawMessage.contains("Auth fail", ignoreCase = true) ||
        rawMessage.contains("Auth cancel", ignoreCase = true) ->
        "SSH Username သို့မဟုတ် Password မှားယွင်းနေပါသည် (Auth fail)"
      cause is UnknownHostException || rawMessage.contains("UnknownHost", ignoreCase = true) ->
        "ဆာဗာလိပ်စာ ($host) ရှာမတွေ့ပါ — အင်တာနက်ချိတ်ဆက်မှုကို စစ်ဆေးပါ"
      cause is SocketTimeoutException || rawMessage.contains("timeout", ignoreCase = true) ->
        "ဆာဗာ ($host:$port) သို့ ချိတ်ဆက်ချိန် ကျော်လွန်သွားပါသည် (Timeout)"
      cause is ConnectException || rawMessage.contains("Connection refused", ignoreCase = true) ->
        "ဆာဗာ ($host:$port) မှ ချိတ်ဆက်မှုကို လက်မခံပါ (Connection refused)"
      rawMessage.contains("Algorithm negotiation fail", ignoreCase = true) ->
        "SSH Algorithm မကိုက်ညီပါ (Algorithm negotiation fail)"
      rawMessage.isNotBlank() ->
        "SSH ချိတ်ဆက်မှု မအောင်မြင်ပါ: $rawMessage"
      else ->
        "SSH ချိတ်ဆက်မှု မအောင်မြင်ပါ ($host:$port)"
    }
  }

  private fun containsMyanmarText(text: String): Boolean {
    return text.any { ch -> ch in '\u1000'..'\u109F' }
  }

  private class JschTunnelChannel(
    private val channel: ChannelDirectTCPIP,
    override val inputStream: InputStream,
    override val outputStream: OutputStream,
  ) : TunnelChannel {
    override fun close() {
      runCatching { inputStream.close() }
      runCatching { outputStream.close() }
      runCatching { channel.disconnect() }
    }
  }

  /**
   * Answers OpenSSH password / PAM keyboard-interactive prompts strictly from
   * the user's runtime profile password without ever logging or persisting it.
   */
  private class PasswordOnlyUserInfo(
    private val secretPassword: String,
  ) : UserInfo, UIKeyboardInteractive {
    override fun getPassphrase(): String? = null

    override fun getPassword(): String = secretPassword

    override fun promptPassword(message: String?): Boolean = true

    override fun promptPassphrase(message: String?): Boolean = false

    override fun promptYesNo(message: String?): Boolean = true

    override fun showMessage(message: String?) {}

    override fun promptKeyboardInteractive(
      destination: String?,
      name: String?,
      instruction: String?,
      prompt: Array<out String>?,
      echo: BooleanArray?,
    ): Array<String> {
      val count = prompt?.size ?: return emptyArray()
      return Array(count) { secretPassword }
    }
  }

  companion object {
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val CHANNEL_CONNECT_TIMEOUT_MS = 15_000
    private const val SERVER_ALIVE_INTERVAL_MS = 10_000
    private const val SERVER_ALIVE_COUNT_MAX = 2
    private const val SLOW_CONNECT_TIMEOUT_MS = 35_000
    private const val SLOW_CHANNEL_CONNECT_TIMEOUT_MS = 30_000
    private const val SLOW_SERVER_ALIVE_INTERVAL_MS = 20_000
    private const val SLOW_SERVER_ALIVE_COUNT_MAX = 3
    private const val WATCHDOG_INTERVAL_MS = 1_500L
  }
}
