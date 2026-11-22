package com.example.data

/**
 * Per-profile transport / payload injection mode (Milestone 4).
 */
enum class PayloadMode(
  val code: String,
  val displayLabel: String,
  val burmeseLabel: String,
  val burmeseDescription: String,
) {
  DIRECT(
    code = "DIRECT",
    displayLabel = "Direct",
    burmeseLabel = "Direct (တိုက်ရိုက်)",
    burmeseDescription = "Payload မပါဘဲ SSH Host:Port သို့ TCP တိုက်ရိုက်ချိတ်ဆက်မည်",
  ),
  HTTP_PAYLOAD(
    code = "HTTP_PAYLOAD",
    displayLabel = "HTTP Payload",
    burmeseLabel = "HTTP Payload (Plain)",
    burmeseDescription = "Custom HTTP Payload ပေးပို့ပြီး 200/101 တုံ့ပြန်ချက်ရမှ SSH ချိတ်ဆက်မည်",
  ),
  WEBSOCKET(
    code = "WEBSOCKET",
    displayLabel = "WebSocket",
    burmeseLabel = "WebSocket (HTTP 101)",
    burmeseDescription = "HTTP Upgrade: websocket ပေးပို့ပြီး HTTP 101 Switching Protocols ရမှ SSH ချိတ်ဆက်မည်",
  ),
  SSL_TLS_SNI(
    code = "SSL_TLS_SNI",
    displayLabel = "SSL/TLS + SNI",
    burmeseLabel = "SSL/TLS + SNI",
    burmeseDescription = "TLS Handshake တွင် SNI Host ထည့်သွင်း၍ SSLSocket ပေါ်မှ SSH ချိတ်ဆက်မည်",
  );

  companion object {
    fun fromCode(code: String?): PayloadMode? {
      if (code.isNullOrBlank()) return null
      val normalized = code.trim().uppercase().replace('-', '_')
      return when (normalized) {
        "DIRECT", "SSH_DIRECT", "NONE" -> DIRECT
        "HTTP_PAYLOAD", "HTTP", "PLAIN", "PROXY", "SSH_PROXY" -> HTTP_PAYLOAD
        "WEBSOCKET", "WS", "SSH_WS", "SSH_WEBSOCKET" -> WEBSOCKET
        "SSL_TLS_SNI", "SSL", "TLS", "SNI", "SSL_SNI", "SSH_SSL" -> SSL_TLS_SNI
        else -> entries.firstOrNull { it.code == normalized }
      }
    }

    fun defaultForConnectionMode(connectionMode: ConnectionMode): PayloadMode {
      return when (connectionMode) {
        ConnectionMode.SSH_PROXY -> HTTP_PAYLOAD
        ConnectionMode.SSH_WEBSOCKET -> WEBSOCKET
        ConnectionMode.SSH_SSL -> SSL_TLS_SNI
        ConnectionMode.SSH_DIRECT,
        ConnectionMode.DNSTT,
        ConnectionMode.VLESS_WS -> DIRECT
      }
    }
  }
}

/**
 * Editable starter payload examples ONLY — uses [host] and [port] placeholders only.
 * Never embeds any real IP, domain, host, port, key, or password, and never overwrites
 * a user's saved payload unless they explicitly tap a template button.
 */
object PayloadTemplates {
  const val CONNECT_EXAMPLE: String =
    "CONNECT [host]:[port] HTTP/1.1[crlf]Host: [host][crlf]Connection: Keep-Alive[crlf][crlf]"

  const val WEBSOCKET_EXAMPLE: String =
    "GET / HTTP/1.1[crlf]Host: [host]:[port][crlf]Upgrade: websocket[crlf]Connection: Upgrade[crlf]Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==[crlf]Sec-WebSocket-Version: 13[crlf][crlf]"

  const val SPLIT_WEBSOCKET_EXAMPLE: String =
    "GET / HTTP/1.1[crlf]Host: [sni][crlf][crlf][split]GET / HTTP/1.1[crlf]Host: [host][crlf]Upgrade: websocket[crlf]Connection: Upgrade[crlf]User-Agent: [ua][crlf][crlf]"

  const val BLANK: String = ""
}
