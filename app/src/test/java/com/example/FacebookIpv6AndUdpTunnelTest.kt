package com.example

import com.example.data.ConnectionMode
import com.example.data.EffectiveConnectionCombiner
import com.example.data.NetworkConfigEntity
import com.example.data.PayloadMode
import com.example.data.ServerAccountEntity
import com.example.dnstt.DnsttValidator
import com.example.ssh.BadVpnUdpGwCodec
import com.example.ssh.LocalSocks5Proxy
import com.example.ssh.SshUdpTunnelSession
import com.example.ssh.TunnelChannel
import com.example.ssh.TunnelConnector
import com.example.vless.VlessProtocol
import com.example.vless.VlessUdpTunnelSession
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FacebookIpv6AndUdpTunnelTest {

  @Test
  fun facebookIpv6TargetRecoversViaTunneledIpv4WhileOtherSitesLoadNormally() {
    val openedTargets = CopyOnWriteArrayList<String>()
    val facebookIpv6 = "2a03:2880:f348:1:face:b00c:0:25de"
    val facebookIpv4 = "157.240.1.35"

    val connector = TunnelConnector { host, port, _ ->
      openedTargets.add("$host:$port")
      when {
        host == facebookIpv6 -> throw IOException("channel is not opened.")
        host == "1.1.1.1" && port == 53 -> createMockDnsTcpChannel(ptrHost = "edge-star-mini6-shv-01-sin6.facebook.com", resolvedIpv4 = facebookIpv4)
        host == facebookIpv4 && port == 443 -> createEchoChannel(prefix = "FB-V4:")
        host == "www.youtube.com" && port == 443 -> createEchoChannel(prefix = "YT:")
        else -> throw IOException("Unexpected target $host:$port")
      }
    }

    val proxy = LocalSocks5Proxy(connector = connector)
    try {
      val port = proxy.start(preferredPort = 0)

      // 1. Facebook IPv6 literal (ATYP=0x04) must recover cleanly via tunneled IPv4
      Socket("127.0.0.1", port).use { sock ->
        sock.soTimeout = 5_000
        val out = sock.getOutputStream()
        val inp = DataInputStream(sock.getInputStream())
        socks5Handshake(out, inp)

        val fbIpv6Bytes = InetAddress.getByName(facebookIpv6).address
        val req =
          ByteArray(4 + 16 + 2).apply {
            this[0] = 0x05
            this[1] = 0x01 // CONNECT
            this[2] = 0x00
            this[3] = 0x04 // ATYP_IPV6
            System.arraycopy(fbIpv6Bytes, 0, this, 4, 16)
            this[20] = ((443 ushr 8) and 0xFF).toByte()
            this[21] = (443 and 0xFF).toByte()
          }
        out.write(req)
        out.flush()

        val reply = ByteArray(10)
        inp.readFully(reply)
        assertEquals("Facebook IPv6 via tunneled IPv4 fallback must succeed", 0x00.toByte(), reply[1])

        val msg = "GET / HTTP/1.1\r\nHost: www.facebook.com\r\n\r\n".toByteArray(Charsets.US_ASCII)
        out.write(msg)
        out.flush()

        val expected = ("FB-V4:" + String(msg, Charsets.US_ASCII)).toByteArray(Charsets.US_ASCII)
        val resp = ByteArray(expected.size)
        inp.readFully(resp)
        assertArrayEquals(expected, resp)
      }

      // 2. Other sites (domain ATYP=0x03) continue working normally
      Socket("127.0.0.1", port).use { sock ->
        sock.soTimeout = 5_000
        val out = sock.getOutputStream()
        val inp = DataInputStream(sock.getInputStream())
        socks5Handshake(out, inp)

        val dom = "www.youtube.com".toByteArray(Charsets.US_ASCII)
        val req =
          ByteArray(4 + 1 + dom.size + 2).apply {
            this[0] = 0x05
            this[1] = 0x01
            this[2] = 0x00
            this[3] = 0x03 // ATYP_DOMAIN
            this[4] = dom.size.toByte()
            System.arraycopy(dom, 0, this, 5, dom.size)
            this[5 + dom.size] = ((443 ushr 8) and 0xFF).toByte()
            this[6 + dom.size] = (443 and 0xFF).toByte()
          }
        out.write(req)
        out.flush()

        val reply = ByteArray(10)
        inp.readFully(reply)
        assertEquals(0x00.toByte(), reply[1])

        val ping = "YT-OK".toByteArray(Charsets.US_ASCII)
        out.write(ping)
        out.flush()
        val expected = "YT:YT-OK".toByteArray(Charsets.US_ASCII)
        val resp = ByteArray(expected.size)
        inp.readFully(resp)
        assertArrayEquals(expected, resp)
      }

      assertTrue(openedTargets.contains("$facebookIpv4:443"))
      assertTrue(openedTargets.contains("www.youtube.com:443"))
    } finally {
      proxy.stop()
    }
  }

  @Test
  fun ipv6CapableUpstreamConnectsDirectlyWithoutFallback() {
    val openedTargets = CopyOnWriteArrayList<String>()
    val ipv6Target = "2606:4700:4700::1111"

    val ipv6Canonical = InetAddress.getByName(ipv6Target)
    val proxy =
      LocalSocks5Proxy(
        connector = { host, port, _ ->
          openedTargets.add("$host:$port")
          val parsed = runCatching { InetAddress.getByName(host) }.getOrNull()
          if (parsed == ipv6Canonical && port == 443) {
            createEchoChannel(prefix = "NATIVE6:")
          } else {
            throw IOException("Unexpected fallback for $host:$port")
          }
        }
      )

    try {
      val port = proxy.start(preferredPort = 0)
      Socket("127.0.0.1", port).use { sock ->
        sock.soTimeout = 5_000
        val out = sock.getOutputStream()
        val inp = DataInputStream(sock.getInputStream())
        socks5Handshake(out, inp)

        val ipv6Bytes = InetAddress.getByName(ipv6Target).address
        val req =
          ByteArray(4 + 16 + 2).apply {
            this[0] = 0x05
            this[1] = 0x01
            this[2] = 0x00
            this[3] = 0x04
            System.arraycopy(ipv6Bytes, 0, this, 4, 16)
            this[20] = ((443 ushr 8) and 0xFF).toByte()
            this[21] = (443 and 0xFF).toByte()
          }
        out.write(req)
        out.flush()

        val reply = ByteArray(10)
        inp.readFully(reply)
        assertEquals(0x00.toByte(), reply[1])

        val msg = "PING6".toByteArray(Charsets.US_ASCII)
        out.write(msg)
        out.flush()
        val expected = "NATIVE6:PING6".toByteArray(Charsets.US_ASCII)
        val resp = ByteArray(expected.size)
        inp.readFully(resp)
        assertArrayEquals(expected, resp)
      }
      assertEquals(1, openedTargets.size)
      assertEquals(ipv6Canonical, InetAddress.getByName(openedTargets.first().substringBeforeLast(':')))
      assertTrue(openedTargets.first().endsWith(":443"))
    } finally {
      proxy.stop()
    }
  }

  @Test
  fun dnsttModeRemainsPresentSelectableAndValidEndToEnd() {
    assertTrue(ConnectionMode.DNSTT in ServerAccountEntity.SUPPORTED_SERVER_MODES)

    val net = NetworkConfigEntity(name = "Direct", payloadMode = PayloadMode.DIRECT.code)
    val dnsttServer =
      ServerAccountEntity(
        name = "Owner DNSTT",
        connectionMode = ConnectionMode.DNSTT.code,
        host = "127.0.0.1",
        port = 22,
        username = "amkt",
        password = "secret",
        dnsttNameserver = "ns.example.org",
        dnsttPublicKey = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        dnsttResolver = "8.8.8.8:53",
      )
    val combined = EffectiveConnectionCombiner.combine(net, dnsttServer)
    assertEquals(ConnectionMode.DNSTT, combined.connectionMode)
    assertEquals("ns.example.org", combined.dnsttNameserver)
    assertNull(DnsttValidator.validateProfile(combined))
  }

  @Test
  fun socks5UdpAssociateTunnelsDnsQueriesAndVoipGameDatagramsAndBlocksOnStop() {
    val exec = Executors.newCachedThreadPool()
    val connector = TunnelConnector { host, port, _ ->
      when {
        port == 53 ->
          createMockDnsTcpChannel(
            ptrHost = "dns.example",
            resolvedIpv4 = "93.184.216.34",
          )
        host == SshUdpTunnelSession.DEFAULT_UDPGW_HOST &&
          port == SshUdpTunnelSession.DEFAULT_UDPGW_PORT ->
          createMockUdpGwEchoChannel(exec)
        else -> throw IOException("Unexpected channel $host:$port")
      }
    }

    val proxy =
      LocalSocks5Proxy(
        connector = connector,
        udpConnector = { SshUdpTunnelSession(connector = connector) },
      )

    try {
      val socksPort = proxy.start(preferredPort = 0)
      Socket("127.0.0.1", socksPort).use { ctrl ->
        ctrl.soTimeout = 5_000
        val out = ctrl.getOutputStream()
        val inp = DataInputStream(ctrl.getInputStream())
        socks5Handshake(out, inp)

        // Request CMD_UDP_ASSOCIATE (0x03)
        out.write(byteArrayOf(0x05, 0x03, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
        out.flush()

        val reply = ByteArray(10)
        inp.readFully(reply)
        assertEquals(0x00.toByte(), reply[1])
        val relayPort = ((reply[8].toInt() and 0xFF) shl 8) or (reply[9].toInt() and 0xFF)
        assertTrue(relayPort > 0)

        DatagramSocket().use { clientUdp ->
          clientUdp.soTimeout = 5_000
          val relayAddr = InetAddress.getByName("127.0.0.1")

          // 1. Send UDP DNS query to 198.18.0.2:53 -> tunneled via DNS-over-TCP inside VPN
          val dnsQuery = buildSimpleDnsAQuery("example.com", txId = 0x1234)
          val wrappedDns = LocalSocks5Proxy.encodeSocks5UdpPacket("198.18.0.2", 53, dnsQuery)!!
          clientUdp.send(DatagramPacket(wrappedDns, wrappedDns.size, relayAddr, relayPort))

          val rxBuf = ByteArray(4096)
          val rxDns = DatagramPacket(rxBuf, rxBuf.size)
          clientUdp.receive(rxDns)
          val decodedDns = LocalSocks5Proxy.decodeSocks5UdpPacket(rxBuf, rxDns.length)!!
          assertEquals(53, decodedDns.port)
          assertEquals(0x12, decodedDns.payload[0].toInt() and 0xFF)
          assertEquals(0x34, decodedDns.payload[1].toInt() and 0xFF)

          // 2. Send non-DNS UDP VoIP/game packet to 203.0.113.50:3478 -> tunneled via badvpn-udpgw
          val gamePayload = "VOICE-CALL-STUN-PKT".toByteArray(Charsets.US_ASCII)
          val wrappedGame = LocalSocks5Proxy.encodeSocks5UdpPacket("203.0.113.50", 3478, gamePayload)!!
          clientUdp.send(DatagramPacket(wrappedGame, wrappedGame.size, relayAddr, relayPort))

          val rxGame = DatagramPacket(rxBuf, rxBuf.size)
          clientUdp.receive(rxGame)
          val decodedGame = LocalSocks5Proxy.decodeSocks5UdpPacket(rxBuf, rxGame.length)!!
          assertEquals("203.0.113.50", decodedGame.host)
          assertEquals(3478, decodedGame.port)
          assertArrayEquals(gamePayload, decodedGame.payload)
        }
      }

      // 3. Stop / Kill Switch: proxy shuts down all TCP + UDP sockets so zero traffic leaks
      proxy.stop()
      assertEquals(0, proxy.boundPort)
    } finally {
      proxy.stop()
      exec.shutdownNow()
    }
  }

  @Test
  fun vlessUdpTunnelSessionFramesAndRelaysUdpPacketsWithCommandUdp() {
    val uuidBytes = VlessProtocol.parseUuidBytes("b831381d-6324-4d53-ad4f-8cda48b30811")
    val header =
      VlessProtocol.encodeRequestHeader(
        uuidBytes = uuidBytes,
        targetHost = "8.8.8.8",
        targetPort = 53,
        command = VlessProtocol.COMMAND_UDP,
      )
    val decoded = VlessProtocol.decodeRequestHeader(header)
    assertEquals(VlessProtocol.COMMAND_UDP.toInt(), decoded.command)
    assertEquals("8.8.8.8", decoded.targetHost)
    assertEquals(53, decoded.port)

    // Verify VlessUdpTunnelSession 2-byte length-prefixed packet roundtrip
    val c2sIn = PipedInputStream(8192)
    val c2sOut = PipedOutputStream(c2sIn)
    val s2cIn = PipedInputStream(8192)
    val s2cOut = PipedOutputStream(s2cIn)
    val exec = Executors.newSingleThreadExecutor()
    exec.execute {
      val dataIn = DataInputStream(c2sIn)
      try {
        while (true) {
          val len = dataIn.readUnsignedShort()
          val pkt = ByteArray(len)
          dataIn.readFully(pkt)
          val reply = ("VLESS-UDP:" + String(pkt, Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
          s2cOut.write((reply.size ushr 8) and 0xFF)
          s2cOut.write(reply.size and 0xFF)
          s2cOut.write(reply)
          s2cOut.flush()
        }
      } catch (_: Exception) {}
    }

    val channel =
      object : TunnelChannel {
        override val inputStream: InputStream = s2cIn
        override val outputStream: OutputStream = c2sOut

        override fun close() {
          runCatching { c2sOut.close() }
          runCatching { s2cIn.close() }
        }
      }

    val session =
      VlessUdpTunnelSession(
        tcpConnector = { _, _, _ -> throw IOException("not used") },
        udpStreamOpener = { _, _ -> VlessUdpTunnelSession.wrapVlessChannel(channel) },
      )

    try {
      session.sendDatagram("203.0.113.99", 5060, "SIP-PING".toByteArray(Charsets.UTF_8))
      val received = session.receiveDatagram()!!
      assertEquals("203.0.113.99", received.host)
      assertEquals(5060, received.port)
      assertEquals("VLESS-UDP:SIP-PING", String(received.payload, Charsets.UTF_8))
    } finally {
      session.close()
      exec.shutdownNow()
    }
  }

  private fun socks5Handshake(out: OutputStream, inp: DataInputStream) {
    out.write(byteArrayOf(0x05, 0x01, 0x00))
    out.flush()
    val greeting = ByteArray(2)
    inp.readFully(greeting)
    assertArrayEquals(byteArrayOf(0x05, 0x00), greeting)
  }

  private fun createEchoChannel(prefix: String): TunnelChannel {
    val c2sIn = PipedInputStream(8192)
    val c2sOut = PipedOutputStream(c2sIn)
    val s2cIn = PipedInputStream(8192)
    val s2cOut = PipedOutputStream(s2cIn)
    Thread {
      try {
        val buf = ByteArray(4096)
        val n = c2sIn.read(buf)
        if (n > 0) {
          s2cOut.write(prefix.toByteArray(Charsets.US_ASCII))
          s2cOut.write(buf, 0, n)
          s2cOut.flush()
        }
      } catch (_: Exception) {} finally {
        runCatching { s2cOut.close() }
      }
    }.apply { isDaemon = true }.start()

    return object : TunnelChannel {
      override val inputStream: InputStream = s2cIn
      override val outputStream: OutputStream = c2sOut

      override fun close() {
        runCatching { c2sOut.close() }
        runCatching { s2cIn.close() }
      }
    }
  }

  private fun createMockDnsTcpChannel(ptrHost: String, resolvedIpv4: String): TunnelChannel {
    val c2sIn = PipedInputStream(4096)
    val c2sOut = PipedOutputStream(c2sIn)
    val s2cIn = PipedInputStream(4096)
    val s2cOut = PipedOutputStream(s2cIn)
    Thread {
      try {
        val inp = DataInputStream(c2sIn)
        val qLen = inp.readUnsignedShort()
        val query = ByteArray(qLen)
        inp.readFully(query)
        val qtype = parseQuestionType(query)
        val resp =
          if (qtype == 12) buildDnsPtrResponse(query, ptrHost)
          else buildDnsAResponse(query, resolvedIpv4)
        s2cOut.write((resp.size ushr 8) and 0xFF)
        s2cOut.write(resp.size and 0xFF)
        s2cOut.write(resp)
        s2cOut.flush()
      } catch (_: Exception) {} finally {
        runCatching { s2cOut.close() }
      }
    }.apply { isDaemon = true }.start()

    return object : TunnelChannel {
      override val inputStream: InputStream = s2cIn
      override val outputStream: OutputStream = c2sOut

      override fun close() {
        runCatching { c2sOut.close() }
        runCatching { s2cIn.close() }
      }
    }
  }

  private fun createMockUdpGwEchoChannel(exec: java.util.concurrent.ExecutorService): TunnelChannel {
    val c2sIn = PipedInputStream(16_384)
    val c2sOut = PipedOutputStream(c2sIn)
    val s2cIn = PipedInputStream(16_384)
    val s2cOut = PipedOutputStream(s2cIn)
    exec.execute {
      try {
        val inp = DataInputStream(c2sIn)
        while (true) {
          val pkt = BadVpnUdpGwCodec.readPacket(inp) ?: break
          val echo =
            BadVpnUdpGwCodec.encodePacket(
              conId = pkt.conId,
              targetHost = pkt.host,
              targetPort = pkt.port,
              payload = pkt.payload,
            ) ?: continue
          s2cOut.write(echo)
          s2cOut.flush()
        }
      } catch (_: Exception) {} finally {
        runCatching { s2cOut.close() }
      }
    }

    return object : TunnelChannel {
      override val inputStream: InputStream = s2cIn
      override val outputStream: OutputStream = c2sOut

      override fun close() {
        runCatching { c2sOut.close() }
        runCatching { s2cIn.close() }
      }
    }
  }

  private fun parseQuestionType(query: ByteArray): Int {
    var pos = 12
    while (pos < query.size) {
      val len = query[pos++].toInt() and 0xFF
      if (len == 0) break
      pos += len
    }
    return if (pos + 2 <= query.size) {
      ((query[pos].toInt() and 0xFF) shl 8) or (query[pos + 1].toInt() and 0xFF)
    } else {
      1
    }
  }

  private fun buildSimpleDnsAQuery(domain: String, txId: Int): ByteArray {
    val out = ByteArrayOutputStream()
    out.write((txId ushr 8) and 0xFF)
    out.write(txId and 0xFF)
    out.write(byteArrayOf(0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
    for (label in domain.split('.')) {
      val b = label.toByteArray(Charsets.US_ASCII)
      out.write(b.size)
      out.write(b)
    }
    out.write(byteArrayOf(0x00, 0x00, 0x01, 0x00, 0x01))
    return out.toByteArray()
  }

  private fun buildDnsAResponse(query: ByteArray, ipv4: String): ByteArray {
    val qEnd = findQuestionEnd(query)
    val out = ByteArrayOutputStream()
    out.write(query, 0, 2) // TXID
    out.write(byteArrayOf(0x81.toByte(), 0x80.toByte())) // Standard response, no error
    out.write(byteArrayOf(0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00)) // 1 question, 1 answer
    out.write(query, 12, qEnd - 12)
    // Answer: pointer to QNAME (0xC00C), TYPE=A(1), CLASS=IN(1), TTL=60, RDLEN=4, IPv4
    out.write(byteArrayOf(0xC0.toByte(), 0x0C, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x3C, 0x00, 0x04))
    out.write(InetAddress.getByName(ipv4).address)
    return out.toByteArray()
  }

  private fun buildDnsPtrResponse(query: ByteArray, host: String): ByteArray {
    val qEnd = findQuestionEnd(query)
    val rdata = ByteArrayOutputStream()
    for (label in host.trimEnd('.').split('.')) {
      val b = label.toByteArray(Charsets.US_ASCII)
      rdata.write(b.size)
      rdata.write(b)
    }
    rdata.write(0x00)
    val rdataBytes = rdata.toByteArray()

    val out = ByteArrayOutputStream()
    out.write(query, 0, 2)
    out.write(byteArrayOf(0x81.toByte(), 0x80.toByte()))
    out.write(byteArrayOf(0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00))
    out.write(query, 12, qEnd - 12)
    out.write(byteArrayOf(0xC0.toByte(), 0x0C, 0x00, 0x0C, 0x00, 0x01, 0x00, 0x00, 0x00, 0x3C))
    out.write((rdataBytes.size ushr 8) and 0xFF)
    out.write(rdataBytes.size and 0xFF)
    out.write(rdataBytes)
    return out.toByteArray()
  }

  private fun findQuestionEnd(query: ByteArray): Int {
    var pos = 12
    while (pos < query.size) {
      val len = query[pos++].toInt() and 0xFF
      if (len == 0) break
      pos += len
    }
    return (pos + 4).coerceAtMost(query.size)
  }
}
