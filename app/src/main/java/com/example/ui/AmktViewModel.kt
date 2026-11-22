package com.example.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.config.ConfigFileCodec
import com.example.config.ConfigImportResult
import com.example.config.SplitConfigImportResult
import com.example.data.AppLogStore
import com.example.data.ConnectionMode
import com.example.data.EffectiveConnectionCombiner
import com.example.data.LogEntry
import com.example.data.LogLevel
import com.example.data.NetworkConfigEntity
import com.example.data.NetworkServerRepository
import com.example.data.PayloadMode
import com.example.data.ProfileExpiryChecker
import com.example.data.RemoteServerListRepository
import com.example.data.ServerAccountEntity
import com.example.data.ServerProfileEntity
import com.example.data.ServerProfileRepository
import com.example.dnstt.DnsttValidator
import com.example.membership.MembershipRepository
import com.example.membership.OwnerBanner
import com.example.membership.PassStatus
import com.example.redeem.RedeemCodeMapper
import com.example.redeem.RedeemOutcome
import com.example.redeem.RedeemRepository
import com.example.vless.VlessConfigParser
import com.example.vpn.VpnEngineController
import com.example.vpn.VpnPreferencesRepository
import com.example.vpn.VpnProtectionSettings
import com.example.vpn.VpnTrafficStats
import com.example.vpn.VpnTunnelState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RemoteSyncFeedback(
  val message: String,
  val isError: Boolean,
  val timestampMs: Long = System.currentTimeMillis(),
)

class AmktViewModel(
  private val repository: ServerProfileRepository,
  private val membershipRepository: MembershipRepository,
  private val vpnPreferencesRepository: VpnPreferencesRepository,
  private val vpnController: VpnEngineController,
  private val configCodec: ConfigFileCodec,
  private val logStore: AppLogStore,
  private val networkServerRepository: NetworkServerRepository? = null,
  private val redeemRepository: RedeemRepository? = null,
) : ViewModel() {

  // Legacy single-profile flows (retained for backward compatibility with existing tests)
  val profiles: StateFlow<List<ServerProfileEntity>> =
    repository.allProfiles.stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5_000),
      initialValue = emptyList(),
    )

  val selectedProfile: StateFlow<ServerProfileEntity?> =
    repository.selectedProfile.stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5_000),
      initialValue = null,
    )

  // Milestone 7 split source-of-truth flows (Networks + Servers/Accounts)
  val networks: StateFlow<List<NetworkConfigEntity>> =
    (networkServerRepository?.allNetworks ?: flowOf(emptyList())).stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5_000),
      initialValue = emptyList(),
    )

  val servers: StateFlow<List<ServerAccountEntity>> =
    (networkServerRepository?.allServers ?: flowOf(emptyList())).stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5_000),
      initialValue = emptyList(),
    )

  private val lastNetworkId: StateFlow<Long> =
    (networkServerRepository?.lastNetworkId ?: flowOf(-1L)).stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5_000),
      initialValue = -1L,
    )

  private val lastServerId: StateFlow<Long> =
    (networkServerRepository?.lastServerId ?: flowOf(-1L)).stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5_000),
      initialValue = -1L,
    )

  val selectedNetwork: StateFlow<NetworkConfigEntity?> =
    combine(networks, lastNetworkId) { netList, savedId ->
      if (savedId > 0L) netList.firstOrNull { it.id == savedId } else null
    }.stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5_000),
      initialValue = null,
    )

  val selectedServer: StateFlow<ServerAccountEntity?> =
    combine(servers, lastServerId) { srvList, savedId ->
      if (savedId > 0L) srvList.firstOrNull { it.id == savedId } else null
    }.stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5_000),
      initialValue = null,
    )

  val referencingServerCounts: StateFlow<Map<Long, Int>> =
    servers
      .map { list ->
        list
          .mapNotNull { it.defaultNetworkId }
          .groupingBy { it }
          .eachCount()
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyMap(),
      )

  val passStatus: StateFlow<PassStatus> =
    membershipRepository.passStatusFlow.stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5_000),
      initialValue = PassStatus(deviceId = membershipRepository.getDeviceId()),
    )

  val ownerBanner: StateFlow<OwnerBanner?> =
    membershipRepository.ownerBannerFlow.stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5_000),
      initialValue = null,
    )

  val bannerConfigUrl: StateFlow<String> =
    membershipRepository.bannerConfigUrlFlow.stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5_000),
      initialValue = "",
    )

  val remoteServerListUrl: StateFlow<String> =
    (networkServerRepository?.remoteServerListUrl ?: repository.remoteServerListUrl).stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5_000),
      initialValue = RemoteServerListRepository.DEFAULT_REMOTE_SERVER_LIST_URL,
    )

  val vpnSettings: StateFlow<VpnProtectionSettings> =
    vpnPreferencesRepository.settingsFlow.stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5_000),
      initialValue = VpnProtectionSettings(),
    )

  val vpnState: StateFlow<VpnTunnelState> = vpnController.state
  val vpnTrafficStats: StateFlow<VpnTrafficStats> = vpnController.trafficStats
  val logs: StateFlow<List<LogEntry>> = logStore.logs

  val redeemWebhookUrl: StateFlow<String> =
    (redeemRepository?.redeemWebhookUrlFlow
        ?: flowOf(RedeemRepository.DEFAULT_REDEEM_WEBHOOK_URL))
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = RedeemRepository.DEFAULT_REDEEM_WEBHOOK_URL,
      )

  val deviceId: String
    get() = membershipRepository.getDeviceId()

  private val _isFetchingRemoteServers = MutableStateFlow(false)
  val isFetchingRemoteServers: StateFlow<Boolean> = _isFetchingRemoteServers.asStateFlow()

  private val _remoteSyncFeedback = MutableStateFlow<RemoteSyncFeedback?>(null)
  val remoteSyncFeedback: StateFlow<RemoteSyncFeedback?> = _remoteSyncFeedback.asStateFlow()

  private val _isValidatingCode = MutableStateFlow(false)
  val isValidatingCode: StateFlow<Boolean> = _isValidatingCode.asStateFlow()

  private val _isRedeemingCode = MutableStateFlow(false)
  val isRedeemingCode: StateFlow<Boolean> = _isRedeemingCode.asStateFlow()

  private val _redeemOutcome = MutableStateFlow<RedeemOutcome?>(null)
  val redeemOutcome: StateFlow<RedeemOutcome?> = _redeemOutcome.asStateFlow()

  private val _isFetchingBanner = MutableStateFlow(false)
  val isFetchingBanner: StateFlow<Boolean> = _isFetchingBanner.asStateFlow()

  private val _selectedModeFilter = MutableStateFlow<ConnectionMode?>(null)
  val selectedModeFilter: StateFlow<ConnectionMode?> = _selectedModeFilter.asStateFlow()

  private val _snackbarMessage = MutableStateFlow<String?>(null)
  val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

  private val _isDarkTheme = MutableStateFlow(true)
  val isDarkTheme: StateFlow<Boolean> = _isDarkTheme.asStateFlow()

  init {
    viewModelScope.launch {
      networkServerRepository?.initializeOnLaunch()
    }
  }

  fun toggleTheme() {
    _isDarkTheme.value = !_isDarkTheme.value
  }

  fun setModeFilter(mode: ConnectionMode?) {
    _selectedModeFilter.value = mode
  }

  fun clearSnackbar() {
    _snackbarMessage.value = null
  }

  fun setKillSwitchEnabled(enabled: Boolean) {
    viewModelScope.launch {
      vpnPreferencesRepository.setKillSwitchEnabled(enabled)
      val currentAuto = vpnSettings.value.autoReconnectEnabled
      vpnController.updateProtectionSettings(killSwitch = enabled, autoReconnect = currentAuto)
      logStore.append(
        tag = "KILLSW",
        message =
          if (enabled) "Kill Switch ဖွင့်လိုက်ပါပြီ (SSH ပြတ်တောက်ပါက Traffic အားလုံး ပိတ်ဆို့မည်)"
          else "Kill Switch ပိတ်လိုက်ပါပြီ",
        level = LogLevel.INFO,
      )
    }
  }

  fun setAutoReconnectEnabled(enabled: Boolean) {
    viewModelScope.launch {
      vpnPreferencesRepository.setAutoReconnectEnabled(enabled)
      val currentKill = vpnSettings.value.killSwitchEnabled
      vpnController.updateProtectionSettings(killSwitch = currentKill, autoReconnect = enabled)
      logStore.append(
        tag = "VPN",
        message =
          if (enabled) "Auto-Reconnect ဖွင့်လိုက်ပါပြီ"
          else "Auto-Reconnect ပိတ်လိုက်ပါပြီ",
        level = LogLevel.INFO,
      )
    }
  }

  // ==================== Milestone 7: Network CRUD & Selection ====================

  fun selectNetwork(network: NetworkConfigEntity) {
    val repo = networkServerRepository ?: return
    viewModelScope.launch {
      repo.selectNetwork(network.id)
      logStore.append(
        tag = "NETWORK",
        message = "ကွန်ရက် ရွေးချယ်ပြီး: ${network.name} [${network.resolvedPayloadMode.displayLabel}]",
        level = LogLevel.INFO,
      )
    }
  }

  fun saveNetwork(
    network: NetworkConfigEntity,
    selectAfterSave: Boolean = true,
    onSaved: () -> Unit = {},
  ) {
    val repo = networkServerRepository ?: return
    viewModelScope.launch {
      val savedId = repo.saveNetwork(network, selectAfterSave = selectAfterSave)
      logStore.append(
        tag = "NETWORK",
        message = "ကွန်ရက် သိမ်းဆည်းပြီး (#$savedId): ${network.name} [${network.resolvedPayloadMode.displayLabel}]",
        level = LogLevel.SUCCESS,
      )
      _snackbarMessage.value = "ကွန်ရက် (Network) သိမ်းဆည်းပြီးပါပြီ"
      onSaved()
    }
  }

  fun duplicateNetwork(network: NetworkConfigEntity) {
    val repo = networkServerRepository ?: return
    viewModelScope.launch {
      repo.duplicateNetwork(network)
      logStore.append(
        tag = "NETWORK",
        message = "ကွန်ရက် မိတ္တူပွားပြီး: ${network.name}",
        level = LogLevel.INFO,
      )
      _snackbarMessage.value = "ကွန်ရက် မိတ္တူပွားပြီးပါပြီ"
    }
  }

  fun deleteNetwork(network: NetworkConfigEntity, unlinkReferencingServers: Boolean) {
    val repo = networkServerRepository ?: return
    viewModelScope.launch {
      val deleted = repo.deleteNetwork(network.id, unlinkReferencingServers = unlinkReferencingServers)
      if (deleted) {
        logStore.append(
          tag = "NETWORK",
          message = "ကွန်ရက် ဖျက်လိုက်ပြီး: ${network.name}",
          level = LogLevel.WARNING,
        )
        _snackbarMessage.value = "ကွန်ရက် ဖျက်ပြီးပါပြီ"
      } else {
        _snackbarMessage.value = "ဤကွန်ရက်ကို အသုံးပြုနေသော ဆာဗာများ ရှိနေပါသည် — အတည်ပြုပြီးမှ ဖျက်ပါ"
      }
    }
  }

  suspend fun loadNetworkById(id: Long): NetworkConfigEntity? =
    networkServerRepository?.getNetworkById(id)

  suspend fun getReferencingServers(networkId: Long): List<ServerAccountEntity> =
    networkServerRepository?.getServersReferencingNetwork(networkId) ?: emptyList()

  // ==================== Milestone 7: Server/Account CRUD & Selection ====================

  fun selectServer(server: ServerAccountEntity) {
    val repo = networkServerRepository ?: return
    viewModelScope.launch {
      // Keep the user's current Network selection so switching Servers reuses the active Network.
      // Only auto-pick server.defaultNetworkId if no Network is currently selected.
      val currentNet = selectedNetwork.value
      if (currentNet == null && server.defaultNetworkId != null) {
        repo.selectCombination(server.defaultNetworkId, server.id)
      } else {
        repo.selectServer(server.id)
      }
      logStore.append(
        tag = "SERVER",
        message =
          "ဆာဗာ ရွေးချယ်ပြီး: ${server.name} [${server.resolvedConnectionMode.displayLabel}] -> ${server.primaryEndpointSummary()}",
        level = LogLevel.INFO,
      )
    }
  }

  fun saveServer(
    server: ServerAccountEntity,
    selectAfterSave: Boolean = true,
    onSaved: () -> Unit = {},
  ) {
    val repo = networkServerRepository ?: return
    viewModelScope.launch {
      val savedId = repo.saveServer(server, selectAfterSave = selectAfterSave)
      if (selectAfterSave && selectedNetwork.value == null && server.defaultNetworkId != null) {
        repo.selectNetwork(server.defaultNetworkId)
      }
      logStore.append(
        tag = "SERVER",
        message =
          "ဆာဗာ သိမ်းဆည်းပြီး (#$savedId): ${server.name} [${server.resolvedConnectionMode.displayLabel}]",
        level = LogLevel.SUCCESS,
      )
      _snackbarMessage.value = "ဆာဗာ / အကောင့် သိမ်းဆည်းပြီးပါပြီ"
      onSaved()
    }
  }

  fun duplicateServer(server: ServerAccountEntity) {
    val repo = networkServerRepository ?: return
    viewModelScope.launch {
      repo.duplicateServer(server)
      logStore.append(
        tag = "SERVER",
        message = "ဆာဗာ မိတ္တူပွားပြီး: ${server.name}",
        level = LogLevel.INFO,
      )
      _snackbarMessage.value = "ဆာဗာ မိတ္တူပွားပြီးပါပြီ"
    }
  }

  fun deleteServer(server: ServerAccountEntity) {
    val repo = networkServerRepository ?: return
    viewModelScope.launch {
      repo.deleteServer(server.id)
      logStore.append(
        tag = "SERVER",
        message = "ဆာဗာ ဖျက်လိုက်ပြီး: ${server.name}",
        level = LogLevel.WARNING,
      )
      _snackbarMessage.value = "ဆာဗာ ဖျက်ပြီးပါပြီ"
    }
  }

  suspend fun loadServerById(id: Long): ServerAccountEntity? =
    networkServerRepository?.getServerById(id)

  // ==================== Legacy Profile Methods (backward compat) ====================

  fun selectProfile(profile: ServerProfileEntity) {
    viewModelScope.launch {
      repository.selectProfile(profile.id)
      logStore.append(
        tag = "PROFILE",
        message =
          "ရွေးချယ်ပြီး: ${profile.name} [${profile.connectionMode.displayLabel}] -> ${profile.primaryEndpointSummary()}",
        level = LogLevel.INFO,
      )
    }
  }

  fun saveProfile(
    profile: ServerProfileEntity,
    selectAfterSave: Boolean = true,
    onSaved: () -> Unit = {},
  ) {
    viewModelScope.launch {
      val savedId = repository.saveProfile(profile, selectAfterSave = selectAfterSave)
      logStore.append(
        tag = "PROFILE",
        message =
          "သိမ်းဆည်းပြီး (#$savedId): ${profile.name} [${profile.connectionMode.displayLabel}]",
        level = LogLevel.SUCCESS,
      )
      _snackbarMessage.value = "Profile သိမ်းဆည်းပြီးပါပြီ"
      onSaved()
    }
  }

  fun deleteProfile(profile: ServerProfileEntity) {
    viewModelScope.launch {
      repository.deleteProfile(profile.id)
      logStore.append(
        tag = "PROFILE",
        message = "ဖျက်လိုက်ပြီး: ${profile.name}",
        level = LogLevel.WARNING,
      )
      _snackbarMessage.value = "Profile ဖျက်ပြီးပါပြီ"
    }
  }

  fun duplicateProfile(profile: ServerProfileEntity) {
    viewModelScope.launch {
      repository.duplicateProfile(profile)
      logStore.append(
        tag = "PROFILE",
        message = "မိတ္တူပွားပြီး: ${profile.name}",
        level = LogLevel.INFO,
      )
      _snackbarMessage.value = "Profile မိတ္တူပွားပြီးပါပြီ"
    }
  }

  suspend fun loadProfileById(id: Long): ServerProfileEntity? = repository.getById(id)

  // ==================== Import / Export (Split v2 + Legacy v1) ====================

  fun importFromBytes(rawBytes: ByteArray, fileName: String?) {
    viewModelScope.launch {
      val splitRepo = networkServerRepository
      if (splitRepo != null) {
        val result =
          withContext(Dispatchers.Default) { configCodec.decodeSplitImportedBytes(rawBytes, fileName) }
        when (result) {
          is SplitConfigImportResult.Success -> {
            val outcome =
              splitRepo.importSplitBundle(
                networks = result.networks,
                servers = result.servers,
                fallbackSelectedNetworkId = selectedNetwork.value?.id,
                selectImported = true,
              )
            val summaryMsg = outcome.toBurmeseSummary(result.sourceFormat)
            logStore.append(tag = "IMPORT", message = summaryMsg, level = LogLevel.SUCCESS)
            _snackbarMessage.value = summaryMsg
          }
          is SplitConfigImportResult.Failure -> {
            logStore.append(
              tag = "IMPORT",
              message = "Config ဖတ်မရပါ (${fileName ?: "unknown"}): ${result.message}",
              level = LogLevel.ERROR,
            )
            _snackbarMessage.value = "Config ဖတ်မရပါ — ${result.message}"
          }
        }
        return@launch
      }

      val result =
        withContext(Dispatchers.Default) { configCodec.decodeImportedBytes(rawBytes, fileName) }
      when (result) {
        is ConfigImportResult.Success -> {
          repository.importProfiles(result.profiles)
          val count = result.profiles.size
          val names = result.profiles.joinToString(", ") { it.name }
          logStore.append(
            tag = "IMPORT",
            message = "${result.sourceFormat} မှ Profile $count ခု ထည့်သွင်းပြီး ($names)",
            level = LogLevel.SUCCESS,
          )
          _snackbarMessage.value = "Config $count ခု ထည့်သွင်းပြီးပါပြီ"
        }
        is ConfigImportResult.Failure -> {
          logStore.append(
            tag = "IMPORT",
            message = "Config ဖတ်မရပါ (${fileName ?: "unknown"}): ${result.message}",
            level = LogLevel.ERROR,
          )
          _snackbarMessage.value = "Config ဖတ်မရပါ — ${result.message}"
        }
      }
    }
  }

  fun importFromLinkText(rawText: String) {
    viewModelScope.launch {
      val splitRepo = networkServerRepository
      if (splitRepo != null) {
        val result = withContext(Dispatchers.Default) { configCodec.decodeSplitLinkText(rawText) }
        when (result) {
          is SplitConfigImportResult.Success -> {
            val outcome =
              splitRepo.importSplitBundle(
                networks = result.networks,
                servers = result.servers,
                fallbackSelectedNetworkId = selectedNetwork.value?.id,
                selectImported = true,
              )
            val summaryMsg = outcome.toBurmeseSummary(result.sourceFormat)
            logStore.append(tag = "IMPORT", message = summaryMsg, level = LogLevel.SUCCESS)
            _snackbarMessage.value = summaryMsg
          }
          is SplitConfigImportResult.Failure -> {
            logStore.append(
              tag = "IMPORT",
              message = "လင့်ခ် ဖတ်မရပါ: ${result.message}",
              level = LogLevel.ERROR,
            )
            _snackbarMessage.value = result.message
          }
        }
        return@launch
      }

      val result = withContext(Dispatchers.Default) { configCodec.decodeLinkText(rawText) }
      when (result) {
        is ConfigImportResult.Success -> {
          repository.importProfiles(result.profiles)
          val count = result.profiles.size
          logStore.append(
            tag = "IMPORT",
            message = "လင့်ခ်စာသားမှ Profile $count ခု ထည့်သွင်းပြီး",
            level = LogLevel.SUCCESS,
          )
          _snackbarMessage.value = "Config $count ခု ထည့်သွင်းပြီးပါပြီ"
        }
        is ConfigImportResult.Failure -> {
          logStore.append(
            tag = "IMPORT",
            message = "လင့်ခ် ဖတ်မရပါ: ${result.message}",
            level = LogLevel.ERROR,
          )
          _snackbarMessage.value = result.message
        }
      }
    }
  }

  fun buildSplitExportBytes(
    network: NetworkConfigEntity?,
    server: ServerAccountEntity?,
    lockOverride: Boolean,
    expiryOverride: String,
    notesOverride: String,
  ): ByteArray {
    return configCodec.encodeSplitToMyVpnBytes(
      network = network,
      server = server,
      lockOverride = lockOverride,
      expiryOverride = expiryOverride.trim(),
      notesOverride = notesOverride.trim(),
    )
  }

  fun suggestedSplitExportFileName(displayName: String): String =
    configCodec.suggestedExportFileName(displayName)

  fun buildEncryptedExportBytes(
    profile: ServerProfileEntity,
    lockOverride: Boolean,
    expiryOverride: String,
    notesOverride: String,
  ): ByteArray {
    return configCodec.encodeToMyVpnBytes(
      profile = profile,
      lockOverride = lockOverride,
      expiryOverride = expiryOverride.trim(),
      notesOverride = notesOverride.trim(),
    )
  }

  fun suggestedExportFileName(profile: ServerProfileEntity): String =
    configCodec.suggestedExportFileName(profile)

  fun notifyExportResult(
    profileName: String,
    locked: Boolean,
    success: Boolean,
    error: String? = null,
  ) {
    if (success) {
      val lockText = if (locked) "Locked" else "Unlocked"
      logStore.append(
        tag = "EXPORT",
        message = "$profileName ကို AES-256-GCM .myvpn ($lockText) အဖြစ် သိမ်းဆည်းပြီး",
        level = LogLevel.SUCCESS,
      )
      _snackbarMessage.value = ".myvpn ဖိုင် ထုတ်ယူသိမ်းဆည်းပြီးပါပြီ"
    } else {
      logStore.append(
        tag = "EXPORT",
        message = ".myvpn ထုတ်ယူမှု မအောင်မြင်ပါ: ${error ?: "Unknown"}",
        level = LogLevel.ERROR,
      )
      _snackbarMessage.value = "ဖိုင်ထုတ်ယူမှု မအောင်မြင်ပါ — ${error.orEmpty()}"
    }
  }

  // ==================== VPN Connect Validation (Combined Network + Server) ====================

  fun isVpnActiveOrBusy(): Boolean {
    return when (vpnState.value) {
      is VpnTunnelState.Connected,
      is VpnTunnelState.Connecting,
      is VpnTunnelState.Reconnecting,
      is VpnTunnelState.KillSwitchBlocking -> true
      is VpnTunnelState.Disconnected,
      is VpnTunnelState.Error -> false
    }
  }

  fun disconnectVpn() {
    vpnController.stopTunnel()
    _snackbarMessage.value = "VPN ချိတ်ဆက်မှု ဖြတ်တောက်နေပါသည်"
  }

  /**
   * Milestone 7 pre-flight validation combining the selected [NetworkConfigEntity] and
   * [ServerAccountEntity] into an effective [ServerProfileEntity] before requesting VPN permission.
   */
  fun validateSelectionForVpnConnect(): Triple<NetworkConfigEntity, ServerAccountEntity, ServerProfileEntity>? {
    val network = selectedNetwork.value
    if (network == null) {
      val msg = "ဦးစွာ ကွန်ရက် (Network) တစ်ခု ရွေးချယ်ပါ"
      _snackbarMessage.value = msg
      logStore.append(tag = "CONNECT", message = msg, level = LogLevel.WARNING)
      return null
    }

    val server = selectedServer.value
    if (server == null) {
      val msg = "ဦးစွာ ဆာဗာ / အကောင့် (Server) တစ်ခု ရွေးချယ်ပါ"
      _snackbarMessage.value = msg
      logStore.append(tag = "CONNECT", message = msg, level = LogLevel.WARNING)
      return null
    }

    if (server.isExpired()) {
      val msg = ProfileExpiryChecker.buildExpiredRefusalMessage(server.name, server.expiryDate)
      logStore.append(tag = "EXPIRY", message = msg, level = LogLevel.ERROR)
      _snackbarMessage.value = msg
      return null
    }

    val combined = EffectiveConnectionCombiner.combine(network, server)
    val validated = validateEffectiveProfile(combined, networkLabel = network.name, serverLabel = server.name)
      ?: return null
    return Triple(network, server, validated)
  }

  fun startCombinedVpnAfterConsent(
    network: NetworkConfigEntity,
    server: ServerAccountEntity,
    effectiveProfile: ServerProfileEntity,
  ) {
    val settings = vpnSettings.value
    vpnController.startCombinedTunnel(
      network = network,
      server = server,
      effectiveProfile = effectiveProfile,
      killSwitch = settings.killSwitchEnabled,
      autoReconnect = settings.autoReconnectEnabled,
    )
  }

  /**
   * Legacy single-profile pre-flight validation (retained for existing tests).
   */
  fun validateProfileForVpnConnect(): ServerProfileEntity? {
    val active = selectedProfile.value
    if (active == null) {
      _snackbarMessage.value = "ဦးစွာ ဆာဗာ Profile တစ်ခု ရွေးချယ်ပါ"
      logStore.append(
        tag = "CONNECT",
        message = "Profile မရွေးချယ်ရသေးသဖြင့် ချိတ်ဆက်မှု မစတင်နိုင်ပါ",
        level = LogLevel.WARNING,
      )
      return null
    }

    if (active.isExpired()) {
      val msg = ProfileExpiryChecker.buildExpiredRefusalMessage(active.name, active.expiryDate)
      logStore.append(tag = "EXPIRY", message = msg, level = LogLevel.ERROR)
      _snackbarMessage.value = msg
      return null
    }

    return validateEffectiveProfile(active, networkLabel = "", serverLabel = active.name)
  }

  private fun validateEffectiveProfile(
    active: ServerProfileEntity,
    networkLabel: String,
    serverLabel: String,
  ): ServerProfileEntity? {
    val payloadMode = active.resolvedPayloadMode
    val comboTitle =
      if (networkLabel.isNotBlank()) "ကွန်ရက်: $networkLabel + ဆာဗာ: $serverLabel"
      else active.name
    logStore.append(
      tag = "CHECK",
      message = "Pre-flight စစ်ဆေးမှု — $comboTitle",
      level = LogLevel.INFO,
    )

    if (!active.supportsVpnTunnel()) {
      val msg = "ဤဆာဗာ အမျိုးအစားကို မပံ့ပိုးသေးပါ။ အခြားဆာဗာတစ်ခုကို ရွေးချယ်ပါ။"
      logStore.append(tag = "CHECK", message = msg, level = LogLevel.WARNING)
      _snackbarMessage.value = msg
      return null
    }

    if (active.isVlessMode()) {
      val vlessResult = VlessConfigParser.fromProfile(active)
      if (vlessResult.isFailure) {
        val msg =
          vlessResult.exceptionOrNull()?.message
            ?: "VLESS ချိတ်ဆက်မှု အချက်အလက် မပြည့်စုံပါ — vless:// လင့်ခ် သို့မဟုတ် UUID ထည့်သွင်းပါ"
        logStore.append(tag = "CHECK", message = "သတိပေးချက်: $msg", level = LogLevel.WARNING)
        _snackbarMessage.value = msg
        return null
      }
      return active
    }

    if (active.isDnsttMode()) {
      val dnsttError = DnsttValidator.validateProfile(active)
      if (dnsttError != null) {
        logStore.append(tag = "CHECK", message = "သတိပေးချက်: $dnsttError", level = LogLevel.WARNING)
        _snackbarMessage.value = dnsttError
        return null
      }
      return active
    }

    if (active.sshHost.isBlank()) {
      val msg = "SSH Host မထည့်ရသေးပါ"
      logStore.append(tag = "CHECK", message = msg, level = LogLevel.WARNING)
      _snackbarMessage.value = msg
      return null
    }

    if (active.requiresAccountInput()) {
      val msg = "SSH Username သို့မဟုတ် Password မထည့်ရသေးပါ"
      logStore.append(
        tag = "CHECK",
        message = "သတိပေးချက်: $msg",
        level = LogLevel.WARNING,
      )
      _snackbarMessage.value = msg
      return null
    }

    if (
      (payloadMode == PayloadMode.HTTP_PAYLOAD || payloadMode == PayloadMode.WEBSOCKET) &&
        active.payloadTemplate.isBlank()
    ) {
      val msg =
        "${payloadMode.displayLabel} စနစ်အတွက် Payload စာသား ထည့်သွင်းရန် လိုအပ်ပါသည် — ကွန်ရက် (Network) ပြင်ဆင်ပြီး Payload ထည့်ပါ"
      logStore.append(tag = "CHECK", message = "သတိပေးချက်: $msg", level = LogLevel.WARNING)
      _snackbarMessage.value = msg
      return null
    }

    return active
  }

  fun startVpnAfterConsent(profile: ServerProfileEntity) {
    val settings = vpnSettings.value
    vpnController.startTunnel(
      profile = profile,
      killSwitch = settings.killSwitchEnabled,
      autoReconnect = settings.autoReconnectEnabled,
    )
  }

  fun onVpnPermissionDenied() {
    val msg = "VPN ခွင့်ပြုချက် မပေးထားသဖြင့် ချိတ်ဆက်မှု မစတင်နိုင်ပါ"
    logStore.append(tag = "VPN", message = msg, level = LogLevel.WARNING)
    _snackbarMessage.value = msg
  }

  // ==================== Access Code & Remote JSON Sync ====================

  fun validateAccessCode(rawCode: String) {
    val trimmed = rawCode.trim()
    if (trimmed.isBlank()) {
      _snackbarMessage.value = "Access Code ထည့်သွင်းပါ"
      return
    }
    viewModelScope.launch {
      _isValidatingCode.value = true
      logStore.append(
        tag = "PASS",
        message = "Code စစ်ဆေးနေသည် -> ${MembershipRepository.VALIDATE_ENDPOINT}",
        level = LogLevel.INFO,
      )
      val result = membershipRepository.validateCodeWithOwnerServer(trimmed)
      _isValidatingCode.value = false
      result.fold(
        onSuccess = { res ->
          if (res.valid) {
            val expNote = if (res.expiresAt.isNotBlank()) " (Expires: ${res.expiresAt})" else ""
            val msg = res.message.ifBlank { "Access Code အတည်ပြုပြီးပါပြီ$expNote" }
            logStore.append(tag = "PASS", message = "အတည်ပြုပြီး: $msg", level = LogLevel.SUCCESS)
            _snackbarMessage.value = msg
          } else {
            val msg = res.message.ifBlank { "Access Code မမှန်ကန်ပါ သို့မဟုတ် သက်တမ်းကုန်နေပါသည်" }
            logStore.append(tag = "PASS", message = "ငြင်းပယ်ခံရသည်: $msg", level = LogLevel.WARNING)
            _snackbarMessage.value = msg
          }
        },
        onFailure = { err ->
          val reason = err.message ?: "ကွန်ရက်ချိတ်ဆက်မှု မအောင်မြင်ပါ"
          logStore.append(
            tag = "PASS",
            message = "Code စစ်ဆေးမှု မအောင်မြင်ပါ: $reason",
            level = LogLevel.ERROR,
          )
          _snackbarMessage.value = "စစ်ဆေးမရပါ — $reason"
        },
      )
    }
  }

  fun clearAccessCode() {
    viewModelScope.launch {
      membershipRepository.clearSavedCode()
      logStore.append(
        tag = "PASS",
        message = "သိမ်းဆည်းထားသော Access Code ကို ဖျက်ပြီးပါပြီ",
        level = LogLevel.INFO,
      )
      _snackbarMessage.value = "Access Code ဖျက်ပြီးပါပြီ"
    }
  }

  // ==================== Milestone 8: Buyer Redeem Code & Webhook ====================

  fun clearRedeemOutcome() {
    _redeemOutcome.value = null
  }

  fun redeemBuyerCode(rawCode: String) {
    val repo = redeemRepository
    if (repo == null) {
      val msg = "Redeem စနစ် အဆင်သင့်မဖြစ်သေးပါ"
      _redeemOutcome.value = RedeemOutcome.Error(msg)
      _snackbarMessage.value = msg
      return
    }
    val normalized = RedeemCodeMapper.normalizeCode(rawCode)
    if (normalized.isBlank()) {
      val msg = "ကုတ် (Redeem Code) ထည့်သွင်းပါ"
      _redeemOutcome.value = RedeemOutcome.Error(msg)
      _snackbarMessage.value = msg
      return
    }

    viewModelScope.launch {
      _isRedeemingCode.value = true
      _redeemOutcome.value = null
      val targetUrl = repo.getRedeemWebhookUrl()
      logStore.append(
        tag = "REDEEM",
        message = "ကုတ် စစ်ဆေးနေသည် ($normalized) -> $targetUrl",
        level = LogLevel.INFO,
      )
      val outcome = repo.redeemCode(rawCode = normalized, deviceId = deviceId)
      when (outcome) {
        is RedeemOutcome.Success -> {
          val splitRepo = networkServerRepository
          val finalOutcome =
            if (splitRepo != null) {
              val (stored, wasUpdated) = splitRepo.upsertRedeemedServer(normalized, outcome.server)
              outcome.copy(server = stored, wasUpdated = wasUpdated)
            } else {
              outcome
            }
          _isRedeemingCode.value = false
          _redeemOutcome.value = finalOutcome
          val actionLabel = if (finalOutcome.wasUpdated) "အပ်ဒိတ်ပြီး" else "အသစ်ထည့်သွင်းပြီး"
          logStore.append(
            tag = "REDEEM",
            message =
              "ကုတ် အတည်ပြုပြီး ($actionLabel): ${finalOutcome.server.name} — ${finalOutcome.message}",
            level = LogLevel.SUCCESS,
          )
          _snackbarMessage.value = finalOutcome.message
        }
        is RedeemOutcome.Invalid -> {
          _isRedeemingCode.value = false
          _redeemOutcome.value = outcome
          logStore.append(
            tag = "REDEEM",
            message = "ကုတ် ငြင်းပယ်ခံရသည်: ${outcome.message}",
            level = LogLevel.WARNING,
          )
          _snackbarMessage.value = outcome.message
        }
        is RedeemOutcome.Error -> {
          _isRedeemingCode.value = false
          _redeemOutcome.value = outcome
          logStore.append(
            tag = "REDEEM",
            message = "ကုတ် စစ်ဆေးမှု အမှား: ${outcome.message}",
            level = LogLevel.ERROR,
          )
          _snackbarMessage.value = outcome.message
        }
      }
    }
  }

  fun saveRedeemWebhookUrl(rawUrl: String) {
    val repo = redeemRepository ?: return
    viewModelScope.launch {
      val result = repo.saveRedeemWebhookUrl(rawUrl)
      result.fold(
        onSuccess = { saved ->
          logStore.append(
            tag = "REDEEM",
            message = "Redeem Webhook URL သိမ်းဆည်းပြီး: $saved",
            level = LogLevel.SUCCESS,
          )
          _snackbarMessage.value = "Redeem Webhook URL သိမ်းဆည်းပြီးပါပြီ"
        },
        onFailure = { err ->
          val msg = err.message ?: "Webhook URL မမှန်ကန်ပါ"
          logStore.append(tag = "REDEEM", message = msg, level = LogLevel.WARNING)
          _snackbarMessage.value = msg
        },
      )
    }
  }

  fun resetRedeemWebhookUrl() {
    val repo = redeemRepository ?: return
    viewModelScope.launch {
      val defaultUrl = repo.resetWebhookUrlToDefault()
      logStore.append(
        tag = "REDEEM",
        message = "Redeem Webhook URL ကို မူလလိပ်စာသို့ ပြန်ထားပြီး: $defaultUrl",
        level = LogLevel.INFO,
      )
      _snackbarMessage.value = "Redeem Webhook URL ကို မူလအတိုင်း ပြန်ထားပြီးပါပြီ"
    }
  }

  fun clearRemoteSyncFeedback() {
    _remoteSyncFeedback.value = null
  }

  /**
   * Visible "Update servers" refresh action for Home, Server picker sheet, and Servers tab.
   * Re-fetches the saved Remote Server List URL and upserts servers + networks by name
   * using the exact same logic as About -> Load. If no URL is saved yet, shows a guiding
   * Burmese message and invokes [onMissingUrl] so the caller can open About/Settings.
   */
  fun refreshSavedRemoteServers(onMissingUrl: () -> Unit = {}) {
    if (_isFetchingRemoteServers.value) return
    viewModelScope.launch {
      val savedUrl =
        networkServerRepository?.getSavedRemoteServerListUrl()?.trim()
          ?: remoteServerListUrl.value.trim()
      if (savedUrl.isBlank()) {
        val guideMsg =
          "Remote Server List JSON URL မထည့်ရသေးပါ — အကြောင်းအရာ (About) တွင် URL ဦးစွာ ထည့်သွင်းပါ"
        logStore.append(tag = "REMOTE", message = guideMsg, level = LogLevel.WARNING)
        _remoteSyncFeedback.value = RemoteSyncFeedback(message = guideMsg, isError = true)
        _snackbarMessage.value = guideMsg
        onMissingUrl()
        return@launch
      }
      performFetchServersFromRemoteUrl(cleanUrl = savedUrl, replaceExisting = false)
    }
  }

  fun resetRemoteServerListUrl() {
    val splitRepo = networkServerRepository ?: return
    viewModelScope.launch {
      splitRepo.resetRemoteServerListUrlToDefault()
      logStore.append(
        tag = "REMOTE",
        message = "ဆာဗာစာရင်း ဆက်တင်ကို မူလအတိုင်း ပြန်ထားပြီးပါပြီ",
        level = LogLevel.INFO,
      )
      _snackbarMessage.value = "ဆာဗာစာရင်း ဆက်တင်ကို မူလအတိုင်း ပြန်ထားပြီးပါပြီ"
    }
  }

  fun fetchServersFromRemoteUrl(url: String, replaceExisting: Boolean) {
    viewModelScope.launch {
      val cleanUrl =
        url.trim().ifBlank {
          networkServerRepository?.getSavedRemoteServerListUrl()?.trim().orEmpty()
            .ifBlank { RemoteServerListRepository.DEFAULT_REMOTE_SERVER_LIST_URL }
        }
      if (cleanUrl.isBlank()) {
        val msg = "ဆာဗာစာရင်း ဆွဲယူမရပါ"
        _remoteSyncFeedback.value = RemoteSyncFeedback(message = msg, isError = true)
        _snackbarMessage.value = msg
        return@launch
      }
      performFetchServersFromRemoteUrl(cleanUrl = cleanUrl, replaceExisting = replaceExisting)
    }
  }

  private suspend fun performFetchServersFromRemoteUrl(
    cleanUrl: String,
    replaceExisting: Boolean,
  ) {
    _isFetchingRemoteServers.value = true
    _remoteSyncFeedback.value = null
    val splitRepo = networkServerRepository
    logStore.append(
      tag = "REMOTE",
      message = "ဆာဗာစာရင်း အပ်ဒိတ် ဆွဲယူနေသည်...",
      level = LogLevel.INFO,
    )
    if (splitRepo != null) {
      val result =
        splitRepo.fetchAndMergeSplitFromRemoteUrl(
          url = cleanUrl,
          replaceExisting = replaceExisting,
          fallbackSelectedNetworkId = selectedNetwork.value?.id,
        )
      _isFetchingRemoteServers.value = false
      result.fold(
        onSuccess = { summary ->
          val summaryMsg = summary.toBurmeseMessage()
          logStore.append(tag = "REMOTE", message = summaryMsg, level = LogLevel.SUCCESS)
          _remoteSyncFeedback.value = RemoteSyncFeedback(message = summaryMsg, isError = false)
          _snackbarMessage.value = summaryMsg
        },
        onFailure = { err ->
          val reason = err.message ?: "ဆွဲယူမရပါ"
          val errorMsg = "Remote JSON ဆွဲယူမရပါ — $reason"
          logStore.append(
            tag = "REMOTE",
            message = "Remote JSON ဆွဲယူမှု မအောင်မြင်ပါ: $reason",
            level = LogLevel.ERROR,
          )
          _remoteSyncFeedback.value = RemoteSyncFeedback(message = errorMsg, isError = true)
          _snackbarMessage.value = errorMsg
        },
      )
      return
    }

    val result = repository.fetchAndImportFromRemoteUrl(cleanUrl, replaceExisting)
    _isFetchingRemoteServers.value = false
    result.fold(
      onSuccess = { summary ->
        val summaryMsg = summary.toBurmeseMessage()
        logStore.append(tag = "REMOTE", message = summaryMsg, level = LogLevel.SUCCESS)
        _remoteSyncFeedback.value = RemoteSyncFeedback(message = summaryMsg, isError = false)
        _snackbarMessage.value = summaryMsg
      },
      onFailure = { err ->
        val reason = err.message ?: "ဆွဲယူမရပါ"
        val errorMsg = "Remote JSON ဆွဲယူမရပါ — $reason"
        logStore.append(
          tag = "REMOTE",
          message = "Remote JSON ဆွဲယူမှု မအောင်မြင်ပါ: $reason",
          level = LogLevel.ERROR,
        )
        _remoteSyncFeedback.value = RemoteSyncFeedback(message = errorMsg, isError = true)
        _snackbarMessage.value = errorMsg
      },
    )
  }

  fun fetchOwnerBanner(configUrl: String) {
    viewModelScope.launch {
      _isFetchingBanner.value = true
      logStore.append(
        tag = "BANNER",
        message =
          if (configUrl.isBlank()) "Remote Banner URL ရှင်းလင်းနေသည်"
          else "Remote Banner JSON ဆွဲယူနေသည်: $configUrl",
        level = LogLevel.INFO,
      )
      val result = membershipRepository.fetchOwnerBannerFromUrl(configUrl)
      _isFetchingBanner.value = false
      result.fold(
        onSuccess = { banner ->
          if (banner != null) {
            logStore.append(
              tag = "BANNER",
              message = "Owner Banner ရယူပြီး (${banner.linkUrl.ifBlank { banner.imageUrl }})",
              level = LogLevel.SUCCESS,
            )
            _snackbarMessage.value = "Owner Banner အသစ် ရယူပြီးပါပြီ"
          } else {
            logStore.append(
              tag = "BANNER",
              message = "Owner Banner မရှိပါ / ရှင်းလင်းပြီး",
              level = LogLevel.INFO,
            )
            _snackbarMessage.value = "Owner Banner ရှင်းလင်းပြီးပါပြီ"
          }
        },
        onFailure = { err ->
          val reason = err.message ?: "ဆွဲယူမရပါ"
          logStore.append(
            tag = "BANNER",
            message = "Owner Banner ဆွဲယူမှု မအောင်မြင်ပါ: $reason",
            level = LogLevel.ERROR,
          )
          _snackbarMessage.value = "Banner ဆွဲယူမရပါ — $reason"
        },
      )
    }
  }

  fun clearLogs() {
    logStore.clear()
    logStore.append(
      tag = "LOG",
      message = "မှတ်တမ်းများ ရှင်းလင်းပြီးပါပြီ",
      level = LogLevel.INFO,
    )
  }

  fun exportLogsText(): String = logStore.exportPlainText()

  fun notifyMessage(message: String) {
    _snackbarMessage.value = message
  }

  class Factory(
    private val repository: ServerProfileRepository,
    private val membershipRepository: MembershipRepository,
    private val vpnPreferencesRepository: VpnPreferencesRepository,
    private val vpnController: VpnEngineController,
    private val configCodec: ConfigFileCodec,
    private val logStore: AppLogStore,
    private val networkServerRepository: NetworkServerRepository? = null,
    private val redeemRepository: RedeemRepository? = null,
  ) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      return AmktViewModel(
        repository = repository,
        membershipRepository = membershipRepository,
        vpnPreferencesRepository = vpnPreferencesRepository,
        vpnController = vpnController,
        configCodec = configCodec,
        logStore = logStore,
        networkServerRepository = networkServerRepository,
        redeemRepository = redeemRepository,
      ) as T
    }
  }
}
