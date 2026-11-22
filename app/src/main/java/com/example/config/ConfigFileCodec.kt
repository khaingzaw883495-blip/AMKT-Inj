package com.example.config

import com.example.data.EffectiveConnectionCombiner
import com.example.data.NetworkConfigEntity
import com.example.data.ServerAccountEntity
import com.example.data.ServerProfileEntity
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

sealed interface ConfigImportResult {
  data class Success(
    val profiles: List<ServerProfileEntity>,
    val sourceFormat: String,
  ) : ConfigImportResult

  data class Failure(val message: String) : ConfigImportResult
}

/**
 * Coordinates encoding/decoding of encrypted .myvpn configs (both Milestone 7 split format and
 * legacy single-profile format) and plain .txt link bundles (vless:// / ss://).
 */
class ConfigFileCodec {
  private val moshi: Moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
  private val legacyAdapter = moshi.adapter(MyVpnConfigDto::class.java)
  private val splitBundleAdapter = moshi.adapter(SplitMyVpnBundleDto::class.java)

  fun encodeToMyVpnBytes(
    profile: ServerProfileEntity,
    lockOverride: Boolean? = null,
    expiryOverride: String? = null,
    notesOverride: String? = null,
  ): ByteArray {
    val dto =
      MyVpnConfigDto.fromEntity(
        profile = profile,
        lockOverride = lockOverride,
        expiryOverride = expiryOverride,
        notesOverride = notesOverride,
      )
    val json = legacyAdapter.toJson(dto)
    return MyVpnCrypto.encryptJson(json)
  }

  /**
   * Milestone 7 split `.myvpn` export supporting combined (Network + Server), Network-only,
   * or Server-only payloads encrypted with AES-256-GCM.
   */
  fun encodeSplitToMyVpnBytes(
    network: NetworkConfigEntity?,
    server: ServerAccountEntity?,
    lockOverride: Boolean? = null,
    expiryOverride: String? = null,
    notesOverride: String? = null,
  ): ByteArray {
    require(network != null || server != null) {
      "Export requires at least a Network or a Server"
    }
    val bundle =
      SplitMyVpnBundleDto(
        formatVersion = 2,
        network = network?.let { NetworkConfigDto.fromEntity(it, lockOverride = lockOverride) },
        server =
          server?.let {
            ServerAccountDto.fromEntity(
              server = it,
              linkedNetworkName = network?.name.orEmpty(),
              lockOverride = lockOverride,
              expiryOverride = expiryOverride,
              notesOverride = notesOverride,
            )
          },
      )
    val json = splitBundleAdapter.toJson(bundle)
    return MyVpnCrypto.encryptJson(json)
  }

  /**
   * Decodes `.myvpn` (split v2 OR legacy v1), plain JSON, or `.txt` links into Milestone 7
   * split `(networks, servers)` entities with automatic deduplication for legacy/link bundles.
   */
  fun decodeSplitImportedBytes(
    rawBytes: ByteArray,
    fileName: String? = null,
  ): SplitConfigImportResult {
    if (rawBytes.isEmpty()) {
      return SplitConfigImportResult.Failure("ဖိုင်အတွင်း အချက်အလက် မရှိပါ")
    }

    val cleanBaseName =
      fileName
        ?.substringAfterLast('/')
        ?.substringBeforeLast('.')
        ?.trim()
        .orEmpty()

    val asText = rawBytes.toString(Charsets.UTF_8).trim()
    val looksEncrypted =
      MyVpnCrypto.isEncryptedMyVpnEnvelope(rawBytes) ||
        (fileName?.endsWith(".myvpn", ignoreCase = true) == true &&
          !asText.startsWith("{") &&
          !asText.contains("vless://", ignoreCase = true) &&
          !asText.contains("ss://", ignoreCase = true))

    if (looksEncrypted) {
      return try {
        val json = MyVpnCrypto.decryptToJson(rawBytes)
        decodeSplitJsonPayload(json, cleanBaseName, ".myvpn (AES-256-GCM)")
      } catch (e: Exception) {
        SplitConfigImportResult.Failure(e.message ?: ".myvpn ဖိုင် Decrypt မအောင်မြင်ပါ")
      }
    }

    val decodedB64 = Base64Compat.decodeOrNull(asText)
    if (decodedB64 != null && MyVpnCrypto.isEncryptedMyVpnEnvelope(decodedB64)) {
      return try {
        val json = MyVpnCrypto.decryptToJson(decodedB64)
        decodeSplitJsonPayload(json, cleanBaseName, ".myvpn (Base64 AES-256-GCM)")
      } catch (e: Exception) {
        SplitConfigImportResult.Failure(e.message ?: ".myvpn ဖိုင် Decrypt မအောင်မြင်ပါ")
      }
    }

    if (asText.startsWith("{")) {
      val parsedJson = decodeSplitJsonPayload(asText, cleanBaseName, "JSON Config")
      if (parsedJson is SplitConfigImportResult.Success) {
        return parsedJson
      }
    }

    val parsedLinks = LinkConfigParser.parseLinks(asText)
    if (parsedLinks.isNotEmpty()) {
      return splitLegacyEntitiesResult(parsedLinks, ".txt (vless:// / ss://)")
    }

    return SplitConfigImportResult.Failure(
      "ပံ့ပိုးထားသော .myvpn သို့မဟုတ် vless:// / ss:// ဖိုင် မဟုတ်ပါ"
    )
  }

  fun decodeSplitLinkText(rawText: String): SplitConfigImportResult {
    val parsed = LinkConfigParser.parseLinks(rawText)
    return if (parsed.isNotEmpty()) {
      splitLegacyEntitiesResult(parsed, "Link Text")
    } else {
      SplitConfigImportResult.Failure("မှန်ကန်သော vless:// သို့မဟုတ် ss:// လင့်ခ် မတွေ့ပါ")
    }
  }

  private fun decodeSplitJsonPayload(
    json: String,
    fallbackName: String,
    sourceFormat: String,
  ): SplitConfigImportResult {
    val trimmed = json.trim()
    // 1. Try Milestone 7 split bundle (contains "network" and/or "server")
    val splitBundle = runCatching { splitBundleAdapter.fromJson(trimmed) }.getOrNull()
    if (splitBundle != null && (splitBundle.network != null || splitBundle.server != null)) {
      val netEntity =
        splitBundle.network?.toEntity(fallbackName = fallbackName.ifBlank { "Imported Network" })
      val srvEntity =
        splitBundle.server?.toEntity(fallbackName = fallbackName.ifBlank { "Imported Server" })

      val networks = listOfNotNull(netEntity)
      val servers =
        if (srvEntity != null) {
          val linkedName =
            splitBundle.server.networkName.trim().ifBlank { netEntity?.name.orEmpty() }
          val linkedSig = netEntity?.normalizedSignature().orEmpty()
          listOf(
            ImportedServerItem(
              server = srvEntity,
              linkedNetworkName = linkedName,
              linkedNetworkSignature = linkedSig,
            )
          )
        } else {
          emptyList()
        }

      return SplitConfigImportResult.Success(
        networks = networks,
        servers = servers,
        sourceFormat = sourceFormat,
      )
    }

    // 2. Fallback: Legacy single-profile .myvpn JSON -> split automatically
    val legacyDto =
      runCatching { legacyAdapter.fromJson(trimmed) }.getOrNull()
        ?: return SplitConfigImportResult.Failure(".myvpn JSON အချက်အလက် ဖတ်မရပါ")
    val hasAnyLegacyField =
      legacyDto.name.isNotBlank() ||
        legacyDto.sshHost.isNotBlank() ||
        legacyDto.dnsttNameserver.isNotBlank() ||
        legacyDto.vlessLink.isNotBlank() ||
        legacyDto.payload.isNotBlank() ||
        legacyDto.sni.isNotBlank()
    if (!hasAnyLegacyField) {
      return SplitConfigImportResult.Failure(".myvpn JSON အတွင်း အချက်အလက် မတွေ့ပါ")
    }
    val legacyEntity = legacyDto.toEntity(fallbackName = fallbackName)
    return splitLegacyEntitiesResult(listOf(legacyEntity), "$sourceFormat (Legacy Split)")
  }

  private fun splitLegacyEntitiesResult(
    legacyProfiles: List<ServerProfileEntity>,
    sourceFormat: String,
  ): SplitConfigImportResult.Success {
    val uniqueNetworks = LinkedHashMap<String, NetworkConfigEntity>()
    val importedServers = mutableListOf<ImportedServerItem>()

    for (legacy in legacyProfiles) {
      val (net, srv) = EffectiveConnectionCombiner.splitLegacyProfile(legacy)
      val sig = net.normalizedSignature()
      val canonicalNet = uniqueNetworks.getOrPut(sig) { net }
      importedServers +=
        ImportedServerItem(
          server = srv,
          linkedNetworkName = canonicalNet.name,
          linkedNetworkSignature = sig,
        )
    }

    return SplitConfigImportResult.Success(
      networks = uniqueNetworks.values.toList(),
      servers = importedServers,
      sourceFormat = sourceFormat,
    )
  }

  fun decodeImportedBytes(rawBytes: ByteArray, fileName: String? = null): ConfigImportResult {
    if (rawBytes.isEmpty()) {
      return ConfigImportResult.Failure("ဖိုင်အတွင်း အချက်အလက် မရှိပါ")
    }

    val cleanBaseName =
      fileName
        ?.substringAfterLast('/')
        ?.substringBeforeLast('.')
        ?.trim()
        .orEmpty()

    // 1. Try encrypted .myvpn (raw binary or Base64 envelope)
    val asText = rawBytes.toString(Charsets.UTF_8).trim()
    val looksEncrypted =
      MyVpnCrypto.isEncryptedMyVpnEnvelope(rawBytes) ||
        (fileName?.endsWith(".myvpn", ignoreCase = true) == true &&
          !asText.startsWith("{") &&
          !asText.contains("vless://", ignoreCase = true) &&
          !asText.contains("ss://", ignoreCase = true))

    if (looksEncrypted) {
      return try {
        val json = MyVpnCrypto.decryptToJson(rawBytes)
        val dto =
          legacyAdapter.fromJson(json)
            ?: return ConfigImportResult.Failure(".myvpn JSON အချက်အလက် ဖတ်မရပါ")
        ConfigImportResult.Success(
          profiles = listOf(dto.toEntity(fallbackName = cleanBaseName)),
          sourceFormat = ".myvpn (AES-256-GCM)",
        )
      } catch (e: Exception) {
        ConfigImportResult.Failure(e.message ?: ".myvpn ဖိုင် Decrypt မအောင်မြင်ပါ")
      }
    }

    // 2. Try Base64-encoded .myvpn even if filename didn't end with .myvpn
    val decodedB64 = Base64Compat.decodeOrNull(asText)
    if (decodedB64 != null && MyVpnCrypto.isEncryptedMyVpnEnvelope(decodedB64)) {
      return try {
        val json = MyVpnCrypto.decryptToJson(decodedB64)
        val dto =
          legacyAdapter.fromJson(json)
            ?: return ConfigImportResult.Failure(".myvpn JSON အချက်အလက် ဖတ်မရပါ")
        ConfigImportResult.Success(
          profiles = listOf(dto.toEntity(fallbackName = cleanBaseName)),
          sourceFormat = ".myvpn (Base64 AES-256-GCM)",
        )
      } catch (e: Exception) {
        ConfigImportResult.Failure(e.message ?: ".myvpn ဖိုင် Decrypt မအောင်မြင်ပါ")
      }
    }

    // 3. Try plain .txt links (vless:// or ss://)
    val parsedLinks = LinkConfigParser.parseLinks(asText)
    if (parsedLinks.isNotEmpty()) {
      return ConfigImportResult.Success(
        profiles = parsedLinks,
        sourceFormat = ".txt (vless:// / ss://)",
      )
    }

    return ConfigImportResult.Failure(
      "ပံ့ပိုးထားသော .myvpn သို့မဟုတ် vless:// / ss:// ဖိုင် မဟုတ်ပါ"
    )
  }

  fun decodeLinkText(rawText: String): ConfigImportResult {
    val parsed = LinkConfigParser.parseLinks(rawText)
    return if (parsed.isNotEmpty()) {
      ConfigImportResult.Success(parsed, "Link Text")
    } else {
      ConfigImportResult.Failure("မှန်ကန်သော vless:// သို့မဟုတ် ss:// လင့်ခ် မတွေ့ပါ")
    }
  }

  fun suggestedExportFileName(profile: ServerProfileEntity): String =
    suggestedExportFileName(profile.name)

  fun suggestedExportFileName(displayName: String): String {
    val sanitized =
      displayName
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')
        .ifBlank { "amkt_config" }
    return "$sanitized.myvpn"
  }
}
