package com.example.ssh

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * UDP tunnel session for SSH (and DNSTT+SSH) connections:
 * - Port 53 (DNS UDP): transparently converted into RFC 1035 DNS-over-TCP through [connector],
 *   working on 100% of SSH servers with zero DNS leaks.
 * - Non-DNS UDP (voice/video calls, games, STUN): forwarded through `badvpn-udpgw` on
 *   `127.0.0.1:7300` inside an encrypted SSH `direct-tcpip` channel when available on the VPS.
 *   Never bypasses the VPN to raw sockets.
 */
internal class SshUdpTunnelSession(
  private val connector: TunnelConnector,
  private val onLog: (String) -> Unit = {},
  private val udpgwHost: String = DEFAULT_UDPGW_HOST,
  private val udpgwPort: Int = DEFAULT_UDPGW_PORT,
) : UdpTunnelSession {

  private val closed = AtomicBoolean(false)
  private val inboundQueue = LinkedBlockingQueue<UdpDatagram>(256)
  private val dnsExecutor: ExecutorService =
    Executors.newCachedThreadPool { r ->
      Thread(r, "amkt-ssh-dns-udp").apply { isDaemon = true }
    }

  private val flowIdGen = AtomicInteger(1)
  private val endpointToConId = ConcurrentHashMap<String, Int>()
  private val conIdToEndpoint = ConcurrentHashMap<Int, Pair<String, Int>>()
  private val udpgwWriteLock = Any()

  @Volatile private var udpgwChannel: TunnelChannel? = null
  @Volatile private var udpgwUnavailable: Boolean = false
  @Volatile private var udpgwLoggedMissing: Boolean = false
  @Volatile private var udpgwReaderThread: Thread? = null

  @Throws(IOException::class)
  override fun sendDatagram(targetHost: String, targetPort: Int, payload: ByteArray) {
    if (closed.get()) throw IOException("UDP tunnel session closed")
    if (targetPort <= 0) return

    if (targetPort == DNS_PORT) {
      val copy = payload.copyOf()
      dnsExecutor.execute {
        if (closed.get()) return@execute
        try {
          val response =
            TunneledDnsResolver.sendDnsQueryOverTcp(
              connector = connector,
              dnsServerHost = targetHost,
              dnsServerPort = DNS_PORT,
              dnsQueryBytes = copy,
            )
          if (!closed.get()) {
            inboundQueue.offer(UdpDatagram(host = targetHost, port = targetPort, payload = response))
          }
        } catch (_: IOException) {
          // Tunneled DNS query dropped cleanly without leaking outside VPN
        }
      }
      return
    }

    val channel = ensureUdpGwChannel() ?: return
    val normalizedTarget = resolveEndpointForUdpGw(targetHost) ?: return
    val key = "$normalizedTarget:$targetPort"
    val conId =
      endpointToConId.computeIfAbsent(key) {
        val assigned = (flowIdGen.getAndIncrement() and 0xFFFF).let { if (it == 0) 1 else it }
        conIdToEndpoint[assigned] = targetHost to targetPort
        assigned
      }

    val frame =
      BadVpnUdpGwCodec.encodePacket(
        conId = conId,
        targetHost = normalizedTarget,
        targetPort = targetPort,
        payload = payload,
      ) ?: return

    synchronized(udpgwWriteLock) {
      if (closed.get()) return
      try {
        channel.outputStream.write(frame)
        channel.outputStream.flush()
      } catch (_: IOException) {
        runCatching { channel.close() }
        if (udpgwChannel === channel) udpgwChannel = null
      }
    }
  }

  override fun receiveDatagram(): UdpDatagram? {
    while (!closed.get()) {
      try {
        val next = inboundQueue.poll(500L, TimeUnit.MILLISECONDS)
        if (next != null) return next
      } catch (_: InterruptedException) {
        return null
      }
    }
    return null
  }

  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    dnsExecutor.shutdownNow()
    runCatching { udpgwChannel?.close() }
    udpgwChannel = null
    udpgwReaderThread?.interrupt()
    udpgwReaderThread = null
    inboundQueue.clear()
  }

  private fun ensureUdpGwChannel(): TunnelChannel? {
    udpgwChannel?.let { return it }
    if (udpgwUnavailable || closed.get()) return null
    synchronized(this) {
      udpgwChannel?.let { return it }
      if (udpgwUnavailable || closed.get()) return null
      return try {
        val ch = connector.openChannel(udpgwHost, udpgwPort, 0)
        udpgwChannel = ch
        startUdpGwReader(ch)
        ch
      } catch (e: IOException) {
        udpgwUnavailable = true
        if (!udpgwLoggedMissing) {
          udpgwLoggedMissing = true
          onLog(
            "SSH ဆာဗာတွင် UDPGW ($udpgwHost:$udpgwPort) မဖွင့်ထားပါ — DNS UDP ကို SSH-TCP ဖြင့် ဆက်လက်ပို့ဆောင်နေသည် (${e.message ?: "unavailable"})"
          )
        }
        null
      }
    }
  }

  private fun startUdpGwReader(channel: TunnelChannel) {
    val thread =
      Thread(
        {
          val input = DataInputStream(channel.inputStream)
          try {
            while (!closed.get()) {
              val decoded = BadVpnUdpGwCodec.readPacket(input) ?: break
              val originalEndpoint = conIdToEndpoint[decoded.conId]
              val replyHost = originalEndpoint?.first ?: decoded.host
              val replyPort = originalEndpoint?.second ?: decoded.port
              if (!closed.get()) {
                inboundQueue.offer(UdpDatagram(replyHost, replyPort, decoded.payload))
              }
            }
          } catch (_: EOFException) {
            // UDPGW stream ended
          } catch (_: IOException) {
            // Tunnel closed
          } finally {
            runCatching { channel.close() }
            if (udpgwChannel === channel) udpgwChannel = null
          }
        },
        "amkt-ssh-udpgw-rx",
      ).apply { isDaemon = true }
    udpgwReaderThread = thread
    thread.start()
  }

  private fun resolveEndpointForUdpGw(targetHost: String): String? {
    val clean = TunneledDnsResolver.normalizeHostLiteral(targetHost)
    if (clean.isBlank()) return null
    if (!clean.contains(':')) {
      // Check if domain or IPv4; if domain, resolve IPv4 inside tunnel so UDPGW receives an IP literal
      val ipv4Regex = Regex("^(\\d{1,3}\\.){3}\\d{1,3}$")
      if (ipv4Regex.matches(clean)) return clean
      return TunneledDnsResolver.resolveIpv4ViaTunnel(connector, clean)
    }
    return TunneledDnsResolver.resolveIpv6ToIpv4ViaTunnel(connector, clean) ?: clean
  }

  companion object {
    const val DEFAULT_UDPGW_HOST = "127.0.0.1"
    const val DEFAULT_UDPGW_PORT = 7300
    private const val DNS_PORT = 53
  }
}

/**
 * Pure Kotlin codec for the standard `badvpn-udpgw` wire protocol (`PacketProto` 2-byte LE
 * length prefix + `udpgw_header`: `flags(1)` + `conid(2 LE)` + `addr` + `port(2 BE)` + `payload`).
 */
internal object BadVpnUdpGwCodec {
  const val FLAG_KEEPALIVE: Int = 1 shl 0
  const val FLAG_REBIND: Int = 1 shl 1
  const val FLAG_DNS: Int = 1 shl 2
  const val FLAG_IPV6: Int = 1 shl 3

  private const val MAX_PACKET_SIZE = 32_768

  data class DecodedUdpGwPacket(
    val flags: Int,
    val conId: Int,
    val host: String,
    val port: Int,
    val payload: ByteArray,
  )

  fun encodePacket(
    conId: Int,
    targetHost: String,
    targetPort: Int,
    payload: ByteArray,
    isDns: Boolean = false,
  ): ByteArray? {
    val cleanHost = targetHost.trim().trim('[', ']').substringBefore('%')
    val addr = runCatching { InetAddress.getByName(cleanHost) }.getOrNull() ?: return null
    val addrBytes = addr.address
    val isIpv6 = addr is Inet6Address && addrBytes.size == 16
    if (!isIpv6 && addrBytes.size != 4) return null

    var flags = 0
    if (isDns) flags = flags or FLAG_DNS
    if (isIpv6) flags = flags or FLAG_IPV6

    val bodySize = 3 + addrBytes.size + 2 + payload.size
    if (bodySize > MAX_PACKET_SIZE) return null

    val out = ByteArray(2 + bodySize)
    // PacketProto 2-byte little-endian length
    out[0] = (bodySize and 0xFF).toByte()
    out[1] = ((bodySize ushr 8) and 0xFF).toByte()
    // udpgw_header: flags (1B) + conid (2B LE)
    out[2] = (flags and 0xFF).toByte()
    out[3] = (conId and 0xFF).toByte()
    out[4] = ((conId ushr 8) and 0xFF).toByte()
    // address + port (2B BE)
    System.arraycopy(addrBytes, 0, out, 5, addrBytes.size)
    val portOffset = 5 + addrBytes.size
    out[portOffset] = ((targetPort ushr 8) and 0xFF).toByte()
    out[portOffset + 1] = (targetPort and 0xFF).toByte()
    // UDP payload
    System.arraycopy(payload, 0, out, portOffset + 2, payload.size)
    return out
  }

  @Throws(IOException::class)
  fun readPacket(input: DataInputStream): DecodedUdpGwPacket? {
    while (true) {
      val lenLo = input.read()
      if (lenLo == -1) return null
      val lenHi = input.read()
      if (lenHi == -1) return null
      val bodySize = (lenHi shl 8) or lenLo
      if (bodySize < 3 || bodySize > MAX_PACKET_SIZE) {
        throw IOException("Invalid UDPGW frame length: $bodySize")
      }
      val body = ByteArray(bodySize)
      input.readFully(body)

      val flags = body[0].toInt() and 0xFF
      if ((flags and FLAG_KEEPALIVE) != 0) {
        continue
      }
      val conId = ((body[2].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
      val isIpv6 = (flags and FLAG_IPV6) != 0
      val addrLen = if (isIpv6) 16 else 4
      if (bodySize < 3 + addrLen + 2) continue

      val addrBytes = body.copyOfRange(3, 3 + addrLen)
      val host = InetAddress.getByAddress(addrBytes).hostAddress ?: continue
      val portOffset = 3 + addrLen
      val port = ((body[portOffset].toInt() and 0xFF) shl 8) or (body[portOffset + 1].toInt() and 0xFF)
      val payload = body.copyOfRange(portOffset + 2, bodySize)
      return DecodedUdpGwPacket(flags = flags, conId = conId, host = host, port = port, payload = payload)
    }
  }
}
