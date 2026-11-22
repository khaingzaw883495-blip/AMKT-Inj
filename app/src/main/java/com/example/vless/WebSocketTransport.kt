package com.example.vless

import com.example.config.Base64Compat
import com.example.ssh.PayloadInjector
import com.example.ssh.TunnelChannel
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Pure RFC 6455 binary WebSocket client transport supporting plain TCP (`security=none`)
 * and TLS + SNI (`security=tls`), designed for streaming VLESS v0 channels directly through
 * [com.example.ssh.LocalSocks5Proxy] with zero extra third-party dependencies.
 */
class WebSocketTransport(
  private val sslSocketFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory,
  private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
  private val handshakeTimeoutMs: Int = DEFAULT_HANDSHAKE_TIMEOUT_MS,
) {
  /**
   * Opens a TCP (or TLS+SNI) connection to [config], performs the RFC 6455 HTTP `101` Upgrade
   * handshake, verifies `Sec-WebSocket-Accept`, and returns a ready [RawWebSocketConnection].
   */
  @Throws(IOException::class)
  fun open(
    config: VlessConfig,
    isAborted: () -> Boolean = { false },
    onSocketOpened: (Socket) -> Unit = {},
  ): RawWebSocketConnection {
    if (isAborted()) {
      throw IOException("ချိတ်ဆက်မှုကို အသုံးပြုသူမှ ပယ်ဖျက်လိုက်ပါပြီ")
    }

    val rawSocket = Socket()
    onSocketOpened(rawSocket)
    var activeSocket: Socket = rawSocket
    try {
      rawSocket.tcpNoDelay = true
      rawSocket.connect(InetSocketAddress(config.serverHost, config.serverPort), connectTimeoutMs)
      if (isAborted()) {
        throw IOException("ချိတ်ဆက်မှုကို အသုံးပြုသူမှ ပယ်ဖျက်လိုက်ပါပြီ")
      }
      rawSocket.soTimeout = handshakeTimeoutMs

      if (config.useTls) {
        val effectiveSni = config.effectiveSni
        val sslSocket =
          sslSocketFactory.createSocket(rawSocket, effectiveSni, config.serverPort, true) as SSLSocket
        activeSocket = sslSocket
        onSocketOpened(sslSocket)
        sslSocket.soTimeout = handshakeTimeoutMs
        sslSocket.useClientMode = true

        val sslParams = sslSocket.sslParameters
        if (PayloadInjector.isValidDnsSniHostname(effectiveSni)) {
          sslParams.serverNames = listOf(SNIHostName(effectiveSni))
        }
        sslSocket.sslParameters = sslParams
        sslSocket.startHandshake()

        val session = sslSocket.session
        if (session == null || !session.isValid || session.protocol.equals("NONE", ignoreCase = true)) {
          throw IOException("VLESS TLS/SNI Handshake မအောင်မြင်ပါ (SNI: $effectiveSni)")
        }
      }

      if (isAborted()) {
        throw IOException("ချိတ်ဆက်မှုကို အသုံးပြုသူမှ ပယ်ဖျက်လိုက်ပါပြီ")
      }

      val secKey = generateWebSocketKey()
      val upgradeRequest =
        buildHandshakeRequest(
          wsPath = config.wsPath,
          wsHost = config.effectiveWsHost,
          secWebSocketKey = secKey,
        )

      val output = activeSocket.getOutputStream()
      output.write(upgradeRequest.toByteArray(StandardCharsets.ISO_8859_1))
      output.flush()

      val input = activeSocket.getInputStream()
      val handshakeHeaders = readHandshakeResponseHeaders(input)
      validateUpgradeResponse(handshakeHeaders, secKey)

      activeSocket.soTimeout = 0
      return RawWebSocketConnection(
        socket = activeSocket,
        input = input,
        output = output,
      )
    } catch (e: SSLHandshakeException) {
      runCatching { activeSocket.close() }
      runCatching { rawSocket.close() }
      if (isAborted()) {
        throw IOException("ချိတ်ဆက်မှုကို အသုံးပြုသူမှ ပယ်ဖျက်လိုက်ပါပြီ", e)
      }
      throw IOException("VLESS TLS/SNI Handshake မအောင်မြင်ပါ (${e.message ?: "TLS error"})", e)
    } catch (e: IOException) {
      runCatching { activeSocket.close() }
      runCatching { rawSocket.close() }
      if (isAborted()) {
        throw IOException("ချိတ်ဆက်မှုကို အသုံးပြုသူမှ ပယ်ဖျက်လိုက်ပါပြီ", e)
      }
      throw e
    } catch (e: Exception) {
      runCatching { activeSocket.close() }
      runCatching { rawSocket.close() }
      throw IOException("VLESS WebSocket ချိတ်ဆက်မှု မအောင်မြင်ပါ: ${e.message ?: "unknown"}", e)
    }
  }

  /**
   * Opens a live VLESS v0 stream over WebSocket to [targetHost]:[targetPort] and returns a
   * [TunnelChannel] directly consumable by [com.example.ssh.LocalSocks5Proxy].
   */
  @Throws(IOException::class)
  fun openVlessChannel(
    config: VlessConfig,
    targetHost: String,
    targetPort: Int,
    isAborted: () -> Boolean = { false },
    onSocketOpened: (Socket) -> Unit = {},
  ): TunnelChannel {
    val ws = open(config = config, isAborted = isAborted, onSocketOpened = onSocketOpened)
    return try {
      val vlessHeader =
        VlessProtocol.encodeRequestHeader(
          uuidBytes = config.uuidBytes,
          targetHost = targetHost,
          targetPort = targetPort,
          command = VlessProtocol.COMMAND_TCP,
        )
      ws.writeBinaryFrame(vlessHeader, 0, vlessHeader.size)
      VlessWebSocketChannel(ws)
    } catch (e: Exception) {
      ws.close()
      if (e is IOException) throw e
      throw IOException("VLESS Header ပေးပို့မှု မအောင်မြင်ပါ: ${e.message ?: "unknown"}", e)
    }
  }

  @Throws(IOException::class)
  private fun readHandshakeResponseHeaders(input: InputStream): List<String> {
    val buffer = ByteArrayOutputStream(256)
    var b1 = -1
    var b2 = -1
    var b3 = -1
    var totalRead = 0
    var foundEnd = false

    while (totalRead < MAX_HANDSHAKE_HEADER_BYTES) {
      val next = input.read()
      if (next == -1) break
      buffer.write(next)
      totalRead += 1

      val isCrLfCrLf = b1 == '\r'.code && b2 == '\n'.code && b3 == '\r'.code && next == '\n'.code
      val isLfLf = b3 == '\n'.code && next == '\n'.code
      if (isCrLfCrLf || isLfLf) {
        foundEnd = true
        break
      }

      b1 = b2
      b2 = b3
      b3 = next
    }

    if (totalRead == 0) {
      throw IOException("WebSocket ဆာဗာမှ တုံ့ပြန်ချက် မရရှိဘဲ ချိတ်ဆက်မှု ပိတ်သွားပါသည် (EOF)")
    }
    if (!foundEnd && totalRead >= MAX_HANDSHAKE_HEADER_BYTES) {
      throw IOException("WebSocket Upgrade Header အရွယ်အစား ကျော်လွန်နေပါသည်")
    }

    val text = String(buffer.toByteArray(), StandardCharsets.ISO_8859_1)
    return text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
  }

  @Throws(IOException::class)
  private fun validateUpgradeResponse(lines: List<String>, secWebSocketKey: String) {
    val statusLine = lines.firstOrNull().orEmpty()
    if (!statusLine.startsWith("HTTP/", ignoreCase = true)) {
      throw IOException("WebSocket တုံ့ပြန်ချက် ပုံစံ မမှန်ကန်ပါ: ${statusLine.take(80)}")
    }
    val parts = statusLine.split(Regex("\\s+"), limit = 3)
    val statusCode = parts.getOrNull(1)?.toIntOrNull() ?: -1
    if (statusCode != 101) {
      throw IOException(
        "WebSocket Upgrade မအောင်မြင်ပါ — HTTP 101 လိုအပ်သော်လည်း ရရှိချက်: $statusLine"
      )
    }

    val expectedAccept = computeAcceptKey(secWebSocketKey)
    val serverAccept =
      lines
        .drop(1)
        .firstOrNull { it.startsWith("Sec-WebSocket-Accept:", ignoreCase = true) }
        ?.substringAfter(':')
        ?.trim()
    if (serverAccept != null && serverAccept != expectedAccept) {
      throw IOException("WebSocket Sec-WebSocket-Accept အတည်ပြုချက် မကိုက်ညီပါ")
    }
  }

  companion object {
    const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000
    const val DEFAULT_HANDSHAKE_TIMEOUT_MS = 15_000
    private const val MAX_HANDSHAKE_HEADER_BYTES = 16_384
    private const val WS_MAGIC_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    private val secureRandom = SecureRandom()

    fun generateWebSocketKey(): String {
      val nonce = ByteArray(16)
      secureRandom.nextBytes(nonce)
      return Base64Compat.encode(nonce)
    }

    fun computeAcceptKey(secWebSocketKey: String): String {
      val sha1 = MessageDigest.getInstance("SHA-1")
      val digest = sha1.digest((secWebSocketKey.trim() + WS_MAGIC_GUID).toByteArray(StandardCharsets.US_ASCII))
      return Base64Compat.encode(digest)
    }

    /**
     * Builds an RFC 6455 HTTP/1.1 WebSocket Upgrade request string.
     */
    fun buildHandshakeRequest(
      wsPath: String,
      wsHost: String,
      secWebSocketKey: String,
      userAgent: String = PayloadInjector.DEFAULT_USER_AGENT,
    ): String {
      val cleanPath = VlessConfigParser.normalizeWebSocketPath(wsPath)
      val cleanHost = wsHost.trim()
      return buildString {
        append("GET ").append(cleanPath).append(" HTTP/1.1\r\n")
        append("Host: ").append(cleanHost).append("\r\n")
        append("Upgrade: websocket\r\n")
        append("Connection: Upgrade\r\n")
        append("Sec-WebSocket-Key: ").append(secWebSocketKey.trim()).append("\r\n")
        append("Sec-WebSocket-Version: 13\r\n")
        append("User-Agent: ").append(userAgent).append("\r\n")
        append("\r\n")
      }
    }
  }
}

/**
 * Low-level RFC 6455 framed WebSocket connection over a live [Socket].
 * Client frames are ALWAYS masked with a random 4-byte masking key per RFC 6455 §5.1.
 */
class RawWebSocketConnection(
  private val socket: Socket,
  private val input: InputStream,
  private val output: OutputStream,
) : Closeable {
  private val writeLock = Any()
  private val closed = AtomicBoolean(false)
  private val maskRandom = SecureRandom()

  @Throws(IOException::class)
  fun writeBinaryFrame(data: ByteArray, offset: Int, length: Int) {
    if (closed.get()) throw IOException("WebSocket closed")
    var pos = offset
    var remaining = length
    while (remaining > 0) {
      val chunkLen = remaining.coerceAtMost(MAX_OUTBOUND_FRAME_BYTES)
      writeMaskedFrame(opcode = OPCODE_BINARY, payload = data, offset = pos, length = chunkLen)
      pos += chunkLen
      remaining -= chunkLen
    }
  }

  /**
   * Reads the next application binary/text payload from the server.
   * Automatically handles RFC 6455 Ping/Pong control frames and returns `null` on Close/EOF.
   */
  @Throws(IOException::class)
  fun readBinaryPayload(): ByteArray? {
    while (!closed.get()) {
      val b0 = input.read()
      if (b0 == -1) return null
      val b1 = input.read()
      if (b1 == -1) return null

      val opcode = b0 and 0x0F
      val masked = (b1 and 0x80) != 0
      var payloadLen = (b1 and 0x7F).toLong()

      if (payloadLen == 126L) {
        val hi = readUnsignedByteOrThrow()
        val lo = readUnsignedByteOrThrow()
        payloadLen = ((hi shl 8) or lo).toLong()
      } else if (payloadLen == 127L) {
        var len64 = 0L
        for (i in 0 until 8) {
          len64 = (len64 shl 8) or readUnsignedByteOrThrow().toLong()
        }
        payloadLen = len64
      }

      if (payloadLen < 0L || payloadLen > MAX_INBOUND_FRAME_BYTES) {
        throw IOException("WebSocket frame payload too large: $payloadLen bytes")
      }

      val maskKey =
        if (masked) {
          ByteArray(4).also { readFully(it) }
        } else {
          null
        }

      val payload = ByteArray(payloadLen.toInt())
      if (payload.isNotEmpty()) {
        readFully(payload)
        if (maskKey != null) {
          for (i in payload.indices) {
            payload[i] = (payload[i].toInt() xor maskKey[i and 3].toInt()).toByte()
          }
        }
      }

      when (opcode) {
        OPCODE_BINARY, OPCODE_TEXT, OPCODE_CONTINUATION -> {
          if (payload.isNotEmpty()) return payload
        }
        OPCODE_PING -> {
          // Reply with masked Pong carrying the exact Ping application data
          runCatching { writeMaskedFrame(OPCODE_PONG, payload, 0, payload.size) }
        }
        OPCODE_PONG -> {
          // Heartbeat acknowledged; continue reading
        }
        OPCODE_CLOSE -> {
          runCatching { sendCloseFrame() }
          return null
        }
        else -> {
          // Ignore unknown extension opcodes
        }
      }
    }
    return null
  }

  fun sendCloseFrame() {
    runCatching { writeMaskedFrame(OPCODE_CLOSE, ByteArray(0), 0, 0) }
  }

  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    runCatching { sendCloseFrame() }
    runCatching { socket.close() }
  }

  @Throws(IOException::class)
  private fun writeMaskedFrame(opcode: Int, payload: ByteArray, offset: Int, length: Int) {
    val mask = ByteArray(4)
    maskRandom.nextBytes(mask)

    val headerLen =
      when {
        length < 126 -> 2 + 4
        length <= 0xFFFF -> 4 + 4
        else -> 10 + 4
      }
    val frame = ByteArray(headerLen + length)
    var pos = 0
    frame[pos++] = (0x80 or (opcode and 0x0F)).toByte() // FIN=1 | opcode
    when {
      length < 126 -> {
        frame[pos++] = (0x80 or length).toByte() // MASK=1 | 7-bit len
      }
      length <= 0xFFFF -> {
        frame[pos++] = (0x80 or 126).toByte()
        frame[pos++] = ((length ushr 8) and 0xFF).toByte()
        frame[pos++] = (length and 0xFF).toByte()
      }
      else -> {
        frame[pos++] = (0x80 or 127).toByte()
        val len64 = length.toLong()
        for (shift in 56 downTo 0 step 8) {
          frame[pos++] = ((len64 ushr shift) and 0xFFL).toByte()
        }
      }
    }
    System.arraycopy(mask, 0, frame, pos, 4)
    pos += 4

    for (i in 0 until length) {
      frame[pos + i] = (payload[offset + i].toInt() xor mask[i and 3].toInt()).toByte()
    }

    synchronized(writeLock) {
      output.write(frame)
      output.flush()
    }
  }

  @Throws(IOException::class)
  private fun readUnsignedByteOrThrow(): Int {
    val b = input.read()
    if (b == -1) throw EOFException("Unexpected EOF in WebSocket frame header")
    return b
  }

  @Throws(IOException::class)
  private fun readFully(dest: ByteArray) {
    var readTotal = 0
    while (readTotal < dest.size) {
      val n = input.read(dest, readTotal, dest.size - readTotal)
      if (n == -1) throw EOFException("Unexpected EOF in WebSocket frame payload")
      readTotal += n
    }
  }

  companion object {
    private const val OPCODE_CONTINUATION = 0x0
    private const val OPCODE_TEXT = 0x1
    private const val OPCODE_BINARY = 0x2
    private const val OPCODE_CLOSE = 0x8
    private const val OPCODE_PING = 0x9
    private const val OPCODE_PONG = 0xA

    private const val MAX_OUTBOUND_FRAME_BYTES = 16 * 1024
    private const val MAX_INBOUND_FRAME_BYTES = 4 * 1024 * 1024L
  }
}

/**
 * Adapts a [RawWebSocketConnection] carrying a VLESS v0 stream into a [TunnelChannel] for
 * [com.example.ssh.LocalSocks5Proxy]: strips the VLESS response header on first read and
 * wraps every write into masked RFC 6455 binary frames.
 */
internal class VlessWebSocketChannel(
  private val ws: RawWebSocketConnection,
) : TunnelChannel {

  override val inputStream: InputStream = VlessWebSocketInputStream(ws)
  override val outputStream: OutputStream = VlessWebSocketOutputStream(ws)

  override fun close() {
    ws.close()
  }

  private class VlessWebSocketInputStream(
    private val ws: RawWebSocketConnection,
  ) : InputStream() {
    private var currentChunk: ByteArray = ByteArray(0)
    private var chunkOffset: Int = 0
    private var responseHeaderConsumed: Boolean = false
    private var eof: Boolean = false

    private val rawFrameStream =
      object : InputStream() {
        override fun read(): Int {
          val single = ByteArray(1)
          val n = read(single, 0, 1)
          return if (n <= 0) -1 else (single[0].toInt() and 0xFF)
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
          if (len == 0) return 0
          if (eof) return -1
          while (chunkOffset >= currentChunk.size) {
            val next = ws.readBinaryPayload()
            if (next == null) {
              eof = true
              return -1
            }
            currentChunk = next
            chunkOffset = 0
          }
          val available = currentChunk.size - chunkOffset
          val toCopy = len.coerceAtMost(available)
          System.arraycopy(currentChunk, chunkOffset, b, off, toCopy)
          chunkOffset += toCopy
          return toCopy
        }
      }

    override fun read(): Int {
      val single = ByteArray(1)
      val n = read(single, 0, 1)
      return if (n <= 0) -1 else (single[0].toInt() and 0xFF)
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
      if (len == 0) return 0
      if (!responseHeaderConsumed) {
        VlessProtocol.decodeResponseHeader(rawFrameStream)
        responseHeaderConsumed = true
      }
      return rawFrameStream.read(b, off, len)
    }

    override fun close() {
      ws.close()
    }
  }

  private class VlessWebSocketOutputStream(
    private val ws: RawWebSocketConnection,
  ) : OutputStream() {
    override fun write(b: Int) {
      ws.writeBinaryFrame(byteArrayOf(b.toByte()), 0, 1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
      if (len <= 0) return
      ws.writeBinaryFrame(b, off, len)
    }

    override fun close() {
      ws.sendCloseFrame()
    }
  }
}
