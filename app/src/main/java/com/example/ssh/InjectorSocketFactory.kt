package com.example.ssh

import com.example.data.AppLogStore
import com.example.data.ConnectionMode
import com.example.data.LogLevel
import com.example.data.PayloadMode
import com.example.data.ServerProfileEntity
import com.jcraft.jsch.SocketFactory
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Custom JSch [SocketFactory] for Milestones 4 & 5.1 that performs multi-part `[split]` HTTP
 * Payload injection, lenient multi-response HTTP / WebSocket `101` Upgrade handshakes, and
 * SSL/TLS + SNI handshakes before JSch starts the SSH2 protocol on the resulting socket.
 */
class InjectorSocketFactory(
  private val profile: ServerProfileEntity,
  private val logStore: AppLogStore,
  private val sslSocketFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory,
  private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
  private val handshakeTimeoutMs: Int = DEFAULT_HANDSHAKE_TIMEOUT_MS,
  private val splitDelayMs: Long = DEFAULT_SPLIT_DELAY_MS,
  private val redactHostLogs: Boolean = false,
  private val socketProtector: ((Socket) -> Boolean)? = null,
) : SocketFactory {

  @Volatile private var aborted: Boolean = false
  @Volatile private var activeRawSocket: Socket? = null
  @Volatile private var activeSslSocket: SSLSocket? = null

  /**
   * Immediately cancels any in-flight TCP connect, `[split]` write delay, HTTP response wait,
   * or TLS handshake so Disconnect returns immediately instead of waiting for socket timeouts.
   */
  fun abortActiveHandshake() {
    aborted = true
    runCatching { activeSslSocket?.close() }
    runCatching { activeRawSocket?.close() }
  }

  @Throws(IOException::class)
  override fun createSocket(host: String, port: Int): Socket {
    if (aborted) {
      throw IOException("ချိတ်ဆက်မှုကို အသုံးပြုသူမှ ပယ်ဖျက်လိုက်ပါပြီ")
    }
    val payloadMode = profile.resolvedPayloadMode
    val targetHost = host.trim()
    val targetPort = port.coerceIn(1, 65535)

    return when (payloadMode) {
      PayloadMode.DIRECT -> openTcpSocket(targetHost, targetPort)
      PayloadMode.HTTP_PAYLOAD -> connectWithHttpPayload(targetHost, targetPort)
      PayloadMode.WEBSOCKET -> connectWithWebSocketUpgrade(targetHost, targetPort)
      PayloadMode.SSL_TLS_SNI -> connectWithSslTlsSni(targetHost, targetPort)
    }
  }

  @Throws(IOException::class)
  override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()

  @Throws(IOException::class)
  override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()

  @Throws(IOException::class)
  private fun openTcpSocket(tcpHost: String, tcpPort: Int): Socket {
    if (aborted) {
      throw IOException("ချိတ်ဆက်မှုကို အသုံးပြုသူမှ ပယ်ဖျက်လိုက်ပါပြီ")
    }
    val socket = Socket()
    activeRawSocket = socket
    try {
      socket.tcpNoDelay = true
      socket.connect(InetSocketAddress(tcpHost, tcpPort), connectTimeoutMs)
      runCatching { socketProtector?.invoke(socket) }
      if (aborted) {
        runCatching { socket.close() }
        throw IOException("ချိတ်ဆက်မှုကို အသုံးပြုသူမှ ပယ်ဖျက်လိုက်ပါပြီ")
      }
      socket.soTimeout = handshakeTimeoutMs
      return socket
    } catch (e: IOException) {
      runCatching { socket.close() }
      if (activeRawSocket === socket) activeRawSocket = null
      if (aborted) {
        throw IOException("ချိတ်ဆက်မှုကို အသုံးပြုသူမှ ပယ်ဖျက်လိုက်ပါပြီ", e)
      }
      throw e
    }
  }

  @Throws(IOException::class)
  private fun connectWithHttpPayload(sshHost: String, sshPort: Int): Socket {
    val useProxy =
      (profile.connectionMode == ConnectionMode.SSH_PROXY ||
        profile.connectionMode == ConnectionMode.OPENVPN) &&
        profile.proxyHost.isNotBlank()
    val tcpHost = if (useProxy) profile.proxyHost.trim() else sshHost
    val tcpPort = if (useProxy) profile.proxyPort.coerceIn(1, 65535) else sshPort

    val partTexts =
      PayloadInjector.buildPayloadPartTexts(
        template = profile.payloadTemplate,
        host = sshHost,
        port = sshPort,
        sni = profile.sniHost,
        extraHeaders = profile.extraHeaders,
      )
    if (partTexts.isEmpty()) {
      throw IOException("HTTP Payload စာသား မထည့်ရသေးပါ — Profile ပြင်ဆင်ပြီး Payload ထည့်သွင်းပါ")
    }

    val socket = openTcpSocket(tcpHost, tcpPort)
    try {
      sendPayloadPartsAndAwaitSuccess(
        socket = socket,
        mode = PayloadMode.HTTP_PAYLOAD,
        tag = "PAYLOAD",
        sendLabel = "HTTP Payload",
        tcpHost = tcpHost,
        tcpPort = tcpPort,
        partTexts = partTexts,
      )
      socket.soTimeout = 0
      return socket
    } catch (e: IOException) {
      runCatching { socket.close() }
      throw e
    } catch (e: Exception) {
      runCatching { socket.close() }
      throw IOException("HTTP Payload ချိတ်ဆက်မှု မအောင်မြင်ပါ: ${e.message ?: "unknown"}", e)
    } finally {
      if (activeRawSocket === socket) activeRawSocket = null
    }
  }

  @Throws(IOException::class)
  private fun connectWithWebSocketUpgrade(sshHost: String, sshPort: Int): Socket {
    val useProxy = profile.proxyHost.isNotBlank()
    val tcpHost = if (useProxy) profile.proxyHost.trim() else sshHost
    val tcpPort = if (useProxy) profile.proxyPort.coerceIn(1, 65535) else sshPort

    val partTexts =
      PayloadInjector.buildPayloadPartTexts(
        template = profile.payloadTemplate,
        host = sshHost,
        port = sshPort,
        sni = profile.sniHost,
        extraHeaders = profile.extraHeaders,
      )
    if (partTexts.isEmpty()) {
      throw IOException("WebSocket Payload စာသား မထည့်ရသေးပါ — Profile ပြင်ဆင်ပြီး Payload ထည့်သွင်းပါ")
    }

    val socket = openTcpSocket(tcpHost, tcpPort)
    try {
      sendPayloadPartsAndAwaitSuccess(
        socket = socket,
        mode = PayloadMode.WEBSOCKET,
        tag = "WS",
        sendLabel = "WebSocket Upgrade Payload",
        tcpHost = tcpHost,
        tcpPort = tcpPort,
        partTexts = partTexts,
      )
      socket.soTimeout = 0
      return socket
    } catch (e: IOException) {
      runCatching { socket.close() }
      throw e
    } catch (e: Exception) {
      runCatching { socket.close() }
      throw IOException("WebSocket Upgrade မအောင်မြင်ပါ: ${e.message ?: "unknown"}", e)
    } finally {
      if (activeRawSocket === socket) activeRawSocket = null
    }
  }

  @Throws(IOException::class)
  private fun connectWithSslTlsSni(sshHost: String, sshPort: Int): Socket {
    val useProxy = profile.proxyHost.isNotBlank()
    val tcpHost = if (useProxy) profile.proxyHost.trim() else sshHost
    val tcpPort = if (useProxy) profile.proxyPort.coerceIn(1, 65535) else sshPort

    val rawSocket = openTcpSocket(tcpHost, tcpPort)
    var sslSocket: SSLSocket? = null
    try {
      // Optional pre-TLS HTTP payload if the user configured a payload template
      if (profile.payloadTemplate.isNotBlank()) {
        val partTexts =
          PayloadInjector.buildPayloadPartTexts(
            template = profile.payloadTemplate,
            host = sshHost,
            port = sshPort,
            sni = profile.sniHost,
            extraHeaders = profile.extraHeaders,
          )
        if (partTexts.isNotEmpty()) {
          sendPayloadPartsAndAwaitSuccess(
            socket = rawSocket,
            mode = PayloadMode.SSL_TLS_SNI,
            tag = "PAYLOAD",
            sendLabel = "TLS မတိုင်မီ HTTP Payload",
            tcpHost = tcpHost,
            tcpPort = tcpPort,
            partTexts = partTexts,
          )
        }
      }

      if (aborted) {
        throw IOException("ချိတ်ဆက်မှုကို အသုံးပြုသူမှ ပယ်ဖျက်လိုက်ပါပြီ")
      }

      val effectiveSni = PayloadInjector.resolveSniHost(profile.sniHost, sshHost)
      val wrapped =
        sslSocketFactory.createSocket(rawSocket, effectiveSni, sshPort, true) as SSLSocket
      sslSocket = wrapped
      activeSslSocket = wrapped
      wrapped.soTimeout = handshakeTimeoutMs
      wrapped.useClientMode = true

      val sslParams = wrapped.sslParameters
      if (PayloadInjector.isValidDnsSniHostname(effectiveSni)) {
        sslParams.serverNames = listOf(SNIHostName(effectiveSni))
      } else if (effectiveSni.isNotBlank() && !redactHostLogs) {
        logStore.append(
          tag = "SSL",
          message = "သတိပေးချက်: '$effectiveSni' သည် IP လိပ်စာဖြစ်သဖြင့် SNI Hostname extension မထည့်ဘဲ TLS ဆက်လက်လုပ်ဆောင်မည်",
          level = LogLevel.WARNING,
        )
      }
      wrapped.sslParameters = sslParams

      logStore.append(
        tag = "SSL",
        message =
          if (redactHostLogs) {
            "TLS/SSL Handshake စတင်နေသည်..."
          } else {
            "TLS/SSL Handshake စတင်နေသည် (SNI: ${effectiveSni.ifBlank { "none" }} -> $sshHost:$sshPort)..."
          },
        level = LogLevel.INFO,
      )

      wrapped.startHandshake()

      val tlsSession = wrapped.session
      if (tlsSession == null || !tlsSession.isValid || tlsSession.protocol.equals("NONE", ignoreCase = true)) {
        val errSuffix = if (redactHostLogs) "" else " (SNI: $effectiveSni)"
        throw IOException("TLS/SSL Handshake အတည်ပြုမှု မအောင်မြင်ပါ$errSuffix")
      }

      logStore.append(
        tag = "SSL",
        message =
          if (redactHostLogs) {
            "TLS/SSL Handshake အောင်မြင်ပြီးပါပြီ (${tlsSession.protocol})"
          } else {
            "TLS/SSL Handshake အောင်မြင်ပြီးပါပြီ (SNI: ${effectiveSni.ifBlank { sshHost }} • ${tlsSession.protocol})"
          },
        level = LogLevel.SUCCESS,
      )

      wrapped.soTimeout = 0
      return wrapped
    } catch (e: SSLHandshakeException) {
      runCatching { sslSocket?.close() }
      runCatching { rawSocket.close() }
      if (aborted) {
        throw IOException("ချိတ်ဆက်မှုကို အသုံးပြုသူမှ ပယ်ဖျက်လိုက်ပါပြီ", e)
      }
      throw IOException(
        "TLS/SSL Handshake မအောင်မြင်ပါ (${e.message ?: "certificate or SNI error"})",
        e,
      )
    } catch (e: IOException) {
      runCatching { sslSocket?.close() }
      runCatching { rawSocket.close() }
      throw e
    } catch (e: Exception) {
      runCatching { sslSocket?.close() }
      runCatching { rawSocket.close() }
      throw IOException("TLS/SSL ချိတ်ဆက်မှု မအောင်မြင်ပါ: ${e.message ?: "unknown"}", e)
    } finally {
      if (activeSslSocket === sslSocket) activeSslSocket = null
      if (activeRawSocket === rawSocket) activeRawSocket = null
    }
  }

  @Throws(IOException::class)
  private fun sendPayloadPartsAndAwaitSuccess(
    socket: Socket,
    mode: PayloadMode,
    tag: String,
    sendLabel: String,
    tcpHost: String,
    tcpPort: Int,
    partTexts: List<String>,
  ) {
    val partsBytes = partTexts.map { it.toByteArray(Charsets.ISO_8859_1) }
    val totalBytes = partsBytes.sumOf { it.size }
    val combinedText = partTexts.joinToString(separator = "")
    val expectWsUpgrade = PayloadInjector.requiresWebSocketUpgrade(mode, combinedText)

    val output = socket.getOutputStream()
    for ((index, chunk) in partsBytes.withIndex()) {
      if (aborted) {
        throw IOException("ချိတ်ဆက်မှုကို အသုံးပြုသူမှ ပယ်ဖျက်လိုက်ပါပြီ")
      }
      output.write(chunk)
      output.flush()
      if (index < partsBytes.lastIndex && splitDelayMs > 0L) {
        sleepInterruptibly(splitDelayMs)
      }
    }

    val splitNote =
      if (partsBytes.size > 1) " • split ${partsBytes.size} အပိုင်း" else ""
    logStore.append(
      tag = tag,
      message =
        if (redactHostLogs) {
          "$sendLabel ပေးပို့ပြီးပါပြီ ($totalBytes bytes$splitNote)"
        } else {
          "$sendLabel ပေးပို့ပြီးပါပြီ ($tcpHost:$tcpPort • $totalBytes bytes$splitNote)"
        },
      level = LogLevel.INFO,
    )

    PayloadInjector.readUntilSuccessStatus(
      input = socket.getInputStream(),
      mode = mode,
      expectWebSocketUpgrade = expectWsUpgrade,
      isAborted = { aborted },
      onStatusLine = { status, isSuccess ->
        if (isSuccess) {
          logStore.append(
            tag = tag,
            message = "တုံ့ပြန်ချက်: ${status.rawStatusLine} — အောင်မြင်ပါပြီ",
            level = LogLevel.SUCCESS,
          )
        } else {
          logStore.append(
            tag = tag,
            message = "တုံ့ပြန်ချက်: ${status.rawStatusLine} — ဆက်စောင့်နေသည်",
            level = LogLevel.INFO,
          )
        }
      },
    )
  }

  @Throws(IOException::class)
  private fun sleepInterruptibly(durationMs: Long) {
    val deadline = System.nanoTime() + durationMs * 1_000_000L
    while (true) {
      if (aborted) {
        throw IOException("ချိတ်ဆက်မှုကို အသုံးပြုသူမှ ပယ်ဖျက်လိုက်ပါပြီ")
      }
      val remainingMs = (deadline - System.nanoTime()) / 1_000_000L
      if (remainingMs <= 0L) return
      try {
        Thread.sleep(remainingMs.coerceAtMost(20L))
      } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw IOException("ချိတ်ဆက်မှုကို အသုံးပြုသူမှ ပယ်ဖျက်လိုက်ပါပြီ", e)
      }
    }
  }

  companion object {
    const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000
    const val DEFAULT_HANDSHAKE_TIMEOUT_MS = 15_000
    const val DEFAULT_SPLIT_DELAY_MS = 120L
  }
}
