package com.example.data

import com.example.config.MyVpnConfigDto
import kotlinx.coroutines.flow.Flow

/**
 * Outcome of importing/merging a remote JSON server list into Room.
 */
data class RemoteSyncSummary(
  val addedCount: Int,
  val updatedCount: Int,
  val totalCount: Int,
  val replacedAll: Boolean,
) {
  fun toBurmeseMessage(): String {
    return if (replacedAll) {
      "Remote JSON မှ ဆာဗာ $totalCount ခု ဖြင့် အစားထိုးပြီးပါပြီ"
    } else {
      "Remote JSON ဆွဲယူပြီးပါပြီ — အသစ် $addedCount ခု ထည့်သွင်းပြီး၊ ရှိပြီးသား $updatedCount ခု အပ်ဒိတ်လုပ်ပြီး"
    }
  }
}

class ServerProfileRepository(
  private val dao: ServerProfileDao,
  private val remoteServerListRepository: RemoteServerListRepository,
) {
  val allProfiles: Flow<List<ServerProfileEntity>> = dao.observeAllProfiles()
  val selectedProfile: Flow<ServerProfileEntity?> = dao.observeSelectedProfile()
  val remoteServerListUrl: Flow<String> = remoteServerListRepository.remoteServerListUrlFlow

  /**
   * First-run bundled template seeding has been removed; fresh installs start empty and
   * existing user profiles are preserved untouched.
   */
  suspend fun seedInitialTemplatesOnce(): Int = 0

  suspend fun getById(id: Long): ServerProfileEntity? = dao.getProfileById(id)

  suspend fun saveProfile(profile: ServerProfileEntity, selectAfterSave: Boolean = false): Long {
    val now = System.currentTimeMillis()
    val savedId =
      if (profile.id == 0L) {
        dao.insertProfile(profile.copy(createdAt = now, updatedAt = now))
      } else {
        dao.updateProfile(profile.copy(updatedAt = now))
        profile.id
      }
    if (selectAfterSave) {
      dao.selectProfile(savedId)
    }
    return savedId
  }

  suspend fun importProfiles(
    profiles: List<ServerProfileEntity>,
    replaceExisting: Boolean = false,
  ): List<Long> {
    if (profiles.isEmpty()) return emptyList()
    val now = System.currentTimeMillis()
    val stamped =
      profiles.mapIndexed { index, profile ->
        profile.copy(
          id = 0L,
          isSelected = false,
          createdAt = now - index * 1000L,
          updatedAt = now - index * 1000L,
        )
      }
    return if (replaceExisting) {
      dao.replaceAllAndSelectFirst(stamped)
    } else {
      val insertedIds = dao.insertProfiles(stamped)
      insertedIds.firstOrNull()?.let { firstId -> dao.selectProfile(firstId) }
      insertedIds
    }
  }

  /**
   * Imports new profiles and updates existing profiles (matched by trimmed case-insensitive name)
   * WITHOUT duplicating existing profiles and WITHOUT wiping locally edited fields that the
   * remote JSON entry omits or leaves blank.
   */
  suspend fun mergeRemoteDtosByName(dtos: List<MyVpnConfigDto>): RemoteSyncSummary {
    if (dtos.isEmpty()) {
      return RemoteSyncSummary(addedCount = 0, updatedCount = 0, totalCount = 0, replacedAll = false)
    }

    val existingList = dao.getAllProfilesSnapshot()
    val existingByName =
      existingList.associateBy { normalizeNameKey(it.name) }.toMutableMap()
    val hadSelection = existingList.any { it.isSelected }

    // Deduplicate incoming entries with identical names within the same remote file (last wins)
    val deduplicatedIncoming = LinkedHashMap<String, Pair<MyVpnConfigDto, String>>()
    dtos.forEachIndexed { index, dto ->
      val fallbackName = "Server #${index + 1}"
      val entityPreview = dto.toEntity(fallbackName = fallbackName)
      val key = normalizeNameKey(entityPreview.name)
      deduplicatedIncoming[key] = dto to fallbackName
    }

    val now = System.currentTimeMillis()
    var addedCount = 0
    var updatedCount = 0
    var firstAddedId: Long? = null
    var offset = 0

    for ((key, pair) in deduplicatedIncoming) {
      val (dto, fallbackName) = pair
      val stamp = now - (offset++ * 10L)
      val existing = existingByName[key]
      if (existing != null) {
        val merged = mergeRemoteDto(existing = existing, incomingDto = dto, now = stamp)
        dao.updateProfile(merged)
        existingByName[key] = merged
        updatedCount += 1
      } else {
        val fresh =
          dto.toEntity(fallbackName = fallbackName).copy(
            id = 0L,
            isSelected = false,
            createdAt = stamp,
            updatedAt = stamp,
          )
        val newId = dao.insertProfile(fresh)
        existingByName[key] = fresh.copy(id = newId)
        if (firstAddedId == null) firstAddedId = newId
        addedCount += 1
      }
    }

    if (!hadSelection && firstAddedId != null) {
      dao.selectProfile(firstAddedId)
    }

    return RemoteSyncSummary(
      addedCount = addedCount,
      updatedCount = updatedCount,
      totalCount = addedCount + updatedCount,
      replacedAll = false,
    )
  }

  /**
   * Fetches a remote JSON server list and either merges by name (default, preserving local edits)
   * or replaces all profiles (only after a successful download and parse — never wipes Room on failure).
   */
  suspend fun fetchAndImportFromRemoteUrl(
    url: String,
    replaceExisting: Boolean,
  ): Result<RemoteSyncSummary> {
    val result = remoteServerListRepository.fetchDtosFromRemoteUrl(url)
    return result.map { dtos ->
      if (replaceExisting) {
        val entities =
          dtos.mapIndexed { index, dto -> dto.toEntity(fallbackName = "Server #${index + 1}") }
        importProfiles(entities, replaceExisting = true)
        RemoteSyncSummary(
          addedCount = entities.size,
          updatedCount = 0,
          totalCount = entities.size,
          replacedAll = true,
        )
      } else {
        mergeRemoteDtosByName(dtos)
      }
    }
  }

  suspend fun selectProfile(id: Long) {
    dao.selectProfile(id)
  }

  suspend fun deleteProfile(id: Long) {
    val target = dao.getProfileById(id)
    dao.deleteProfileById(id)
    if (target?.isSelected == true) {
      dao.getMostRecentProfile()?.let { fallback -> dao.selectProfile(fallback.id) }
    }
  }

  suspend fun duplicateProfile(profile: ServerProfileEntity): Long {
    val now = System.currentTimeMillis()
    return dao.insertProfile(
      profile.copy(
        id = 0L,
        name = "${profile.name} (မိတ္တူ)",
        isSelected = false,
        createdAt = now,
        updatedAt = now,
      )
    )
  }

  companion object {
    internal fun normalizeNameKey(name: String): String = name.trim().lowercase()

    /**
     * Merges a remote JSON DTO into an existing local [ServerProfileEntity]:
     * - Updates every field explicitly supplied (non-blank / >0) by the remote entry.
     * - Preserves locally edited fields (such as password, username, custom payload, SNI,
     *   extraHeaders, vlessLink, expiryDate, notes, ports) whenever the remote entry omits
     *   them or leaves them blank.
     */
    fun mergeRemoteDto(
      existing: ServerProfileEntity,
      incomingDto: MyVpnConfigDto,
      now: Long = System.currentTimeMillis(),
    ): ServerProfileEntity {
      val resolvedMode =
        ConnectionMode.fromCode(incomingDto.mode) ?: existing.connectionMode
      val resolvedPayloadMode =
        PayloadMode.fromCode(incomingDto.payloadMode) ?: existing.resolvedPayloadMode

      return existing.copy(
        name = incomingDto.name.trim().ifBlank { existing.name },
        mode = resolvedMode.code,
        sshHost = incomingDto.sshHost.trim().ifBlank { existing.sshHost },
        sshPort = if (incomingDto.sshPort in 1..65535) incomingDto.sshPort else existing.sshPort,
        username = incomingDto.username.trim().ifBlank { existing.username },
        password = incomingDto.password.ifBlank { existing.password },
        payload = incomingDto.payload.ifBlank { existing.payload },
        payloadMode = resolvedPayloadMode.code,
        extraHeaders = incomingDto.extraHeaders.ifBlank { existing.extraHeaders },
        proxyHost = incomingDto.proxyHost.trim().ifBlank { existing.proxyHost },
        proxyPort =
          if (incomingDto.proxyPort in 1..65535) incomingDto.proxyPort else existing.proxyPort,
        sni = incomingDto.sni.trim().ifBlank { existing.sni },
        dnsttNameserver = incomingDto.dnsttNameserver.trim().ifBlank { existing.dnsttNameserver },
        dnsttPublicKey = incomingDto.dnsttPublicKey.trim().ifBlank { existing.dnsttPublicKey },
        dnsttResolver = incomingDto.dnsttResolver.trim().ifBlank { existing.dnsttResolver },
        vlessLink = incomingDto.vlessLink.trim().ifBlank { existing.vlessLink },
        expiryDate = incomingDto.expiryDate.trim().ifBlank { existing.expiryDate },
        locked = existing.locked || incomingDto.locked,
        notes = incomingDto.notes.trim().ifBlank { existing.notes },
        updatedAt = now,
      )
    }
  }
}
