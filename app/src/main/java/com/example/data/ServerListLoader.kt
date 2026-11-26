package com.example.data

import com.example.config.MyVpnConfigDto
import com.example.config.NetworkConfigDto
import com.example.config.ServerAccountDto
import com.example.membership.OwnerBanner
import com.example.membership.OwnerBannerNestedDto
import com.example.membership.OwnerBannerPayloadDto
import com.example.membership.RemotePriceDto
import com.example.membership.RemotePriceItem
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

@JsonClass(generateAdapter = true)
data class ServerListEnvelopeDto(
  @Json(name = "servers") val servers: List<MyVpnConfigDto>? = null,
  @Json(name = "profiles") val profiles: List<MyVpnConfigDto>? = null,
)

/**
 * Combined remote JSON envelope supporting both Milestone 7 split entries (`networks` + `servers`),
 * optional owner announcement (`ads` / `own_ad` / `banner`), and legacy single-profile entries.
 */
@JsonClass(generateAdapter = true)
data class SplitRemoteEnvelopeDto(
  @Json(name = "format_version") val formatVersion: Int? = null,
  @Json(name = "networks") val networks: List<NetworkConfigDto>? = null,
  @Json(name = "network") val network: NetworkConfigDto? = null,
  @Json(name = "servers") val servers: List<SplitOrLegacyServerDto>? = null,
  @Json(name = "server") val server: SplitOrLegacyServerDto? = null,
  @Json(name = "profiles") val profiles: List<MyVpnConfigDto>? = null,
  @Json(name = "ads") val ads: OwnerBannerNestedDto? = null,
  @Json(name = "own_ad") val ownAd: OwnerBannerNestedDto? = null,
  @Json(name = "banner") val banner: OwnerBannerNestedDto? = null,
  @Json(name = "prices") val prices: List<RemotePriceDto>? = null,
  @Json(name = "pricing") val pricing: List<RemotePriceDto>? = null,
  @Json(name = "plans") val plans: List<RemotePriceDto>? = null,
  @Json(name = "app_users") val appUsers: Int? = null,
  @Json(name = "total_app_users") val totalAppUsers: Int? = null,
  @Json(name = "total_users") val totalUsers: Int? = null,
)

/**
 * Superset DTO so a `"servers"` array entry in remote JSON can be either:
 * - a Milestone 7 [ServerAccountDto] (`host`, `port`, `connection_mode`, `vless_uuid`, `network_name`), OR
 * - a legacy [MyVpnConfigDto] (`ssh_host`, `ssh_port`, `payload`, `sni`, `proxy_host`, `mode`).
 */
@JsonClass(generateAdapter = true)
data class SplitOrLegacyServerDto(
  @Json(name = "name") val name: String = "",
  @Json(name = "connection_mode") val connectionMode: String = "",
  @Json(name = "mode") val mode: String = "",
  @Json(name = "host") val host: String = "",
  @Json(name = "ssh_host") val sshHost: String = "",
  @Json(name = "port") val port: Int = 0,
  @Json(name = "ssh_port") val sshPort: Int = 0,
  @Json(name = "username") val username: String = "",
  @Json(name = "password") val password: String = "",
  @Json(name = "payload") val payload: String = "",
  @Json(name = "payload_template") val payloadTemplate: String = "",
  @Json(name = "payload_mode") val payloadMode: String = "",
  @Json(name = "extra_headers") val extraHeaders: String = "",
  @Json(name = "proxy_host") val proxyHost: String = "",
  @Json(name = "http_proxy_host") val httpProxyHost: String = "",
  @Json(name = "proxy_port") val proxyPort: Int = 0,
  @Json(name = "http_proxy_port") val httpProxyPort: Int = 0,
  @Json(name = "sni") val sni: String = "",
  @Json(name = "sni_host") val sniHost: String = "",
  @Json(name = "dnstt_nameserver") val dnsttNameserver: String = "",
  @Json(name = "dnstt_public_key") val dnsttPublicKey: String = "",
  @Json(name = "dnstt_resolver") val dnsttResolver: String = "",
  @Json(name = "vless_link") val vlessLink: String = "",
  @Json(name = "vless_uuid") val vlessUuid: String = "",
  @Json(name = "expiry_date") val expiryDate: String = "",
  @Json(name = "expiry") val expiry: String = "",
  @Json(name = "ovpn") val ovpn: String = "",
  @Json(name = "region") val region: String = "",
  @Json(name = "tags") val tags: String = "",
  @Json(name = "locked") val locked: Boolean = false,
  @Json(name = "notes") val notes: String = "",
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
  fun isOpenVpnEntry(): Boolean {
    if (ovpn.isNotBlank()) return true
    val resolved = ConnectionMode.fromCode(connectionMode.ifBlank { mode })
    return resolved == ConnectionMode.OPENVPN
  }

  fun carriesEmbeddedLegacyNetwork(): Boolean {
    if (isOpenVpnEntry()) return false
    val hasLegacyNetworkFields =
      payload.isNotBlank() ||
        payloadTemplate.isNotBlank() ||
        payloadMode.isNotBlank() ||
        extraHeaders.isNotBlank() ||
        proxyHost.isNotBlank() ||
        httpProxyHost.isNotBlank() ||
        sni.isNotBlank() ||
        sniHost.isNotBlank()
    if (hasLegacyNetworkFields) return true
    // Legacy flat profile format uses ssh_host / mode without connection_mode or network_name
    val isExplicitSplit =
      connectionMode.isNotBlank() ||
        host.isNotBlank() ||
        networkName.isNotBlank() ||
        vlessUuid.isNotBlank() ||
        region.isNotBlank() ||
        tags.isNotBlank()
    return !isExplicitSplit && (sshHost.isNotBlank() || mode.isNotBlank())
  }

  fun toLegacyProfileDto(): MyVpnConfigDto =
    MyVpnConfigDto(
      name = name,
      mode = mode.ifBlank { connectionMode },
      sshHost = sshHost.ifBlank { host },
      sshPort = if (sshPort > 0) sshPort else port,
      username = username,
      password = password,
      payload = payload.ifBlank { payloadTemplate },
      payloadMode = payloadMode,
      extraHeaders = extraHeaders,
      proxyHost = proxyHost.ifBlank { httpProxyHost },
      proxyPort = if (proxyPort > 0) proxyPort else httpProxyPort,
      sni = sni.ifBlank { sniHost },
      dnsttNameserver = dnsttNameserver,
      dnsttPublicKey = dnsttPublicKey,
      dnsttResolver = dnsttResolver,
      vlessLink = vlessLink,
      expiryDate = expiryDate.ifBlank { expiry },
      locked = locked,
      notes = notes,
    )

  fun toSplitServerDto(): ServerAccountDto =
    ServerAccountDto(
      name = name,
      connectionMode = connectionMode,
      legacyMode = mode,
      host = host,
      legacySshHost = sshHost,
      port = port,
      legacySshPort = sshPort,
      username = username,
      password = password,
      dnsttNameserver = dnsttNameserver,
      dnsttPublicKey = dnsttPublicKey,
      dnsttResolver = dnsttResolver,
      vlessLink = vlessLink,
      vlessUuid = vlessUuid,
      expiryDate = expiryDate,
      expiry = expiry,
      ovpnBase64 = ovpn,
      region = region,
      tags = tags,
      notes = notes,
      locked = locked,
      networkName = networkName,
      online = online,
      onlineUsers = onlineUsers,
      onlineCount = onlineCount,
      usersOnline = usersOnline,
      activeUsers = activeUsers,
      limit = limit,
      maxUsers = maxUsers,
      userLimit = userLimit,
      maxOnline = maxOnline,
      capacity = capacity,
      lifetimeUsers = lifetimeUsers,
      usedBy = usedBy,
      distinctUsers = distinctUsers,
      totalUsers = totalUsers,
      serverUsers = serverUsers,
    )
}

/**
 * Parsed Milestone 7 remote JSON payload containing split Networks, split Servers,
 * and/or legacy flat profiles to be auto-split & deduped.
 */
data class SplitRemoteListResult(
  val networkDtos: List<NetworkConfigDto>,
  val serverDtos: List<ServerAccountDto>,
  val legacyProfileDtos: List<MyVpnConfigDto>,
  val ownerBanner: OwnerBanner? = null,
  val prices: List<RemotePriceItem> = emptyList(),
  val totalAppUsers: Int? = null,
  val hasNetworksSection: Boolean = networkDtos.isNotEmpty() || legacyProfileDtos.isNotEmpty(),
  val hasServersSection: Boolean = serverDtos.isNotEmpty() || legacyProfileDtos.isNotEmpty(),
) {
  val isEmpty: Boolean
    get() = networkDtos.isEmpty() && serverDtos.isEmpty() && legacyProfileDtos.isEmpty()
}

/**
 * Parses editable server/network lists from the owner's remote JSON server list URL
 * (supporting both Milestone 7 split & legacy formats).
 */
class ServerListLoader {
  private val moshi: Moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
  private val envelopeAdapter = moshi.adapter(ServerListEnvelopeDto::class.java)
  private val splitEnvelopeAdapter = moshi.adapter(SplitRemoteEnvelopeDto::class.java)
  private val singleAdapter = moshi.adapter(MyVpnConfigDto::class.java)
  private val singleNetworkAdapter = moshi.adapter(NetworkConfigDto::class.java)
  private val listAdapter =
    moshi.adapter<List<MyVpnConfigDto>>(
      Types.newParameterizedType(List::class.java, MyVpnConfigDto::class.java)
    )
  private val splitOrLegacyListAdapter =
    moshi.adapter<List<SplitOrLegacyServerDto>>(
      Types.newParameterizedType(List::class.java, SplitOrLegacyServerDto::class.java)
    )

  fun parseServerListDtos(rawJson: String): Result<List<MyVpnConfigDto>> {
    val trimmed = rawJson.trim()
    if (trimmed.isBlank()) {
      return Result.failure(IllegalArgumentException("JSON အချက်အလက် မရှိပါ"))
    }

    return try {
      val dtos: List<MyVpnConfigDto> =
        when {
          trimmed.startsWith("[") -> listAdapter.fromJson(trimmed).orEmpty()
          trimmed.startsWith("{") -> {
            val envelope = envelopeAdapter.fromJson(trimmed)
            val fromEnvelope = envelope?.servers ?: envelope?.profiles
            if (fromEnvelope != null) {
              fromEnvelope
            } else {
              val single = singleAdapter.fromJson(trimmed)
              if (single != null && hasAnyServerField(single)) listOf(single) else emptyList()
            }
          }
          else -> return Result.failure(IllegalArgumentException("JSON ဖော်မတ် မမှန်ကန်ပါ"))
        }

      if (dtos.isEmpty()) {
        return Result.failure(IllegalArgumentException("JSON အတွင်း ဆာဗာ Profile မတွေ့ပါ"))
      }
      Result.success(dtos)
    } catch (e: Exception) {
      Result.failure(
        IllegalArgumentException("JSON ဖော်မတ် မမှန်ကန်ပါ: ${e.message ?: "invalid syntax"}", e)
      )
    }
  }

  /**
   * Parses Milestone 7 split remote JSON (`{"networks": [...], "servers": [...]}`), network-only,
   * server-only, OR legacy single-profile JSON (`{"servers": [...]}`, `{"profiles": [...]}`, `[...]`).
   */
  fun parseSplitRemoteList(rawJson: String): Result<SplitRemoteListResult> {
    val trimmed = rawJson.trim()
    if (trimmed.isBlank()) {
      return Result.failure(IllegalArgumentException("JSON အချက်အလက် မရှိပါ"))
    }

    return try {
      val result: SplitRemoteListResult =
        when {
          trimmed.startsWith("[") -> {
            val entries = splitOrLegacyListAdapter.fromJson(trimmed).orEmpty()
            classifyMixedEntries(entries)
          }
          trimmed.startsWith("{") -> {
            val env = splitEnvelopeAdapter.fromJson(trimmed)
            val networks = mutableListOf<NetworkConfigDto>()
            val servers = mutableListOf<ServerAccountDto>()
            val legacy = mutableListOf<MyVpnConfigDto>()

            val explicitNetworksPresent = env?.networks != null || env?.network != null
            val explicitServersPresent = env?.servers != null || env?.server != null
            val explicitProfilesPresent = env?.profiles != null

            env?.networks?.let { networks.addAll(it) }
            env?.network?.let { networks.add(it) }
            env?.profiles?.let { legacy.addAll(it) }

            val mixedEntries = mutableListOf<SplitOrLegacyServerDto>()
            env?.servers?.let { mixedEntries.addAll(it) }
            env?.server?.let { mixedEntries.add(it) }

            if (mixedEntries.isNotEmpty()) {
              if (explicitNetworksPresent) {
                // Explicit split envelope ("networks" + "servers"): every entry under "servers"
                // is strictly a Server and must NEVER fabricate a synthetic Network entry.
                mixedEntries.forEach { entry -> servers.add(entry.toSplitServerDto()) }
              } else {
                val classified = classifyMixedEntries(mixedEntries)
                servers.addAll(classified.serverDtos)
                legacy.addAll(classified.legacyProfileDtos)
              }
            }

            // If no envelope keys matched, try single Network or single Legacy/Server object
            if (
              !explicitNetworksPresent &&
                !explicitServersPresent &&
                !explicitProfilesPresent &&
                networks.isEmpty() &&
                servers.isEmpty() &&
                legacy.isEmpty()
            ) {
              val singleNet = singleNetworkAdapter.fromJson(trimmed)
              val singleMixed = moshi.adapter(SplitOrLegacyServerDto::class.java).fromJson(trimmed)
              val hasServerIdentity =
                singleMixed != null &&
                  (singleMixed.host.isNotBlank() ||
                    singleMixed.sshHost.isNotBlank() ||
                    singleMixed.dnsttNameserver.isNotBlank() ||
                    singleMixed.vlessLink.isNotBlank() ||
                    singleMixed.vlessUuid.isNotBlank() ||
                    singleMixed.ovpn.isNotBlank())
              val hasNetworkOnlyIdentity =
                !hasServerIdentity &&
                  singleNet != null &&
                  (singleNet.payloadTemplate.isNotBlank() ||
                    singleNet.legacyPayload.isNotBlank() ||
                    singleNet.sniHost.isNotBlank() ||
                    singleNet.legacySni.isNotBlank() ||
                    singleNet.httpProxyHost.isNotBlank() ||
                    singleNet.legacyProxyHost.isNotBlank() ||
                    singleNet.payloadMode.isNotBlank())

              when {
                hasNetworkOnlyIdentity -> networks.add(singleNet)
                hasServerIdentity -> {
                  val classified = classifyMixedEntries(listOf(singleMixed))
                  servers.addAll(classified.serverDtos)
                  legacy.addAll(classified.legacyProfileDtos)
                }
              }
            }

            val parsedBanner =
              OwnerBannerPayloadDto(
                  nestedAds = env?.ads,
                  nestedOwnAd = env?.ownAd,
                  nestedBanner = env?.banner,
                )
                .resolve()
            val parsedPrices =
              (env?.prices ?: env?.pricing ?: env?.plans)
                .orEmpty()
                .mapNotNull { it.resolveOrNull() }
            val parsedAppUsers =
              listOfNotNull(env?.appUsers, env?.totalAppUsers, env?.totalUsers)
                .firstOrNull { it >= 0 }

            SplitRemoteListResult(
              networkDtos = networks,
              serverDtos = servers,
              legacyProfileDtos = legacy,
              ownerBanner = parsedBanner,
              prices = parsedPrices,
              totalAppUsers = parsedAppUsers,
              hasNetworksSection = explicitNetworksPresent || networks.isNotEmpty() || legacy.isNotEmpty(),
              hasServersSection = explicitServersPresent || servers.isNotEmpty() || legacy.isNotEmpty(),
            )
          }
          else -> return Result.failure(IllegalArgumentException("JSON ဖော်မတ် မမှန်ကန်ပါ"))
        }

      if (result.isEmpty) {
        return Result.failure(IllegalArgumentException("JSON အတွင်း ကွန်ရက် သို့မဟုတ် ဆာဗာ အချက်အလက် မတွေ့ပါ"))
      }

      // Validate any OPENVPN remote entries up front so invalid base64/.ovpn fails cleanly
      // without ever touching the local Room database.
      for (srv in result.serverDtos) {
        if (srv.inferServerConnectionMode() == ConnectionMode.OPENVPN && srv.ovpnBase64.isNotBlank()) {
          val ovpnValidation = com.example.openvpn.OvpnConfigParser.decodeBase64Ovpn(srv.ovpnBase64)
          if (ovpnValidation.isFailure) {
            val msg = ovpnValidation.exceptionOrNull()?.message ?: "OpenVPN config ဖော်မတ် မမှန်ကန်ပါ"
            return Result.failure(IllegalArgumentException(msg))
          }
        }
      }

      Result.success(result)
    } catch (e: Exception) {
      Result.failure(
        IllegalArgumentException("JSON ဖော်မတ် မမှန်ကန်ပါ: ${e.message ?: "invalid syntax"}", e)
      )
    }
  }

  fun parseServerListJson(rawJson: String): Result<List<ServerProfileEntity>> {
    return parseServerListDtos(rawJson).map { dtos ->
      dtos.mapIndexed { index, dto -> dto.toEntity(fallbackName = "Server #${index + 1}") }
    }
  }

  private fun classifyMixedEntries(entries: List<SplitOrLegacyServerDto>): SplitRemoteListResult {
    val splitServers = mutableListOf<ServerAccountDto>()
    val legacyProfiles = mutableListOf<MyVpnConfigDto>()
    for (entry in entries) {
      if (entry.carriesEmbeddedLegacyNetwork()) {
        legacyProfiles += entry.toLegacyProfileDto()
      } else {
        splitServers += entry.toSplitServerDto()
      }
    }
    return SplitRemoteListResult(
      networkDtos = emptyList(),
      serverDtos = splitServers,
      legacyProfileDtos = legacyProfiles,
    )
  }

  private fun hasAnyServerField(dto: MyVpnConfigDto): Boolean {
    return dto.name.isNotBlank() ||
      dto.sshHost.isNotBlank() ||
      dto.dnsttNameserver.isNotBlank() ||
      dto.vlessLink.isNotBlank()
  }
}
