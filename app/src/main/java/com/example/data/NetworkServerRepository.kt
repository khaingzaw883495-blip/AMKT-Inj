package com.example.data

import com.example.config.ImportedServerItem
import com.example.config.MyVpnConfigDto
import com.example.config.NetworkConfigDto
import com.example.config.ServerAccountDto
import kotlinx.coroutines.flow.Flow

/**
 * Summary of importing a split `.myvpn` / `.txt` bundle into Room.
 */
data class SplitImportOutcome(
  val networksAdded: Int,
  val networksReused: Int,
  val serversAdded: Int,
  val selectedNetworkId: Long?,
  val selectedServerId: Long?,
) {
  fun toBurmeseSummary(sourceFormat: String): String {
    val parts = mutableListOf<String>()
    if (networksAdded > 0) parts += "ကွန်ရက်အသစ် $networksAdded ခု"
    if (networksReused > 0) parts += "ရှိပြီးသားကွန်ရက် $networksReused ခု ပြန်သုံးပြီး"
    if (serversAdded > 0) parts += "ဆာဗာ $serversAdded ခု"
    val detail = if (parts.isEmpty()) "အချက်အလက် ထည့်သွင်းပြီး" else parts.joinToString(" • ")
    return "$sourceFormat မှ ထည့်သွင်းပြီး: $detail"
  }
}

/**
 * Summary of merging/replacing a Remote JSON list containing Networks and/or Servers.
 */
data class SplitRemoteSyncSummary(
  val networksAdded: Int,
  val networksUpdated: Int,
  val serversAdded: Int,
  val serversUpdated: Int,
  val replacedAll: Boolean,
) {
  fun toBurmeseMessage(): String {
    return if (replacedAll) {
      "Remote JSON မှ ကွန်ရက် $networksAdded ခု နှင့် ဆာဗာ $serversAdded ခု ဖြင့် အစားထိုးပြီးပါပြီ"
    } else {
      "Remote JSON ဆွဲယူပြီးပါပြီ — ကွန်ရက် (အသစ် $networksAdded၊ အပ်ဒိတ် $networksUpdated) • ဆာဗာ (အသစ် $serversAdded၊ အပ်ဒိတ် $serversUpdated)"
    }
  }
}

/**
 * Milestone 7 primary repository managing separate [NetworkConfigEntity] and [ServerAccountEntity]
 * tables, last-used combination persistence, deduplicated starter seeding, split `.myvpn` import,
 * and separate merge-by-name Remote JSON synchronization.
 */
class NetworkServerRepository(
  private val networkDao: NetworkConfigDao,
  private val serverDao: ServerAccountDao,
  private val remoteServerListRepository: RemoteServerListRepository,
) {
  val allNetworks: Flow<List<NetworkConfigEntity>> = networkDao.observeAllNetworks()
  val allServers: Flow<List<ServerAccountEntity>> = serverDao.observeAllServers()
  val lastNetworkId: Flow<Long> = remoteServerListRepository.lastNetworkIdFlow
  val lastServerId: Flow<Long> = remoteServerListRepository.lastServerIdFlow
  val remoteServerListUrl: Flow<String> = remoteServerListRepository.remoteServerListUrlFlow

  suspend fun getSavedRemoteServerListUrl(): String =
    remoteServerListRepository.getSavedRemoteServerListUrl()

  suspend fun resetRemoteServerListUrlToDefault(): String =
    remoteServerListRepository.resetRemoteServerListUrlToDefault()

  fun isDefaultRemoteServerListUrl(url: String): Boolean =
    remoteServerListRepository.isDefaultRemoteServerListUrl(url)

  /**
   * Ensures clean startup state:
   * 1. On upgrade (when the DB already contains Networks or Servers), preserves all existing user
   *    data untouched and ensures the saved Network + Server selection points to valid rows.
   * 2. On genuine fresh install, starts with ZERO networks and ZERO servers — no bundled starter
   *    profiles are seeded. Users fetch the owner's list via Update or About -> Load.
   *
   * Returns `(0, 0)`.
   */
  suspend fun initializeOnLaunch(): Pair<Int, Int> {
    val existingNetworks = networkDao.getAllNetworksSnapshot()
    val existingServers = serverDao.getAllServersSnapshot()

    if (existingNetworks.isNotEmpty() || existingServers.isNotEmpty()) {
      ensureValidLastSelection(existingNetworks, existingServers)
    }
    return 0 to 0
  }

  private suspend fun ensureValidLastSelection(
    networks: List<NetworkConfigEntity>,
    servers: List<ServerAccountEntity>,
  ) {
    val (savedNetId, savedSrvId) = remoteServerListRepository.getLastSelection()
    val hasValidNet = savedNetId > 0L && networks.any { it.id == savedNetId }
    val hasValidSrv = savedSrvId > 0L && servers.any { it.id == savedSrvId }
    if (hasValidNet && hasValidSrv) return

    val chosenSrv =
      if (hasValidSrv) {
        servers.firstOrNull { it.id == savedSrvId }
      } else {
        servers.firstOrNull { it.wasSelectedOnMigration } ?: servers.firstOrNull()
      }

    val chosenNet =
      if (hasValidNet) {
        networks.firstOrNull { it.id == savedNetId }
      } else {
        chosenSrv?.defaultNetworkId?.let { linkedId -> networks.firstOrNull { it.id == linkedId } }
          ?: networks.firstOrNull()
      }

    val targetNetId = chosenNet?.id ?: -1L
    val targetSrvId = chosenSrv?.id ?: -1L
    if (!hasValidNet && savedNetId != targetNetId) {
      remoteServerListRepository.saveLastNetworkId(chosenNet?.id)
    }
    if (!hasValidSrv && savedSrvId != targetSrvId) {
      remoteServerListRepository.saveLastServerId(chosenSrv?.id)
    }
  }

  suspend fun getNetworkById(id: Long): NetworkConfigEntity? = networkDao.getNetworkById(id)

  suspend fun getServerById(id: Long): ServerAccountEntity? = serverDao.getServerById(id)

  suspend fun getAllNetworksSnapshot(): List<NetworkConfigEntity> =
    networkDao.getAllNetworksSnapshot()

  suspend fun getAllServersSnapshot(): List<ServerAccountEntity> =
    serverDao.getAllServersSnapshot()

  suspend fun selectNetwork(networkId: Long) {
    remoteServerListRepository.saveLastNetworkId(networkId)
  }

  suspend fun selectServer(serverId: Long) {
    remoteServerListRepository.saveLastServerId(serverId)
  }

  suspend fun selectCombination(networkId: Long?, serverId: Long?) {
    remoteServerListRepository.saveLastSelection(networkId, serverId)
  }

  suspend fun saveNetwork(
    network: NetworkConfigEntity,
    selectAfterSave: Boolean = false,
  ): Long {
    val now = System.currentTimeMillis()
    val savedId =
      if (network.id == 0L) {
        networkDao.insertNetwork(network.copy(createdAt = now, updatedAt = now))
      } else {
        networkDao.updateNetwork(network.copy(updatedAt = now))
        network.id
      }
    if (selectAfterSave) {
      remoteServerListRepository.saveLastNetworkId(savedId)
    }
    return savedId
  }

  suspend fun duplicateNetwork(network: NetworkConfigEntity): Long {
    val now = System.currentTimeMillis()
    return networkDao.insertNetwork(
      network.copy(
        id = 0L,
        name = "${network.name} (မိတ္တူ)",
        createdAt = now,
        updatedAt = now,
      )
    )
  }

  suspend fun getServersReferencingNetwork(networkId: Long): List<ServerAccountEntity> =
    serverDao.getServersReferencingNetwork(networkId)

  /**
   * Deletes [networkId]. If any Servers reference this Network and [unlinkReferencingServers] is
   * false, refuses deletion and returns `false` so callers must confirm first.
   */
  suspend fun deleteNetwork(
    networkId: Long,
    unlinkReferencingServers: Boolean,
  ): Boolean {
    val refCount = serverDao.countServersReferencingNetwork(networkId)
    if (refCount > 0 && !unlinkReferencingServers) {
      return false
    }
    if (refCount > 0) {
      serverDao.clearNetworkReferences(networkId)
    }
    networkDao.deleteNetworkById(networkId)

    val (savedNetId, _) = remoteServerListRepository.getLastSelection()
    if (savedNetId == networkId) {
      val fallback = networkDao.getAllNetworksSnapshot().firstOrNull()
      remoteServerListRepository.saveLastNetworkId(fallback?.id)
    }
    return true
  }

  suspend fun saveServer(
    server: ServerAccountEntity,
    selectAfterSave: Boolean = false,
  ): Long {
    val now = System.currentTimeMillis()
    val savedId =
      if (server.id == 0L) {
        serverDao.insertServer(server.copy(createdAt = now, updatedAt = now))
      } else {
        serverDao.updateServer(server.copy(updatedAt = now))
        server.id
      }
    if (selectAfterSave) {
      remoteServerListRepository.saveLastServerId(savedId)
    }
    return savedId
  }

  suspend fun duplicateServer(server: ServerAccountEntity): Long {
    val now = System.currentTimeMillis()
    return serverDao.insertServer(
      server.copy(
        id = 0L,
        name = "${server.name} (မိတ္တူ)",
        wasSelectedOnMigration = false,
        createdAt = now,
        updatedAt = now,
      )
    )
  }

  suspend fun deleteServer(serverId: Long) {
    serverDao.deleteServerById(serverId)
    val (_, savedSrvId) = remoteServerListRepository.getLastSelection()
    if (savedSrvId == serverId) {
      val fallback = serverDao.getAllServersSnapshot().firstOrNull()
      remoteServerListRepository.saveLastServerId(fallback?.id)
    }
  }

  /**
   * Upserts a Server returned by the buyer redeem-code webhook:
   * - Matches existing server by normalized name OR matching `REDEEM:<CODE>` tag so redeeming the
   *   same code again updates in place without duplicating.
   * - Never creates or modifies a Network (Network is always picked separately).
   * - Selects the redeemed server; if no Network is currently selected, selects the first available
   *   Network so the user can connect immediately.
   *
   * Returns `(storedServer, wasUpdated)`.
   */
  suspend fun upsertRedeemedServer(
    code: String,
    incoming: ServerAccountEntity,
  ): Pair<ServerAccountEntity, Boolean> {
    val now = System.currentTimeMillis()
    val normalizedName = normalizeNameKey(incoming.name)
    val codeTag = "REDEEM:${code.trim().uppercase()}"
    val existingServers = serverDao.getAllServersSnapshot()
    val match =
      existingServers.firstOrNull { normalizeNameKey(it.name) == normalizedName }
        ?: existingServers.firstOrNull {
          it.tags.split(',').any { t -> t.trim().equals(codeTag, ignoreCase = true) }
        }

    val (stored, wasUpdated) =
      if (match != null) {
        val mergedTags =
          if (match.tags.contains(codeTag, ignoreCase = true)) match.tags
          else listOf(match.tags.trim(), codeTag).filter { it.isNotBlank() }.joinToString(", ")
        val updated =
          incoming.copy(
            id = match.id,
            tags = mergedTags,
            defaultNetworkId = match.defaultNetworkId,
            wasSelectedOnMigration = match.wasSelectedOnMigration,
            createdAt = match.createdAt,
            updatedAt = now,
          )
        serverDao.updateServer(updated)
        updated to true
      } else {
        val fresh =
          incoming.copy(
            id = 0L,
            tags = incoming.tags.ifBlank { codeTag },
            defaultNetworkId = null,
            wasSelectedOnMigration = false,
            createdAt = now,
            updatedAt = now,
          )
        val newId = serverDao.insertServer(fresh)
        fresh.copy(id = newId) to false
      }

    // Select the redeemed server; preserve existing Network or default to first available Network
    val existingNetworks = networkDao.getAllNetworksSnapshot()
    val (savedNetId, _) = remoteServerListRepository.getLastSelection()
    val hasValidNet = savedNetId > 0L && existingNetworks.any { it.id == savedNetId }
    val resolvedNetId = if (hasValidNet) savedNetId else existingNetworks.firstOrNull()?.id
    remoteServerListRepository.saveLastSelection(resolvedNetId, stored.id)

    return stored to wasUpdated
  }

  /**
   * Imports a decoded split bundle (combined Network+Server, Network-only, Server-only, or
   * legacy single-profile `.myvpn` / `.txt` links):
   * - Deduplicates identical Networks by normalized signature against existing DB rows.
   * - Links imported Servers to their bundled Network, or named Network, or [fallbackSelectedNetworkId]
   *   (leaving `defaultNetworkId = null` if none exists so Server-only imports still save cleanly).
   */
  suspend fun importSplitBundle(
    networks: List<NetworkConfigEntity>,
    servers: List<ImportedServerItem>,
    fallbackSelectedNetworkId: Long? = null,
    selectImported: Boolean = true,
  ): SplitImportOutcome {
    val existingNetworks = networkDao.getAllNetworksSnapshot()
    val sigToId = LinkedHashMap<String, Long>()
    val nameToId = LinkedHashMap<String, Long>()
    for (existing in existingNetworks) {
      sigToId.putIfAbsent(existing.normalizedSignature(), existing.id)
      nameToId.putIfAbsent(normalizeNameKey(existing.name), existing.id)
    }

    val now = System.currentTimeMillis()
    var networksAdded = 0
    var networksReused = 0
    var firstNetId: Long? = null

    networks.forEachIndexed { idx, incomingNet ->
      val sig = incomingNet.normalizedSignature()
      val existingId = sigToId[sig]
      val resolvedId =
        if (existingId != null) {
          networksReused += 1
          existingId
        } else {
          val stamp = now - idx * 10L
          val newId =
            networkDao.insertNetwork(
              incomingNet.copy(id = 0L, createdAt = stamp, updatedAt = stamp)
            )
          sigToId[sig] = newId
          nameToId.putIfAbsent(normalizeNameKey(incomingNet.name), newId)
          networksAdded += 1
          newId
        }
      if (firstNetId == null) firstNetId = resolvedId
    }

    var serversAdded = 0
    var firstSrvId: Long? = null
    servers.forEachIndexed { idx, item ->
      val stamp = now - idx * 10L
      val linkedNetId: Long? =
        when {
          item.linkedNetworkSignature.isNotBlank() && sigToId[item.linkedNetworkSignature] != null ->
            sigToId[item.linkedNetworkSignature]
          item.linkedNetworkName.isNotBlank() &&
            nameToId[normalizeNameKey(item.linkedNetworkName)] != null ->
            nameToId[normalizeNameKey(item.linkedNetworkName)]
          fallbackSelectedNetworkId != null &&
            fallbackSelectedNetworkId > 0L &&
            existingNetworks.any { it.id == fallbackSelectedNetworkId } ->
            fallbackSelectedNetworkId
          else -> null
        }

      val newSrvId =
        serverDao.insertServer(
          item.server.copy(
            id = 0L,
            defaultNetworkId = linkedNetId,
            wasSelectedOnMigration = false,
            createdAt = stamp,
            updatedAt = stamp,
          )
        )
      serversAdded += 1
      if (firstSrvId == null) firstSrvId = newSrvId
    }

    if (selectImported) {
      if (firstNetId != null) remoteServerListRepository.saveLastNetworkId(firstNetId)
      if (firstSrvId != null) remoteServerListRepository.saveLastServerId(firstSrvId)
    }

    return SplitImportOutcome(
      networksAdded = networksAdded,
      networksReused = networksReused,
      serversAdded = serversAdded,
      selectedNetworkId = firstNetId,
      selectedServerId = firstSrvId,
    )
  }

  /**
   * Downloads a Remote JSON URL and merges Networks and Servers separately by trimmed
   * case-insensitive name (no duplicates, local edits preserved when remote omits fields, and
   * local Room DB is NEVER wiped on fetch/parse error).
   */
  suspend fun fetchAndMergeSplitFromRemoteUrl(
    url: String,
    replaceExisting: Boolean,
    fallbackSelectedNetworkId: Long? = null,
  ): Result<SplitRemoteSyncSummary> {
    val fetchResult = remoteServerListRepository.fetchSplitListFromRemoteUrl(url)
    return fetchResult.map { parsed ->
      mergeSplitRemoteList(
        parsed = parsed,
        replaceExisting = replaceExisting,
        fallbackSelectedNetworkId = fallbackSelectedNetworkId,
      )
    }
  }

  suspend fun mergeSplitRemoteList(
    parsed: SplitRemoteListResult,
    replaceExisting: Boolean,
    fallbackSelectedNetworkId: Long? = null,
  ): SplitRemoteSyncSummary {
    val allNetDtos = mutableListOf<NetworkConfigDto>()
    val allSrvDtos = mutableListOf<ServerAccountDto>()
    allNetDtos.addAll(parsed.networkDtos)
    allSrvDtos.addAll(parsed.serverDtos)

    // Split any legacy flat profile DTOs into separate Network + Server DTOs (deduping identical networks)
    if (parsed.legacyProfileDtos.isNotEmpty()) {
      val seenLegacyNetSigs = LinkedHashMap<String, String>()
      parsed.legacyProfileDtos.forEachIndexed { idx, legacyDto ->
        val (netDto, srvDto) = splitLegacyDtoPreservingOmissions(legacyDto, idx)
        val previewNet = netDto.toEntity(fallbackName = "Network #${idx + 1}")
        val sig = previewNet.normalizedSignature()
        val canonicalNetName =
          seenLegacyNetSigs.getOrPut(sig) {
            allNetDtos += netDto
            previewNet.name
          }
        allSrvDtos += srvDto.copy(networkName = canonicalNetName)
      }
    }

    val now = System.currentTimeMillis()

    if (replaceExisting) {
      val dedupedNets = LinkedHashMap<String, NetworkConfigEntity>()
      allNetDtos.forEachIndexed { idx, dto ->
        val entity = dto.toEntity(fallbackName = "Network #${idx + 1}")
        dedupedNets[normalizeNameKey(entity.name)] =
          entity.copy(id = 0L, createdAt = now - idx * 10L, updatedAt = now - idx * 10L)
      }
      val insertedNetIds =
        if (dedupedNets.isNotEmpty()) {
          networkDao.replaceAllNetworks(dedupedNets.values.toList())
        } else {
          emptyList()
        }
      val netNameToId = LinkedHashMap<String, Long>()
      dedupedNets.values.zip(insertedNetIds).forEach { (net, id) ->
        netNameToId[normalizeNameKey(net.name)] = id
      }
      // If remote replace had servers only, keep existing networks map for linking
      if (dedupedNets.isEmpty()) {
        networkDao.getAllNetworksSnapshot().forEach { net ->
          netNameToId[normalizeNameKey(net.name)] = net.id
        }
      }

      val dedupedSrvs = LinkedHashMap<String, ServerAccountEntity>()
      allSrvDtos.forEachIndexed { idx, dto ->
        val entity = dto.toEntity(fallbackName = "Server #${idx + 1}")
        val linkedNetId =
          dto.networkName.trim().takeIf { it.isNotEmpty() }?.let { netNameToId[normalizeNameKey(it)] }
            ?: insertedNetIds.firstOrNull()
        dedupedSrvs[normalizeNameKey(entity.name)] =
          entity.copy(
            id = 0L,
            defaultNetworkId = linkedNetId,
            createdAt = now - idx * 10L,
            updatedAt = now - idx * 10L,
          )
      }
      val insertedSrvIds =
        if (dedupedSrvs.isNotEmpty()) {
          serverDao.replaceAllServers(dedupedSrvs.values.toList())
        } else {
          emptyList()
        }

      remoteServerListRepository.saveLastSelection(
        networkId = insertedNetIds.firstOrNull() ?: fallbackSelectedNetworkId,
        serverId = insertedSrvIds.firstOrNull(),
      )

      return SplitRemoteSyncSummary(
        networksAdded = insertedNetIds.size,
        networksUpdated = 0,
        serversAdded = insertedSrvIds.size,
        serversUpdated = 0,
        replacedAll = true,
      )
    }

    // Merge mode (default): separate merge-by-name for Networks and for Servers
    val existingNets = networkDao.getAllNetworksSnapshot()
    val netByName = existingNets.associateBy { normalizeNameKey(it.name) }.toMutableMap()
    val netBySig = existingNets.associateBy { it.normalizedSignature() }.toMutableMap()

    val incomingNetsByName = LinkedHashMap<String, Pair<NetworkConfigDto, String>>()
    allNetDtos.forEachIndexed { idx, dto ->
      val fallback = "Network #${idx + 1}"
      val key = normalizeNameKey(dto.toEntity(fallback).name)
      incomingNetsByName[key] = dto to fallback
    }

    var networksAdded = 0
    var networksUpdated = 0
    var firstAddedNetId: Long? = null
    var offset = 0

    for ((key, pair) in incomingNetsByName) {
      val (dto, fallback) = pair
      val stamp = now - (offset++ * 10L)
      val existingByName = netByName[key]
      if (existingByName != null) {
        val merged = mergeRemoteNetworkDto(existingByName, dto, stamp)
        networkDao.updateNetwork(merged)
        netByName[key] = merged
        netBySig[merged.normalizedSignature()] = merged
        networksUpdated += 1
      } else {
        val fresh = dto.toEntity(fallback).copy(id = 0L, createdAt = stamp, updatedAt = stamp)
        val newId = networkDao.insertNetwork(fresh)
        val stored = fresh.copy(id = newId)
        netByName[key] = stored
        netBySig[stored.normalizedSignature()] = stored
        if (firstAddedNetId == null) firstAddedNetId = newId
        networksAdded += 1
      }
    }

    val existingSrvs = serverDao.getAllServersSnapshot()
    val srvByName = existingSrvs.associateBy { normalizeNameKey(it.name) }.toMutableMap()

    val incomingSrvsByName = LinkedHashMap<String, Pair<ServerAccountDto, String>>()
    allSrvDtos.forEachIndexed { idx, dto ->
      val fallback = "Server #${idx + 1}"
      val key = normalizeNameKey(dto.toEntity(fallback).name)
      incomingSrvsByName[key] = dto to fallback
    }

    var serversAdded = 0
    var serversUpdated = 0
    var firstAddedSrvId: Long? = null

    for ((key, pair) in incomingSrvsByName) {
      val (dto, fallback) = pair
      val stamp = now - (offset++ * 10L)
      val explicitNetName = dto.networkName.trim()
      val resolvedNetId: Long? =
        explicitNetName.takeIf { it.isNotEmpty() }?.let { netByName[normalizeNameKey(it)]?.id }

      val existingSrv = srvByName[key]
      if (existingSrv != null) {
        val merged =
          mergeRemoteServerDto(
            existing = existingSrv,
            incoming = dto,
            resolvedNetworkId = resolvedNetId,
            now = stamp,
          )
        serverDao.updateServer(merged)
        srvByName[key] = merged
        serversUpdated += 1
      } else {
        val linkedDefaultNetId =
          if (explicitNetName.isNotEmpty()) {
            resolvedNetId
          } else {
            resolvedNetId ?: fallbackSelectedNetworkId?.takeIf { it > 0L }
          }
        val fresh =
          dto.toEntity(fallback).copy(
            id = 0L,
            defaultNetworkId = linkedDefaultNetId,
            createdAt = stamp,
            updatedAt = stamp,
          )
        val newId = serverDao.insertServer(fresh)
        srvByName[key] = fresh.copy(id = newId)
        if (firstAddedSrvId == null) firstAddedSrvId = newId
        serversAdded += 1
      }
    }

    val (savedNetId, savedSrvId) = remoteServerListRepository.getLastSelection()
    if (savedNetId <= 0L && firstAddedNetId != null) {
      remoteServerListRepository.saveLastNetworkId(firstAddedNetId)
    }
    if (savedSrvId <= 0L && firstAddedSrvId != null) {
      remoteServerListRepository.saveLastServerId(firstAddedSrvId)
    }

    return SplitRemoteSyncSummary(
      networksAdded = networksAdded,
      networksUpdated = networksUpdated,
      serversAdded = serversAdded,
      serversUpdated = serversUpdated,
      replacedAll = false,
    )
  }

  companion object {
    internal fun normalizeNameKey(name: String): String = name.trim().lowercase()

    private fun splitLegacyDtoPreservingOmissions(
      legacy: MyVpnConfigDto,
      index: Int,
    ): Pair<NetworkConfigDto, ServerAccountDto> {
      val fallback = legacy.name.trim().ifBlank { "Server #${index + 1}" }
      val inferredMode =
        if (legacy.mode.isNotBlank() || legacy.payloadMode.isNotBlank()) {
          legacy.inferConnectionMode()
        } else {
          null
        }
      val resolvedPayloadModeCode =
        PayloadMode.fromCode(legacy.payloadMode)?.code
          ?: inferredMode?.let { PayloadMode.defaultForConnectionMode(it).code }
          ?: ""

      val netDto =
        NetworkConfigDto(
          name = fallback,
          payloadMode = resolvedPayloadModeCode,
          payloadTemplate = legacy.payload,
          sniHost = legacy.sni,
          extraHeaders = legacy.extraHeaders,
          httpProxyHost = legacy.proxyHost,
          httpProxyPort = legacy.proxyPort,
          locked = legacy.locked,
        )

      val serverModeCode =
        when (inferredMode) {
          ConnectionMode.DNSTT -> ConnectionMode.DNSTT.code
          ConnectionMode.VLESS_WS -> ConnectionMode.VLESS_WS.code
          null -> ""
          else -> ConnectionMode.SSH_DIRECT.code
        }

      val srvDto =
        ServerAccountDto(
          name = fallback,
          connectionMode = serverModeCode,
          host = legacy.sshHost,
          port = legacy.sshPort,
          username = legacy.username,
          password = legacy.password,
          dnsttNameserver = legacy.dnsttNameserver,
          dnsttPublicKey = legacy.dnsttPublicKey,
          dnsttResolver = legacy.dnsttResolver,
          vlessLink = legacy.vlessLink,
          expiryDate = legacy.expiryDate,
          notes = legacy.notes,
          locked = legacy.locked,
          networkName = fallback,
        )
      return netDto to srvDto
    }

    fun mergeRemoteNetworkDto(
      existing: NetworkConfigEntity,
      incoming: NetworkConfigDto,
      now: Long = System.currentTimeMillis(),
    ): NetworkConfigEntity {
      val resolvedPayloadMode =
        PayloadMode.fromCode(incoming.payloadMode) ?: existing.resolvedPayloadMode
      return existing.copy(
        name = incoming.name.trim().ifBlank { existing.name },
        payloadMode = resolvedPayloadMode.code,
        payloadTemplate = incoming.effectivePayloadTemplate.ifBlank { existing.payloadTemplate },
        sniHost = incoming.effectiveSniHost.ifBlank { existing.sniHost },
        extraHeaders = incoming.extraHeaders.ifBlank { existing.extraHeaders },
        httpProxyHost = incoming.effectiveProxyHost.ifBlank { existing.httpProxyHost },
        httpProxyPort =
          if (incoming.effectiveProxyPort in 1..65535) incoming.effectiveProxyPort
          else existing.httpProxyPort,
        locked = existing.locked || incoming.locked,
        updatedAt = now,
      )
    }

    fun mergeRemoteServerDto(
      existing: ServerAccountEntity,
      incoming: ServerAccountDto,
      resolvedNetworkId: Long? = null,
      now: Long = System.currentTimeMillis(),
    ): ServerAccountEntity {
      val explicitMode =
        if (incoming.effectiveModeRaw.isNotBlank()) incoming.inferServerConnectionMode()
        else existing.resolvedConnectionMode
      return existing.copy(
        name = incoming.name.trim().ifBlank { existing.name },
        connectionMode = explicitMode.code,
        host = incoming.effectiveHost.ifBlank { existing.host },
        port = if (incoming.effectivePort in 1..65535) incoming.effectivePort else existing.port,
        username = incoming.username.trim().ifBlank { existing.username },
        password = incoming.password.ifBlank { existing.password },
        dnsttNameserver = incoming.dnsttNameserver.trim().ifBlank { existing.dnsttNameserver },
        dnsttPublicKey = incoming.dnsttPublicKey.trim().ifBlank { existing.dnsttPublicKey },
        dnsttResolver = incoming.dnsttResolver.trim().ifBlank { existing.dnsttResolver },
        vlessLink = incoming.vlessLink.trim().ifBlank { existing.vlessLink },
        vlessUuid = incoming.vlessUuid.trim().ifBlank { existing.vlessUuid },
        expiryDate = incoming.expiryDate.trim().ifBlank { existing.expiryDate },
        region = incoming.region.trim().ifBlank { existing.region },
        tags = incoming.tags.trim().ifBlank { existing.tags },
        notes = incoming.notes.trim().ifBlank { existing.notes },
        locked = existing.locked || incoming.locked,
        defaultNetworkId = resolvedNetworkId ?: existing.defaultNetworkId,
        updatedAt = now,
      )
    }
  }
}
