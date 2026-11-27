package com.example.ssh

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
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

data class UdpDatagram(
  val host: String,
  val port: Int,
  val payload: ByteArray,
)

interface UdpTunnelSession : Closeable {
  @Throws(IOException::class)
  fun sendDatagram(targetHost: String, targetPort: Int, payload: ByteArray)

  @Throws(IOException::class)
  fun receiveDatagram(): UdpDatagram?
}

fun interface UdpTunnelConnector {
  @Throws(IOException::class)
  fun openUdpSession(): UdpTunnelSession
}

/**
 * RFC 1928 local SOCKS5 TCP + UDP ASSOCIATE proxy bound strictly to 127.0.0.1.
 *
 * - Supports TCP `CONNECT` (0x01) for IPv4, Domain, and IPv6, with automatic leak-free
 *   tunneled IPv4 fallback when an IPv4-only server rejects IPv6 destinations (e.g. Facebook
 *   `2a03:2880:...` returning `channel is not opened`).
 * - Supports RFC 1928 `UDP ASSOCIATE` (0x03) via [udpConnector], defaulting to leak-free
 *   tunneled DNS-over-TCP on port 53 through [connector].
 */
class LocalSocks5Proxy(
  private val connector: TunnelConnector,
  private val onLog: (String) -> Unit = {},
  private val udpConnector: UdpTunnelConnector? = null,
) {
  private val running = AtomicBoolean(false)
  private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()
  private val activeChannels = ConcurrentHashMap.newKeySet<TunnelChannel>()
  private val activeUdpSockets = ConcurrentHashMap.newKeySet<DatagramSocket>()
  private val activeUdpSessions = ConcurrentHashMap.newKeySet<UdpTunnelSession>()
  private val bytesUpCounter = AtomicLong(0L)
  private val bytesDownCounter = AtomicLong(0L)
  private val activeStreamsCounter = AtomicInteger(0)
  private val hasObservedIpv6Reject = AtomicBoolean(false)

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
    hasObservedIpv6Reject.set(false)
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

    activeUdpSockets.forEach { runCatching { it.close() } }
    activeUdpSockets.clear()

    activeUdpSessions.forEach { closeQuietly(it) }
    activeUdpSessions.clear()

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
    var udpSocket: DatagramSocket? = null
    var udpSession: UdpTunnelSession? = null
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
      if (cmd != CMD_CONNECT && cmd != CMD_UDP_ASSOCIATE) {
        sendReply(output, REP_COMMAND_NOT_SUPPORTED)
        return
      }

      val rawTargetHost =
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
      val targetHost = TunneledDnsResolver.normalizeHostLiteral(rawTargetHost)

      // Handle RFC 1928 UDP ASSOCIATE (0x03)
      if (cmd == CMD_UDP_ASSOCIATE) {
        val relay = bindLoopbackUdpSocket()
        udpSocket = relay
        activeUdpSockets.add(relay)

        val session =
          try {
            (udpConnector ?: defaultDnsOnlyUdpConnector()).openUdpSession()
          } catch (e: Exception) {
            sendReply(output, REP_GENERAL_FAILURE)
            onLog("SOCKS5 UDP session စတင်မရပါ: ${e.message ?: "error"}")
            return
          }
        udpSession = session
        activeUdpSessions.add(session)

        client.soTimeout = 0
        sendUdpAssociateReply(output, relay.localPort)

        activeStreamsCounter.incrementAndGet()
        streamCounted = true
        publishStats()

        handleUdpAssociate(
          controlSocket = client,
          controlInput = input,
          udpSocket = relay,
          udpSession = session,
          pool = pool,
        )
        return
      }

      if (targetPort <= 0) {
        sendReply(output, REP_GENERAL_FAILURE)
        return
      }

      // 3. Open tunnel TCP channel (with tunneled IPv4 recovery if IPv6 target is rejected)
      channel =
        try {
          openChannelWithIpv4Recovery(
            atyp = atyp,
            targetHost = targetHost,
            targetPort = targetPort,
            clientPort = client.port,
          )
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
      // Socket or tunnel stream closed during disconnect
    } finally {
      if (streamCounted) {
        activeStreamsCounter.decrementAndGet().coerceAtLeast(0)
        publishStats()
      }
      if (udpSession != null) {
        activeUdpSessions.remove(udpSession)
        closeQuietly(udpSession)
      }
      if (udpSocket != null) {
        activeUdpSockets.remove(udpSocket)
        runCatching { udpSocket.close() }
      }
      if (channel != null) {
        activeChannels.remove(channel)
        closeQuietly(channel)
      }
      activeSockets.remove(client)
      closeQuietly(client)
    }
  }

  @Throws(IOException::class)
  private fun openChannelWithIpv4Recovery(
    atyp: Int,
    targetHost: String,
    targetPort: Int,
    clientPort: Int,
  ): TunnelChannel {
    val isIpv6Target = TunneledDnsResolver.isIpv6Literal(targetHost)
    if (isIpv6Target && hasObservedIpv6Reject.get()) {
      val cachedOrResolvedIpv4 =
        TunneledDnsResolver.getCachedIpv4ForIpv6(targetHost)
          ?: TunneledDnsResolver.resolveIpv6ToIpv4ViaTunnel(connector, targetHost)
      if (cachedOrResolvedIpv4 != null) {
        return connector.openChannel(cachedOrResolvedIpv4, targetPort, clientPort)
      }
    }

    return try {
      connector.openChannel(targetHost, targetPort, clientPort)
    } catch (primaryError: IOException) {
      if (isIpv6Target) {
        hasObservedIpv6Reject.set(true)
        val fallbackIpv4 = TunneledDnsResolver.resolveIpv6ToIpv4ViaTunnel(connector, targetHost)
        if (fallbackIpv4 != null) {
          return connector.openChannel(fallbackIpv4, targetPort, clientPort)
        }
      } else if (atyp == ATYP_DOMAIN && !targetHost.contains(':')) {
        val fallbackIpv4 = TunneledDnsResolver.resolveIpv4ViaTunnel(connector, targetHost)
        if (fallbackIpv4 != null) {
          return connector.openChannel(fallbackIpv4, targetPort, clientPort)
        }
      }
      throw primaryError
    }
  }

  private fun handleUdpAssociate(
    controlSocket: Socket,
    controlInput: InputStream,
    udpSocket: DatagramSocket,
    udpSession: UdpTunnelSession,
    pool: ExecutorService,
  ) {
    val clientUdpAddress = AtomicReference<SocketAddress?>(null)
    val assocOpen = AtomicBoolean(true)

    val downstreamFuture =
      pool.submit {
        try {
          while (running.get() && assocOpen.get() && !udpSocket.isClosed) {
            val response = udpSession.receiveDatagram() ?: break
            val destClient = clientUdpAddress.get() ?: continue
            val encoded = encodeSocks5UdpPacket(response.host, response.port, response.payload) ?: continue
            udpSocket.send(DatagramPacket(encoded, encoded.size, destClient))
            bytesDownCounter.addAndGet(response.payload.size.toLong())
            publishStats()
          }
        } catch (_: IOException) {
          // Association closed
        }
      }

    val upstreamFuture =
      pool.submit {
        val rxBuffer = ByteArray(UDP_BUFFER_SIZE)
        while (running.get() && assocOpen.get() && !udpSocket.isClosed) {
          try {
            val packet = DatagramPacket(rxBuffer, rxBuffer.size)
            udpSocket.receive(packet)
            clientUdpAddress.set(packet.socketAddress)
            val parsed = decodeSocks5UdpPacket(rxBuffer, packet.length) ?: continue
            bytesUpCounter.addAndGet(parsed.payload.size.toLong())
            publishStats()
            udpSession.sendDatagram(parsed.host, parsed.port, parsed.payload)
          } catch (_: SocketException) {
            break
          } catch (_: IOException) {
            if (!running.get() || !assocOpen.get() || udpSocket.isClosed) break
          }
        }
      }

    // Per RFC 1928 §6: UDP association stays alive strictly while the TCP control socket stays open.
    try {
      while (running.get() && !controlSocket.isClosed) {
        val b = controlInput.read()
        if (b == -1) break
      }
    } catch (_: IOException) {
      // Control connection closed
    } finally {
      assocOpen.set(false)
      closeQuietly(udpSession)
      runCatching { udpSocket.close() }
      runCatching { upstreamFuture.cancel(true) }
      runCatching { downstreamFuture.cancel(true) }
    }
  }

  private fun defaultDnsOnlyUdpConnector(): UdpTunnelConnector = UdpTunnelConnector {
    DnsOnlyUdpTunnelSession(connector)
  }

  private class DnsOnlyUdpTunnelSession(
    private val connector: TunnelConnector,
  ) : UdpTunnelSession {
    private val closed = AtomicBoolean(false)
    private val queue = LinkedBlockingQueue<UdpDatagram>(128)
    private val worker =
      Executors.newCachedThreadPool { r ->
        Thread(r, "amkt-socks5-dns-udp").apply { isDaemon = true }
      }

    override fun sendDatagram(targetHost: String, targetPort: Int, payload: ByteArray) {
      if (closed.get() || targetPort != 53) return
      val copy = payload.copyOf()
      worker.execute {
        if (closed.get()) return@execute
        try {
          val resp =
            TunneledDnsResolver.sendDnsQueryOverTcp(
              connector = connector,
              dnsServerHost = targetHost,
              dnsServerPort = targetPort,
              dnsQueryBytes = copy,
            )
          if (!closed.get()) {
            queue.offer(UdpDatagram(targetHost, targetPort, resp))
          }
        } catch (_: IOException) {
          // Drop cleanly without leaking outside VPN
        }
      }
    }

    override fun receiveDatagram(): UdpDatagram? {
      while (!closed.get()) {
        try {
          val item = queue.poll(500L, TimeUnit.MILLISECONDS)
          if (item != null) return item
        } catch (_: InterruptedException) {
          return null
        }
      }
      return null
    }

    override fun close() {
      if (!closed.compareAndSet(false, true)) return
      worker.shutdownNow()
      queue.clear()
    }
  }

  private fun bindLoopbackUdpSocket(): DatagramSocket {
    val loopback = InetAddress.getByName(LOOPBACK_HOST)
    return DatagramSocket(0, loopback)
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

  private fun sendUdpAssociateReply(output: OutputStream, udpPort: Int) {
    val reply =
      byteArrayOf(
        SOCKS_VERSION_5.toByte(),
        REP_SUCCEEDED,
        0x00, // RSV
        ATYP_IPV4.toByte(),
        127,
        0,
        0,
        1, // BND.ADDR 127.0.0.1
        ((udpPort ushr 8) and 0xFF).toByte(),
        (udpPort and 0xFF).toByte(),
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
    private const val UDP_BUFFER_SIZE = 65_535

    private const val SOCKS_VERSION_5 = 0x05
    private const val AUTH_NONE: Byte = 0x00
    private const val AUTH_NO_ACCEPTABLE: Byte = 0xFF.toByte()

    private const val CMD_CONNECT = 0x01
    private const val CMD_UDP_ASSOCIATE = 0x03
    private const val ATYP_IPV4 = 0x01
    private const val ATYP_DOMAIN = 0x03
    private const val ATYP_IPV6 = 0x04

    private const val REP_SUCCEEDED: Byte = 0x00
    private const val REP_GENERAL_FAILURE: Byte = 0x01
    private const val REP_HOST_UNREACHABLE: Byte = 0x04
    private const val REP_COMMAND_NOT_SUPPORTED: Byte = 0x07
    private const val REP_ADDRESS_TYPE_NOT_SUPPORTED: Byte = 0x08

    internal fun decodeSocks5UdpPacket(buffer: ByteArray, length: Int): UdpDatagram? {
      if (length < 10) return null
      // RSV (2 bytes) + FRAG (1 byte)
      val frag = buffer[2].toInt() and 0xFF
      if (frag != 0) return null // Fragmented SOCKS5 UDP packets are dropped per RFC 1928 §7
      val atyp = buffer[3].toInt() and 0xFF
      var pos = 4
      val host: String =
        when (atyp) {
          ATYP_IPV4 -> {
            if (pos + 4 + 2 > length) return null
            val addr = buffer.copyOfRange(pos, pos + 4)
            pos += 4
            InetAddress.getByAddress(addr).hostAddress ?: return null
          }
          ATYP_DOMAIN -> {
            if (pos + 1 > length) return null
            val domainLen = buffer[pos++].toInt() and 0xFF
            if (pos + domainLen + 2 > length) return null
            val dom = String(buffer, pos, domainLen, Charsets.US_ASCII)
            pos += domainLen
            dom
          }
          ATYP_IPV6 -> {
            if (pos + 16 + 2 > length) return null
            val addr = buffer.copyOfRange(pos, pos + 16)
            pos += 16
            InetAddress.getByAddress(addr).hostAddress ?: return null
          }
          else -> return null
        }

      val port = ((buffer[pos].toInt() and 0xFF) shl 8) or (buffer[pos + 1].toInt() and 0xFF)
      pos += 2
      if (port <= 0) return null
      val payload = buffer.copyOfRange(pos, length)
      return UdpDatagram(host = TunneledDnsResolver.normalizeHostLiteral(host), port = port, payload = payload)
    }

    internal fun encodeSocks5UdpPacket(host: String, port: Int, payload: ByteArray): ByteArray? {
      val cleanHost = host.trim().trim('[', ']').substringBefore('%')
      if (cleanHost.isBlank() || port !in 1..65535) return null
      val out = ByteArrayOutputStream(24 + payload.size)
      out.write(0x00)
      out.write(0x00) // RSV
      out.write(0x00) // FRAG = 0

      val ipv4Match = Regex("^(\\d{1,3}\\.){3}\\d{1,3}$").matches(cleanHost)
      when {
        ipv4Match -> {
          val addr = runCatching { InetAddress.getByName(cleanHost) as? Inet4Address }.getOrNull() ?: return null
          out.write(ATYP_IPV4)
          out.write(addr.address)
        }
        cleanHost.contains(':') -> {
          val addr = runCatching { InetAddress.getByName(cleanHost) as? Inet6Address }.getOrNull() ?: return null
          out.write(ATYP_IPV6)
          out.write(addr.address)
        }
        else -> {
          val domainBytes = cleanHost.toByteArray(Charsets.US_ASCII)
          if (domainBytes.isEmpty() || domainBytes.size > 255) return null
          out.write(ATYP_DOMAIN)
          out.write(domainBytes.size)
          out.write(domainBytes)
        }
      }

      out.write((port ushr 8) and 0xFF)
      out.write(port and 0xFF)
      out.write(payload)
      return out.toByteArray()
    }
  }
}
