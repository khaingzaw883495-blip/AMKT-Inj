package com.example.ads

import com.example.data.ProfileExpiryChecker
import com.example.data.ServerAccountEntity
import com.example.membership.PassStatus

/**
 * Central configuration and eligibility logic for the AMKT Injector Adsterra Smartlink_2 gate
 * and Home bottom banners.
 *
 * Strictly zero other ad networks, zero analytics, zero tracking, and adult ads OFF.
 */
object AdsterraConfig {
  /** Owner's Smartlink_2 URL (ID 32463249, Mainstream, adult ads OFF). */
  const val SMARTLINK_URL = "https://asiafilm.org/4/711d037be4e548a098cb6dfbc68b6edb"

  /** Owner's Adsterra 320x50 banner script configuration. */
  const val BANNER_320X50_KEY = "fe46d0afda2f021f7659b690e452eff4"
  const val BANNER_320X50_BASE_URL = "https://bellnewyork.org/"
  const val BANNER_320X50_SCRIPT_URL =
    "https://bellnewyork.org/22/fe46d0afda2f021f7659b690e452eff4"

  /** Fixed 7-second countdown for the pre-connect Smartlink gate. */
  const val CONNECT_GATE_SECONDS = 7

  /** Maximum number of +9h time-extension ad-watch grants per local calendar day (per device). */
  const val MAX_TIME_EXTEND_GRANTS_PER_DAY = 2

  /** Kept for compatibility with earlier tests referencing the 2x/day grant cap constant. */
  const val MAX_GATE_SHOWS_PER_DAY = MAX_TIME_EXTEND_GRANTS_PER_DAY

  /** Initial free VPN duration granted automatically on first free connect (6 hours). */
  const val INITIAL_FREE_HOURS = 6
  const val INITIAL_FREE_MS = INITIAL_FREE_HOURS * 3_600_000L

  /** Additional free VPN duration granted per completed Smartlink_2 extension watch (+9 hours). */
  const val AD_GRANT_HOURS = 9
  const val AD_GRANT_MS = AD_GRANT_HOURS * 3_600_000L

  /**
   * Builds the static, constant-only HTML snippet for the 320x50 Adsterra banner WebView.
   * Uses compile-time constants only so zero untrusted user input is ever interpolated.
   */
  fun buildBanner320x50Html(): String =
    """
    <!DOCTYPE html>
    <html>
    <head>
      <meta charset="utf-8" />
      <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
      <style>
        html, body {
          margin: 0;
          padding: 0;
          width: 100%;
          height: 100%;
          overflow: hidden;
          background: transparent;
          display: flex;
          align-items: center;
          justify-content: center;
        }
      </style>
    </head>
    <body>
      <script type="text/javascript">
        atOptions = {
          'key' : '$BANNER_320X50_KEY',
          'format' : 'iframe',
          'height' : 50,
          'width' : 320,
          'params' : {}
        };
      </script>
      <script type="text/javascript" src="$BANNER_320X50_SCRIPT_URL"></script>
    </body>
    </html>
    """.trimIndent()

  /**
   * Restricts external/WebView navigations strictly to `http://` and `https://` URLs so untrusted
   * schemes (`file:`, `content:`, `javascript:`, `data:`, `intent:`) can never execute or leak
   * local data.
   */
  fun isAllowedWebViewUrl(url: String?): Boolean {
    val trimmed = url?.trim().orEmpty()
    if (trimmed.isEmpty()) return false
    val lower = trimmed.lowercase()
    return lower.startsWith("https://") || lower.startsWith("http://")
  }

  /**
   * Evaluates the AMKT VPN Free-Time + Smartlink_2 connect gate policy when tapping CONNECT:
   * - Valid stored no-ads code OR INJ-redeemed paid server -> [ConnectGateDecision.SkipUnlimited]
   *   (no gate, no free-time limits)
   * - First free connect on this device -> [ConnectGateDecision.ShowGateAndGrantInitialFree]
   *   (opens 7s external-browser gate EVERY time, grants 6h initial free time without using a daily grant)
   * - Free user with remaining free time > 0 -> [ConnectGateDecision.ShowGateAndConnect]
   *   (opens 7s external-browser gate EVERY time — NO daily limit on the connect gate!)
   * - Free time expired and daily extension grants < 2 -> [ConnectGateDecision.ShowGateAndGrantExtension]
   *   (opens 7s external-browser gate, grants +9h and consumes 1 of 2 daily grants)
   * - Free time expired and daily extension grants >= 2 -> [ConnectGateDecision.RefuseFreeTimeExpiredNoGrantsLeft]
   */
  fun evaluateConnectGateDecision(
    server: ServerAccountEntity,
    passStatus: PassStatus,
    freeTimeStatus: FreeTimeStatus,
    nowMs: Long = System.currentTimeMillis(),
    todayYmd: Int = ProfileExpiryChecker.currentLocalYmd(),
  ): ConnectGateDecision {
    if (passStatus.isCurrentlyValid(todayYmd)) return ConnectGateDecision.SkipUnlimited
    if (server.isRedeemedServer()) return ConnectGateDecision.SkipUnlimited
    if (!freeTimeStatus.initialized) return ConnectGateDecision.ShowGateAndGrantInitialFree
    if (freeTimeStatus.expiresAtMs > nowMs) return ConnectGateDecision.ShowGateAndConnect
    return if (freeTimeStatus.grantsUsedToday < MAX_TIME_EXTEND_GRANTS_PER_DAY) {
      ConnectGateDecision.ShowGateAndGrantExtension
    } else {
      ConnectGateDecision.RefuseFreeTimeExpiredNoGrantsLeft
    }
  }

  /**
   * Returns `true` whenever a connection must pass through the 7-second external-browser Smartlink_2
   * gate: ALL free connections show the gate with NO daily limit; ONLY validated no-ads codes and
   * INJ-redeemed paid servers skip it.
   */
  fun shouldShowConnectGate(
    server: ServerAccountEntity,
    passStatus: PassStatus,
    @Suppress("UNUSED_PARAMETER") shownTodayCount: Int = 0,
    todayYmd: Int = ProfileExpiryChecker.currentLocalYmd(),
  ): Boolean {
    if (passStatus.isCurrentlyValid(todayYmd)) return false
    if (server.isRedeemedServer()) return false
    return true
  }

  /**
   * Adsterra bottom Smartlink + 320x50 banners are shown whenever the user does not have a
   * currently valid stored no-ads code. (The owner's own announcement banner remains visible.)
   */
  fun shouldShowAdsterraBanners(
    passStatus: PassStatus,
    todayYmd: Int = ProfileExpiryChecker.currentLocalYmd(),
  ): Boolean = !passStatus.isCurrentlyValid(todayYmd)
}

/**
 * Persisted local device free-time state paired with its OWN dedicated daily time-extension grant
 * counter (max 2 grants/day per device, separate from the unlimited connect gate).
 */
data class FreeTimeStatus(
  val initialized: Boolean = false,
  val expiresAtMs: Long = 0L,
  val grantsUsedToday: Int = 0,
) {
  fun remainingMs(nowMs: Long = System.currentTimeMillis()): Long {
    if (!initialized) return AdsterraConfig.INITIAL_FREE_MS
    return (expiresAtMs - nowMs).coerceAtLeast(0L)
  }

  fun hasActiveFreeTime(nowMs: Long = System.currentTimeMillis()): Boolean =
    initialized && expiresAtMs > nowMs

  fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean =
    initialized && expiresAtMs <= nowMs

  fun remainingGrantsToday(): Int =
    (AdsterraConfig.MAX_TIME_EXTEND_GRANTS_PER_DAY - grantsUsedToday).coerceAtLeast(0)

  fun canWatchAdForGrant(): Boolean =
    grantsUsedToday < AdsterraConfig.MAX_TIME_EXTEND_GRANTS_PER_DAY

  fun formatRemainingHms(nowMs: Long = System.currentTimeMillis()): String {
    val totalSec = remainingMs(nowMs) / 1_000L
    val hours = totalSec / 3_600L
    val mins = (totalSec % 3_600L) / 60L
    val secs = totalSec % 60L
    return "%02d:%02d:%02d".format(hours, mins, secs)
  }

  fun formatSummaryLine(nowMs: Long = System.currentTimeMillis()): String {
    if (!initialized) {
      return "Free: ${AdsterraConfig.INITIAL_FREE_HOURS}h ready"
    }
    val remMs = remainingMs(nowMs)
    if (remMs <= 0L) {
      return if (canWatchAdForGrant()) {
        "Free time ended • Extend (+${AdsterraConfig.AD_GRANT_HOURS}h)"
      } else {
        "Free time ended • Daily limit (2/2)"
      }
    }
    val totalMinutes = (remMs + 59_999L) / 60_000L
    val hours = totalMinutes / 60L
    val mins = totalMinutes % 60L
    return if (hours > 0L) {
      "Free: ${hours}h %02dm left".format(mins)
    } else {
      "Free: ${mins}m left"
    }
  }
}

sealed interface ConnectGateDecision {
  data object SkipUnlimited : ConnectGateDecision

  data object ShowGateAndGrantInitialFree : ConnectGateDecision

  data object ShowGateAndConnect : ConnectGateDecision

  data object ShowGateAndGrantExtension : ConnectGateDecision

  data object RefuseFreeTimeExpiredNoGrantsLeft : ConnectGateDecision
}
