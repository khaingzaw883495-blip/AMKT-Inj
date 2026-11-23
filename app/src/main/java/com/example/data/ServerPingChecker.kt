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

  /**
   * Formats live remaining time until the end of [expiryDate] (local 23:59:59) as `HH:MM:SS`.
   * Returns `(countdownHms, isExpired)`. Blank/unparseable dates return `("--:--:--", false)`.
   */
  fun formatExpiryCountdownHms(
    expiryDate: String,
    nowMs: Long = System.currentTimeMillis(),
  ): Pair<String, Boolean> {
    val trimmed = expiryDate.trim()
    if (trimmed.isBlank()) return "--:--:--" to false

    val ymdSource = if (trimmed.length >= 10) trimmed.substring(0, 10) else trimmed
    val parsedYmd = ProfileExpiryChecker.parseYmdOrNull(ymdSource) ?: return "--:--:--" to false
    val endCal =
      ymdToCalendar(parsedYmd).apply {
        set(Calendar.HOUR_OF_DAY, 23)
        set(Calendar.MINUTE, 59)
        set(Calendar.SECOND, 59)
        set(Calendar.MILLISECOND, 999)
      }
    val remainingSec = (endCal.timeInMillis - nowMs) / 1_000L
    if (remainingSec <= 0L) {
      return "00:00:00" to true
    }
    val hours = remainingSec / 3_600L
    val mins = (remainingSec % 3_600L) / 60L
    val secs = remainingSec % 60L
    return "%02d:%02d:%02d".format(hours, mins, secs) to false
  }

  /**
   * Resolves a country flag emoji (e.g. 🇯🇵, 🇸🇬, 🇹🇭) from [server]'s 2-letter region code or
   * display name, falling back to 🌐 when unknown. Never inspects host/IP or technical details.
   */
  fun resolveServerFlagEmoji(server: ServerAccountEntity?): String {
    if (server == null) return "🌐"
    val regionCode = server.region.trim().uppercase()
    if (regionCode.length == 2 && regionCode.all { it in 'A'..'Z' }) {
      val mapped = if (regionCode == "UK") "GB" else regionCode
      return iso2ToFlagEmoji(mapped)
    }
    val nameLower = server.name.lowercase()
    val inferredCode =
      when {
        nameLower.contains("japan") || nameLower.contains("tokyo") || nameLower.contains("osaka") || Regex("\\bjp\\b").containsMatchIn(nameLower) -> "JP"
        nameLower.contains("singapore") || Regex("\\bsg\\b").containsMatchIn(nameLower) -> "SG"
        nameLower.contains("thailand") || nameLower.contains("bangkok") || Regex("\\bth\\b").containsMatchIn(nameLower) -> "TH"
        nameLower.contains("myanmar") || nameLower.contains("yangon") || Regex("\\bmm\\b").containsMatchIn(nameLower) -> "MM"
        nameLower.contains("united states") || nameLower.contains("usa") || nameLower.contains("america") || Regex("\\bus\\b").containsMatchIn(nameLower) -> "US"
        nameLower.contains("united kingdom") || nameLower.contains("london") || Regex("\\b(uk|gb)\\b").containsMatchIn(nameLower) -> "GB"
        nameLower.contains("germany") || nameLower.contains("frankfurt") || Regex("\\bde\\b").containsMatchIn(nameLower) -> "DE"
        nameLower.contains("hong kong") || Regex("\\bhk\\b").containsMatchIn(nameLower) -> "HK"
        nameLower.contains("korea") || nameLower.contains("seoul") || Regex("\\bkr\\b").containsMatchIn(nameLower) -> "KR"
        nameLower.contains("indonesia") || nameLower.contains("jakarta") || Regex("\\bid\\b").containsMatchIn(nameLower) -> "ID"
        nameLower.contains("malaysia") || nameLower.contains("kuala") || Regex("\\bmy\\b").containsMatchIn(nameLower) -> "MY"
        nameLower.contains("vietnam") || Regex("\\bvn\\b").containsMatchIn(nameLower) -> "VN"
        nameLower.contains("taiwan") || Regex("\\btw\\b").containsMatchIn(nameLower) -> "TW"
        nameLower.contains("india") || nameLower.contains("mumbai") || Regex("\\bin\\b").containsMatchIn(nameLower) -> "IN"
        nameLower.contains("france") || nameLower.contains("paris") || Regex("\\bfr\\b").containsMatchIn(nameLower) -> "FR"
        nameLower.contains("netherlands") || nameLower.contains("amsterdam") || Regex("\\bnl\\b").containsMatchIn(nameLower) -> "NL"
        nameLower.contains("canada") || Regex("\\bca\\b").containsMatchIn(nameLower) -> "CA"
        nameLower.contains("australia") || nameLower.contains("sydney") || Regex("\\bau\\b").containsMatchIn(nameLower) -> "AU"
        else -> null
      }
    return if (inferredCode != null) iso2ToFlagEmoji(inferredCode) else "🌐"
  }

  private fun iso2ToFlagEmoji(iso2: String): String {
    val first = Character.toChars(0x1F1E6 + (iso2[0] - 'A'))
    val second = Character.toChars(0x1F1E6 + (iso2[1] - 'A'))
    return String(first) + String(second)
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
