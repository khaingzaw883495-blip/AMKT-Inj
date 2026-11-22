package com.example.redeem

import com.example.data.ConnectionMode
import com.example.data.ServerAccountEntity
import com.example.data.ServerProfileEntity
import com.example.vless.VlessConfigParser
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class RedeemRequestDto(
  val code: String,
  @Json(name = "device_id") val deviceId: String,
)

@JsonClass(generateAdapter = true)
data class RedeemServerDto(
  val mode: String? = null,
  val name: String? = null,
  val host: String? = null,
  val port: Int? = null,
  val username: String? = null,
  val password: String? = null,
  val nameserver: String? = null,
  @Json(name = "dnstt_nameserver") val dnsttNameserver: String? = null,
  @Json(name = "public_key") val publicKey: String? = null,
  @Json(name = "dnstt_public_key") val dnsttPublicKey: String? = null,
  val resolver: String? = null,
  @Json(name = "dnstt_resolver") val dnsttResolver: String? = null,
  @Json(name = "vless_link") val vlessLink: String? = null,
  @Json(name = "vless_uuid") val vlessUuid: String? = null,
  @Json(name = "expires_at") val expiresAt: String? = null,
  val region: String? = null,
  val notes: String? = null,
)

@JsonClass(generateAdapter = true)
data class RedeemResponseDto(
  val valid: Boolean? = null,
  val message: String? = null,
  val server: RedeemServerDto? = null,
)

sealed interface RedeemOutcome {
  data class Success(
    val code: String,
    val message: String,
    val server: ServerAccountEntity,
    val wasUpdated: Boolean = false,
  ) : RedeemOutcome

  data class Invalid(val message: String) : RedeemOutcome

  data class Error(val message: String) : RedeemOutcome
}

object RedeemCodeMapper {
  private val ISO_DATE_PREFIX = Regex("""^(\d{4}-\d{2}-\d{2})""")

  fun normalizeCode(raw: String): String = raw.trim().uppercase()

  fun redeemTagForCode(normalizedCode: String): String = "REDEEM:${normalizeCode(normalizedCode)}"

  /**
   * Normalizes ISO-8601 timestamps (e.g. `2026-12-31T23:59:59Z`) or `YYYY-MM-DD` strings into
   * `YYYY-MM-DD` so [com.example.data.ProfileExpiryChecker] enforces expiry consistently.
   */
  fun normalizeExpiryDate(raw: String?): String {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isBlank() || trimmed.equals("null", ignoreCase = true)) return ""
    val match = ISO_DATE_PREFIX.find(trimmed)
    return match?.groupValues?.getOrNull(1) ?: trimmed
  }

  /**
   * Maps a webhook `server` JSON object into a [ServerAccountEntity] (Server ONLY — never a
   * Network). Supports `ssh`, `dnstt`, and `vless` modes.
   */
  fun mapServerDtoToEntity(
    normalizedCode: String,
    dto: RedeemServerDto,
    now: Long = System.currentTimeMillis(),
  ): Result<ServerAccountEntity> {
    val rawMode = dto.mode?.trim()?.lowercase().orEmpty()
    val rawVlessLink = dto.vlessLink?.trim().orEmpty()
    val rawNameserver = dto.nameserver?.trim().orEmpty().ifBlank { dto.dnsttNameserver?.trim().orEmpty() }
    val rawPublicKey = dto.publicKey?.trim().orEmpty().ifBlank { dto.dnsttPublicKey?.trim().orEmpty() }
    val rawResolver =
      dto.resolver?.trim().orEmpty()
        .ifBlank { dto.dnsttResolver?.trim().orEmpty() }
        .ifBlank { ServerProfileEntity.DEFAULT_DNSTT_RESOLVER }

    val resolvedMode =
      when (rawMode) {
        "dnstt", "slowdns" -> ConnectionMode.DNSTT
        "vless", "vless_ws", "vless-ws", "vlessws" -> ConnectionMode.VLESS_WS
        "ssh", "ssh_direct", "ssh-direct", "ssh_payload", "ssh_ssl", "ssh_websocket" ->
          ConnectionMode.SSH_DIRECT
        "" ->
          when {
            rawVlessLink.isNotBlank() -> ConnectionMode.VLESS_WS
            rawNameserver.isNotBlank() -> ConnectionMode.DNSTT
            else -> ConnectionMode.SSH_DIRECT
          }
        else ->
          return Result.failure(
            IllegalArgumentException("မပံ့ပိုးသော ဆာဗာစနစ် (mode='$rawMode') ဖြစ်နေပါသည်")
          )
      }

    val expiryDate = normalizeExpiryDate(dto.expiresAt)
    val redeemTag = redeemTagForCode(normalizedCode)
    val dtoHost = dto.host?.trim().orEmpty()
    val dtoPort = dto.port?.takeIf { it in 1..65535 }
    val dtoUsername = dto.username?.trim().orEmpty()
    val dtoPassword = dto.password.orEmpty()
    val dtoRegion = dto.region?.trim().orEmpty()
    val dtoNotes = dto.notes?.trim().orEmpty()

    return when (resolvedMode) {
      ConnectionMode.VLESS_WS -> {
        val parsedVless =
          if (rawVlessLink.isNotBlank()) VlessConfigParser.parseLink(rawVlessLink).getOrNull()
          else null
        val host = dtoHost.ifBlank { parsedVless?.serverHost.orEmpty() }
        val port = dtoPort ?: parsedVless?.serverPort ?: 443
        val uuid = dto.vlessUuid?.trim().orEmpty().ifBlank { parsedVless?.uuid.orEmpty() }
        if (rawVlessLink.isBlank() && (uuid.isBlank() || host.isBlank())) {
          return Result.failure(
            IllegalArgumentException("VLESS ဆာဗာအတွက် vless_link သို့မဟုတ် host/uuid မပါဝင်ပါ")
          )
        }
        val name =
          dto.name?.trim().orEmpty()
            .ifBlank { parsedVless?.remark.orEmpty() }
            .ifBlank { "VLESS ${host.ifBlank { normalizedCode }}" }
        Result.success(
          ServerAccountEntity(
            name = name,
            connectionMode = ConnectionMode.VLESS_WS.code,
            host = host,
            port = port,
            vlessLink = rawVlessLink,
            vlessUuid = uuid,
            expiryDate = expiryDate,
            region = dtoRegion,
            tags = redeemTag,
            notes = dtoNotes,
            defaultNetworkId = null,
            createdAt = now,
            updatedAt = now,
          )
        )
      }

      ConnectionMode.DNSTT -> {
        if (rawNameserver.isBlank() || rawPublicKey.isBlank()) {
          return Result.failure(
            IllegalArgumentException("DNSTT ဆာဗာအတွက် nameserver နှင့် public_key မပါဝင်ပါ")
          )
        }
        val name =
          dto.name?.trim().orEmpty()
            .ifBlank { "DNSTT $rawNameserver" }
        Result.success(
          ServerAccountEntity(
            name = name,
            connectionMode = ConnectionMode.DNSTT.code,
            host = dtoHost,
            port = dtoPort ?: ServerAccountEntity.DEFAULT_SSH_PORT,
            username = dtoUsername,
            password = dtoPassword,
            dnsttNameserver = rawNameserver,
            dnsttPublicKey = rawPublicKey,
            dnsttResolver = rawResolver,
            expiryDate = expiryDate,
            region = dtoRegion,
            tags = redeemTag,
            notes = dtoNotes,
            defaultNetworkId = null,
            createdAt = now,
            updatedAt = now,
          )
        )
      }

      else -> {
        if (dtoHost.isBlank()) {
          return Result.failure(
            IllegalArgumentException("SSH ဆာဗာအတွက် host လိပ်စာ မပါဝင်ပါ")
          )
        }
        val port = dtoPort ?: ServerAccountEntity.DEFAULT_SSH_PORT
        val name =
          dto.name?.trim().orEmpty()
            .ifBlank { "SSH $dtoHost" }
        Result.success(
          ServerAccountEntity(
            name = name,
            connectionMode = ConnectionMode.SSH_DIRECT.code,
            host = dtoHost,
            port = port,
            username = dtoUsername,
            password = dtoPassword,
            expiryDate = expiryDate,
            region = dtoRegion,
            tags = redeemTag,
            notes = dtoNotes,
            defaultNetworkId = null,
            createdAt = now,
            updatedAt = now,
          )
        )
      }
    }
  }
}
