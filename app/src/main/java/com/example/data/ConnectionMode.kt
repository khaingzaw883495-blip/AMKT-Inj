package com.example.data

enum class ConnectionMode(
  val code: String,
  val displayLabel: String,
  val shortLabel: String,
  val burmeseDescription: String,
) {
  SSH_DIRECT(
    code = "SSH_DIRECT",
    displayLabel = "SSH DIRECT",
    shortLabel = "DIRECT",
    burmeseDescription = "SSH ဆာဗာသို့ တိုက်ရိုက်ချိတ်ဆက်မှု (Host + Port + Account)",
  ),
  SSH_PROXY(
    code = "SSH_PROXY",
    displayLabel = "SSH + PROXY",
    shortLabel = "PROXY",
    burmeseDescription = "HTTP Proxy နှင့် Custom Payload ဖြင့် SSH ချိတ်ဆက်မှု",
  ),
  SSH_SSL(
    code = "SSH_SSL",
    displayLabel = "SSH + SSL",
    shortLabel = "SSL/SNI",
    burmeseDescription = "TLS / SNI Host ဖြင့် SSH ချိတ်ဆက်မှု",
  ),
  SSH_WEBSOCKET(
    code = "SSH_WEBSOCKET",
    displayLabel = "SSH + WebSocket",
    shortLabel = "SSH WS",
    burmeseDescription = "CloudFront / WebSocket Bridge (HTTP 101) မှတစ်ဆင့် SSH ချိတ်ဆက်မှု",
  ),
  DNSTT(
    code = "DNSTT",
    displayLabel = "DNSTT / SlowDNS",
    shortLabel = "DNSTT",
    burmeseDescription = "Nameserver + Public Key + SSH Account (ပိတ်ဆို့ထားသော ကွန်ရက်အတွက် SlowDNS အရန်စနစ်)",
  ),
  VLESS_WS(
    code = "VLESS_WS",
    displayLabel = "VLESS + WS",
    shortLabel = "VLESS",
    burmeseDescription = "CloudFront VLESS over WebSocket (vless:// လင့်ခ်ဖြင့်)",
  ),
  OPENVPN(
    code = "OPENVPN",
    displayLabel = "OpenVPN",
    shortLabel = "OpenVPN",
    burmeseDescription = "OpenVPN (.ovpn config ဖိုင်ဖြင့် System-wide VPN ချိတ်ဆက်မှု)",
  );

  companion object {
    fun fromCode(code: String?): ConnectionMode? {
      if (code.isNullOrBlank()) return null
      val normalized = code.trim().uppercase().replace('-', '_')
      return when (normalized) {
        "SSH_WS", "SSH WS", "WS", "WEBSOCKET" -> SSH_WEBSOCKET
        "SLOWDNS", "DNS" -> DNSTT
        "VLESS", "VLESS_WEBSOCKET" -> VLESS_WS
        "OPENVPN", "OVPN", "OPEN_VPN" -> OPENVPN
        "DIRECT" -> SSH_DIRECT
        "PROXY" -> SSH_PROXY
        "SSL", "SNI", "SSH_TLS" -> SSH_SSL
        else ->
          entries.firstOrNull {
            it.code == normalized || it.displayLabel.uppercase() == normalized
          }
      }
    }
  }
}
