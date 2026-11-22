package com.example.vless

import com.example.data.ServerProfileEntity
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Validated runtime configuration for a VLESS over WebSocket connection.
 * Built strictly from the user's editable profile or imported `vless://` URI — never hardcoded.
 */
data class VlessConfig(
  val uuid: String,
  val uuidBytes: ByteArray,
  val serverHost: String,
  val serverPort: Int,
  val wsPath: String,
  val wsHost: String,
  val sni: String,
  val useTls: Boolean,
  val encryption: String = "none",
  val networkType: String = "ws",
  val remark: String = "",
) {
  val effectiveWsHost: String
    get() = wsHost.trim().ifBlank { sni.trim().ifBlank { serverHost.trim() } }

  val effectiveSni: String
    get() = sni.trim().ifBlank { wsHost.trim().ifBlank { serverHost.trim() } }

  val securityLabel: String
    get() = if (useTls) "tls" else "none"

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is VlessConfig) return false
    return uuid == other.uuid &&
      uuidBytes.contentEquals(other.uuidBytes) &&
      serverHost == other.serverHost &&
      serverPort == other.serverPort &&
      wsPath == other.wsPath &&
      wsHost == other.wsHost &&
      sni == other.sni &&
      useTls == other.useTls &&
      encryption == other.encryption &&
      networkType == other.networkType &&
      remark == other.remark
  }

  override fun hashCode(): Int {
    var result = uuid.hashCode()
    result = 31 * result + uuidBytes.contentHashCode()
    result = 31 * result + serverHost.hashCode()
    result = 31 * result + serverPort
    result = 31 * result + wsPath.hashCode()
    result = 31 * result + wsHost.hashCode()
    result = 31 * result + sni.hashCode()
    result = 31 * result + useTls.hashCode()
    result = 31 * result + encryption.hashCode()
    result = 31 * result + networkType.hashCode()
    result = 31 * result + remark.hashCode()
    return result
  }
}

/**
 * Parses `vless://` URIs and editable [ServerProfileEntity] entries into a validated [VlessConfig].
 */
object VlessConfigParser {

  /**
   * Parses a `vless://uuid@host:port?type=ws&encryption=none&security=none|tls&host=...&sni=...&path=...#remark`
   * URI into a validated [VlessConfig].
   */
  fun parseLink(rawLink: String): Result<VlessConfig> {
    val trimmed = rawLink.trim()
    if (trimmed.isBlank()) {
      return Result.failure(IllegalArgumentException("VLESS လင့်ခ် (vless://...) မထည့်ရသေးပါ"))
    }
    if (!trimmed.startsWith("vless://", ignoreCase = true)) {
      return Result.failure(
        IllegalArgumentException("VLESS လင့်ခ်သည် vless:// ဖြင့် စတင်ရပါမည်")
      )
    }

    val uri =
      try {
        URI(trimmed)
      } catch (e: Exception) {
        return Result.failure(
          IllegalArgumentException("VLESS လင့်ခ် ပုံစံ မမှန်ကန်ပါ: ${e.message ?: "invalid URI"}", e)
        )
      }

    val rawUuid = percentDecode(uri.rawUserInfo.orEmpty()).trim()
    val uuidBytes =
      try {
        VlessProtocol.parseUuidBytes(rawUuid)
      } catch (e: Exception) {
        return Result.failure(
          IllegalArgumentException("VLESS UUID ပုံစံ မမှန်ကန်ပါ (${rawUuid.take(40)})", e)
        )
      }

    val host = uri.host?.trim()?.trim('[', ']').orEmpty()
    if (host.isBlank()) {
      return Result.failure(IllegalArgumentException("VLESS ဆာဗာ Host လိပ်စာ မပါဝင်ပါ"))
    }

    val params = parseQueryParams(uri.rawQuery)
    val networkType = params["type"].orEmpty().trim().lowercase().ifBlank { "ws" }
    if (networkType != "ws" && networkType != "websocket") {
      return Result.failure(
        IllegalArgumentException(
          "VLESS transport '$networkType' ကို မပံ့ပိုးသေးပါ — type=ws (WebSocket) ကိုသာ အသုံးပြုနိုင်ပါသည်"
        )
      )
    }

    val encryption = params["encryption"].orEmpty().trim().lowercase().ifBlank { "none" }
    if (encryption != "none") {
      return Result.failure(
        IllegalArgumentException("VLESS encryption='$encryption' မမှန်ကန်ပါ — encryption=none ဖြစ်ရပါမည်")
      )
    }

    val securityParam = params["security"].orEmpty().trim().lowercase()
    val port =
      when {
        uri.port in 1..65535 -> uri.port
        securityParam == "tls" -> 443
        else -> 80
      }

    val useTls =
      when (securityParam) {
        "", "none" -> if (securityParam.isEmpty()) port == 443 else false
        "tls" -> true
        else ->
          return Result.failure(
            IllegalArgumentException(
              "VLESS security='$securityParam' ကို မပံ့ပိုးသေးပါ — security=none သို့မဟုတ် security=tls ကို အသုံးပြုပါ"
            )
          )
      }

    val rawPath = params["path"].orEmpty().trim()
    val wsPath = normalizeWebSocketPath(rawPath)
    val wsHost = params["host"].orEmpty().trim()
    val sni = params["sni"].orEmpty().trim().ifBlank { wsHost }
    val remark = percentDecode(uri.rawFragment.orEmpty()).trim()

    return Result.success(
      VlessConfig(
        uuid = formatCanonicalUuid(uuidBytes),
        uuidBytes = uuidBytes,
        serverHost = host,
        serverPort = port,
        wsPath = wsPath,
        wsHost = wsHost.ifBlank { host },
        sni = sni.ifBlank { wsHost.ifBlank { host } },
        useTls = useTls,
        encryption = encryption,
        networkType = "ws",
        remark = remark,
      )
    )
  }

  /**
   * Builds a [VlessConfig] from a saved [ServerProfileEntity].
   * Uses `profile.vlessLink` when present (allowing profile overrides for host/port/sni/path if edited),
   * or falls back to the profile's own fields (`username` as UUID, `sshHost`, `sshPort`, `payload` as path, `sni`).
   */
  fun fromProfile(profile: ServerProfileEntity): Result<VlessConfig> {
    if (profile.vlessLink.isNotBlank()) {
      val parsed = parseLink(profile.vlessLink)
      return parsed.map { base ->
        // Respect any explicit edits the user saved on the profile fields
        val host = profile.sshHost.trim().ifBlank { base.serverHost }
        val port = if (profile.sshPort in 1..65535) profile.sshPort else base.serverPort
        val path =
          if (profile.payload.isNotBlank() && profile.payload.trim().startsWith("/")) {
            normalizeWebSocketPath(profile.payload.trim())
          } else {
            base.wsPath
          }
        val sni = profile.sni.trim().ifBlank { base.sni }
        val wsHost = if (profile.sni.isNotBlank()) profile.sni.trim() else base.wsHost
        base.copy(
          serverHost = host,
          serverPort = port,
          wsPath = path,
          wsHost = wsHost,
          sni = sni,
        )
      }
    }

    // Fallback: user edited fields directly on a VLESS_WS profile (e.g., typed UUID in username)
    val rawUuid = profile.username.trim()
    if (rawUuid.isBlank()) {
      return Result.failure(
        IllegalArgumentException(
          "VLESS ချိတ်ဆက်ရန် vless:// လင့်ခ် သို့မဟုတ် UUID ထည့်သွင်းပါ — Profile ကို ပြင်ဆင်ပြီး ထည့်သွင်းနိုင်ပါသည်"
        )
      )
    }

    val uuidBytes =
      try {
        VlessProtocol.parseUuidBytes(rawUuid)
      } catch (e: Exception) {
        return Result.failure(
          IllegalArgumentException("VLESS UUID ပုံစံ မမှန်ကန်ပါ — မှန်ကန်သော vless:// လင့်ခ် သို့မဟုတ် UUID ထည့်သွင်းပါ", e)
        )
      }

    val host = profile.sshHost.trim()
    if (host.isBlank()) {
      return Result.failure(IllegalArgumentException("VLESS ဆာဗာ Host လိပ်စာ မထည့်ရသေးပါ"))
    }
    val port = if (profile.sshPort in 1..65535) profile.sshPort else 80
    val wsPath = normalizeWebSocketPath(profile.payload.trim())
    val sni = profile.sni.trim().ifBlank { host }
    val useTls = port == 443

    return Result.success(
      VlessConfig(
        uuid = formatCanonicalUuid(uuidBytes),
        uuidBytes = uuidBytes,
        serverHost = host,
        serverPort = port,
        wsPath = wsPath,
        wsHost = sni,
        sni = sni,
        useTls = useTls,
        encryption = "none",
        networkType = "ws",
        remark = profile.name,
      )
    )
  }

  /**
   * Normalizes a WebSocket path: guarantees leading `/` and strips Xray client-side `?ed=N`
   * early-data hints while keeping the actual HTTP path intact.
   */
  fun normalizeWebSocketPath(rawPath: String): String {
    val trimmed = rawPath.trim()
    if (trimmed.isBlank()) return "/"
    val withSlash = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
    val qIdx = withSlash.indexOf('?')
    if (qIdx < 0) return withSlash

    val pathOnly = withSlash.substring(0, qIdx).ifBlank { "/" }
    val query = withSlash.substring(qIdx + 1)
    val remainingParams =
      query
        .split('&')
        .filter { part ->
          val key = part.substringBefore('=').trim().lowercase()
          part.isNotBlank() && key != "ed"
        }
    return if (remainingParams.isEmpty()) pathOnly else "$pathOnly?${remainingParams.joinToString("&")}"
  }

  fun parseQueryParams(rawQuery: String?): Map<String, String> {
    if (rawQuery.isNullOrBlank()) return emptyMap()
    val result = LinkedHashMap<String, String>()
    for (pair in rawQuery.split('&')) {
      val idx = pair.indexOf('=')
      if (idx <= 0) continue
      val key = percentDecode(pair.substring(0, idx)).trim().lowercase()
      val value = percentDecode(pair.substring(idx + 1))
      if (key.isNotEmpty()) {
        result[key] = value
      }
    }
    return result
  }

  /**
   * RFC 3986 percent-decoding that preserves literal `+` characters (unlike `URLDecoder`).
   */
  fun percentDecode(input: String): String {
    if ('%' !in input) return input
    val out = ByteArray(input.length)
    var outIdx = 0
    var i = 0
    while (i < input.length) {
      val ch = input[i]
      if (ch == '%' && i + 2 < input.length) {
        val hi = Character.digit(input[i + 1], 16)
        val lo = Character.digit(input[i + 2], 16)
        if (hi >= 0 && lo >= 0) {
          out[outIdx++] = ((hi shl 4) or lo).toByte()
          i += 3
          continue
        }
      }
      val utf8 = ch.toString().toByteArray(StandardCharsets.UTF_8)
      if (outIdx + utf8.size <= out.size) {
        System.arraycopy(utf8, 0, out, outIdx, utf8.size)
        outIdx += utf8.size
      }
      i += 1
    }
    return String(out, 0, outIdx, StandardCharsets.UTF_8)
  }

  private fun formatCanonicalUuid(bytes: ByteArray): String {
    val bb = ByteBuffer.wrap(bytes)
    val msb = bb.long
    val lsb = bb.long
    return UUID(msb, lsb).toString()
  }
}
