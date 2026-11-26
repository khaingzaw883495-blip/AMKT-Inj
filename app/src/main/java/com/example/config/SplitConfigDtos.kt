package com.example.config

import com.example.data.ConnectionMode
import com.example.data.NetworkConfigEntity
import com.example.data.PayloadMode
import com.example.data.ServerAccountEntity
import com.example.data.ServerProfileEntity
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * JSON DTO for a reusable Network configuration (payload / SSL / SNI / HTTP proxy).
 */
@JsonClass(generateAdapter = true)
data class NetworkConfigDto(
  @Json(name = "name") val name: String = "",
  @Json(name = "payload_mode") val payloadMode: String = "",
  @Json(name = "payload_template") val payloadTemplate: String = "",
  @Json(name = "payload") val legacyPayload: String = "",
  @Json(name = "sni_host") val sniHost: String = "",
  @Json(name = "sni") val legacySni: String = "",
  @Json(name = "extra_headers") val extraHeaders: String = "",
  @Json(name = "http_proxy_host") val httpProxyHost: String = "",
  @Json(name = "proxy_host") val legacyProxyHost: String = "",
  @Json(name = "http_proxy_port") val httpProxyPort: Int = 0,
  @Json(name = "proxy_port") val legacyProxyPort: Int = 0,
  @Json(name = "locked") val locked: Boolean = false,
) {
  val effectivePayloadTemplate: String
    get() = payloadTemplate.ifBlank { legacyPayload }

  val effectiveSniHost: String
    get() = sniHost.trim().ifBlank { legacySni.trim() }

  val effectiveProxyHost: String
    get() = httpProxyHost.trim().ifBlank { legacyProxyHost.trim() }

  val effectiveProxyPort: Int
    get() =
      when {
        httpProxyPort in 1..65535 -> httpProxyPort
        legacyProxyPort in 1..65535 -> legacyProxyPort
        else -> 0
      }

  fun toEntity(fallbackName: String): NetworkConfigEntity {
    val resolvedMode =
      PayloadMode.fromCode(payloadMode)
        ?: when {
          effectivePayloadTemplate.contains("upgrade: websocket", ignoreCase = true) ->
            PayloadMode.WEBSOCKET
          effectiveProxyHost.isNotBlank() || effectivePayloadTemplate.isNotBlank() ->
            PayloadMode.HTTP_PAYLOAD
          effectiveSniHost.isNotBlank() -> PayloadMode.SSL_TLS_SNI
          else -> PayloadMode.DIRECT
        }
    return NetworkConfigEntity(
      name = name.trim().ifBlank { fallbackName.ifBlank { "Network" } },
      payloadMode = resolvedMode.code,
      payloadTemplate = effectivePayloadTemplate,
      sniHost = effectiveSniHost,
      extraHeaders = extraHeaders,
      httpProxyHost = effectiveProxyHost,
      httpProxyPort =
        if (effectiveProxyPort in 1..65535) effectiveProxyPort
        else NetworkConfigEntity.DEFAULT_HTTP_PROXY_PORT,
      locked = locked,
    )
  }

  companion object {
    fun fromEntity(
      network: NetworkConfigEntity,
      lockOverride: Boolean? = null,
    ): NetworkConfigDto =
      NetworkConfigDto(
        name = network.name,
        payloadMode = network.resolvedPayloadMode.code,
        payloadTemplate = network.payloadTemplate,
        sniHost = network.sniHost,
        extraHeaders = network.extraHeaders,
        httpProxyHost = network.httpProxyHost,
        httpProxyPort = network.httpProxyPort,
        locked = lockOverride ?: network.locked,
      )
  }
}

/**
 * JSON DTO for a Server / Account configuration (host / port / credentials / DNSTT / VLESS / expiry).
 */
@JsonClass(generateAdapter = true)
data class ServerAccountDto(
  @Json(name = "name") val name: String = "",
  @Json(name = "connection_mode") val connectionMode: String = "",
  @Json(name = "mode") val legacyMode: String = "",
  @Json(name = "host") val host: String = "",
  @Json(name = "ssh_host") val legacySshHost: String = "",
  @Json(name = "port") val port: Int = 0,
  @Json(name = "ssh_port") val legacySshPort: Int = 0,
  @Json(name = "username") val username: String = "",
  @Json(name = "password") val password: String = "",
  @Json(name = "dnstt_nameserver") val dnsttNameserver: String = "",
  @Json(name = "dnstt_public_key") val dnsttPublicKey: String = "",
  @Json(name = "dnstt_resolver") val dnsttResolver: String = "",
  @Json(name = "vless_link") val vlessLink: String = "",
  @Json(name = "vless_uuid") val vlessUuid: String = "",
  @Json(name = "expiry_date") val expiryDate: String = "",
  @Json(name = "expiry") val expiry: String = "",
  @Json(name = "ovpn") val ovpnBase64: String = "",
  @Json(name = "region") val region: String = "",
  @Json(name = "tags") val tags: String = "",
  @Json(name = "notes") val notes: String = "",
  @Json(name = "locked") val locked: Boolean = false,
  @Json(name = "network_name") val networkName: String = "",
  @Json(name = "online") val online: Int? = null,
  @Json(name = "online_users") val onlineUsers: Int? = null,
  @Json(name = "online_count") val onlineCount: Int? = null,
  @Json(name = "users_online") val usersOnline: Int? = null,
  @Json(name = "active_users") val activeUsers: Int? = null,
  @Json(name = "limit") val limit: Int? = null,
  @Json(name = "max_users") val maxUsers: Int? = null,
  @Json(name = "user_limit") val userLimit: Int? = null,
  @Json(name = "max_online") val maxOnline: Int? = null,
  @Json(name = "capacity") val capacity: Int? = null,
  @Json(name = "lifetime_users") val lifetimeUsers: Int? = null,
  @Json(name = "used_by") val usedBy: Int? = null,
  @Json(name = "distinct_users") val distinctUsers: Int? = null,
  @Json(name = "total_users") val totalUsers: Int? = null,
  @Json(name = "server_users") val serverUsers: Int? = null,
) {
  val effectiveHost: String
    get() = host.trim().ifBlank { legacySshHost.trim() }

  val effectivePort: Int
    get() =
      when {
        port in 1..65535 -> port
        legacySshPort in 1..65535 -> legacySshPort
        else -> 0
      }

  val effectiveModeRaw: String
    get() = connectionMode.trim().ifBlank { legacyMode.trim() }

  val effectiveExpiryDate: String
    get() = expiryDate.trim().ifBlank { expiry.trim() }

  val effectiveOnlineCount: Int?
    get() =
      listOf(online, onlineUsers, onlineCount, usersOnline, activeUsers)
        .firstOrNull { it != null && it >= 0 }

  val explicitUserLimit: Int?
    get() =
      listOf(limit, maxUsers, userLimit, maxOnline, capacity)
        .firstOrNull { it != null && it > 0 }

  val effectiveUserLimit: Int
    get() = explicitUserLimit ?: ServerAccountEntity.DEFAULT_USER_LIMIT

  val effectiveLifetimeUsers: Int?
    get() =
      listOf(lifetimeUsers, usedBy, distinctUsers, totalUsers, serverUsers)
        .firstOrNull { it != null && it >= 0 }

  fun inferServerConnectionMode(): ConnectionMode {
    val explicit = ConnectionMode.fromCode(effectiveModeRaw)
    return when {
      explicit == ConnectionMode.OPENVPN -> ConnectionMode.OPENVPN
      explicit == ConnectionMode.DNSTT -> ConnectionMode.DNSTT
      explicit == ConnectionMode.VLESS_WS -> ConnectionMode.VLESS_WS
      explicit != null -> ConnectionMode.SSH_DIRECT
      ovpnBase64.isNotBlank() -> ConnectionMode.OPENVPN
      vlessLink.isNotBlank() || vlessUuid.isNotBlank() -> ConnectionMode.VLESS_WS
      dnsttNameserver.isNotBlank() && dnsttPublicKey.isNotBlank() -> ConnectionMode.DNSTT
      else -> ConnectionMode.SSH_DIRECT
    }
  }

  fun toEntity(fallbackName: String): ServerAccountEntity {
    val mode = inferServerConnectionMode()
    if (mode == ConnectionMode.OPENVPN) {
      val parsed =
        if (ovpnBase64.isNotBlank()) {
          com.example.openvpn.OvpnConfigParser.decodeBase64Ovpn(ovpnBase64).getOrThrow()
        } else {
          null
        }
      val resolvedName =
        name.trim().ifBlank {
          fallbackName.ifBlank { parsed?.primaryRemoteHost ?: "OpenVPN" }
        }
      val encryptedOvpn =
        if (parsed != null) com.example.openvpn.OvpnSecretStore.encrypt(parsed.rawConfig) else ""
      val encryptedUser =
        if (username.trim().isNotBlank()) {
          com.example.openvpn.OvpnSecretStore.encrypt(username.trim())
        } else {
          ""
        }
      val encryptedPass =
        if (password.isNotBlank()) {
          com.example.openvpn.OvpnSecretStore.encrypt(password)
        } else {
          ""
        }
      return ServerAccountEntity(
        name = resolvedName,
        connectionMode = ConnectionMode.OPENVPN.code,
        host = "",
        port = 0,
        username = encryptedUser,
        password = encryptedPass,
        expiryDate = effectiveExpiryDate,
        region = region.trim(),
        tags = tags.trim(),
        notes = notes.trim(),
        locked = locked,
        ovpnConfigEncrypted = encryptedOvpn,
        ovpnAuthRequired = parsed?.requiresAuthUserPass ?: false,
        isUserImported = false,
        onlineCount = effectiveOnlineCount,
        userLimit = effectiveUserLimit,
        lifetimeUsers = effectiveLifetimeUsers,
      )
    }

    val resolvedName =
      name.trim().ifBlank {
        fallbackName.ifBlank {
          when (mode) {
            ConnectionMode.DNSTT -> "DNSTT — ${dnsttNameserver.ifBlank { effectiveHost.ifBlank { "Server" } }}"
            ConnectionMode.VLESS_WS -> "VLESS — ${effectiveHost.ifBlank { "Server" }}"
            else -> "SSH — ${effectiveHost.ifBlank { "Server" }}"
          }
        }
      }
    val resolvedPort =
      if (effectivePort in 1..65535) effectivePort
      else if (mode == ConnectionMode.VLESS_WS) 80 else ServerAccountEntity.DEFAULT_SSH_PORT

    return ServerAccountEntity(
      name = resolvedName,
      connectionMode = mode.code,
      host = effectiveHost,
      port = resolvedPort,
      username = username.trim(),
      password = password,
      dnsttNameserver = dnsttNameserver.trim(),
      dnsttPublicKey = dnsttPublicKey.trim(),
      dnsttResolver = dnsttResolver.trim().ifBlank { ServerProfileEntity.DEFAULT_DNSTT_RESOLVER },
      vlessLink = vlessLink.trim(),
      vlessUuid = vlessUuid.trim(),
      expiryDate = effectiveExpiryDate,
      region = region.trim(),
      tags = tags.trim(),
      notes = notes.trim(),
      locked = locked,
      isUserImported = false,
      onlineCount = effectiveOnlineCount,
      userLimit = effectiveUserLimit,
      lifetimeUsers = effectiveLifetimeUsers,
    )
  }

  companion object {
    fun fromEntity(
      server: ServerAccountEntity,
      linkedNetworkName: String = "",
      lockOverride: Boolean? = null,
      expiryOverride: String? = null,
      notesOverride: String? = null,
    ): ServerAccountDto =
      ServerAccountDto(
        name = server.name,
        connectionMode = server.resolvedConnectionMode.code,
        host = server.host,
        port = server.port,
        username = server.username,
        password = server.password,
        dnsttNameserver = server.dnsttNameserver,
        dnsttPublicKey = server.dnsttPublicKey,
        dnsttResolver = server.effectiveDnsttResolver(),
        vlessLink = server.vlessLink,
        vlessUuid = server.vlessUuid,
        expiryDate = expiryOverride ?: server.expiryDate,
        region = server.region,
        tags = server.tags,
        notes = notesOverride ?: server.notes,
        locked = lockOverride ?: server.locked,
        networkName = linkedNetworkName.trim(),
      )
  }
}

/**
 * Milestone 7 split `.myvpn` JSON envelope carrying a Network, a Server/Account, or both.
 */
@JsonClass(generateAdapter = true)
data class SplitMyVpnBundleDto(
  @Json(name = "format_version") val formatVersion: Int = 2,
  @Json(name = "network") val network: NetworkConfigDto? = null,
  @Json(name = "server") val server: ServerAccountDto? = null,
)

/**
 * Imported Server item carrying optional network association hints resolved during repository save.
 */
data class ImportedServerItem(
  val server: ServerAccountEntity,
  val linkedNetworkName: String = "",
  val linkedNetworkSignature: String = "",
)

/**
 * Result of decoding a `.myvpn`, `.json`, or `.txt` import into Milestone 7 split entities.
 */
sealed interface SplitConfigImportResult {
  data class Success(
    val networks: List<NetworkConfigEntity>,
    val servers: List<ImportedServerItem>,
    val sourceFormat: String,
  ) : SplitConfigImportResult

  data class Failure(val message: String) : SplitConfigImportResult
}
