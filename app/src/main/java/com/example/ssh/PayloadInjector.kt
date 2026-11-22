package com.example.ssh

import com.example.data.PayloadMode
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets

/**
 * Parsed HTTP response status line returned by an HTTP proxy, WebSocket server, or CDN edge.
 */
data class HttpStatusLine(
  val rawStatusLine: String,
  val statusCode: Int,
)

/**
 * Parsed HTTP response header metadata used to cleanly skip interim response bodies
 * (e.g., `301 Moved Permanently`, `200 OK`, `403 Forbidden`) on pipelined/split payloads
 * without ever over-reading past the final `101`/`200` header terminator.
 */
data class HttpResponseHeaders(
  val statusLine: HttpStatusLine,
  val contentLength: Long? = null,
  val isChunked: Boolean = false,
)

/**
 * Pure HTTP payload templating, `[split]` / `[ua]` support, extra-header assembly, SNI resolution,
 * and lenient non-over-reading HTTP response loop for Milestones 4 & 5.1.
 */
object PayloadInjector {

  const val DEFAULT_USER_AGENT: String =
    "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

  const val DEFAULT_MAX_INTERIM_RESPONSES: Int = 12

  private const val MAX_HEADER_BYTES = 16_384
  private const val MAX_INTERIM_BODY_SKIP_BYTES = 131_072L
  private const val MAX_NOISE_SCAN_BYTES = 65_536

  private val IPV4_REGEX = Regex("^(\\d{1,3}\\.){3}\\d{1,3}$")
  private val DNS_HOST_REGEX =
    Regex("^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)*$")
  private val SPLIT_MARKER_REGEX = Regex("\\[(?:delay_)?split]", RegexOption.IGNORE_CASE)
  private val WS_UPGRADE_HEADER_REGEX = Regex("(?im)^upgrade\\s*:\\s*websocket\\b")

  /**
   * Returns the effective SNI hostname: trimmed [sniHost] if provided, otherwise [profileHost].
   */
  fun resolveSniHost(sniHost: String, profileHost: String): String {
    return sniHost.trim().ifBlank { profileHost.trim() }
  }

  /**
   * RFC 6066 / JSSE SNIHostName accepts DNS hostnames only (not raw IPv4/IPv6 address literals).
   */
  fun isValidDnsSniHostname(host: String): Boolean {
    val trimmed = host.trim()
    if (trimmed.isBlank() || trimmed.length > 253) return false
    if (trimmed.contains(':') || IPV4_REGEX.matches(trimmed)) return false
    return DNS_HOST_REGEX.matches(trimmed)
  }

  /**
   * Returns true if the target tunnel handshake expects HTTP `101 Switching Protocols`:
   * either [mode] is [PayloadMode.WEBSOCKET], or the assembled payload explicitly requests
   * `Upgrade: websocket` (so multi-request WebSocket payloads still succeed even when a user
   * leaves the profile in `HTTP_PAYLOAD` mode).
   */
  fun requiresWebSocketUpgrade(mode: PayloadMode, assembledPayload: String): Boolean {
    return mode == PayloadMode.WEBSOCKET || WS_UPGRADE_HEADER_REGEX.containsMatchIn(assembledPayload)
  }

  /**
   * Builds one or more wire-ready HTTP payload chunks split at `[split]` markers:
   * 1. Splits [template] on `[split]` (case-insensitive) into sequential TCP write chunks.
   * 2. Substitutes `[host]`, `[port]`, `[sni]`, `[ua]`, `[protocol]`, `[crlf]`, `[lf]`, `[cr]`.
   * 3. Normalizes line endings to CRLF and trims a stray leading newline right after `[split]`
   *    when the preceding chunk already ended with `\r\n\r\n`.
   * 4. Appends any non-blank lines from [extraHeaders] to the final chunk and guarantees a
   *    terminating `\r\n\r\n` boundary on the final chunk.
   */
  fun buildPayloadPartTexts(
    template: String,
    host: String,
    port: Int,
    sni: String = "",
    extraHeaders: String = "",
    userAgent: String = DEFAULT_USER_AGENT,
  ): List<String> {
    val cleanHost = host.trim()
    val effectiveSni = resolveSniHost(sni, cleanHost)

    val parsedExtraHeaders =
      extraHeaders
        .lineSequence()
        .map { line ->
          substitutePlaceholders(
            raw = line.trim(),
            host = cleanHost,
            port = port,
            effectiveSni = effectiveSni,
            userAgent = userAgent,
          ).trim()
        }
        .filter { it.isNotEmpty() }
        .toList()

    val rawSegments = template.split(SPLIT_MARKER_REGEX)
    val processed = mutableListOf<String>()
    var previousEndedWithBlankLine = false

    for (rawSegment in rawSegments) {
      val substituted =
        substitutePlaceholders(
          raw = rawSegment,
          host = cleanHost,
          port = port,
          effectiveSni = effectiveSni,
          userAgent = userAgent,
        )
      var normalized = normalizeToCrlf(substituted)
      if (processed.isNotEmpty() && previousEndedWithBlankLine) {
        normalized = normalized.trimStart('\r', '\n')
      }
      if (normalized.isNotEmpty()) {
        previousEndedWithBlankLine = normalized.endsWith("\r\n\r\n")
        processed.add(normalized)
      }
    }

    if (processed.isEmpty()) {
      if (parsedExtraHeaders.isEmpty()) {
        return emptyList()
      }
      val onlyHeaders =
        buildString {
          parsedExtraHeaders.forEachIndexed { idx, header ->
            if (idx > 0) append("\r\n")
            append(header)
          }
          append("\r\n\r\n")
        }
      return listOf(onlyHeaders)
    }

    // Finalize the last chunk: append extraHeaders (if any) and guarantee CRLF+CRLF terminator.
    val lastIdx = processed.lastIndex
    val lastBase = processed[lastIdx].trimEnd('\r', '\n')
    val finalizedLast =
      buildString {
        if (lastBase.isNotEmpty()) {
          append(lastBase)
        }
        parsedExtraHeaders.forEach { header ->
          if (isNotEmpty()) append("\r\n")
          append(header)
        }
        if (isNotEmpty()) {
          append("\r\n\r\n")
        }
      }

    if (finalizedLast.isEmpty()) {
      processed.removeAt(lastIdx)
    } else {
      processed[lastIdx] = finalizedLast
    }

    return processed
  }

  /**
   * Returns wire-ready ISO-8859-1 byte chunks to send in separate TCP `write()` + `flush()` calls.
   */
  fun buildPayloadParts(
    template: String,
    host: String,
    port: Int,
    sni: String = "",
    extraHeaders: String = "",
    userAgent: String = DEFAULT_USER_AGENT,
  ): List<ByteArray> {
    return buildPayloadPartTexts(
      template = template,
      host = host,
      port = port,
      sni = sni,
      extraHeaders = extraHeaders,
      userAgent = userAgent,
    ).map { it.toByteArray(StandardCharsets.ISO_8859_1) }
  }

  /**
   * Builds a combined wire-ready HTTP payload string (all `[split]` chunks concatenated).
   */
  fun buildPayloadText(
    template: String,
    host: String,
    port: Int,
    sni: String = "",
    extraHeaders: String = "",
    userAgent: String = DEFAULT_USER_AGENT,
  ): String {
    return buildPayloadPartTexts(
      template = template,
      host = host,
      port = port,
      sni = sni,
      extraHeaders = extraHeaders,
      userAgent = userAgent,
    ).joinToString(separator = "")
  }

  fun buildPayloadBytes(
    template: String,
    host: String,
    port: Int,
    sni: String = "",
    extraHeaders: String = "",
    userAgent: String = DEFAULT_USER_AGENT,
  ): ByteArray {
    return buildPayloadText(
        template = template,
        host = host,
        port = port,
        sni = sni,
        extraHeaders = extraHeaders,
        userAgent = userAgent,
      )
      .toByteArray(StandardCharsets.ISO_8859_1)
  }

  /**
   * Reads a single HTTP response header block strictly byte-by-byte directly from [input] until
   * `\r\n\r\n` (or `\n\n`) so zero bytes of a subsequent SSH server banner (`SSH-2.0-...`)
   * are ever over-read or lost.
   *
   * Returns `null` on clean EOF before any header bytes arrive.
   */
  @Throws(IOException::class)
  fun readHttpResponseHeaders(
    input: InputStream,
    maxHeaderBytes: Int = MAX_HEADER_BYTES,
    skipLeadingNoise: Boolean = false,
  ): HttpResponseHeaders? {
    val buffer = ByteArrayOutputStream(256)

    if (skipLeadingNoise) {
      val matchedPrefix = scanToHttpPrefix(input) ?: return null
      buffer.write(matchedPrefix)
    }

    var b1 = -1
    var b2 = -1
    var b3 = -1
    var totalRead = buffer.size()
    var foundEnd = false

    while (totalRead < maxHeaderBytes) {
      val next = input.read()
      if (next == -1) {
        break
      }
      // Ignore stray leading CR/LF before the status line starts
      if (totalRead == 0 && (next == '\r'.code || next == '\n'.code)) {
        continue
      }
      buffer.write(next)
      totalRead += 1

      val isCrLfCrLf = b1 == '\r'.code && b2 == '\n'.code && b3 == '\r'.code && next == '\n'.code
      val isLfLf = b3 == '\n'.code && next == '\n'.code
      if (isCrLfCrLf || isLfLf) {
        foundEnd = true
        break
      }

      b1 = b2
      b2 = b3
      b3 = next
    }

    if (totalRead == 0) {
      return null
    }
    if (!foundEnd && totalRead >= maxHeaderBytes) {
      throw IOException("HTTP Header အရွယ်အစား သတ်မှတ်ချက်ထက် ကျော်လွန်နေပါသည်")
    }

    val headerText = String(buffer.toByteArray(), StandardCharsets.ISO_8859_1)
    val lines = headerText.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    val firstLine = lines.firstOrNull().orEmpty()
    if (!firstLine.startsWith("HTTP/", ignoreCase = true)) {
      throw IOException("HTTP တုံ့ပြန်ချက် ပုံစံ မမှန်ကန်ပါ: ${firstLine.take(80)}")
    }

    val parts = firstLine.split(Regex("\\s+"), limit = 3)
    val statusCode =
      parts.getOrNull(1)?.toIntOrNull()
        ?: throw IOException("HTTP Status Code ဖတ်မရပါ: ${firstLine.take(80)}")

    var contentLength: Long? = null
    var isChunked = false
    for (i in 1 until lines.size) {
      val line = lines[i]
      val colonIdx = line.indexOf(':')
      if (colonIdx <= 0) continue
      val headerName = line.substring(0, colonIdx).trim()
      val headerValue = line.substring(colonIdx + 1).trim()
      when {
        headerName.equals("Content-Length", ignoreCase = true) -> {
          contentLength = headerValue.toLongOrNull()?.coerceAtLeast(0L)
        }
        headerName.equals("Transfer-Encoding", ignoreCase = true) -> {
          if (headerValue.contains("chunked", ignoreCase = true)) {
            isChunked = true
          }
        }
      }
    }

    return HttpResponseHeaders(
      statusLine = HttpStatusLine(rawStatusLine = firstLine, statusCode = statusCode),
      contentLength = contentLength,
      isChunked = isChunked,
    )
  }

  /**
   * Backward-compatible single-status reader.
   */
  @Throws(IOException::class)
  fun readHttpResponseStatusLine(
    input: InputStream,
    maxHeaderBytes: Int = MAX_HEADER_BYTES,
  ): HttpStatusLine {
    return readHttpResponseHeaders(
      input = input,
      maxHeaderBytes = maxHeaderBytes,
      skipLeadingNoise = false,
    )?.statusLine
      ?: throw IOException("ဆာဗာမှ HTTP တုံ့ပြန်ချက် မရရှိဘဲ ချိတ်ဆက်မှု ပိတ်သွားပါသည် (EOF)")
  }

  /**
   * Leniently reads HTTP responses in a loop, tolerating and skipping interim responses
   * (such as `301`, `302`, `200`, `403`, etc.) and any associated HTTP bodies until the
   * required success status arrives:
   * - `101 Switching Protocols` when [expectWebSocketUpgrade] is true (or [mode] is [PayloadMode.WEBSOCKET]).
   * - `200 OK` (or `101`) when [mode] is [PayloadMode.HTTP_PAYLOAD] or [PayloadMode.SSL_TLS_SNI]
   *   and [expectWebSocketUpgrade] is false.
   *
   * Invokes [onStatusLine] for EVERY status line received (`isSuccess = true` for the final
   * target status, `false` for skipped interim statuses).
   */
  @Throws(IOException::class)
  fun readUntilSuccessStatus(
    input: InputStream,
    mode: PayloadMode,
    expectWebSocketUpgrade: Boolean = (mode == PayloadMode.WEBSOCKET),
    maxResponses: Int = DEFAULT_MAX_INTERIM_RESPONSES,
    isAborted: () -> Boolean = { false },
    onStatusLine: (status: HttpStatusLine, isSuccess: Boolean) -> Unit = { _, _ -> },
  ): HttpStatusLine {
    val require101Only = mode == PayloadMode.WEBSOCKET || expectWebSocketUpgrade
    val expectedLabel = if (require101Only) "HTTP 101 Switching Protocols" else "HTTP 200/101"
    var lastStatus: HttpStatusLine? = null
    var responsesRead = 0

    while (responsesRead < maxResponses) {
      if (isAborted()) {
        throw IOException("ချိတ်ဆက်မှုကို အသုံးပြုသူမှ ပယ်ဖျက်လိုက်ပါပြီ")
      }

      val response =
        try {
          readHttpResponseHeaders(
            input = input,
            skipLeadingNoise = responsesRead > 0,
          )
        } catch (e: SocketTimeoutException) {
          if (isAborted()) {
            throw IOException("ချိတ်ဆက်မှုကို အသုံးပြုသူမှ ပယ်ဖျက်လိုက်ပါပြီ", e)
          }
          val lastNote = lastStatus?.let { " (နောက်ဆုံးရရှိချက်: ${it.rawStatusLine})" } ?: ""
          throw IOException(
            "HTTP Payload တုံ့ပြန်ချက် စောင့်ဆိုင်းချိန် ကျော်လွန်သွားပါသည် — $expectedLabel မရရှိပါ$lastNote",
            e,
          )
        } catch (e: IOException) {
          if (isAborted()) {
            throw IOException("ချိတ်ဆက်မှုကို အသုံးပြုသူမှ ပယ်ဖျက်လိုက်ပါပြီ", e)
          }
          val lastNote = lastStatus?.let { " (နောက်ဆုံးရရှိချက်: ${it.rawStatusLine})" } ?: ""
          if (lastStatus != null) {
            throw IOException(
              "HTTP Payload မအောင်မြင်ပါ — $expectedLabel မရရှိဘဲ ချိတ်ဆက်မှု ပြတ်တောက်သွားပါသည်$lastNote",
              e,
            )
          }
          throw e
        }

      if (response == null) {
        if (isAborted()) {
          throw IOException("ချိတ်ဆက်မှုကို အသုံးပြုသူမှ ပယ်ဖျက်လိုက်ပါပြီ")
        }
        if (lastStatus != null) {
          throw IOException(
            "HTTP Payload မအောင်မြင်ပါ — $expectedLabel မရရှိဘဲ ဆာဗာချိတ်ဆက်မှု ပိတ်သွားပါသည် (နောက်ဆုံးရရှိချက်: ${lastStatus.rawStatusLine})"
          )
        }
        throw IOException("ဆာဗာမှ HTTP တုံ့ပြန်ချက် မရရှိဘဲ ချိတ်ဆက်မှု ပိတ်သွားပါသည် (EOF)")
      }

      responsesRead += 1
      val status = response.statusLine
      lastStatus = status

      val isSuccess =
        if (require101Only) {
          status.statusCode == 101
        } else {
          status.statusCode == 200 || status.statusCode == 101
        }

      onStatusLine(status, isSuccess)
      if (isSuccess) {
        return status
      }

      // Skip any body attached to this interim response before reading the next HTTP status line
      try {
        skipInterimResponseBody(input, response)
      } catch (e: SocketTimeoutException) {
        if (isAborted()) {
          throw IOException("ချိတ်ဆက်မှုကို အသုံးပြုသူမှ ပယ်ဖျက်လိုက်ပါပြီ", e)
        }
        throw IOException(
          "HTTP Payload တုံ့ပြန်ချက် စောင့်ဆိုင်းချိန် ကျော်လွန်သွားပါသည် — $expectedLabel မရရှိပါ (နောက်ဆုံးရရှိချက်: ${status.rawStatusLine})",
          e,
        )
      } catch (e: IOException) {
        if (isAborted()) {
          throw IOException("ချိတ်ဆက်မှုကို အသုံးပြုသူမှ ပယ်ဖျက်လိုက်ပါပြီ", e)
        }
        throw IOException(
          "HTTP Payload မအောင်မြင်ပါ — $expectedLabel မရရှိဘဲ ဆာဗာချိတ်ဆက်မှု ပိတ်သွားပါသည် (နောက်ဆုံးရရှိချက်: ${status.rawStatusLine})",
          e,
        )
      }
    }

    val lastNote = lastStatus?.let { " (နောက်ဆုံးရရှိချက်: ${it.rawStatusLine})" } ?: ""
    throw IOException(
      "HTTP Payload တုံ့ပြန်ချက် အကြိမ်ရေ ($maxResponses) ကျော်လွန်သော်လည်း $expectedLabel မရရှိပါ$lastNote"
    )
  }

  /**
   * Validates a single HTTP status code according to [mode].
   */
  @Throws(IOException::class)
  fun validateHttpStatus(mode: PayloadMode, status: HttpStatusLine) {
    when (mode) {
      PayloadMode.WEBSOCKET -> {
        if (status.statusCode != 101) {
          throw IOException(
            "WebSocket Upgrade မအောင်မြင်ပါ — HTTP 101 လိုအပ်သော်လည်း ရရှိချက်: ${status.rawStatusLine}"
          )
        }
      }
      PayloadMode.HTTP_PAYLOAD,
      PayloadMode.SSL_TLS_SNI -> {
        if (status.statusCode != 200 && status.statusCode != 101) {
          throw IOException(
            "HTTP Payload တုံ့ပြန်ချက် မအောင်မြင်ပါ — HTTP 200/101 လိုအပ်သော်လည်း ရရှိချက်: ${status.rawStatusLine}"
          )
        }
      }
      PayloadMode.DIRECT -> Unit
    }
  }

  /**
   * Consumes exact body bytes of an interim non-success HTTP response when `Content-Length`
   * or `Transfer-Encoding: chunked` is declared, leaving the stream aligned at the start of
   * the next HTTP response.
   */
  @Throws(IOException::class)
  private fun skipInterimResponseBody(input: InputStream, response: HttpResponseHeaders) {
    val code = response.statusLine.statusCode
    // RFC 7230 §3.3.3: 1xx, 204, and 304 responses never carry a message body.
    if ((code in 100..199) || code == 204 || code == 304) {
      return
    }

    if (response.isChunked) {
      skipChunkedBody(input)
      return
    }

    val length = response.contentLength ?: return
    if (length <= 0L) return
    val toSkip = length.coerceAtMost(MAX_INTERIM_BODY_SKIP_BYTES)
    var remaining = toSkip
    while (remaining > 0L) {
      val b = input.read()
      if (b == -1) return
      remaining -= 1L
    }
  }

  @Throws(IOException::class)
  private fun skipChunkedBody(input: InputStream) {
    var totalSkipped = 0L
    while (totalSkipped < MAX_INTERIM_BODY_SKIP_BYTES) {
      val sizeLine = readAsciiLine(input) ?: return
      val hexPart = sizeLine.substringBefore(';').trim()
      val chunkSize = hexPart.toLongOrNull(16) ?: return
      if (chunkSize == 0L) {
        // Drain optional trailer headers until blank line
        while (true) {
          val trailer = readAsciiLine(input) ?: return
          if (trailer.isEmpty()) return
        }
      }
      var remaining = chunkSize
      while (remaining > 0L && totalSkipped < MAX_INTERIM_BODY_SKIP_BYTES) {
        if (input.read() == -1) return
        remaining -= 1L
        totalSkipped += 1L
      }
      // Consume trailing CRLF after chunk payload
      val c1 = input.read()
      if (c1 == '\r'.code) {
        input.read()
      }
    }
  }

  @Throws(IOException::class)
  private fun readAsciiLine(input: InputStream): String? {
    val sb = StringBuilder(32)
    var sawAny = false
    while (sb.length < 1024) {
      val b = input.read()
      if (b == -1) return if (sawAny) sb.toString() else null
      sawAny = true
      if (b == '\n'.code) break
      if (b != '\r'.code) sb.append(b.toChar())
    }
    return sb.toString()
  }

  /**
   * Scans byte-by-byte past any leftover unframed interim response body until the literal
   * `HTTP/` prefix of the next HTTP response status line is encountered.
   * Returns the 5-byte matched prefix (e.g. `HTTP/`), or `null` on EOF / scan limit reached.
   */
  @Throws(IOException::class)
  private fun scanToHttpPrefix(input: InputStream): ByteArray? {
    val targetUpper = byteArrayOf('H'.code.toByte(), 'T'.code.toByte(), 'T'.code.toByte(), 'P'.code.toByte(), '/'.code.toByte())
    val matched = ByteArray(5)
    var matchIdx = 0
    var scanned = 0

    while (scanned < MAX_NOISE_SCAN_BYTES) {
      val next = input.read()
      if (next == -1) return null
      scanned += 1

      val upper =
        if (next in 'a'.code..'z'.code) (next - 32).toByte() else next.toByte()
      if (upper == targetUpper[matchIdx]) {
        matched[matchIdx] = next.toByte()
        matchIdx += 1
        if (matchIdx == targetUpper.size) {
          return matched
        }
      } else if (upper == targetUpper[0]) {
        matched[0] = next.toByte()
        matchIdx = 1
      } else {
        matchIdx = 0
      }
    }
    return null
  }

  private fun substitutePlaceholders(
    raw: String,
    host: String,
    port: Int,
    effectiveSni: String,
    userAgent: String,
  ): String {
    return raw
      .replace("[host]", host, ignoreCase = true)
      .replace("[port]", port.toString(), ignoreCase = true)
      .replace("[sni]", effectiveSni, ignoreCase = true)
      .replace("[ua]", userAgent, ignoreCase = true)
      .replace("[protocol]", "HTTP/1.1", ignoreCase = true)
      .replace("[crlf]", "\r\n", ignoreCase = true)
      .replace("[lf]", "\n", ignoreCase = true)
      .replace("[cr]", "\r", ignoreCase = true)
  }

  private fun normalizeToCrlf(input: String): String {
    if (input.isEmpty()) return ""
    val out = StringBuilder(input.length + 16)
    var i = 0
    while (i < input.length) {
      val ch = input[i]
      when {
        ch == '\r' && i + 1 < input.length && input[i + 1] == '\n' -> {
          out.append("\r\n")
          i += 2
        }
        ch == '\r' || ch == '\n' -> {
          out.append("\r\n")
          i += 1
        }
        else -> {
          out.append(ch)
          i += 1
        }
      }
    }
    return out.toString()
  }
}
