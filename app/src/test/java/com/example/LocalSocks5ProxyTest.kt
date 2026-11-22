package com.example

import com.example.data.AppLogStore
import com.example.data.ConnectionMode
import com.example.data.ServerProfileEntity
import com.example.ssh.JschSshTunnelEngine
import com.example.ssh.LocalSocks5Proxy
import com.example.ssh.SshTunnelState
import com.example.ssh.TunnelChannel
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSocks5ProxyTest {

  @Test
  fun socks5ProxyRelaysDomainConnectAndCountsTrafficBytes() {
    val echoServer = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val echoPort = echoServer.localPort
    val echoExecutor = Executors.newSingleThreadExecutor()
    echoExecutor.execute {
      try {
        echoServer.accept().use { s ->
          val buf = ByteArray(1024)
          val n = s.getInputStream().read(buf)
          if (n > 0) {
            s.getOutputStream().write(buf, 0, n)
            s.getOutputStream().flush()
          }
        }
      } catch (_: Exception) {}
    }

    var observedHost = ""
    var observedPort = 0

    val proxy =
      LocalSocks5Proxy(
        connector = { host, port, _ ->
          observedHost = host
          observedPort = port
          val targetSocket = Socket("127.0.0.1", echoPort)
          object : TunnelChannel {
            override val inputStream: InputStream = targetSocket.getInputStream()
            override val outputStream: OutputStream = targetSocket.getOutputStream()

            override fun close() {
              targetSocket.close()
            }
          }
        }
      )

    try {
      val boundPort = proxy.start(preferredPort = 0)
      assertTrue(boundPort > 0)

      Socket("127.0.0.1", boundPort).use { client ->
        client.soTimeout = 5_000
        val out = client.getOutputStream()
        val inp = DataInputStream(client.getInputStream())

        // 1. SOCKS5 Greeting (VER=5, NMETHODS=1, NO_AUTH=0)
        out.write(byteArrayOf(0x05, 0x01, 0x00))
        out.flush()
        val greetingReply = ByteArray(2)
        inp.readFully(greetingReply)
        assertArrayEquals(byteArrayOf(0x05, 0x00), greetingReply)

        // 2. SOCKS5 CONNECT with Domain ATYP=0x03 ("ifconfig.me":443)
        val domain = "ifconfig.me".toByteArray(Charsets.US_ASCII)
        val req =
          ByteArray(4 + 1 + domain.size + 2).apply {
            this[0] = 0x05
            this[1] = 0x01 // CONNECT
            this[2] = 0x00 // RSV
            this[3] = 0x03 // DOMAIN
            this[4] = domain.size.toByte()
            System.arraycopy(domain, 0, this, 5, domain.size)
            this[5 + domain.size] = ((443 ushr 8) and 0xFF).toByte()
            this[6 + domain.size] = (443 and 0xFF).toByte()
          }
        out.write(req)
        out.flush()

        val connectReply = ByteArray(10)
        inp.readFully(connectReply)
        assertEquals(0x05.toByte(), connectReply[0])
        assertEquals(0x00.toByte(), connectReply[1]) // REP = Succeeded

        // 3. Send payload & verify bidirectional relay
        val payload = "AMKT-SOCKS5-PING".toByteArray(Charsets.UTF_8)
        out.write(payload)
        out.flush()

        val echoed = ByteArray(payload.size)
        inp.readFully(echoed)
        assertArrayEquals(payload, echoed)
      }

      assertEquals("ifconfig.me", observedHost)
      assertEquals(443, observedPort)
      assertTrue(proxy.trafficStats.value.bytesUp >= 16L)
      assertTrue(proxy.trafficStats.value.bytesDown >= 16L)
    } finally {
      proxy.stop()
      echoServer.close()
      echoExecutor.shutdownNow()
    }
  }

  @Test
  fun sshEngineRefusesMissingCredentialsAndLogsCorrectBurmeseMessage() = runTest {
    val logStore = AppLogStore()
    val engine = JschSshTunnelEngine(logStore)
    val profileWithoutCreds =
      ServerProfileEntity(
        name = "Test Direct",
        mode = ConnectionMode.SSH_DIRECT.code,
        sshHost = "198.51.100.10",
        sshPort = 22,
        username = "",
        password = "",
      )

    val result = engine.connect(profileWithoutCreds)
    assertTrue(result.isFailure)
    assertEquals(SshTunnelState.Disconnected, engine.state.value)
    assertTrue(
      logStore.logs.value.any {
        it.message.contains("SSH Username သို့မဟုတ် Password မထည့်ရသေးပါ")
      }
    )
  }

  @Test
  fun sshEngineRefusesUnsupportedFutureModesCleanly() = runTest {
    val logStore = AppLogStore()
    val engine = JschSshTunnelEngine(logStore)
    val vlessProfile =
      ServerProfileEntity(
        name = "Test VLESS",
        mode = ConnectionMode.VLESS_WS.code,
        sshHost = "198.51.100.20",
        sshPort = 443,
        username = "u",
        password = "p",
      )

    val result = engine.connect(vlessProfile)
    assertTrue(result.isFailure)
    assertEquals(SshTunnelState.Disconnected, engine.state.value)
    assertTrue(logStore.logs.value.any { it.message.contains("VLESS") })
  }

  @Test
  fun sshEngineRequiresDnsttLocalForwardBeforeConnectingDnsttProfile() = runTest {
    val logStore = AppLogStore()
    val engine = JschSshTunnelEngine(logStore)
    val dnsttProfile =
      ServerProfileEntity(
        name = "Test DNSTT",
        mode = ConnectionMode.DNSTT.code,
        username = "u",
        password = "p",
        dnsttNameserver = "ns.example.com",
      )

    val result = engine.connect(dnsttProfile)
    assertTrue(result.isFailure)
    assertEquals(SshTunnelState.Disconnected, engine.state.value)
    assertTrue(logStore.logs.value.any { it.message.contains("DNSTT") })
  }
}
