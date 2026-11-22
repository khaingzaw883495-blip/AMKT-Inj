package com.example.config

import com.example.data.ConnectionMode
import com.example.data.ServerProfileEntity
import java.net.URLDecoder

/**
 * Parses plain .txt config files or pasted text containing vless:// or ss://
 * links (one per line) into ServerProfileEntity entries.
 */
object LinkConfigParser {
  fun parseLinks(rawText: String): List<ServerProfileEntity> {
    return rawText
      .lineSequence()
      .map { it.trim() }
      .filter { it.isNotBlank() && !it.startsWith("#") }
      .mapNotNull { line ->
        when {
          line.startsWith("vless://", ignoreCase = true) -> parseVlessLine(line)
          line.startsWith("ss://", ignoreCase = true) -> parseShadowsocksLine(line)
          else -> null
        }
      }
      .toList()
  }

  fun parseVlessLine(link: String): ServerProfileEntity? {
    val parsed = com.example.vless.VlessConfigParser.parseLink(link).getOrNull() ?: return null
    val remark = parsed.remark.ifBlank { "VLESS — ${parsed.serverHost}:${parsed.serverPort}" }
    return ServerProfileEntity(
      name = remark,
      mode = ConnectionMode.VLESS_WS.code,
      sshHost = parsed.serverHost,
      sshPort = parsed.serverPort,
      username = parsed.uuid,
      payload = parsed.wsPath,
      sni = parsed.effectiveSni,
      vlessLink = link.trim(),
      notes =
        "vless:// လင့်ခ်မှ ထည့်သွင်းထားသည် (path=${parsed.wsPath}, host=${parsed.effectiveWsHost}, security=${parsed.securityLabel})",
    )
  }

  fun parseShadowsocksLine(link: String): ServerProfileEntity? {
    return try {
      val withoutScheme = link.substringAfter("://")
      val remarkPart =
        if ('#' in withoutScheme) decodeUrlComponent(withoutScheme.substringAfter('#')).trim()
        else ""
      val body = withoutScheme.substringBefore('#').substringBefore('?')

      val decodedBody =
        if ('@' in body) {
          val left = body.substringBefore('@')
          val right = body.substringAfter('@')
          val decodedLeft =
            Base64Compat.decodeOrNull(left)?.toString(Charsets.UTF_8)?.takeIf { ':' in it }
              ?: left
          "$decodedLeft@$right"
        } else {
          Base64Compat.decodeOrNull(body)?.toString(Charsets.UTF_8) ?: return null
        }

      val hostPort = decodedBody.substringAfterLast('@')
      val host = hostPort.substringBeforeLast(':').trim('[', ']')
      val port = hostPort.substringAfterLast(':').toIntOrNull() ?: 8388
      if (host.isBlank()) return null

      ServerProfileEntity(
        name = remarkPart.ifBlank { "SS — $host:$port" },
        mode = ConnectionMode.VLESS_WS.code,
        sshHost = host,
        sshPort = port,
        vlessLink = link,
        notes = "ss:// လင့်ခ်မှ ထည့်သွင်းထားသည်",
      )
    } catch (_: Exception) {
      null
    }
  }

  private fun decodeUrlComponent(value: String): String {
    return try {
      URLDecoder.decode(value, "UTF-8")
    } catch (_: Exception) {
      value
    }
  }
}
