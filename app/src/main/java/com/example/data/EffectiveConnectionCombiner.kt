package com.example.data

import com.example.vless.VlessConfigParser

/**
 * Pure bridge combining a reusable [NetworkConfigEntity] (payload/SSL/SNI/proxy) with a
 * [ServerAccountEntity] (host/credentials/DNSTT/VLESS/expiry) into one effective
 * [ServerProfileEntity] consumed directly by the existing SSH, PayloadInjector, DNSTT, VLESS,
 * and AmktVpnService engines without duplicating any tunnel logic.
 */
object EffectiveConnectionCombiner {

  /**
   * Produces the combined runtime profile for [network] + [server].
   */
  fun combine(
    network: NetworkConfigEntity,
    server: ServerAccountEntity,
  ): ServerProfileEntity {
    val networkPayloadMode = network.resolvedPayloadMode
    val effectiveMode =
      when {
        server.isDnsttMode() -> ConnectionMode.DNSTT
        server.isVlessMode() -> ConnectionMode.VLESS_WS
        else ->
          when (networkPayloadMode) {
            PayloadMode.WEBSOCKET -> ConnectionMode.SSH_WEBSOCKET
            PayloadMode.SSL_TLS_SNI -> ConnectionMode.SSH_SSL
            PayloadMode.HTTP_PAYLOAD -> ConnectionMode.SSH_PROXY
            PayloadMode.DIRECT -> ConnectionMode.SSH_DIRECT
          }
      }

    val effectiveUsername =
      if (server.isVlessMode()) server.effectiveVlessUuid() else server.username.trim()

    return ServerProfileEntity(
      id = server.id,
      name = "${server.name} • ${network.name}",
      mode = effectiveMode.code,
      sshHost = server.host.trim(),
      sshPort = server.port.coerceIn(1, 65535),
      username = effectiveUsername,
      password = server.password,
      payload = network.payloadTemplate,
      proxyHost = network.httpProxyHost.trim(),
      proxyPort = network.httpProxyPort.coerceIn(1, 65535),
      sni = network.sniHost.trim(),
      payloadMode = networkPayloadMode.code,
      extraHeaders = network.extraHeaders,
      dnsttNameserver = server.dnsttNameserver.trim(),
      dnsttPublicKey = server.dnsttPublicKey.trim(),
      dnsttResolver = server.effectiveDnsttResolver(),
      vlessLink = server.vlessLink.trim(),
      expiryDate = server.expiryDate.trim(),
      locked = server.locked || network.locked,
      notes = server.notes.trim(),
      isSelected = true,
      createdAt = server.createdAt,
      updatedAt = maxOf(server.updatedAt, network.updatedAt),
    )
  }

  /**
   * Splits a legacy single [ServerProfileEntity] into a clean `(NetworkConfigEntity, ServerAccountEntity)`
   * pair using the exact same rules as Room `MIGRATION_3_4`.
   */
  fun splitLegacyProfile(profile: ServerProfileEntity): Pair<NetworkConfigEntity, ServerAccountEntity> {
    val resolvedPayloadMode = profile.resolvedPayloadMode
    val network =
      NetworkConfigEntity(
        id = 0L,
        name = profile.name.trim().ifBlank { "Network" },
        payloadMode = resolvedPayloadMode.code,
        payloadTemplate = profile.payload,
        sniHost = profile.sni.trim(),
        extraHeaders = profile.extraHeaders,
        httpProxyHost = profile.proxyHost.trim(),
        httpProxyPort = profile.proxyPort.coerceIn(1, 65535),
        locked = profile.locked,
        createdAt = profile.createdAt,
        updatedAt = profile.updatedAt,
      )

    val serverMode =
      when (profile.connectionMode) {
        ConnectionMode.DNSTT -> ConnectionMode.DNSTT.code
        ConnectionMode.VLESS_WS -> ConnectionMode.VLESS_WS.code
        else -> ConnectionMode.SSH_DIRECT.code
      }

    val extractedVlessUuid =
      if (serverMode == ConnectionMode.VLESS_WS.code) {
        VlessConfigParser.parseLink(profile.vlessLink).getOrNull()?.uuid
          ?: profile.username.trim()
      } else {
        ""
      }

    val server =
      ServerAccountEntity(
        id = 0L,
        name = profile.name.trim().ifBlank { "Server" },
        connectionMode = serverMode,
        host = profile.sshHost.trim(),
        port = profile.sshPort.coerceIn(1, 65535),
        username = if (serverMode == ConnectionMode.VLESS_WS.code) "" else profile.username.trim(),
        password = profile.password,
        dnsttNameserver = profile.dnsttNameserver.trim(),
        dnsttPublicKey = profile.dnsttPublicKey.trim(),
        dnsttResolver = profile.effectiveDnsttResolver(),
        vlessLink = profile.vlessLink.trim(),
        vlessUuid = extractedVlessUuid,
        expiryDate = profile.expiryDate.trim(),
        region = "",
        tags = "",
        notes = profile.notes.trim(),
        locked = profile.locked,
        defaultNetworkId = null,
        wasSelectedOnMigration = profile.isSelected,
        createdAt = profile.createdAt,
        updatedAt = profile.updatedAt,
      )

    return network to server
  }
}
