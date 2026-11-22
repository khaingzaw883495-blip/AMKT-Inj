package com.example

import com.example.data.AppLogStore
import com.example.data.ConnectionMode
import com.example.data.PayloadMode
import com.example.data.PayloadTemplates
import com.example.data.ServerProfileEntity
import com.example.ssh.InjectorSocketFactory
import com.example.ssh.PayloadInjector
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PayloadInjectorTest {

  @Test
  fun substitutesHostPortAndSniPlaceholdersWithFallback() {
    val template =
      "GET /ws HTTP/1.1[crlf]Host: [sni]:[port][crlf]X-Target: [host]:[port][crlf][crlf]"

    val withExplicitSni =
      PayloadInjector.buildPayloadText(
        template = template,
        host = "203.0.113.10",
        port = 80,
        sni = "cdn.example.org",
      )
    assertEquals(
      "GET /ws HTTP/1.1\r\nHost: cdn.example.org:80\r\nX-Target: 203.0.113.10:80\r\n\r\n",
      withExplicitSni,
    )

    val withEmptySniFallback =
      PayloadInjector.buildPayloadText(
        template = template,
        host = "edge.example.net",
        port = 143,
        sni = "   ",
      )
    assertEquals(
      "GET /ws HTTP/1.1\r\nHost: edge.example.net:143\r\nX-Target: edge.example.net:143\r\n\r\n",
      withEmptySniFallback,
    )
  }

  @Test
  fun appendsExtraHeadersOnePerLineAndNormalizesTerminator() {
    val built =
      PayloadInjector.buildPayloadText(
        template = "CONNECT [host]:[port] HTTP/1.1\nHost: [host]\n\n",
        host = "198.51.100.5",
        port = 443,
        sni = "front.example.com",
        extraHeaders = "  User-Agent: TestClient/1.0 \n\nX-Online-Host: [sni]\r\n   \nX-Custom: ok",
      )

    assertEquals(
      "CONNECT 198.51.100.5:443 HTTP/1.1\r\n" +
        "Host: 198.51.100.5\r\n" +
        "User-Agent: TestClient/1.0\r\n" +
        "X-Online-Host: front.example.com\r\n" +
        "X-Custom: ok\r\n\r\n",
      built,
    )
  }

  @Test
  fun sniSelectionUsesExplicitSniOrFallsBackToProfileHostAndValidatesDnsName() {
    assertEquals("front.example.com", PayloadInjector.resolveSniHost(" front.example.com ", "198.51.100.7"))
    assertEquals("server.example.com", PayloadInjector.resolveSniHost("", "server.example.com"))
    assertEquals("server.example.com", PayloadInjector.resolveSniHost("   ", " server.example.com "))

    assertTrue(PayloadInjector.isValidDnsSniHostname("front.example.com"))
    assertTrue(PayloadInjector.isValidDnsSniHostname("sub-1.cdn.example.org"))
    assertFalse(PayloadInjector.isValidDnsSniHostname("198.51.100.7"))
    assertFalse(PayloadInjector.isValidDnsSniHostname("2001:db8::1"))
    assertFalse(PayloadInjector.isValidDnsSniHostname(""))
  }

  @Test
  fun webSocketModeAcceptsHttp101AndPreservesImmediateSshBannerBytes() {
    val receivedRequest = AtomicReference<String>("")
    val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    val port = server.localPort
    val executor = Executors.newSingleThreadExecutor()

    executor.execute {
      try {
        server.accept().use { client ->
          val buf = ByteArray(2048)
          val n = client.getInputStream().read(buf)
          if (n > 0) {
            receivedRequest.set(String(buf, 0, n, StandardCharsets.ISO_8859_1))
          }
          val response =
            "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\nSSH-2.0-OpenSSH_Test\r\n"
          client.getOutputStream().write(response.toByteArray(StandardCharsets.ISO_8859_1))
          client.getOutputStream().flush()
          Thread.sleep(200)
        }
      } catch (_: Exception) {}
    }

    val logStore = AppLogStore()
    val profile =
      ServerProfileEntity(
        name = "WS Loopback",
        mode = ConnectionMode.SSH_WEBSOCKET.code,
        payloadMode = PayloadMode.WEBSOCKET.code,
        sshHost = "127.0.0.1",
        sshPort = port,
        payload = PayloadTemplates.WEBSOCKET_EXAMPLE,
        extraHeaders = "X-Test-Header: alpha",
      )

    try {
      val factory = InjectorSocketFactory(profile, logStore)
      factory.createSocket("127.0.0.1", port).use { socket ->
        val bannerBuf = ByteArray(22)
        val read = socket.getInputStream().read(bannerBuf)
        val banner = String(bannerBuf, 0, read, StandardCharsets.ISO_8859_1)
        assertEquals("SSH-2.0-OpenSSH_Test\r\n", banner)
      }

      val sent = receivedRequest.get()
      assertTrue(sent.contains("Host: 127.0.0.1:$port\r\n"))
      assertTrue(sent.contains("Upgrade: websocket\r\n"))
      assertTrue(sent.contains("X-Test-Header: alpha\r\n\r\n"))
      assertTrue(logStore.logs.value.any { it.message.contains("HTTP/1.1 101 Switching Protocols") })
      // Must NOT leak raw payload headers into logs
      assertFalse(logStore.logs.value.any { it.message.contains("X-Test-Header: alpha") })
    } finally {
      server.close()
      executor.shutdownNow()
    }
  }

  @Test
  fun webSocketModeRejectsNon101ResponseWithBurmeseError() {
    val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    val port = server.localPort
    val executor = Executors.newSingleThreadExecutor()

    executor.execute {
      try {
        server.accept().use { client ->
          val buf = ByteArray(1024)
          client.getInputStream().read(buf)
          val response = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"
          client.getOutputStream().write(response.toByteArray(StandardCharsets.ISO_8859_1))
          client.getOutputStream().flush()
        }
      } catch (_: Exception) {}
    }

    val logStore = AppLogStore()
    val profile =
      ServerProfileEntity(
        name = "WS Reject 200",
        mode = ConnectionMode.SSH_WEBSOCKET.code,
        payloadMode = PayloadMode.WEBSOCKET.code,
        sshHost = "127.0.0.1",
        sshPort = port,
        payload = PayloadTemplates.WEBSOCKET_EXAMPLE,
      )

    try {
      val factory = InjectorSocketFactory(profile, logStore)
      try {
        factory.createSocket("127.0.0.1", port)
        fail("Expected IOException when WebSocket server returns HTTP 200 instead of 101")
      } catch (e: IOException) {
        assertTrue(e.message.orEmpty().contains("101"))
        assertTrue(e.message.orEmpty().contains("HTTP/1.1 200 OK"))
      }
    } finally {
      server.close()
      executor.shutdownNow()
    }
  }

  @Test
  fun httpPayloadModeAccepts200AndRejects407() {
    // 1) Accept HTTP 200 Connection Established + preserve SSH banner
    val okServer = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    val okPort = okServer.localPort
    val executor = Executors.newFixedThreadPool(2)
    executor.execute {
      try {
        okServer.accept().use { client ->
          client.getInputStream().read(ByteArray(1024))
          val reply = "HTTP/1.1 200 Connection Established\r\n\r\nSSH-2.0-Dropbear\r\n"
          client.getOutputStream().write(reply.toByteArray(StandardCharsets.ISO_8859_1))
          client.getOutputStream().flush()
          Thread.sleep(200)
        }
      } catch (_: Exception) {}
    }

    // 2) Reject HTTP 407 Proxy Authentication Required
    val failServer = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    val failPort = failServer.localPort
    executor.execute {
      try {
        failServer.accept().use { client ->
          client.getInputStream().read(ByteArray(1024))
          val reply = "HTTP/1.1 407 Proxy Authentication Required\r\n\r\n"
          client.getOutputStream().write(reply.toByteArray(StandardCharsets.ISO_8859_1))
          client.getOutputStream().flush()
        }
      } catch (_: Exception) {}
    }

    try {
      val okProfile =
        ServerProfileEntity(
          name = "HTTP Payload OK",
          mode = ConnectionMode.SSH_PROXY.code,
          payloadMode = PayloadMode.HTTP_PAYLOAD.code,
          sshHost = "127.0.0.1",
          sshPort = okPort,
          payload = PayloadTemplates.CONNECT_EXAMPLE,
        )
      InjectorSocketFactory(okProfile, AppLogStore()).createSocket("127.0.0.1", okPort).use { sock ->
        val buf = ByteArray(18)
        val n = sock.getInputStream().read(buf)
        assertEquals("SSH-2.0-Dropbear\r\n", String(buf, 0, n, StandardCharsets.ISO_8859_1))
      }

      val failProfile = okProfile.copy(name = "HTTP Payload 407", sshPort = failPort)
      try {
        InjectorSocketFactory(failProfile, AppLogStore()).createSocket("127.0.0.1", failPort)
        fail("Expected IOException for HTTP 407")
      } catch (e: IOException) {
        assertTrue(e.message.orEmpty().contains("407"))
      }
    } finally {
      okServer.close()
      failServer.close()
      executor.shutdownNow()
    }
  }

  @Test
  fun directModeConnectsDirectlyWithoutSendingHttpPayload() {
    val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    val port = server.localPort
    val executor = Executors.newSingleThreadExecutor()

    executor.execute {
      try {
        server.accept().use { client ->
          // Immediately send SSH banner without waiting for any HTTP request
          client.getOutputStream().write("SSH-2.0-DirectOK\r\n".toByteArray(StandardCharsets.ISO_8859_1))
          client.getOutputStream().flush()
          Thread.sleep(150)
        }
      } catch (_: Exception) {}
    }

    val directProfile =
      ServerProfileEntity(
        name = "Direct Unchanged",
        mode = ConnectionMode.SSH_DIRECT.code,
        payloadMode = PayloadMode.DIRECT.code,
        sshHost = "127.0.0.1",
        sshPort = port,
        payload = PayloadTemplates.WEBSOCKET_EXAMPLE, // Ignored in DIRECT mode
      )

    try {
      InjectorSocketFactory(directProfile, AppLogStore()).createSocket("127.0.0.1", port).use { sock ->
        val buf = ByteArray(18)
        val n = sock.getInputStream().read(buf)
        assertEquals("SSH-2.0-DirectOK\r\n", String(buf, 0, n, StandardCharsets.ISO_8859_1))
      }
    } finally {
      server.close()
      executor.shutdownNow()
    }
  }

  @Test
  fun starterTemplatesUsePlaceholdersOnlyAndNoHardcodedServers() {
    assertTrue(PayloadTemplates.CONNECT_EXAMPLE.contains("[host]:[port]"))
    assertTrue(PayloadTemplates.WEBSOCKET_EXAMPLE.contains("[host]:[port]"))
    assertTrue(PayloadTemplates.WEBSOCKET_EXAMPLE.contains("Upgrade: websocket"))
    assertTrue(PayloadTemplates.SPLIT_WEBSOCKET_EXAMPLE.contains("[split]"))
    assertTrue(PayloadTemplates.SPLIT_WEBSOCKET_EXAMPLE.contains("[ua]"))
    assertTrue(PayloadTemplates.SPLIT_WEBSOCKET_EXAMPLE.contains("[host]"))
    assertEquals("", PayloadTemplates.BLANK)
  }

  @Test
  fun webSocketModeToleratesInterim301_200_403AndSucceedsOn101WithBurmeseLogs() {
    val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    val port = server.localPort
    val executor = Executors.newSingleThreadExecutor()

    executor.execute {
      try {
        server.accept().use { client ->
          client.soTimeout = 2_000
          // Drain client's split/pipelined HTTP requests
          val buf = ByteArray(4096)
          val firstRead = client.getInputStream().read(buf)
          if (firstRead > 0 && client.getInputStream().available() == 0) {
            Thread.sleep(60)
            if (client.getInputStream().available() > 0) {
              client.getInputStream().read(buf)
            }
          }

          val out = client.getOutputStream()
          val body301 = "<html><body>301 Moved</body></html>"
          val body403 = "<html><body>403 Forbidden</body></html>"
          val resp1 =
            "HTTP/1.1 301 Moved Permanently\r\n" +
              "Location: https://front.example.com/\r\n" +
              "Content-Length: ${body301.length}\r\n\r\n" +
              body301
          val resp2 =
            "HTTP/1.1 200 OK\r\n" +
              "Transfer-Encoding: chunked\r\n\r\n" +
              "5\r\nhello\r\n0\r\n\r\n"
          val resp3 =
            "HTTP/1.1 403 Forbidden\r\n" +
              "Content-Length: ${body403.length}\r\n\r\n" +
              body403
          val resp4 =
            "HTTP/1.1 101 Switching Protocols\r\n" +
              "Upgrade: websocket\r\n" +
              "Connection: Upgrade\r\n\r\n" +
              "SSH-2.0-OpenSSH_9.6\r\n"

          out.write(resp1.toByteArray(StandardCharsets.ISO_8859_1))
          out.flush()
          out.write(resp2.toByteArray(StandardCharsets.ISO_8859_1))
          out.flush()
          out.write(resp3.toByteArray(StandardCharsets.ISO_8859_1))
          out.flush()
          out.write(resp4.toByteArray(StandardCharsets.ISO_8859_1))
          out.flush()
          Thread.sleep(200)
        }
      } catch (_: Exception) {}
    }

    val logStore = AppLogStore()
    val template =
      "GET / HTTP/1.1[crlf]Host: front.example.com[crlf][crlf]" +
        "[split]PURGE / HTTP/1.1[crlf]Host: front.example.com[crlf][crlf]" +
        "GET / HTTP/1.1[crlf]Host: [host][crlf]Upgrade: websocket[crlf]Connection: Upgrade[crlf]User-Agent: [ua][crlf][crlf]"

    val profile =
      ServerProfileEntity(
        name = "WS Multi-Response",
        mode = ConnectionMode.SSH_WEBSOCKET.code,
        payloadMode = PayloadMode.WEBSOCKET.code,
        sshHost = "127.0.0.1",
        sshPort = port,
        payload = template,
      )

    try {
      val factory = InjectorSocketFactory(profile, logStore, splitDelayMs = 20L, handshakeTimeoutMs = 3_000)
      factory.createSocket("127.0.0.1", port).use { sock ->
        val bannerBuf = ByteArray(21)
        val n = sock.getInputStream().read(bannerBuf)
        assertEquals("SSH-2.0-OpenSSH_9.6\r\n", String(bannerBuf, 0, n, StandardCharsets.ISO_8859_1))
      }

      val messages = logStore.logs.value.map { it.message }
      assertTrue(messages.any { it.contains("HTTP/1.1 301 Moved Permanently") && it.contains("ဆက်စောင့်နေသည်") })
      assertTrue(messages.any { it.contains("HTTP/1.1 200 OK") && it.contains("ဆက်စောင့်နေသည်") })
      assertTrue(messages.any { it.contains("HTTP/1.1 403 Forbidden") && it.contains("ဆက်စောင့်နေသည်") })
      assertTrue(messages.any { it.contains("HTTP/1.1 101 Switching Protocols") && it.contains("အောင်မြင်ပါပြီ") })
    } finally {
      server.close()
      executor.shutdownNow()
    }
  }

  @Test
  fun httpPayloadModeTolerates301ThenSucceedsOn200WithInterimBody() {
    val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    val port = server.localPort
    val executor = Executors.newSingleThreadExecutor()

    executor.execute {
      try {
        server.accept().use { client ->
          client.getInputStream().read(ByteArray(1024))
          val body301 = "redirecting..."
          val reply =
            "HTTP/1.1 301 Moved Permanently\r\nContent-Length: ${body301.length}\r\n\r\n" +
              body301 +
              "HTTP/1.1 200 Connection Established\r\n\r\n" +
              "SSH-2.0-Dropbear_Ok\r\n"
          client.getOutputStream().write(reply.toByteArray(StandardCharsets.ISO_8859_1))
          client.getOutputStream().flush()
          Thread.sleep(200)
        }
      } catch (_: Exception) {}
    }

    val logStore = AppLogStore()
    val profile =
      ServerProfileEntity(
        name = "HTTP Payload 301->200",
        mode = ConnectionMode.SSH_PROXY.code,
        payloadMode = PayloadMode.HTTP_PAYLOAD.code,
        sshHost = "127.0.0.1",
        sshPort = port,
        payload = PayloadTemplates.CONNECT_EXAMPLE,
      )

    try {
      InjectorSocketFactory(profile, logStore, handshakeTimeoutMs = 3_000)
        .createSocket("127.0.0.1", port)
        .use { sock ->
          val buf = ByteArray(21)
          val n = sock.getInputStream().read(buf)
          assertEquals("SSH-2.0-Dropbear_Ok\r\n", String(buf, 0, n, StandardCharsets.ISO_8859_1))
        }

      val messages = logStore.logs.value.map { it.message }
      assertTrue(messages.any { it.contains("HTTP/1.1 301 Moved Permanently") && it.contains("ဆက်စောင့်နေသည်") })
      assertTrue(messages.any { it.contains("HTTP/1.1 200 Connection Established") && it.contains("အောင်မြင်ပါပြီ") })
    } finally {
      server.close()
      executor.shutdownNow()
    }
  }

  @Test
  fun httpPayloadModeWithWebSocketUpgradeWaitsPast200AndSucceedsOn101() {
    val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    val port = server.localPort
    val executor = Executors.newSingleThreadExecutor()

    executor.execute {
      try {
        server.accept().use { client ->
          client.getInputStream().read(ByteArray(2048))
          val reply =
            "HTTP/1.1 301 Moved Permanently\r\nContent-Length: 0\r\n\r\n" +
              "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok" +
              "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n" +
              "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n\r\n" +
              "SSH-2.0-WSOverHttp\r\n"
          client.getOutputStream().write(reply.toByteArray(StandardCharsets.ISO_8859_1))
          client.getOutputStream().flush()
          Thread.sleep(200)
        }
      } catch (_: Exception) {}
    }

    val profile =
      ServerProfileEntity(
        name = "HTTP_PAYLOAD with WS Upgrade",
        mode = ConnectionMode.SSH_PROXY.code,
        payloadMode = PayloadMode.HTTP_PAYLOAD.code,
        sshHost = "127.0.0.1",
        sshPort = port,
        payload = PayloadTemplates.SPLIT_WEBSOCKET_EXAMPLE,
        sni = "front.example.com",
      )

    try {
      InjectorSocketFactory(profile, AppLogStore(), splitDelayMs = 15L, handshakeTimeoutMs = 3_000)
        .createSocket("127.0.0.1", port)
        .use { sock ->
          val buf = ByteArray(20)
          val n = sock.getInputStream().read(buf)
          assertEquals("SSH-2.0-WSOverHttp\r\n", String(buf, 0, n, StandardCharsets.ISO_8859_1))
        }
    } finally {
      server.close()
      executor.shutdownNow()
    }
  }

  @Test
  fun splitMarkerProducesSeparateTcpWritesAndSubstitutesUserAgent() {
    val template =
      "GET / HTTP/1.1[crlf]Host: front.example.com[crlf][crlf]" +
        "[split]GET / HTTP/1.1[crlf]Host: [host][crlf]Upgrade: websocket[crlf]Connection: Upgrade[crlf]User-Agent: [ua][crlf][crlf]"

    val parts =
      PayloadInjector.buildPayloadPartTexts(
        template = template,
        host = "edge.example.net",
        port = 80,
      )
    assertEquals(2, parts.size)
    assertEquals("GET / HTTP/1.1\r\nHost: front.example.com\r\n\r\n", parts[0])
    assertTrue(parts[1].contains("Host: edge.example.net\r\n"))
    assertTrue(parts[1].contains("User-Agent: ${PayloadInjector.DEFAULT_USER_AGENT}\r\n"))
    assertFalse(parts[1].contains("[ua]"))
    assertFalse(parts[1].contains("[split]"))

    // Verify separate TCP writes on the wire with a real loopback socket
    val receivedChunks = mutableListOf<String>()
    val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    val port = server.localPort
    val executor = Executors.newSingleThreadExecutor()

    executor.execute {
      try {
        server.accept().use { client ->
          client.soTimeout = 1_500
          val input = client.getInputStream()
          val buf = ByteArray(2048)
          val n1 = input.read(buf)
          if (n1 > 0) receivedChunks.add(String(buf, 0, n1, StandardCharsets.ISO_8859_1))
          val n2 = input.read(buf)
          if (n2 > 0) receivedChunks.add(String(buf, 0, n2, StandardCharsets.ISO_8859_1))
          val ok = "HTTP/1.1 101 Switching Protocols\r\n\r\nSSH-2.0-SplitOk\r\n"
          client.getOutputStream().write(ok.toByteArray(StandardCharsets.ISO_8859_1))
          client.getOutputStream().flush()
          Thread.sleep(150)
        }
      } catch (_: Exception) {}
    }

    val profile =
      ServerProfileEntity(
        name = "Split TCP Write Test",
        mode = ConnectionMode.SSH_WEBSOCKET.code,
        payloadMode = PayloadMode.WEBSOCKET.code,
        sshHost = "127.0.0.1",
        sshPort = port,
        payload = template,
      )

    try {
      InjectorSocketFactory(profile, AppLogStore(), splitDelayMs = 80L, handshakeTimeoutMs = 3_000)
        .createSocket("127.0.0.1", port)
        .use { sock ->
          val buf = ByteArray(17)
          val n = sock.getInputStream().read(buf)
          assertEquals("SSH-2.0-SplitOk\r\n", String(buf, 0, n, StandardCharsets.ISO_8859_1))
        }
      assertEquals(2, receivedChunks.size)
      assertTrue(receivedChunks[0].startsWith("GET / HTTP/1.1\r\nHost: front.example.com"))
      assertTrue(receivedChunks[1].contains("Upgrade: websocket"))
      assertTrue(receivedChunks[1].contains("User-Agent: Mozilla/5.0"))
    } finally {
      server.close()
      executor.shutdownNow()
    }
  }

  @Test
  fun eofAndTimeoutWithoutSuccessFailWithBurmeseErrorContainingLastStatusLine() {
    // 1) EOF after 301 -> 403
    val eofServer = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    val eofPort = eofServer.localPort
    // 2) Timeout after 301
    val timeoutServer = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    val timeoutPort = timeoutServer.localPort
    val executor = Executors.newFixedThreadPool(2)

    executor.execute {
      try {
        eofServer.accept().use { client ->
          client.getInputStream().read(ByteArray(1024))
          val reply =
            "HTTP/1.1 301 Moved Permanently\r\nContent-Length: 0\r\n\r\n" +
              "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n"
          client.getOutputStream().write(reply.toByteArray(StandardCharsets.ISO_8859_1))
          client.getOutputStream().flush()
          // Close immediately -> EOF without 101
        }
      } catch (_: Exception) {}
    }

    executor.execute {
      try {
        timeoutServer.accept().use { client ->
          client.getInputStream().read(ByteArray(1024))
          val reply = "HTTP/1.1 301 Moved Permanently\r\nContent-Length: 0\r\n\r\n"
          client.getOutputStream().write(reply.toByteArray(StandardCharsets.ISO_8859_1))
          client.getOutputStream().flush()
          // Stall so client hits socket read timeout
          Thread.sleep(2_000)
        }
      } catch (_: Exception) {}
    }

    try {
      val eofProfile =
        ServerProfileEntity(
          name = "WS EOF",
          mode = ConnectionMode.SSH_WEBSOCKET.code,
          payloadMode = PayloadMode.WEBSOCKET.code,
          sshHost = "127.0.0.1",
          sshPort = eofPort,
          payload = PayloadTemplates.WEBSOCKET_EXAMPLE,
        )
      try {
        InjectorSocketFactory(eofProfile, AppLogStore(), handshakeTimeoutMs = 2_000)
          .createSocket("127.0.0.1", eofPort)
        fail("Expected IOException on EOF without 101")
      } catch (e: IOException) {
        val msg = e.message.orEmpty()
        assertTrue("Expected last status 403 in error: $msg", msg.contains("HTTP/1.1 403 Forbidden"))
        assertTrue("Expected HTTP 101 expectation in error: $msg", msg.contains("101"))
      }

      val timeoutProfile = eofProfile.copy(name = "WS Timeout", sshPort = timeoutPort)
      try {
        InjectorSocketFactory(timeoutProfile, AppLogStore(), handshakeTimeoutMs = 250)
          .createSocket("127.0.0.1", timeoutPort)
        fail("Expected IOException on timeout without 101")
      } catch (e: IOException) {
        val msg = e.message.orEmpty()
        assertTrue("Expected last status 301 in timeout error: $msg", msg.contains("HTTP/1.1 301 Moved Permanently"))
        assertTrue("Expected timeout Burmese notice: $msg", msg.contains("စောင့်ဆိုင်းချိန်"))
      }
    } finally {
      eofServer.close()
      timeoutServer.close()
      executor.shutdownNow()
    }
  }

  @Test
  fun abortActiveHandshakeImmediatelyUnblocksWaitingSocket() {
    val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    val port = server.localPort
    val executor = Executors.newFixedThreadPool(2)

    executor.execute {
      try {
        server.accept().use { client ->
          client.getInputStream().read(ByteArray(1024))
          // Hold socket open without replying
          Thread.sleep(5_000)
        }
      } catch (_: Exception) {}
    }

    val profile =
      ServerProfileEntity(
        name = "Abort Test",
        mode = ConnectionMode.SSH_WEBSOCKET.code,
        payloadMode = PayloadMode.WEBSOCKET.code,
        sshHost = "127.0.0.1",
        sshPort = port,
        payload = PayloadTemplates.WEBSOCKET_EXAMPLE,
      )
    val factory = InjectorSocketFactory(profile, AppLogStore(), handshakeTimeoutMs = 10_000)

    executor.execute {
      Thread.sleep(100)
      factory.abortActiveHandshake()
    }

    val startMs = System.currentTimeMillis()
    try {
      try {
        factory.createSocket("127.0.0.1", port)
        fail("Expected IOException when handshake is aborted")
      } catch (e: IOException) {
        val elapsedMs = System.currentTimeMillis() - startMs
        assertTrue("Abort should unblock quickly (<1500ms), took ${elapsedMs}ms", elapsedMs < 1_500L)
        assertTrue(e.message.orEmpty().contains("ပယ်ဖျက်"))
      }
    } finally {
      server.close()
      executor.shutdownNow()
    }
  }
}
