package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Reusable Network transport configuration (HTTP Injector "Payload + Remote Proxy / SSL SNI" model).
 * Created once and shared across many Server/Account entries. Contains NO server host, credentials,
 * or expiry data.
 */
@Entity(tableName = "network_configs")
data class NetworkConfigEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0L,
  val name: String,
  val payloadMode: String = PayloadMode.DIRECT.code,
  val payloadTemplate: String = "",
  val sniHost: String = "",
  val extraHeaders: String = "",
  val httpProxyHost: String = "",
  val httpProxyPort: Int = DEFAULT_HTTP_PROXY_PORT,
  val locked: Boolean = false,
  val createdAt: Long = System.currentTimeMillis(),
  val updatedAt: Long = System.currentTimeMillis(),
) {
  val resolvedPayloadMode: PayloadMode
    get() = PayloadMode.fromCode(payloadMode) ?: PayloadMode.DIRECT

  /**
   * Deterministic normalized signature used to deduplicate identical networks during Room v3->v4
   * migration, starter template seeding, and legacy single-profile imports.
   */
  fun normalizedSignature(): String {
    return computeSignature(
      payloadMode = resolvedPayloadMode.code,
      payloadTemplate = payloadTemplate,
      sniHost = sniHost,
      extraHeaders = extraHeaders,
      httpProxyHost = httpProxyHost,
      httpProxyPort = httpProxyPort,
    )
  }

  fun summaryLine(): String {
    val parts = mutableListOf(resolvedPayloadMode.displayLabel)
    if (sniHost.isNotBlank()) {
      parts += "SNI: ${sniHost.trim()}"
    }
    if (httpProxyHost.isNotBlank()) {
      parts += "Proxy: ${httpProxyHost.trim()}:$httpProxyPort"
    }
    if (payloadTemplate.isNotBlank() && resolvedPayloadMode == PayloadMode.DIRECT) {
      parts += "Path/Payload: ${payloadTemplate.trim().take(28)}"
    }
    return parts.joinToString(" • ")
  }

  companion object {
    const val DEFAULT_HTTP_PROXY_PORT = 8080

    fun computeSignature(
      payloadMode: String,
      payloadTemplate: String,
      sniHost: String,
      extraHeaders: String,
      httpProxyHost: String,
      httpProxyPort: Int,
    ): String {
      val normMode = (PayloadMode.fromCode(payloadMode) ?: PayloadMode.DIRECT).code
      val normPayload = normalizeMultiline(payloadTemplate)
      val normSni = sniHost.trim().lowercase()
      val normHeaders = normalizeMultiline(extraHeaders)
      val normProxyHost = httpProxyHost.trim().lowercase()
      // Only consider proxy port when an HTTP proxy host is actually configured.
      val normProxyPort = if (normProxyHost.isEmpty()) 0 else httpProxyPort.coerceIn(1, 65535)
      return listOf(
        normMode,
        normPayload,
        normSni,
        normHeaders,
        normProxyHost,
        normProxyPort.toString(),
      ).joinToString("\u001F")
    }

    private fun normalizeMultiline(raw: String): String {
      return raw.replace("\r\n", "\n").replace("\r", "\n").trim()
    }
  }
}
