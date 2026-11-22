package com.example.data

import com.example.vless.VlessConfigParser
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lightweight real TCP connect latency checker and expiry countdown formatter for Home and
 * Server Picker Bottom Sheet. Never fabricates random latency numbers.
 */
object ServerPingChecker {

  /**
   * Measures real TCP handshake latency in milliseconds for [server], or returns `null` when
   * unreachable, offline, or not TCP-pingable (e.g. blank host).
   */
  suspend fun measureTcpPingMs(
    server: ServerAccountEntity,
    timeoutMs: Int = 1_500,
  ): Int? =
    withContext(Dispatchers.IO) {
      val (host, port) = resolveProbeEndpoint(server) ?: return@withContext null
      val startNs = System.nanoTime()
      try {
        Socket().use { socket ->
          socket.connect(InetSocketAddress(host, port), timeoutMs)
        }
        val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).toInt()
        elapsedMs.coerceAtLeast(1)
      } catch (_: Exception) {
        null
      }
    }

  internal fun resolveProbeEndpoint(server: ServerAccountEntity): Pair<String, Int>? {
    return when (server.resolvedConnectionMode) {
      ConnectionMode.VLESS_WS -> {
        val parsed =
          if (server.vlessLink.isNotBlank()) VlessConfigParser.parseLink(server.vlessLink).getOrNull()
          else null
        val host = server.host.trim().ifBlank { parsed?.serverHost.orEmpty() }
        val port = if (server.port in 1..65535) server.port else (parsed?.serverPort ?: 443)
        if (host.isBlank()) null else host to port
      }
      ConnectionMode.DNSTT -> {
        val resolver = server.effectiveDnsttResolver()
        val host = resolver.substringBefore(':').trim()
        val port = resolver.substringAfter(':', "53").toIntOrNull()?.takeIf { it in 1..65535 } ?: 53
        if (host.isBlank()) null else host to port
      }
      else -> {
        val host = server.host.trim()
        val port = if (server.port in 1..65535) server.port else ServerAccountEntity.DEFAULT_SSH_PORT
        if (host.isBlank()) null else host to port
      }
    }
  }

  /**
   * Formats a human-readable Burmese expiry / remaining-days string for the Home card.
   * Returns `(displayText, isExpired)`.
   */
  fun formatRemainingExpiry(
    expiryDate: String,
    todayYmd: Int = ProfileExpiryChecker.currentLocalYmd(),
  ): Pair<String, Boolean> {
    val trimmed = expiryDate.trim()
    if (trimmed.isBlank()) return "သက်တမ်း မသတ်မှတ်ထားပါ" to false

    val parsedYmd = ProfileExpiryChecker.parseYmdOrNull(trimmed) ?: return trimmed to false
    if (parsedYmd < todayYmd) {
      return "သက်တမ်းကုန်ဆုံးပါပြီ ($trimmed)" to true
    }
    if (parsedYmd == todayYmd) {
      return "ယနေ့ သက်တမ်းကုန်မည် ($trimmed)" to false
    }
    val daysLeft = daysBetweenYmd(todayYmd, parsedYmd)
    return if (daysLeft > 0) {
      "ကျန်ရှိရက်: $daysLeft ရက် ($trimmed)" to false
    } else {
      trimmed to false
    }
  }

  private fun daysBetweenYmd(startYmd: Int, endYmd: Int): Long {
    val startCal = ymdToCalendar(startYmd)
    val endCal = ymdToCalendar(endYmd)
    val diffMs = endCal.timeInMillis - startCal.timeInMillis
    return (diffMs + 43_200_000L) / 86_400_000L
  }

  private fun ymdToCalendar(ymd: Int): Calendar {
    val year = ymd / 10_000
    val month = (ymd / 100) % 100
    val day = ymd % 100
    return Calendar.getInstance().apply {
      set(Calendar.YEAR, year)
      set(Calendar.MONTH, (month - 1).coerceIn(0, 11))
      set(Calendar.DAY_OF_MONTH, day.coerceIn(1, 31))
      set(Calendar.HOUR_OF_DAY, 0)
      set(Calendar.MINUTE, 0)
      set(Calendar.SECOND, 0)
      set(Calendar.MILLISECOND, 0)
    }
  }
}
