package com.example

import com.example.data.ConnectionMode
import com.example.data.ServerAccountEntity
import com.example.data.ServerPingChecker
import java.net.InetAddress
import java.net.ServerSocket
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerPingCheckerTest {

  @Test
  fun measuresRealLoopbackTcpPingAndReturnsNullWhenUnreachable() = runBlocking {
    val listener = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    val openPort = listener.localPort
    try {
      val openServer =
        ServerAccountEntity(
          name = "Loopback Open",
          connectionMode = ConnectionMode.SSH_DIRECT.code,
          host = "127.0.0.1",
          port = openPort,
        )
      val pingMs = ServerPingChecker.measureTcpPingMs(openServer, timeoutMs = 1_500)
      assertNotNull("Expected non-null real TCP ping for open loopback port", pingMs)
      assertTrue("Ping ms must be >= 1", pingMs!! >= 1)
    } finally {
      listener.close()
    }

    // Closed port returns null cleanly without throwing or fabricating numbers
    val closedServer =
      ServerAccountEntity(
        name = "Loopback Closed",
        connectionMode = ConnectionMode.SSH_DIRECT.code,
        host = "127.0.0.1",
        port = openPort,
      )
    val closedPing = ServerPingChecker.measureTcpPingMs(closedServer, timeoutMs = 400)
    assertNull("Closed port must return null rather than fake ms", closedPing)
  }

  @Test
  fun formatsRemainingExpiryDaysAndExpiredStatusAccurately() {
    val (emptyLabel, emptyExpired) = ServerPingChecker.formatRemainingExpiry("", todayYmd = 20260601)
    assertFalse(emptyExpired)
    assertEquals("သက်တမ်း မသတ်မှတ်ထားပါ", emptyLabel)

    val (futureLabel, futureExpired) =
      ServerPingChecker.formatRemainingExpiry("2026-06-11", todayYmd = 20260601)
    assertFalse(futureExpired)
    assertTrue("Expected 10 days remaining, got $futureLabel", futureLabel.contains("10 ရက်"))

    val (todayLabel, todayExpired) =
      ServerPingChecker.formatRemainingExpiry("2026-06-01", todayYmd = 20260601)
    assertFalse(todayExpired)
    assertTrue(todayLabel.contains("ယနေ့"))

    val (pastLabel, pastExpired) =
      ServerPingChecker.formatRemainingExpiry("2026-05-31", todayYmd = 20260601)
    assertTrue(pastExpired)
    assertTrue(pastLabel.contains("သက်တမ်းကုန်ဆုံးပါပြီ"))
  }
}
