package com.example.openvpn

import com.example.config.Base64Compat

/**
 * Metadata extracted from a standard `.ovpn` configuration file without leaking secrets to logs/UI.
 */
data class ParsedOvpnConfig(
  val rawConfig: String,
  val primaryRemoteHost: String,
  val primaryRemotePort: Int,
  val protocol: String,
  val requiresAuthUserPass: Boolean,
  val hasInlineCredentials: Boolean,
  val hasInlineCertificates: Boolean,
  val cipherSummary: String,
) {
  val isTcp: Boolean
    get() = protocol.equals("tcp", ignoreCase = true)

  val isUdp: Boolean
    get() = protocol.equals("udp", ignoreCase = true)
}

/**
 * Standard `.ovpn` config parser & validator supporting:
 * - `remote <host> [port] [proto]` (single or multiple remotes, UDP/TCP, IPv4/IPv6/hostname)
 * - `proto udp|tcp|udp4|tcp4|udp6|tcp6|tcp-client`
 * - `port <port>`
 * - `cipher` / `data-ciphers` / `auth`
 * - Inline blocks (`<ca>`, `<cert>`, `<key>`, `<tls-auth>`, `<tls-crypt>`, `<tls-crypt-v2>`, `<auth-user-pass>`)
 * - External cert/key references (`ca`, `cert`, `key`, `pkcs12`)
 * - Detection of `auth-user-pass` credential prompt requirement
 */
object OvpnConfigParser {
  private const val DEFAULT_OPENVPN_PORT = 1194
  private const val MAX_OVPN_BYTES = 512 * 1024

  private val GENERIC_FILE_NAMES =
    setOf("document", "file", "content", "unnamed", "config", "client", "profile", "vpn")

  fun parse(rawOvpn: String): Result<ParsedOvpnConfig> {
    val normalized = rawOvpn.replace("\r\n", "\n").replace("\r", "\n").trim()
    if (normalized.isEmpty()) {
      return Result.failure(IllegalArgumentException(".ovpn ဖိုင်အချက်အလက် ဗလာဖြစ်နေပါသည်"))
    }
    if (normalized.toByteArray(Charsets.UTF_8).size > MAX_OVPN_BYTES) {
      return Result.failure(IllegalArgumentException(".ovpn ဖိုင်အရွယ်အစား အလွန်ကြီးနေပါသည်"))
    }

    var primaryHost = ""
    var primaryPort = 0
    var globalPort = 0
    var protocol = ""
    var remoteProto = ""
    var hasClientOrTls = false
    var hasDev = false
    var hasAuthUserPassDirective = false
    var hasInlineAuthUserPass = false
    var hasInlineCertMaterial = false
    var cipher = ""
    var authDigest = ""

    var inBlockTag: String? = null
    val inlineAuthLines = mutableListOf<String>()

    for (rawLine in normalized.lineSequence()) {
      val trimmed = rawLine.trim()
      if (trimmed.isEmpty()) continue

      if (inBlockTag != null) {
        if (trimmed.equals("</$inBlockTag>", ignoreCase = true)) {
          if (inBlockTag.equals("auth-user-pass", ignoreCase = true)) {
            if (inlineAuthLines.size >= 2 && inlineAuthLines[0].isNotBlank()) {
              hasInlineAuthUserPass = true
            }
          }
          inBlockTag = null
        } else if (inBlockTag.equals("auth-user-pass", ignoreCase = true)) {
          inlineAuthLines += trimmed
        }
        continue
      }

      if (trimmed.startsWith("#") || trimmed.startsWith(";")) continue

      if (trimmed.startsWith("<") && trimmed.endsWith(">") && !trimmed.startsWith("</")) {
        val tag = trimmed.substring(1, trimmed.length - 1).trim().lowercase()
        inBlockTag = tag
        if (tag in setOf("ca", "cert", "key", "tls-auth", "tls-crypt", "tls-crypt-v2", "pkcs12")) {
          hasInlineCertMaterial = true
          hasClientOrTls = true
        }
        if (tag == "auth-user-pass") {
          inlineAuthLines.clear()
        }
        continue
      }

      val tokens = tokenizeLine(trimmed)
      if (tokens.isEmpty()) continue
      val directive = tokens[0].lowercase()

      when (directive) {
        "client", "tls-client", "pull" -> hasClientOrTls = true
        "dev" -> hasDev = true
        "proto" -> {
          if (tokens.size >= 2 && protocol.isBlank()) {
            protocol = normalizeProto(tokens[1])
          }
        }
        "port" -> {
          if (tokens.size >= 2) {
            tokens[1].toIntOrNull()?.takeIf { it in 1..65535 }?.let { globalPort = it }
          }
        }
        "remote" -> {
          if (tokens.size >= 2 && primaryHost.isBlank()) {
            primaryHost = tokens[1].trim()
            if (tokens.size >= 3) {
              tokens[2].toIntOrNull()?.takeIf { it in 1..65535 }?.let { primaryPort = it }
            }
            if (tokens.size >= 4 && remoteProto.isBlank()) {
              remoteProto = normalizeProto(tokens[3])
            }
          }
        }
        "auth-user-pass" -> {
          hasAuthUserPassDirective = true
        }
        "cipher", "data-ciphers" -> {
          if (tokens.size >= 2 && cipher.isBlank()) {
            cipher = tokens[1]
          }
        }
        "auth" -> {
          if (tokens.size >= 2 && authDigest.isBlank()) {
            authDigest = tokens[1]
          }
        }
        "ca", "cert", "key", "pkcs12" -> {
          hasClientOrTls = true
        }
      }
    }

    if (inBlockTag != null) {
      return Result.failure(
        IllegalArgumentException(".ovpn ဖိုင်ဖော်မတ် မပြည့်စုံပါ (inline block မပိတ်ထားပါ)")
      )
    }

    if (primaryHost.isBlank()) {
      return Result.failure(
        IllegalArgumentException(".ovpn ဖိုင်အတွင်း remote ဆာဗာလိပ်စာ မတွေ့ပါ")
      )
    }

    if (!hasClientOrTls && !hasDev) {
      return Result.failure(
        IllegalArgumentException("မှန်ကန်သော OpenVPN (.ovpn) ဖိုင်မဟုတ်ပါ")
      )
    }

    val effectivePort =
      when {
        primaryPort in 1..65535 -> primaryPort
        globalPort in 1..65535 -> globalPort
        else -> DEFAULT_OPENVPN_PORT
      }
    val effectiveProto =
      remoteProto.ifBlank { protocol.ifBlank { "udp" } }
    val requiresCredentials = hasAuthUserPassDirective && !hasInlineAuthUserPass
    val cipherSummary =
      listOf(cipher, authDigest).filter { it.isNotBlank() }.joinToString("/")

    return Result.success(
      ParsedOvpnConfig(
        rawConfig = normalized,
        primaryRemoteHost = primaryHost,
        primaryRemotePort = effectivePort,
        protocol = effectiveProto,
        requiresAuthUserPass = requiresCredentials,
        hasInlineCredentials = hasInlineAuthUserPass,
        hasInlineCertificates = hasInlineCertMaterial,
        cipherSummary = cipherSummary,
      )
    )
  }

  /**
   * Decodes a Base64-encoded `.ovpn` field from Remote JSON and validates it as a real `.ovpn` config.
   */
  fun decodeBase64Ovpn(base64Content: String): Result<ParsedOvpnConfig> {
    val trimmed = base64Content.trim()
    if (trimmed.isEmpty()) {
      return Result.failure(IllegalArgumentException("OpenVPN config အချက်အလက် မပါဝင်ပါ"))
    }
    val decodedBytes =
      Base64Compat.decodeOrNull(trimmed)
        ?: return Result.failure(IllegalArgumentException("OpenVPN Base64 ဖော်မတ် မမှန်ကန်ပါ"))
    val rawText = decodedBytes.toString(Charsets.UTF_8)
    return parse(rawText)
  }

  /**
   * Resolves a user-facing profile name from an imported `.ovpn` filename:
   * - Uses filename without `.ovpn` extension when friendly and non-blank.
   * - Falls back to the parsed `remote` host if no friendly filename was available.
   */
  fun resolveProfileName(fileName: String?, parsed: ParsedOvpnConfig): String {
    val base =
      fileName
        ?.substringAfterLast('/')
        ?.substringAfterLast('\\')
        ?.trim()
        ?.let { raw ->
          when {
            raw.endsWith(".ovpn", ignoreCase = true) -> raw.dropLast(5)
            raw.endsWith(".conf", ignoreCase = true) -> raw.dropLast(5)
            else -> raw
          }
        }
        ?.trim()
        .orEmpty()

    if (base.isNotBlank() && base.lowercase() !in GENERIC_FILE_NAMES) {
      return base
    }
    return parsed.primaryRemoteHost.ifBlank { "OpenVPN" }
  }

  /**
   * Produces a connect-time-only `.ovpn` string with ONLY active `remote` directive lines
   * rewritten to `remote <localHost> <localPort>` (preserving an explicit 4th TCP proto token
   * when present on the `remote` line, mapping IPv6 `tcp6` to `tcp` for IPv4 loopback).
   *
   * Never modifies the input string, never alters `proto` directives (e.g., never silently turns
   * `udp` into `tcp`), and leaves comments and inline `<ca>`/`<cert>`/`<key>` blocks untouched.
   */
  fun rewriteRemoteForLocalForward(
    rawOvpn: String,
    localHost: String = OpenVpnPayloadForwarder.LOOPBACK_HOST,
    localPort: Int = OpenVpnPayloadForwarder.DEFAULT_LOCAL_PORT,
  ): String {
    val normalized = rawOvpn.replace("\r\n", "\n").replace("\r", "\n")
    val out = StringBuilder(normalized.length + 32)
    var inBlockTag: String? = null
    val lines = normalized.split("\n")

    for ((idx, line) in lines.withIndex()) {
      if (idx > 0) out.append('\n')
      val trimmed = line.trim()

      if (inBlockTag != null) {
        out.append(line)
        if (trimmed.equals("</$inBlockTag>", ignoreCase = true)) {
          inBlockTag = null
        }
        continue
      }

      if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";")) {
        out.append(line)
        continue
      }

      if (trimmed.startsWith("<") && trimmed.endsWith(">") && !trimmed.startsWith("</")) {
        val tag = trimmed.substring(1, trimmed.length - 1).trim().lowercase()
        inBlockTag = tag
        out.append(line)
        continue
      }

      val tokens = tokenizeLine(trimmed)
      if (tokens.isNotEmpty() && tokens[0].equals("remote", ignoreCase = true)) {
        val leadingWs = line.takeWhile { it == ' ' || it == '\t' }
        val explicitProtoToken =
          tokens.getOrNull(3)?.trim()?.takeIf { it.isNotEmpty() }?.let { proto ->
            if (proto.equals("tcp6", ignoreCase = true) || proto.equals("tcp6-client", ignoreCase = true)) {
              "tcp"
            } else {
              proto
            }
          }
        out.append(leadingWs)
        out.append("remote ").append(localHost).append(' ').append(localPort)
        if (explicitProtoToken != null) {
          out.append(' ').append(explicitProtoToken)
        }
      } else {
        out.append(line)
      }
    }
    return out.toString()
  }

  private fun normalizeProto(raw: String): String {
    val lower = raw.trim().lowercase()
    return when {
      lower.startsWith("tcp") -> "tcp"
      lower.startsWith("udp") -> "udp"
      else -> lower
    }
  }

  private fun tokenizeLine(line: String): List<String> {
    val result = mutableListOf<String>()
    val current = StringBuilder()
    var inQuotes = false
    var quoteChar = '"'
    var i = 0
    while (i < line.length) {
      val ch = line[i]
      if (inQuotes) {
        if (ch == '\\' && i + 1 < line.length) {
          current.append(line[i + 1])
          i += 2
          continue
        }
        if (ch == quoteChar) {
          inQuotes = false
        } else {
          current.append(ch)
        }
      } else {
        when {
          ch == '#' || ch == ';' -> break
          ch == '"' || ch == '\'' -> {
            inQuotes = true
            quoteChar = ch
          }
          ch.isWhitespace() -> {
            if (current.isNotEmpty()) {
              result += current.toString()
              current.setLength(0)
            }
          }
          else -> current.append(ch)
        }
      }
      i += 1
    }
    if (current.isNotEmpty()) {
      result += current.toString()
    }
    return result
  }
}
