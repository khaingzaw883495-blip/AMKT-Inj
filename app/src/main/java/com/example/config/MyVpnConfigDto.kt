package com.example.config

import com.example.data.ConnectionMode
import com.example.data.PayloadMode
import com.example.data.ServerProfileEntity
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * JSON schema for the owner's .myvpn config format (Section 5).
 * Required fields: ssh_host, ssh_port, username, password, payload, proxy_host,
 * proxy_port, sni, dnstt_nameserver, dnstt_public_key, vless_link, expiry_date,
 * locked, notes. Optional name/mode fields preserve UI metadata.
 */
@JsonClass(generateAdapter = true)
data class MyVpnConfigDto(
  @Json(name = "name") val name: String = "",
  @Json(name = "mode") val mode: String = "",
  @Json(name = "ssh_host") val sshHost: String = "",
  @Json(name = "ssh_port") val sshPort: Int = 0,
  @Json(name = "username") val username: String = "",
  @Json(name = "password") val password: String = "",
  @Json(name = "payload") val payload: String = "",
  @Json(name = "payload_mode") val payloadMode: String = "",
  @Json(name = "extra_headers") val extraHeaders: String = "",
  @Json(name = "proxy_host") val proxyHost: String = "",
  @Json(name = "proxy_port") val proxyPort: Int = 0,
  @Json(name = "sni") val sni: String = "",
  @Json(name = "dnstt_nameserver") val dnsttNameserver: String = "",
  @Json(name = "dnstt_public_key") val dnsttPublicKey: String = "",
  @Json(name = "dnstt_resolver") val dnsttResolver: String = "",
  @Json(name = "vless_link") val vlessLink: String = "",
  @Json(name = "expiry_date") val expiryDate: String = "",
  @Json(name = "locked") val locked: Boolean = false,
  @Json(name = "notes") val notes: String = "",
) {
  fun inferConnectionMode(): ConnectionMode {
    ConnectionMode.fromCode(mode)?.let { return it }
    val explicitPayloadMode = PayloadMode.fromCode(payloadMode)
    if (explicitPayloadMode != null) {
      return when (explicitPayloadMode) {
        PayloadMode.WEBSOCKET -> ConnectionMode.SSH_WEBSOCKET
        PayloadMode.SSL_TLS_SNI -> ConnectionMode.SSH_SSL
        PayloadMode.HTTP_PAYLOAD -> ConnectionMode.SSH_PROXY
        PayloadMode.DIRECT -> ConnectionMode.SSH_DIRECT
      }
    }
    return when {
      vlessLink.isNotBlank() -> ConnectionMode.VLESS_WS
      dnsttNameserver.isNotBlank() && dnsttPublicKey.isNotBlank() -> ConnectionMode.DNSTT
      payload.contains("upgrade: websocket", ignoreCase = true) -> ConnectionMode.SSH_WEBSOCKET
      proxyHost.isNotBlank() -> ConnectionMode.SSH_PROXY
      sni.isNotBlank() -> ConnectionMode.SSH_SSL
      else -> ConnectionMode.SSH_DIRECT
    }
  }

  fun toEntity(fallbackName: String): ServerProfileEntity {
    val resolvedMode = inferConnectionMode()
    val resolvedPayloadMode =
      PayloadMode.fromCode(payloadMode) ?: PayloadMode.defaultForConnectionMode(resolvedMode)
    val resolvedName =
      name.trim().ifBlank {
        fallbackName.ifBlank {
          when (resolvedMode) {
            ConnectionMode.DNSTT -> "DNSTT — ${dnsttNameserver.ifBlank { sshHost }}"
            ConnectionMode.VLESS_WS -> "VLESS — ${sshHost.ifBlank { "Imported" }}"
            else -> "${resolvedMode.displayLabel} — ${sshHost.ifBlank { "Imported" }}"
          }
        }
      }
    return ServerProfileEntity(
      name = resolvedName,
      mode = resolvedMode.code,
      sshHost = sshHost.trim(),
      sshPort = if (sshPort > 0) sshPort else 22,
      username = username.trim(),
      password = password,
      payload = payload,
      payloadMode = resolvedPayloadMode.code,
      extraHeaders = extraHeaders,
      proxyHost = proxyHost.trim(),
      proxyPort = if (proxyPort > 0) proxyPort else 8080,
      sni = sni.trim(),
      dnsttNameserver = dnsttNameserver.trim(),
      dnsttPublicKey = dnsttPublicKey.trim(),
      dnsttResolver = dnsttResolver.trim().ifBlank { ServerProfileEntity.DEFAULT_DNSTT_RESOLVER },
      vlessLink = vlessLink.trim(),
      expiryDate = expiryDate.trim(),
      locked = locked,
      notes = notes.trim(),
    )
  }

  companion object {
    fun fromEntity(
      profile: ServerProfileEntity,
      lockOverride: Boolean? = null,
      expiryOverride: String? = null,
      notesOverride: String? = null,
    ): MyVpnConfigDto =
      MyVpnConfigDto(
        name = profile.name,
        mode = profile.mode,
        sshHost = profile.sshHost,
        sshPort = profile.sshPort,
        username = profile.username,
        password = profile.password,
        payload = profile.payload,
        payloadMode = profile.resolvedPayloadMode.code,
        extraHeaders = profile.extraHeaders,
        proxyHost = profile.proxyHost,
        proxyPort = profile.proxyPort,
        sni = profile.sni,
        dnsttNameserver = profile.dnsttNameserver,
        dnsttPublicKey = profile.dnsttPublicKey,
        dnsttResolver = profile.effectiveDnsttResolver(),
        vlessLink = profile.vlessLink,
        expiryDate = expiryOverride ?: profile.expiryDate,
        locked = lockOverride ?: profile.locked,
        notes = notesOverride ?: profile.notes,
      )
  }
}
