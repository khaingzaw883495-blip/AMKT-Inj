package com.example.vless

import com.example.ssh.TunnelConnector
import com.example.ssh.TunneledDnsResolver
import com.example.ssh.UdpDatagram
import com.example.ssh.UdpTunnelSession
import java.io.DataInputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Real VLESS v0 UDP tunnel session over WebSocket (`VlessProtocol.COMMAND_UDP = 0x02`).
 *
 * Each destination `(host, port)` opens a dedicated VLESS WebSocket UDP stream where every
 * UDP datagram is framed with a 2-byte big-endian length prefix (`[len BE][payload]`) after
 * the initial VLESS request/response headers. If an IPv6 UDP target is rejected by an IPv4-only
 * server, falls back to tunneled IPv4 via [TunneledDnsResolver] without leaking outside the VPN.
 */
internal class VlessUdpTunnelSession(
  private val tcpConnector: TunnelConnector,
  private val udpStreamOpener: (targetHost: String, targetPort: Int) -> VlessUdpFlowChannel,
) : UdpTunnelSession {

  internal interface VlessUdpFlowChannel : java.io.Closeable {
    @Throws(IOException::class)
    fun sendPacket(payload: ByteArray)

    @Throws(IOException::class)
    fun readPacket(): ByteArray?
  }

  private val closed = AtomicBoolean(false)
  private val flows = ConcurrentHashMap<String, VlessUdpFlowChannel>()
  private val inboundQueue = LinkedBlockingQueue<UdpDatagram>(256)
  private val workerPool: ExecutorService =
    Executors.newCachedThreadPool { r ->
      Thread(r, "amkt-vless-udp").apply { isDaemon = true }
    }

  @Throws(IOException::class)
  override fun sendDatagram(targetHost: String, targetPort: Int, payload: ByteArray) {
    if (closed.get()) throw IOException("VLESS UDP session closed")
    if (targetPort <= 0) return

    // Map local TUN virtual DNS server (198.18.0.2) or IPv6 DNS literals cleanly inside tunnel
    val resolvedHost =
      if (targetPort == 53) {
        TunneledDnsResolver.normalizeDnsServerForTunnel(targetHost)
      } else {
        TunneledDnsResolver.normalizeHostLiteral(targetHost)
      }

    val key = "$resolvedHost:$targetPort"
    val existing = flows[key]
    if (existing != null) {
      try {
        existing.sendPacket(payload)
        return
      } catch (_: IOException) {
        flows.remove(key, existing)
        runCatching { existing.close() }
      }
    }

    val created =
      try {
        openFlowWithFallback(resolvedHost, targetPort)
      } catch (e: IOException) {
        if (targetPort == 53) {
          // Safety fallback: DNS-over-TCP over VLESS TCP stream if server blocks UDP port 53
          val copy = payload.copyOf()
          workerPool.execute {
            if (closed.get()) return@execute
            runCatching {
              val resp =
                TunneledDnsResolver.sendDnsQueryOverTcp(
                  connector = tcpConnector,
                  dnsServerHost = resolvedHost,
                  dnsServerPort = 53,
                  dnsQueryBytes = copy,
                )
              if (!closed.get()) {
                inboundQueue.offer(UdpDatagram(targetHost, targetPort, resp))
              }
            }
          }
          return
        }
        throw e
      }

    val prev = flows.putIfAbsent(key, created)
    val activeFlow =
      if (prev == null) {
        startFlowReader(key = key, replyHost = targetHost, replyPort = targetPort, flow = created)
        created
      } else {
        runCatching { created.close() }
        prev
      }
    activeFlow.sendPacket(payload)
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
    workerPool.shutdownNow()
    flows.values.forEach { runCatching { it.close() } }
    flows.clear()
    inboundQueue.clear()
  }

  private fun openFlowWithFallback(targetHost: String, targetPort: Int): VlessUdpFlowChannel {
    return try {
      udpStreamOpener(targetHost, targetPort)
    } catch (e: IOException) {
      if (TunneledDnsResolver.isIpv6Literal(targetHost)) {
        val ipv4 = TunneledDnsResolver.resolveIpv6ToIpv4ViaTunnel(tcpConnector, targetHost)
        if (ipv4 != null) {
          return udpStreamOpener(ipv4, targetPort)
        }
      }
      throw e
    }
  }

  private fun startFlowReader(
    key: String,
    replyHost: String,
    replyPort: Int,
    flow: VlessUdpFlowChannel,
  ) {
    workerPool.execute {
      try {
        while (!closed.get()) {
          val pkt = flow.readPacket() ?: break
          if (!closed.get()) {
            inboundQueue.offer(UdpDatagram(host = replyHost, port = replyPort, payload = pkt))
          }
        }
      } catch (_: IOException) {
        // Stream terminated
      } finally {
        flows.remove(key, flow)
        runCatching { flow.close() }
      }
    }
  }

  companion object {
    /**
     * Wraps a VLESS v0 WebSocket [com.example.ssh.TunnelChannel] opened with
     * [VlessProtocol.COMMAND_UDP] into a length-prefixed `[2B length BE][payload]` UDP flow.
     */
    fun wrapVlessChannel(channel: com.example.ssh.TunnelChannel): VlessUdpFlowChannel {
      return object : VlessUdpFlowChannel {
        private val writeLock = Any()
        private val dataIn = DataInputStream(channel.inputStream)

        override fun sendPacket(payload: ByteArray) {
          val len = payload.size
          if (len > 65_535) throw IOException("UDP packet too large: $len")
          val frame = ByteArray(2 + len)
          frame[0] = ((len ushr 8) and 0xFF).toByte()
          frame[1] = (len and 0xFF).toByte()
          System.arraycopy(payload, 0, frame, 2, len)
          synchronized(writeLock) {
            channel.outputStream.write(frame)
            channel.outputStream.flush()
          }
        }

        override fun readPacket(): ByteArray? {
          val hi = dataIn.read()
          if (hi == -1) return null
          val lo = dataIn.read()
          if (lo == -1) return null
          val len = (hi shl 8) or lo
          if (len <= 0) return ByteArray(0)
          val buf = ByteArray(len)
          dataIn.readFully(buf)
          return buf
        }

        override fun close() {
          channel.close()
        }
      }
    }
  }
}
