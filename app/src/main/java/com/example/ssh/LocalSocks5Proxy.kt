package com.example.ssh

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SshTrafficStats(
  val bytesUp: Long = 0L,
  val bytesDown: Long = 0L,
  val activeStreams: Int = 0,
)

interface TunnelChannel : Closeable {
  val inputStream: InputStream
  val outputStream: OutputStream
}

fun interface TunnelConnector {
  @Throws(IOException::class)
  fun openChannel(targetHost: String, targetPort: Int, clientPort: Int): TunnelChannel
}

/**
 * RFC 1928 local SOCKS5 TCP proxy bound strictly to 127.0.0.1.
 *
 * Each accepted SOCKS5 CONNECT request opens a dynamic tunnel stream through
 * [connector] (backed by JSch ChannelDirectTCPIP in production) and relays bytes
 * bidirectionally until either side closes or [stop] is called.
 */
class LocalSocks5Proxy(
  private val connector: TunnelConnector,
  private val onLog: (String) -> Unit = {},
) {
  private val running = AtomicBoolean(false)
  private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()
  private val activeChannels = ConcurrentHashMap.newKeySet<TunnelChannel>()
  private val bytesUpCounter = AtomicLong(0L)
  private val bytesDownCounter = AtomicLong(0L)
  private val activeStreamsCounter = AtomicInteger(0)

  private val _trafficStats = MutableStateFlow(SshTrafficStats())
  val trafficStats: StateFlow<SshTrafficStats> = _trafficStats.asStateFlow()

  @Volatile private var serverSocket: ServerSocket? = null
  @Volatile private var executor: ExecutorService? = null

  val boundHost: String = LOOPBACK_HOST
  @Volatile
  var boundPort: Int = 0
    private set

  @Throws(IOException::class)
  fun start(preferredPort: Int = DEFAULT_PORT): Int {
    if (running.get()) return boundPort

    val loopback = InetAddress.getByName(LOOPBACK_HOST)
    val socket = bindPreferredOrFallback(loopback, preferredPort)
    serverSocket = socket
    boundPort = socket.localPort
    bytesUpCounter.set(0L)
    bytesDownCounter.set(0L)
    activeStreamsCounter.set(0)
    publishStats()

    val pool =
      Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "amkt-socks5-worker").apply { isDaemon = true }
      }
    executor = pool
    running.set(true)

    pool.execute { acceptLoop(socket, pool) }
    return boundPort
  }

  fun stop() {
    if (!running.compareAndSet(true, false)) {
      closeQuietly(serverSocket)
      serverSocket = null
      boundPort = 0
      return
    }

    closeQuietly(serverSocket)
    serverSocket = null
    boundPort = 0

    activeSockets.forEach { closeQuietly(it) }
    activeSockets.clear()

    activeChannels.forEach { closeQuietly(it) }
    activeChannels.clear()

    executor?.shutdownNow()
    executor = null

    activeStreamsCounter.set(0)
    publishStats()
  }

  private fun bindPreferredOrFallback(loopback: InetAddress, preferredPort: Int): ServerSocket {
    val portsToTry =
      if (preferredPort == 0) listOf(0)
      else (preferredPort..(preferredPort + 9)).toList()

    var lastError: IOException? = null
    for (candidate in portsToTry) {
      val s = ServerSocket()
      try {
        s.reuseAddress = true
        s.bind(InetSocketAddress(loopback, candidate), 64)
        return s
      } catch (e: IOException) {
        closeQuietly(s)
        lastError = e
      }
    }
    throw lastError ?: IOException("Unable to bind SOCKS5 proxy on 127.0.0.1:$preferredPort")
  }

  private fun acceptLoop(server: ServerSocket, pool: ExecutorService) {
    while (running.get() && !server.isClosed) {
      try {
        val client = server.accept()
        client.tcpNoDelay = true
        client.soTimeout = HANDSHAKE_TIMEOUT_MS
        activeSockets.add(client)
        pool.execute { handleClient(client, pool) }
      } catch (_: SocketException) {
        break
      } catch (_: IOException) {
        if (!running.get()) break
      }
    }
  }

  private fun handleClient(client: Socket, pool: ExecutorService) {
    var channel: TunnelChannel? = null
    var streamCounted = false
    try {
      val input = DataInputStream(BufferedInputStream(client.getInputStream()))
      val output = client.getOutputStream()

      // 1. RFC 1928 Greeting: VER | NMETHODS | METHODS
      val version = input.readUnsignedByte()
      if (version != SOCKS_VERSION_5) {
        return
      }
      val methodCount = input.readUnsignedByte()
      val methods = ByteArray(methodCount)
      input.readFully(methods)

      val supportsNoAuth = methods.any { it == AUTH_NONE }
      if (!supportsNoAuth) {
        output.write(byteArrayOf(SOCKS_VERSION_5.toByte(), AUTH_NO_ACCEPTABLE))
        output.flush()
        return
      }
      output.write(byteArrayOf(SOCKS_VERSION_5.toByte(), AUTH_NONE))
      output.flush()

      // 2. RFC 1928 Request: VER | CMD | RSV | ATYP | DST.ADDR | DST.PORT
      val reqVer = input.readUnsignedByte()
      val cmd = input.readUnsignedByte()
      input.readUnsignedByte() // RSV
      val atyp = input.readUnsignedByte()

      if (reqVer != SOCKS_VERSION_5) return
      if (cmd != CMD_CONNECT) {
        sendReply(output, REP_COMMAND_NOT_SUPPORTED)
        return
      }

      val targetHost =
        when (atyp) {
          ATYP_IPV4 -> {
            val addr = ByteArray(4)
            input.readFully(addr)
            InetAddress.getByAddress(addr).hostAddress ?: return
          }
          ATYP_DOMAIN -> {
            val len = input.readUnsignedByte()
            val domainBytes = ByteArray(len)
            input.readFully(domainBytes)
            String(domainBytes, Charsets.US_ASCII)
          }
          ATYP_IPV6 -> {
            val addr = ByteArray(16)
            input.readFully(addr)
            InetAddress.getByAddress(addr).hostAddress ?: return
          }
          else -> {
            sendReply(output, REP_ADDRESS_TYPE_NOT_SUPPORTED)
            return
          }
        }

      val targetPort = input.readUnsignedShort()
      if (targetPort <= 0) {
        sendReply(output, REP_GENERAL_FAILURE)
        return
      }

      // 3. Open SSH direct-tcpip tunnel channel
      channel =
        try {
          connector.openChannel(targetHost, targetPort, client.port)
        } catch (e: Exception) {
          sendReply(output, REP_HOST_UNREACHABLE)
          onLog("SOCKS5 ချိတ်ဆက်မှု မအောင်မြင်ပါ ($targetHost:$targetPort): ${e.message ?: "error"}")
          return
        }

      activeChannels.add(channel)
      client.soTimeout = 0 // Unlimited idle read timeout once tunnel is streaming
      sendReply(output, REP_SUCCEEDED)

      activeStreamsCounter.incrementAndGet()
      streamCounted = true
      publishStats()

      val remoteIn = channel.inputStream
      val remoteOut = channel.outputStream

      // 4. Bidirectional relay
      val uploadFuture =
        pool.submit {
          relayStream(
            source = input,
            destination = remoteOut,
            counter = bytesUpCounter,
          )
          runCatching { remoteOut.close() }
        }

      relayStream(
        source = remoteIn,
        destination = output,
        counter = bytesDownCounter,
      )
      runCatching { client.shutdownOutput() }
      runCatching { uploadFuture.get() }
    } catch (_: EOFException) {
      // Client closed cleanly
    } catch (_: IOException) {
      // Socket or SSH stream closed during disconnect
    } finally {
      if (streamCounted) {
        activeStreamsCounter.decrementAndGet().coerceAtLeast(0)
        publishStats()
      }
      if (channel != null) {
        activeChannels.remove(channel)
        closeQuietly(channel)
      }
      activeSockets.remove(client)
      closeQuietly(client)
    }
  }

  private fun relayStream(
    source: InputStream,
    destination: OutputStream,
    counter: AtomicLong,
  ) {
    val buffer = ByteArray(BUFFER_SIZE)
    try {
      while (running.get()) {
        val read = source.read(buffer)
        if (read == -1) break
        if (read > 0) {
          destination.write(buffer, 0, read)
          destination.flush()
          counter.addAndGet(read.toLong())
          publishStats()
        }
      }
    } catch (_: IOException) {
      // Normal teardown when either peer closes
    }
  }

  private fun sendReply(output: OutputStream, replyCode: Byte) {
    val reply =
      byteArrayOf(
        SOCKS_VERSION_5.toByte(),
        replyCode,
        0x00, // RSV
        ATYP_IPV4.toByte(),
        0x00,
        0x00,
        0x00,
        0x00, // BND.ADDR 0.0.0.0
        0x00,
        0x00, // BND.PORT 0
      )
    output.write(reply)
    output.flush()
  }

  private fun publishStats() {
    _trafficStats.value =
      SshTrafficStats(
        bytesUp = bytesUpCounter.get(),
        bytesDown = bytesDownCounter.get(),
        activeStreams = activeStreamsCounter.get().coerceAtLeast(0),
      )
  }

  private fun closeQuietly(closeable: Closeable?) {
    if (closeable == null) return
    try {
      closeable.close()
    } catch (_: Exception) {}
  }

  companion object {
    const val LOOPBACK_HOST = "127.0.0.1"
    const val DEFAULT_PORT = 1080
    private const val HANDSHAKE_TIMEOUT_MS = 10_000
    private const val BUFFER_SIZE = 16 * 1024

    private const val SOCKS_VERSION_5 = 0x05
    private const val AUTH_NONE: Byte = 0x00
    private const val AUTH_NO_ACCEPTABLE: Byte = 0xFF.toByte()

    private const val CMD_CONNECT = 0x01
    private const val ATYP_IPV4 = 0x01
    private const val ATYP_DOMAIN = 0x03
    private const val ATYP_IPV6 = 0x04

    private const val REP_SUCCEEDED: Byte = 0x00
    private const val REP_GENERAL_FAILURE: Byte = 0x01
    private const val REP_HOST_UNREACHABLE: Byte = 0x04
    private const val REP_COMMAND_NOT_SUPPORTED: Byte = 0x07
    private const val REP_ADDRESS_TYPE_NOT_SUPPORTED: Byte = 0x08
  }
}
