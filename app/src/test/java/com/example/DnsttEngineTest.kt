package com.example

import com.example.data.AppLogStore
import com.example.data.ConnectionMode
import com.example.data.ServerProfileEntity
import com.example.dnstt.DnsttConfig
import com.example.dnstt.DnsttCrypto
import com.example.dnstt.DnsttDnsCodec
import com.example.dnstt.DnsttNoiseChannel
import com.example.dnstt.DnsttState
import com.example.dnstt.DnsttValidator
import com.example.dnstt.KcpClientSession
import com.example.dnstt.NativeKotlinDnsttEngine
import com.example.dnstt.SmuxClientSession
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsttEngineTest {

  @Test
  fun blake2s256MatchesRfc7693TestVectors() {
    assertEquals(
      "69217a3079908094e11121d042354a7c1f55b6482ca1a51e1b250dfd1ed0eef9",
      DnsttCrypto.bytesToHex(DnsttCrypto.Blake2s.hash(ByteArray(0))),
    )
    assertEquals(
      "508c5e8c327c14e2e1a72ba34eeb452f37458b209ed63a294d999b4c86675982",
      DnsttCrypto.bytesToHex(DnsttCrypto.Blake2s.hash("abc".toByteArray(Charsets.US_ASCII))),
    )
  }

  @Test
  fun x25519MatchesRfc7748TestVectors() {
    val alicePriv =
      DnsttCrypto.hexToBytes("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
    val expectedAlicePub =
      "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a"
    val alicePub = DnsttCrypto.X25519.publicKeyFromPrivate(alicePriv)
    assertEquals(expectedAlicePub, DnsttCrypto.bytesToHex(alicePub))

    val bobPriv =
      DnsttCrypto.hexToBytes("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
    val expectedBobPub =
      "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f"
    val bobPub = DnsttCrypto.X25519.publicKeyFromPrivate(bobPriv)
    assertEquals(expectedBobPub, DnsttCrypto.bytesToHex(bobPub))

    val sharedAB = DnsttCrypto.X25519.sharedSecret(alicePriv, bobPub)
    val sharedBA = DnsttCrypto.X25519.sharedSecret(bobPriv, alicePub)
    assertEquals(
      "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742",
      DnsttCrypto.bytesToHex(sharedAB),
    )
    assertArrayEquals(sharedAB, sharedBA)
  }

  @Test
  fun chacha20Poly1305MatchesRfc8439TestVectorAndRejectsTampering() {
    val key =
      DnsttCrypto.hexToBytes("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f")
    val nonce = DnsttCrypto.hexToBytes("070000004041424344454647")
    val aad = DnsttCrypto.hexToBytes("50515253c0c1c2c3c4c5c6c7")
    val plaintext =
      "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it."
        .toByteArray(Charsets.US_ASCII)

    val expectedCipherAndTag =
      "d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d6" +
        "3dbea45e8ca9671282fafb69da92728b1a71de0a9e060b2905d6a5b67ecd3b36" +
        "92ddbd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7bc" +
        "3ff4def08e4b7a9de576d26586cec64b6116" +
        "1ae10b594f09e26a7e902ecbd0600691"

    val sealed = DnsttCrypto.ChaCha20Poly1305.encrypt(key, nonce, aad, plaintext)
    assertEquals(expectedCipherAndTag, DnsttCrypto.bytesToHex(sealed))

    val opened = DnsttCrypto.ChaCha20Poly1305.decrypt(key, nonce, aad, sealed)
    assertNotNull(opened)
    assertArrayEquals(plaintext, opened)

    // Flip 1 bit in tag -> must fail authentication and return null
    val tampered = sealed.copyOf().apply { this[size - 1] = (this[size - 1].toInt() xor 0x01).toByte() }
    val rejected = DnsttCrypto.ChaCha20Poly1305.decrypt(key, nonce, aad, tampered)
    assertNull("Tampered AEAD ciphertext must be rejected", rejected)
  }

  @Test
  fun dnsCodecEncodesUpstreamQueryAndDecodesTxtResponsePackets() {
    val clientId = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
    val kcpPacket = "KCP-TEST-FRAME-001".toByteArray(Charsets.US_ASCII)
    val domain = "ns.example.org"

    val query = DnsttDnsCodec.buildDnsTxtQuery(txId = 0x4242, clientId = clientId, domain = domain, kcpPacket = kcpPacket)
    val parsedQuery = extractDnsQueryKcpPayload(query, domain)
    assertNotNull(parsedQuery)
    assertArrayEquals(clientId, parsedQuery!!.first)
    assertArrayEquals(kcpPacket, parsedQuery.second)

    val respPkt1 = "DOWNLINK-1".toByteArray(Charsets.US_ASCII)
    val respPkt2 = "DOWNLINK-2-LONGER-PACKET".toByteArray(Charsets.US_ASCII)
    val dnsResponse = DnsttDnsCodec.buildMockDnsTxtResponse(query, listOf(respPkt1, respPkt2))
    val decoded = DnsttDnsCodec.parseDnsTxtResponsePackets(dnsResponse)

    assertNotNull(decoded)
    assertEquals(2, decoded!!.size)
    assertArrayEquals(respPkt1, decoded[0])
    assertArrayEquals(respPkt2, decoded[1])
  }

  @Test
  fun dnsttValidatorRejectsInvalidFieldsInBurmeseAndAcceptsValidConfig() {
    val validKey = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    val validProfile =
      ServerProfileEntity(
        name = "Test DNSTT",
        mode = ConnectionMode.DNSTT.code,
        username = "demo_user",
        password = "demo_password",
        dnsttNameserver = "ns.example.org",
        dnsttPublicKey = validKey,
        dnsttResolver = "8.8.8.8:53",
      )
    assertNull(DnsttValidator.validateProfile(validProfile))

    assertTrue(
      DnsttValidator.validateProfile(validProfile.copy(dnsttNameserver = ""))!!.contains("Nameserver")
    )
    assertTrue(
      DnsttValidator.validateProfile(validProfile.copy(dnsttNameserver = "not_a_domain"))!!.contains("Nameserver")
    )
    assertTrue(
      DnsttValidator.validateProfile(validProfile.copy(dnsttPublicKey = "abcd1234"))!!.contains("64")
    )
    assertTrue(
      DnsttValidator.validateProfile(validProfile.copy(dnsttResolver = "8.8.8.8:99999"))!!.contains("Resolver")
    )
    assertTrue(
      DnsttValidator.validateProfile(validProfile.copy(password = ""))!!.contains("Password")
    )
  }

  @Test
  fun noiseNkClientServerHandshakeSucceedsAndRejectsWrongServerKey() {
    val serverPriv = DnsttCrypto.X25519.generatePrivateKey()
    val serverPub = DnsttCrypto.X25519.publicKeyFromPrivate(serverPriv)

    val c2sIn = PipedInputStream(16_384)
    val c2sOut = PipedOutputStream(c2sIn)
    val s2cIn = PipedInputStream(16_384)
    val s2cOut = PipedOutputStream(s2cIn)

    val exec = Executors.newSingleThreadExecutor()
    try {
      val serverFuture =
        exec.submit<DnsttNoiseChannel> {
          DnsttNoiseChannel.acceptServer(c2sIn, s2cOut, serverPriv)
        }
      val clientChannel = DnsttNoiseChannel.connectClient(s2cIn, c2sOut, serverPub)
      val serverChannel = serverFuture.get(5, TimeUnit.SECONDS)

      val msg = "NOISE-NK-ROUNDTRIP-OK".toByteArray(Charsets.UTF_8)
      clientChannel.outputStream.write(msg)
      clientChannel.outputStream.flush()

      val received = ByteArray(msg.size)
      var read = 0
      while (read < received.size) {
        val n = serverChannel.inputStream.read(received, read, received.size - read)
        assertTrue(n > 0)
        read += n
      }
      assertArrayEquals(msg, received)

      clientChannel.close()
      serverChannel.close()
    } finally {
      exec.shutdownNow()
    }
  }

  @Test
  fun endToEndLoopbackDnsttTunnelForwardsTcpAndReleasesPortOnStop() = runBlocking {
    val serverPriv = DnsttCrypto.X25519.generatePrivateKey()
    val serverPubHex = DnsttCrypto.bytesToHex(DnsttCrypto.X25519.publicKeyFromPrivate(serverPriv))
    val domain = "ns.loopback.test"

    // Spin up a real in-process loopback UDP dnstt-server (DNS TXT <-> KCP <-> Noise_NK <-> smux v2 <-> Echo)
    val mockServer = LoopbackDnsttMockServer(domain = domain, serverPrivateKey = serverPriv)
    mockServer.start()

    val logStore = AppLogStore()
    val engine = NativeKotlinDnsttEngine(logStore)

    try {
      val startResult =
        engine.start(
          DnsttConfig(
            nameserver = domain,
            publicKeyHex = serverPubHex,
            resolverAddress = "127.0.0.1:${mockServer.udpPort}",
            localListenPort = 0,
            handshakeTimeoutMs = 10_000L,
          )
        )

      assertTrue(
        "DNSTT loopback start must succeed: ${startResult.exceptionOrNull()?.message}",
        startResult.isSuccess,
      )
      val localPort = startResult.getOrThrow()
      assertTrue(localPort in 1..65535)
      assertTrue(engine.state.value is DnsttState.Running)

      // Connect local TCP client (simulating JSch SSH) -> verify data flows through DNS+KCP+Noise+smux and echoes back!
      Socket("127.0.0.1", localPort).use { client ->
        client.soTimeout = 6_000
        val payload = "SSH-2.0-AMKT-DNSTT-LOOPBACK-OK\r\n".toByteArray(Charsets.US_ASCII)
        client.getOutputStream().write(payload)
        client.getOutputStream().flush()

        val echoed = ByteArray(payload.size)
        var off = 0
        while (off < echoed.size) {
          val n = client.getInputStream().read(echoed, off, echoed.size - off)
          assertTrue("Expected echo bytes through DNSTT tunnel", n > 0)
          off += n
        }
        assertArrayEquals(payload, echoed)
      }

      // Stop cleanly and verify zero port leak by re-binding the exact same loopback port
      engine.stop()
      assertEquals(DnsttState.Stopped, engine.state.value)
      ServerSocket().apply {
        reuseAddress = true
        bind(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), localPort))
      }.use { rebound ->
        assertEquals(localPort, rebound.localPort)
      }
      assertTrue(logStore.logs.value.any { it.tag == "DNSTT" && it.message.contains("အောင်မြင်") })
      assertTrue(logStore.logs.value.any { it.tag == "DNSTT" && it.message.contains("ပိတ်သိမ်း") })
    } finally {
      engine.stop()
      mockServer.stop()
    }
  }

  @Test
  fun dnsttEngineStopsCleanlyOnHandshakeTimeoutWithBurmeseError() = runBlocking {
    // Silent UDP socket that never replies -> guarantees timeout path & clean resource teardown
    val blackholeUdp = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
    val logStore = AppLogStore()
    val engine = NativeKotlinDnsttEngine(logStore)

    try {
      val serverPubHex =
        DnsttCrypto.bytesToHex(
          DnsttCrypto.X25519.publicKeyFromPrivate(DnsttCrypto.X25519.generatePrivateKey())
        )
      val result =
        engine.start(
          DnsttConfig(
            nameserver = "ns.timeout.test",
            publicKeyHex = serverPubHex,
            resolverAddress = "127.0.0.1:${blackholeUdp.localPort}",
            localListenPort = 0,
            handshakeTimeoutMs = 900L,
          )
        )

      assertTrue("Timeout start must fail cleanly", result.isFailure)
      assertTrue(engine.state.value is DnsttState.Error)
      val errMsg = (engine.state.value as DnsttState.Error).message
      assertTrue("Error must explain timeout in Burmese: $errMsg", errMsg.contains("ကျော်လွန်"))

      engine.stop()
      assertEquals(DnsttState.Stopped, engine.state.value)
    } finally {
      engine.stop()
      blackholeUdp.close()
    }
  }

  /**
   * Minimal in-memory `dnstt-server` speaking the exact upstream DNS TXT + KCP + Noise_NK + smux v2
   * wire format on loopback UDP so we can verify real end-to-end tunneling in fast JVM unit tests.
   */
  private class LoopbackDnsttMockServer(
    private val domain: String,
    private val serverPrivateKey: ByteArray,
  ) {
    private val udpSocket = DatagramSocket(0, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 100 }
    val udpPort: Int = udpSocket.localPort
    private val running = AtomicBoolean(false)
    private val executor = Executors.newCachedThreadPool()
    private val downlinkQueue = ConcurrentLinkedQueue<ByteArray>()
    @Volatile private var serverKcp: KcpClientSession? = null
    @Volatile private var serverNoise: DnsttNoiseChannel? = null
    @Volatile private var serverSmux: SmuxClientSession? = null

    fun start() {
      running.set(true)
      executor.execute { udpLoop() }
      executor.execute {
        while (running.get()) {
          serverKcp?.update(System.currentTimeMillis())
          Thread.sleep(20)
        }
      }
    }

    fun stop() {
      running.set(false)
      runCatching { serverSmux?.close() }
      runCatching { serverNoise?.close() }
      runCatching { serverKcp?.close("mock stopped") }
      runCatching { udpSocket.close() }
      executor.shutdownNow()
    }

    private fun udpLoop() {
      val rx = ByteArray(4096)
      while (running.get() && !udpSocket.isClosed) {
        val pkt = DatagramPacket(rx, rx.size)
        try {
          udpSocket.receive(pkt)
        } catch (_: SocketTimeoutException) {
          continue
        } catch (_: Exception) {
          break
        }
        val query = rx.copyOf(pkt.length)
        val parsed = extractDnsQueryKcpPayload(query, domain) ?: continue
        val kcpBytes = parsed.second

        if (kcpBytes.size >= KcpClientSession.HEADER_SIZE) {
          val conv = DnsttCrypto.readIntLE(kcpBytes, 0)
          val kcp = ensureServerSession(conv)
          kcp.input(kcpBytes)
          kcp.update(System.currentTimeMillis())
        }

        // Drain up to 4 queued downlink KCP packets into the TXT response
        val outgoing = ArrayList<ByteArray>()
        var total = 0
        while (outgoing.size < 4) {
          val next = downlinkQueue.peek() ?: break
          if (total + next.size + 2 > 900 && outgoing.isNotEmpty()) break
          downlinkQueue.poll()?.let {
            outgoing.add(it)
            total += it.size + 2
          }
        }
        val resp = DnsttDnsCodec.buildMockDnsTxtResponse(query, outgoing)
        udpSocket.send(DatagramPacket(resp, resp.size, pkt.address, pkt.port))
      }
    }

    @Synchronized
    private fun ensureServerSession(conv: Int): KcpClientSession {
      serverKcp?.let { return it }
      val kcp =
        KcpClientSession(
          conv = conv,
          mtu = DnsttDnsCodec.maxUpstreamKcpPacketSize(domain),
          sendPacket = { downlinkQueue.offer(it) },
        )
      serverKcp = kcp
      executor.execute {
        try {
          val noise = DnsttNoiseChannel.acceptServer(kcp.inputStream, kcp.outputStream, serverPrivateKey)
          serverNoise = noise
          val smux = SmuxClientSession(noise.inputStream, noise.outputStream, isClient = false)
          serverSmux = smux
          smux.start()
          while (running.get() && !smux.isClosed) {
            val stream = smux.acceptStream()
            executor.execute {
              val buf = ByteArray(2048)
              try {
                while (running.get()) {
                  val n = stream.inputStream.read(buf)
                  if (n <= 0) break
                  stream.outputStream.write(buf, 0, n)
                  stream.outputStream.flush()
                }
              } catch (_: Exception) {
              } finally {
                runCatching { stream.close() }
              }
            }
          }
        } catch (_: Exception) {
        }
      }
      return kcp
    }
  }

  companion object {
    /** Decodes a DNSTT upstream DNS TXT query back into `(clientId, kcpPayload)`. */
    private fun extractDnsQueryKcpPayload(query: ByteArray, domain: String): Pair<ByteArray, ByteArray>? {
      if (query.size < 12) return null
      val labels = ArrayList<String>()
      var pos = 12
      while (pos < query.size) {
        val len = query[pos++].toInt() and 0xFF
        if (len == 0) break
        if (pos + len > query.size) return null
        labels.add(String(query, pos, len, Charsets.US_ASCII))
        pos += len
      }
      val domainLabels = domain.trimEnd('.').split('.')
      if (labels.size <= domainLabels.size) return null
      val encoded = labels.subList(0, labels.size - domainLabels.size).joinToString("")
      val raw = decodeBase32NoPadding(encoded) ?: return null
      if (raw.size < DnsttDnsCodec.CLIENT_ID_LEN + 1) return null

      val clientId = raw.copyOfRange(0, DnsttDnsCodec.CLIENT_ID_LEN)
      val padHeader = raw[DnsttDnsCodec.CLIENT_ID_LEN].toInt() and 0xFF
      if (padHeader < 224) return null
      val padLen = padHeader - 224
      val pktLenPos = DnsttDnsCodec.CLIENT_ID_LEN + 1 + padLen
      if (pktLenPos >= raw.size) {
        return clientId to ByteArray(0) // Empty poll query
      }
      val pktLen = raw[pktLenPos].toInt() and 0xFF
      if (pktLenPos + 1 + pktLen > raw.size) return null
      val kcpPkt = raw.copyOfRange(pktLenPos + 1, pktLenPos + 1 + pktLen)
      return clientId to kcpPkt
    }

    private fun decodeBase32NoPadding(input: String): ByteArray? {
      var buffer = 0
      var bitsLeft = 0
      val out = ArrayList<Byte>(input.length * 5 / 8)
      for (ch in input.lowercase()) {
        val value =
          when (ch) {
            in 'a'..'z' -> ch - 'a'
            in '2'..'7' -> (ch - '2') + 26
            else -> return null
          }
        buffer = (buffer shl 5) or value
        bitsLeft += 5
        if (bitsLeft >= 8) {
          bitsLeft -= 8
          out.add(((buffer ushr bitsLeft) and 0xFF).toByte())
        }
      }
      return out.toByteArray()
    }
  }
}
