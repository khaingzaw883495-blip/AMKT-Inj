package com.example

import com.example.config.LinkConfigParser
import com.example.data.AppLogStore
import com.example.data.ConnectionMode
import com.example.data.ServerProfileEntity
import com.example.vless.NativeVlessTunnelEngine
import com.example.vless.VlessConfigParser
import com.example.vless.VlessProtocol
import com.example.vless.VlessTunnelState
import com.example.vless.WebSocketTransport
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VlessEngineTest {

  private val sampleUuid = "11111111-2222-3333-4444-555555555555"

  @Test
  fun encodesAndDecodesVlessV0RequestHeadersForDomainIpv4AndIpv6() {
    val uuidBytes = VlessProtocol.parseUuidBytes(sampleUuid)
    assertEquals(16, uuidBytes.size)
    assertEquals(0x11.toByte(), uuidBytes[0])
    assertEquals(0x55.toByte(), uuidBytes[15])

    // 1. Domain target
    val domainHeader =
      VlessProtocol.encodeRequestHeader(
        uuidBytes = uuidBytes,
        targetHost = "edge.example.com",
        targetPort = 443,
        command = VlessProtocol.COMMAND_TCP,
      )
    val decodedDomain = VlessProtocol.decodeRequestHeader(domainHeader)
    assertEquals(0, decodedDomain.version)
    assertArrayEquals(uuidBytes, decodedDomain.uuidBytes)
    assertEquals(0, decodedDomain.addons.size)
    assertEquals(VlessProtocol.COMMAND_TCP.toInt(), decodedDomain.command)
    assertEquals(443, decodedDomain.port)
    assertEquals(VlessProtocol.ADDRESS_TYPE_DOMAIN.toInt(), decodedDomain.addressType)
    assertEquals("edge.example.com", decodedDomain.targetHost)
    assertEquals(domainHeader.size, decodedDomain.headerLength)

    // 2. IPv4 target
    val ipv4Header =
      VlessProtocol.encodeRequestHeader(
        uuidBytes = uuidBytes,
        targetHost = "198.51.100.42",
        targetPort = 80,
      )
    val decodedIpv4 = VlessProtocol.decodeRequestHeader(ipv4Header)
    assertEquals(VlessProtocol.ADDRESS_TYPE_IPV4.toInt(), decodedIpv4.addressType)
    assertEquals("198.51.100.42", decodedIpv4.targetHost)
    assertEquals(80, decodedIpv4.port)

    // 3. IPv6 target
    val ipv6Header =
      VlessProtocol.encodeRequestHeader(
        uuidBytes = uuidBytes,
        targetHost = "2001:db8::1",
        targetPort = 8443,
      )
    val decodedIpv6 = VlessProtocol.decodeRequestHeader(ipv6Header)
    assertEquals(VlessProtocol.ADDRESS_TYPE_IPV6.toInt(), decodedIpv6.addressType)
    assertEquals(8443, decodedIpv6.port)
    assertTrue(
      "Decoded IPv6 must match 2001:db8::1",
      InetAddress.getByName(decodedIpv6.targetHost) == InetAddress.getByName("2001:db8::1"),
    )

    // 4. Response header decoding (empty addons + non-empty addons)
    val emptyResp = VlessProtocol.decodeResponseHeader(ByteArrayInputStream(byteArrayOf(0x00, 0x00)))
    assertEquals(0, emptyResp.version)
    assertEquals(0, emptyResp.addons.size)

    val customAddons = byteArrayOf(0x0A, 0x0B, 0x0C)
    val encodedResp = VlessProtocol.encodeResponseHeader(customAddons)
    val decodedResp = VlessProtocol.decodeResponseHeader(ByteArrayInputStream(encodedResp))
    assertEquals(0, decodedResp.version)
    assertArrayEquals(customAddons, decodedResp.addons)
  }

  @Test
  fun buildsRfc6455WebSocketHandshakeAndComputesStandardAcceptKey() {
    // Official RFC 6455 Section 1.3 test vector
    val rfcVectorKey = "dGhlIHNhbXBsZSBub25jZQ=="
    val expectedAccept = "s3pPLMBiTxaQ9kYGzzhZRbK+xOo="
    assertEquals(expectedAccept, WebSocketTransport.computeAcceptKey(rfcVectorKey))

    val request =
      WebSocketTransport.buildHandshakeRequest(
        wsPath = "/vless-ws?ed=2048",
        wsHost = "cdn.example.com",
        secWebSocketKey = rfcVectorKey,
      )
    assertTrue(request.startsWith("GET /vless-ws HTTP/1.1\r\n"))
    assertTrue(request.contains("Host: cdn.example.com\r\n"))
    assertTrue(request.contains("Upgrade: websocket\r\n"))
    assertTrue(request.contains("Connection: Upgrade\r\n"))
    assertTrue(request.contains("Sec-WebSocket-Key: $rfcVectorKey\r\n"))
    assertTrue(request.contains("Sec-WebSocket-Version: 13\r\n"))
    assertTrue(request.endsWith("\r\n\r\n"))
  }

  @Test
  fun parsesVlessLinksAndProfileFallbackAndRejectsUnsupportedOptions() {
    val plainWsLink =
      "vless://$sampleUuid@edge.example.com:80?encryption=none&security=none&type=ws&host=cdn.example.com&path=%2Fvless-ws%3Fed%3D2048#SG%20WS%20Node"
    val parsedPlain = VlessConfigParser.parseLink(plainWsLink).getOrThrow()
    assertEquals(sampleUuid, parsedPlain.uuid)
    assertEquals("edge.example.com", parsedPlain.serverHost)
    assertEquals(80, parsedPlain.serverPort)
    assertEquals("/vless-ws", parsedPlain.wsPath)
    assertEquals("cdn.example.com", parsedPlain.wsHost)
    assertFalse(parsedPlain.useTls)
    assertEquals("none", parsedPlain.securityLabel)
    assertEquals("SG WS Node", parsedPlain.remark)

    // LinkConfigParser delegates cleanly and populates username = UUID
    val importedEntity = LinkConfigParser.parseVlessLine(plainWsLink)
    assertNotNull(importedEntity)
    assertEquals(ConnectionMode.VLESS_WS, importedEntity!!.connectionMode)
    assertEquals(sampleUuid, importedEntity.username)
    assertEquals("/vless-ws", importedEntity.payload)
    assertEquals("edge.example.com", importedEntity.sshHost)
    assertEquals(80, importedEntity.sshPort)

    // TLS link with explicit SNI
    val tlsLink =
      "vless://$sampleUuid@edge.example.com:443?encryption=none&security=tls&type=ws&sni=sni.example.com&host=ws.example.com&path=/ws#TLS"
    val parsedTls = VlessConfigParser.parseLink(tlsLink).getOrThrow()
    assertTrue(parsedTls.useTls)
    assertEquals("sni.example.com", parsedTls.effectiveSni)
    assertEquals("ws.example.com", parsedTls.effectiveWsHost)
    assertEquals("/ws", parsedTls.wsPath)

    // Profile fallback: starter-style profile where user typed UUID in username field
    val starterStyleProfile =
      ServerProfileEntity(
        name = "Example CloudFront VLESS",
        mode = ConnectionMode.VLESS_WS.code,
        sshHost = "edge.example.com",
        sshPort = 80,
        username = sampleUuid,
        payload = "/vless-ws",
        sni = "edge.example.com",
        vlessLink = "",
      )
    val fromProfile = VlessConfigParser.fromProfile(starterStyleProfile).getOrThrow()
    assertEquals(sampleUuid, fromProfile.uuid)
    assertEquals("edge.example.com", fromProfile.serverHost)
    assertEquals(80, fromProfile.serverPort)
    assertEquals("/vless-ws", fromProfile.wsPath)
    assertFalse(fromProfile.useTls)

    // Rejections
    assertTrue(
      VlessConfigParser.parseLink("vless://not-a-uuid@edge.example.com:80?type=ws").isFailure
    )
    assertTrue(
      VlessConfigParser.parseLink("vless://$sampleUuid@edge.example.com:443?type=grpc").isFailure
    )
    assertTrue(
      VlessConfigParser.parseLink(
        "vless://$sampleUuid@edge.example.com:80?type=ws&encryption=aes-128-gcm"
      ).isFailure
    )
  }

  @Test
  fun endToEndLoopbackVlessWebSocketRelaysSocks5TrafficAndLogsBurmeseSteps() = runBlocking {
    val mockServer = MockVlessWebSocketServer(expectedPath = "/vless-ws", expectedUuid = sampleUuid)
    val serverPort = mockServer.start()
    val logStore = AppLogStore()
    val engine =
      NativeVlessTunnelEngine(
        logStore = logStore,
        transportFactory = {
          WebSocketTransport(connectTimeoutMs = 4_000, handshakeTimeoutMs = 4_000)
        },
      )

    try {
      val profile =
        ServerProfileEntity(
          name = "Loopback VLESS WS",
          mode = ConnectionMode.VLESS_WS.code,
          sshHost = "127.0.0.1",
          sshPort = serverPort,
          payload = "/vless-ws",
          sni = "cdn.example.com",
          vlessLink =
            "vless://$sampleUuid@127.0.0.1:$serverPort?encryption=none&security=none&type=ws&host=cdn.example.com&path=%2Fvless-ws#Loopback",
        )

      val connectResult = engine.connect(profile = profile, localSocksPort = 0)
      assertTrue(
        "VLESS engine must connect cleanly: ${connectResult.exceptionOrNull()?.message}",
        connectResult.isSuccess,
      )
      val socksPort = connectResult.getOrThrow()
      assertTrue("Local SOCKS5 proxy must bind a valid port", socksPort in 1..65535)
      assertTrue(engine.state.value is VlessTunnelState.Connected)

      // Open a real SOCKS5 CONNECT through 127.0.0.1:socksPort -> target echo.example.test:443
      Socket("127.0.0.1", socksPort).use { client ->
        client.soTimeout = 5_000
        val out = client.getOutputStream()
        val inp = DataInputStream(BufferedInputStream(client.getInputStream()))

        // SOCKS5 greeting: VER=5, NMETHODS=1, NO_AUTH=0
        out.write(byteArrayOf(0x05, 0x01, 0x00))
        out.flush()
        val greetReply = ByteArray(2)
        inp.readFully(greetReply)
        assertArrayEquals(byteArrayOf(0x05, 0x00), greetReply)

        // SOCKS5 CONNECT to domain echo.example.test:443
        val targetDomain = "echo.example.test".toByteArray(StandardCharsets.US_ASCII)
        val req = ByteArrayOutputStream()
        req.write(byteArrayOf(0x05, 0x01, 0x00, 0x03, targetDomain.size.toByte()))
        req.write(targetDomain)
        req.write(byteArrayOf((443 ushr 8).toByte(), (443 and 0xFF).toByte()))
        out.write(req.toByteArray())
        out.flush()

        val connReply = ByteArray(10)
        inp.readFully(connReply)
        assertEquals(0x05.toByte(), connReply[0])
        assertEquals(0x00.toByte(), connReply[1])

        // Send application payload over VLESS/WS and read echoed response
        val message = "PING-VLESS-M6"
        out.write(message.toByteArray(StandardCharsets.UTF_8))
        out.flush()

        val expectedReply = "ECHO:echo.example.test:443:$message".toByteArray(StandardCharsets.UTF_8)
        val actualReply = ByteArray(expectedReply.size)
        inp.readFully(actualReply)
        assertEquals(
          String(expectedReply, StandardCharsets.UTF_8),
          String(actualReply, StandardCharsets.UTF_8),
        )
      }

      // Verify Burmese step-by-step logs
      val logMessages = logStore.logs.value.map { it.message }
      assertTrue(
        "Expected Burmese connecting log",
        logMessages.any { it.contains("VLESS ချိတ်ဆက်နေသည်") },
      )
      assertTrue(
        "Expected Burmese WebSocket established log",
        logMessages.any { it.contains("WebSocket တည်ဆောက်ပြီးပါပြီ") },
      )
      assertTrue(
        "Expected Burmese tunnel ready log",
        logMessages.any { it.contains("VLESS tunnel စတင်ပြီးပါပြီ") && it.contains("127.0.0.1:$socksPort") },
      )
      assertTrue("Mock server should have accepted probe + data WS connections", mockServer.acceptedCount >= 2)
    } finally {
      engine.disconnect()
      assertEquals(VlessTunnelState.Disconnected, engine.state.value)
      mockServer.stop()
    }
  }

  /**
   * Minimal RFC 6455 + VLESS v0 loopback test server with strict timeouts so tests never hang.
   */
  private class MockVlessWebSocketServer(
    private val expectedPath: String,
    private val expectedUuid: String,
  ) {
    private val executor = Executors.newCachedThreadPool()
    private val openSockets = CopyOnWriteArrayList<Socket>()
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var running = false
    @Volatile var acceptedCount = 0

    fun start(): Int {
      val ss = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
      ss.soTimeout = 500
      serverSocket = ss
      running = true
      executor.submit {
        while (running) {
          try {
            val client = ss.accept()
            acceptedCount += 1
            openSockets.add(client)
            executor.submit { handleClient(client) }
          } catch (_: IOException) {
            // Poll timeout or closed
          }
        }
      }
      return ss.localPort
    }

    fun stop() {
      running = false
      runCatching { serverSocket?.close() }
      openSockets.forEach { runCatching { it.close() } }
      openSockets.clear()
      executor.shutdownNow()
      executor.awaitTermination(2, TimeUnit.SECONDS)
    }

    private fun handleClient(socket: Socket) {
      socket.use { sock ->
        sock.soTimeout = 4_000
        val inp = BufferedInputStream(sock.getInputStream())
        val out = sock.getOutputStream()

        // 1. Read HTTP Upgrade headers
        val headerBuf = ByteArrayOutputStream()
        var prev3 = -1
        var prev2 = -1
        var prev1 = -1
        while (headerBuf.size() < 8192) {
          val b = inp.read()
          if (b == -1) return
          headerBuf.write(b)
          if (prev3 == '\r'.code && prev2 == '\n'.code && prev1 == '\r'.code && b == '\n'.code) {
            break
          }
          prev3 = prev2
          prev2 = prev1
          prev1 = b
        }

        val lines =
          String(headerBuf.toByteArray(), StandardCharsets.ISO_8859_1)
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        val requestLine = lines.firstOrNull().orEmpty()
        val secKey =
          lines.firstOrNull { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            .orEmpty()

        if (!requestLine.startsWith("GET $expectedPath HTTP/1.1") || secKey.isBlank()) {
          out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
          out.flush()
          return
        }

        val acceptKey = WebSocketTransport.computeAcceptKey(secKey)
        val response =
          "HTTP/1.1 101 Switching Protocols\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: $acceptKey\r\n\r\n"
        out.write(response.toByteArray(StandardCharsets.ISO_8859_1))
        out.flush()

        // 2. Read first masked client WebSocket frame (VLESS request header)
        val firstFrame = readMaskedClientFrame(inp) ?: return
        val decoded = VlessProtocol.decodeRequestHeader(firstFrame)
        val expectedUuidBytes = VlessProtocol.parseUuidBytes(expectedUuid)
        if (!decoded.uuidBytes.contentEquals(expectedUuidBytes)) return

        // Any trailing bytes in first frame + next frame form the payload
        val payloadAfterHeader =
          if (firstFrame.size > decoded.headerLength) {
            firstFrame.copyOfRange(decoded.headerLength, firstFrame.size)
          } else {
            readMaskedClientFrame(inp) ?: return
          }

        val payloadText = String(payloadAfterHeader, StandardCharsets.UTF_8)
        val replyBody =
          "ECHO:${decoded.targetHost}:${decoded.port}:$payloadText".toByteArray(StandardCharsets.UTF_8)
        val vlessRespHeader = VlessProtocol.encodeResponseHeader()
        val fullReply = ByteArray(vlessRespHeader.size + replyBody.size)
        System.arraycopy(vlessRespHeader, 0, fullReply, 0, vlessRespHeader.size)
        System.arraycopy(replyBody, 0, fullReply, vlessRespHeader.size, replyBody.size)

        // Write unmasked server binary frame (RFC 6455 §5.1)
        writeServerBinaryFrame(out, fullReply)
      }
    }

    private fun readMaskedClientFrame(inp: BufferedInputStream): ByteArray? {
      val b0 = inp.read()
      if (b0 == -1) return null
      val b1 = inp.read()
      if (b1 == -1) return null
      val opcode = b0 and 0x0F
      if (opcode == 0x8) return null // Close frame (e.g., from probeWs.close())
      val masked = (b1 and 0x80) != 0
      var len = b1 and 0x7F
      if (len == 126) {
        val hi = inp.read()
        val lo = inp.read()
        if (hi == -1 || lo == -1) return null
        len = (hi shl 8) or lo
      }
      val mask = ByteArray(4)
      if (masked) {
        var r = 0
        while (r < 4) {
          val n = inp.read(mask, r, 4 - r)
          if (n == -1) return null
          r += n
        }
      }
      val data = ByteArray(len)
      var r = 0
      while (r < len) {
        val n = inp.read(data, r, len - r)
        if (n == -1) return null
        r += n
      }
      if (masked) {
        for (i in data.indices) {
          data[i] = (data[i].toInt() xor mask[i and 3].toInt()).toByte()
        }
      }
      return data
    }

    private fun writeServerBinaryFrame(out: java.io.OutputStream, payload: ByteArray) {
      val header =
        if (payload.size < 126) {
          byteArrayOf(0x82.toByte(), payload.size.toByte())
        } else {
          byteArrayOf(
            0x82.toByte(),
            126.toByte(),
            ((payload.size ushr 8) and 0xFF).toByte(),
            (payload.size and 0xFF).toByte(),
          )
        }
      out.write(header)
      out.write(payload)
      out.flush()
    }
  }
}
