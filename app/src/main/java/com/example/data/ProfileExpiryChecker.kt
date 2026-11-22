package com.example.data

import java.util.Calendar
import java.util.TimeZone

/**
 * Evaluates profile expiry dates (`YYYY-MM-DD`, `YYYY/MM/DD`, or ISO-8601 prefix) using
 * calendar-day comparison so profiles with no expiry remain valid forever, future/today
 * expiry dates remain valid, and past expiry dates are strictly refused before VPN start.
 */
object ProfileExpiryChecker {

  enum class ExpiryState {
    NONE,
    VALID,
    EXPIRED,
  }

  private val DATE_PREFIX_REGEX = Regex("^(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})")

  /**
   * Returns `true` if [expiryDate] parses to a calendar date strictly before [todayYmd].
   * Blank or unparseable expiry strings return `false` (no expiry enforced).
   */
  fun isExpired(
    expiryDate: String,
    todayYmd: Int = currentLocalYmd(),
  ): Boolean {
    return evaluate(expiryDate, todayYmd) == ExpiryState.EXPIRED
  }

  fun evaluate(
    expiryDate: String,
    todayYmd: Int = currentLocalYmd(),
  ): ExpiryState {
    val trimmed = expiryDate.trim()
    if (trimmed.isBlank()) return ExpiryState.NONE
    val parsedYmd = parseYmdOrNull(trimmed) ?: return ExpiryState.NONE
    return if (parsedYmd < todayYmd) ExpiryState.EXPIRED else ExpiryState.VALID
  }

  fun buildExpiredRefusalMessage(profileName: String, expiryDate: String): String {
    val dateNote = expiryDate.trim().takeIf { it.isNotEmpty() }?.let { " ($it)" }.orEmpty()
    return "ဤ Profile ($profileName) သည် သက်တမ်းကုန်ဆုံးပါပြီ$dateNote — ချိတ်ဆက်၍ မရပါ။ သက်တမ်းအသစ် ထည့်သွင်းပါ။"
  }

  /**
   * Encodes `(year, month 1..12, day 1..31)` as a comparable `YYYYMMDD` integer.
   */
  internal fun encodeYmd(year: Int, month: Int, day: Int): Int {
    return year * 10_000 + month * 100 + day
  }

  internal fun currentLocalYmd(timeZone: TimeZone = TimeZone.getDefault()): Int {
    val cal = Calendar.getInstance(timeZone)
    return encodeYmd(
      year = cal.get(Calendar.YEAR),
      month = cal.get(Calendar.MONTH) + 1,
      day = cal.get(Calendar.DAY_OF_MONTH),
    )
  }

  internal fun parseYmdOrNull(raw: String): Int? {
    val match = DATE_PREFIX_REGEX.find(raw.trim()) ?: return null
    val year = match.groupValues[1].toIntOrNull() ?: return null
    val month = match.groupValues[2].toIntOrNull() ?: return null
    val day = match.groupValues[3].toIntOrNull() ?: return null
    if (year !in 1970..9999 || month !in 1..12 || day !in 1..31) return null

    // Validate real calendar date (e.g., reject 2026-02-30)
    val cal =
      Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        isLenient = false
        clear()
        set(Calendar.YEAR, year)
        set(Calendar.MONTH, month - 1)
        set(Calendar.DAY_OF_MONTH, day)
      }
    return try {
      cal.timeInMillis
      encodeYmd(year, month, day)
    } catch (_: Exception) {
      null
    }
  }
}
