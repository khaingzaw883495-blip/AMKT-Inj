package com.example.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MoreTime
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.BuildConfig
import com.example.R
import com.example.ads.FreeTimeStatus
import com.example.data.NetworkConfigEntity
import com.example.data.ServerAccountEntity
import com.example.data.ServerPingChecker
import com.example.membership.OwnerBanner
import com.example.ui.RemoteSyncFeedback
import com.example.ui.ads.Adsterra320x50BannerSlot
import com.example.ui.ads.BottomSmartlinkBannerCard
import com.example.ui.theme.DisplayFontFamily
import com.example.ui.theme.MonoFontFamily
import com.example.vpn.AmktVpnService
import com.example.vpn.VpnTrafficStats
import com.example.vpn.VpnTunnelState
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
  networks: List<NetworkConfigEntity>,
  servers: List<ServerAccountEntity>,
  selectedNetwork: NetworkConfigEntity?,
  selectedServer: ServerAccountEntity?,
  vpnState: VpnTunnelState,
  trafficStats: VpnTrafficStats,
  ownerBanner: OwnerBanner? = null,
  onConnectClick: () -> Unit,
  onReconnectClick: () -> Unit,
  onSelectNetwork: (NetworkConfigEntity) -> Unit,
  onSelectServer: (ServerAccountEntity) -> Unit,
  onOpenNetworksClick: () -> Unit,
  onOpenServersClick: () -> Unit,
  onOpenSettingsClick: () -> Unit = {},
  onOpenRedeemClick: () -> Unit = {},
  isFetchingRemoteServers: Boolean = false,
  remoteSyncFeedback: RemoteSyncFeedback? = null,
  onRefreshServersClick: () -> Unit = {},
  onDismissRemoteSyncFeedback: () -> Unit = {},
  onOpenBannerLink: (String) -> Unit = {},
  onClearNetwork: (() -> Unit)? = null,
  showAdsterraBanners: Boolean = true,
  showOwnerBanner: Boolean = true,
  freeTimeStatus: FreeTimeStatus? = null,
  hasValidNoAdsPass: Boolean = false,
  hasActiveRedeemedServer: Boolean = false,
  nowEpochMsProvider: () -> Long = { System.currentTimeMillis() },
  onExtendTimeClick: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  var showNetworkSheet by remember { mutableStateOf(false) }
  var showServerSheet by remember { mutableStateOf(false) }

  if (showNetworkSheet) {
    NetworkPickerBottomSheet(
      networks = networks,
      selectedNetworkId = selectedNetwork?.id,
      onSelect = { net ->
        onSelectNetwork(net)
        showNetworkSheet = false
      },
      onManageAll = {
        showNetworkSheet = false
        onOpenNetworksClick()
      },
      onDismiss = { showNetworkSheet = false },
      onClearNetwork =
        onClearNetwork?.let { clearFn ->
          {
            clearFn()
            showNetworkSheet = false
          }
        },
    )
  }

  if (showServerSheet) {
    ServerPickerBottomSheet(
      servers = servers,
      selectedServerId = selectedServer?.id,
      onSelect = { srv ->
        onSelectServer(srv)
        showServerSheet = false
        onDismissRemoteSyncFeedback()
      },
      onManageAll = {
        showServerSheet = false
        onDismissRemoteSyncFeedback()
        onOpenServersClick()
      },
      onOpenRedeem = {
        showServerSheet = false
        onDismissRemoteSyncFeedback()
        onOpenRedeemClick()
      },
      onDismiss = {
        showServerSheet = false
        onDismissRemoteSyncFeedback()
      },
      isFetchingRemoteServers = isFetchingRemoteServers,
      remoteSyncFeedback = remoteSyncFeedback,
      onRefreshServers = onRefreshServersClick,
      onDismissSyncFeedback = onDismissRemoteSyncFeedback,
    )
  }

  // Live ticking VPN session duration
  var elapsedSeconds by remember(vpnState) { mutableLongStateOf(0L) }
  LaunchedEffect(vpnState) {
    val connectedSince = (vpnState as? VpnTunnelState.Connected)?.connectedAtMs
    if (connectedSince == null) {
      elapsedSeconds = 0L
    } else {
      while (true) {
        elapsedSeconds = ((System.currentTimeMillis() - connectedSince) / 1_000L).coerceAtLeast(0L)
        delay(1_000L)
      }
    }
  }

  val isSelectedActiveRedeemed =
    selectedServer?.isRedeemedServer() == true && !selectedServer.isExpired()
  val isPaidUser = hasValidNoAdsPass || hasActiveRedeemedServer || isSelectedActiveRedeemed

  // Live 1-second clock for the selected server's HH:MM:SS remaining-time / free-time countdown
  var nowEpochMs by remember { mutableLongStateOf(nowEpochMsProvider()) }
  val shouldTickClock =
    !selectedServer?.expiryDate.isNullOrBlank() ||
      (freeTimeStatus != null && freeTimeStatus.initialized && !isPaidUser)
  LaunchedEffect(shouldTickClock, selectedServer?.expiryDate, freeTimeStatus?.expiresAtMs) {
    nowEpochMs = nowEpochMsProvider()
    if (shouldTickClock) {
      while (true) {
        delay(1_000L)
        nowEpochMs = nowEpochMsProvider()
      }
    }
  }

  Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    LazyColumn(
      modifier = Modifier.fillMaxWidth().widthIn(max = 600.dp),
      contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      // 1. Top Header Bar: AMKT Injector branding left + version pill & Settings gear right
      item {
        HomeTopHeaderBar(onOpenSettingsClick = onOpenSettingsClick)
      }

      // 2. Centered circular AMKT emblem with dashed status halo
      item {
        AmktCenterEmblemRing(vpnState = vpnState)
      }

      // 3. Main stacked connection card: Network row, Server row, Remaining time row, Status + BIG CONNECT button
      item {
        UnifiedConnectionCard(
          selectedNetwork = selectedNetwork,
          selectedServer = selectedServer,
          vpnState = vpnState,
          nowEpochMs = nowEpochMs,
          freeTimeStatus = freeTimeStatus,
          hasValidNoAdsPass = hasValidNoAdsPass,
          hasActiveRedeemedServer = hasActiveRedeemedServer,
          onOpenNetworkPicker = { showNetworkSheet = true },
          onOpenServerPicker = { showServerSheet = true },
          onExtendTimeClick = onExtendTimeClick,
          onConnectClick = onConnectClick,
        )
      }

      // 4. Read-only Duration & Data Used pills directly beneath the main card
      item {
        CompactStatsPillsRow(
          elapsedSeconds = elapsedSeconds,
          trafficStats = trafficStats,
        )
      }

      // 5. Single small "ကုတ်ထည့်ရန်" (Enter Code) button below the pills
      item {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
          OutlinedButton(
            onClick = onOpenRedeemClick,
            shape = RoundedCornerShape(999.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.7f)),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
            modifier = Modifier.testTag("home_redeem_button"),
          ) {
            Icon(
              imageVector = Icons.Default.ConfirmationNumber,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.secondary,
              modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
              text = stringResource(R.string.redeem_button_short),
              style = MaterialTheme.typography.labelLarge,
              fontWeight = FontWeight.SemiBold,
              color = MaterialTheme.colorScheme.secondary,
            )
          }
        }
      }

      // 6. Contextual alerts (only shown when active / needed)
      if (selectedServer?.isExpired() == true) {
        item {
          CompactAlertCard(
            icon = Icons.Default.WarningAmber,
            title = stringResource(R.string.label_expired),
            message = stringResource(R.string.expired_server_home_warning),
            isError = true,
          )
        }
      }

      if (vpnState is VpnTunnelState.KillSwitchBlocking) {
        item {
          CompactAlertCard(
            icon = Icons.Default.Block,
            title = stringResource(R.string.status_kill_switch_blocking),
            message = vpnState.reason.ifBlank { stringResource(R.string.vpn_kill_switch_blocked_msg) },
            isError = true,
            actionLabel = stringResource(R.string.action_reconnect),
            onAction = onReconnectClick,
          )
        }
      } else if (vpnState is VpnTunnelState.Reconnecting) {
        item {
          CompactAlertCard(
            icon = Icons.Default.Autorenew,
            title = stringResource(R.string.status_reconnecting),
            message = "အကြိမ် ${vpnState.attempt} — ${vpnState.lastReason}",
            isError = false,
          )
        }
      } else if (vpnState is VpnTunnelState.Error) {
        item {
          CompactAlertCard(
            icon = Icons.Default.ErrorOutline,
            title = stringResource(R.string.status_error),
            message = vpnState.message,
            isError = true,
            actionLabel = stringResource(R.string.action_reconnect),
            onAction = onReconnectClick,
          )
        }
      }

      // 7. Owner Announcement Banner (removed completely for active INJ-code users)
      if (showOwnerBanner) {
        item {
          OwnerBannerCard(
            banner = ownerBanner,
            onOpenLink = {
              val targetUrl = ownerBanner?.effectiveLinkUrl() ?: OwnerBanner.DEFAULT_TELEGRAM_URL
              onOpenBannerLink(targetUrl)
            },
          )
        }
      }

      // 8. Bottom Adsterra Smartlink_2 banner + 320x50 banner (removed completely when paid/no-ads)
      if (showAdsterraBanners) {
        item {
          BottomSmartlinkBannerCard(
            onOpenSmartlink = { url -> onOpenBannerLink(url) },
          )
        }
        item {
          Adsterra320x50BannerSlot(
            onOpenExternalUrl = { url -> onOpenBannerLink(url) },
          )
        }
      }
    }
  }
}

@Composable
private fun HomeTopHeaderBar(
  onOpenSettingsClick: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Text(
      text =
        buildAnnotatedString {
          withStyle(
            SpanStyle(
              fontWeight = FontWeight.ExtraBold,
              color = MaterialTheme.colorScheme.onBackground,
            )
          ) {
            append("AMKT ")
          }
          withStyle(
            SpanStyle(
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.primary,
            )
          ) {
            append("Injector")
          }
        },
      style = MaterialTheme.typography.titleLarge,
      fontFamily = DisplayFontFamily,
      modifier = Modifier.testTag("home_brand_title"),
    )

    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
      ) {
        Text(
          text = "v${BuildConfig.VERSION_NAME}",
          style = MaterialTheme.typography.labelSmall,
          fontFamily = MonoFontFamily,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
      }
      IconButton(
        onClick = onOpenSettingsClick,
        modifier = Modifier.size(48.dp).testTag("home_settings_button"),
      ) {
        Icon(
          imageVector = Icons.Default.Settings,
          contentDescription = stringResource(R.string.cd_settings),
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun AmktCenterEmblemRing(vpnState: VpnTunnelState) {
  val ringColor =
    when (vpnState) {
      is VpnTunnelState.Connected -> MaterialTheme.colorScheme.primary
      is VpnTunnelState.Connecting,
      is VpnTunnelState.Reconnecting -> MaterialTheme.colorScheme.tertiary
      is VpnTunnelState.KillSwitchBlocking,
      is VpnTunnelState.Error -> MaterialTheme.colorScheme.error
      is VpnTunnelState.Disconnected -> MaterialTheme.colorScheme.outline
    }

  Box(
    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    contentAlignment = Alignment.Center,
  ) {
    Box(
      modifier = Modifier.size(136.dp),
      contentAlignment = Alignment.Center,
    ) {
      Canvas(modifier = Modifier.fillMaxSize()) {
        val outerStroke =
          Stroke(
            width = 2.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f)),
          )
        drawCircle(
          color = ringColor.copy(alpha = 0.5f),
          radius = size.minDimension / 2f - 2.dp.toPx(),
          style = outerStroke,
        )
        drawCircle(
          color = ringColor.copy(alpha = 0.14f),
          radius = size.minDimension / 2f - 10.dp.toPx(),
        )
        drawCircle(
          color = ringColor.copy(alpha = 0.78f),
          radius = size.minDimension / 2f - 10.dp.toPx(),
          style = Stroke(width = 2.dp.toPx()),
        )
      }
      Image(
        painter = painterResource(id = R.drawable.app_icon_amkt_1795171749485),
        contentDescription = stringResource(R.string.app_name),
        contentScale = ContentScale.Crop,
        modifier = Modifier.size(92.dp).clip(CircleShape),
      )
    }
  }
}

@Composable
private fun UnifiedConnectionCard(
  selectedNetwork: NetworkConfigEntity?,
  selectedServer: ServerAccountEntity?,
  vpnState: VpnTunnelState,
  nowEpochMs: Long,
  freeTimeStatus: FreeTimeStatus?,
  hasValidNoAdsPass: Boolean,
  hasActiveRedeemedServer: Boolean,
  onOpenNetworkPicker: () -> Unit,
  onOpenServerPicker: () -> Unit,
  onExtendTimeClick: () -> Unit,
  onConnectClick: () -> Unit,
) {
  val isConnecting = vpnState is VpnTunnelState.Connecting || vpnState is VpnTunnelState.Reconnecting
  val isActive =
    vpnState is VpnTunnelState.Connected ||
      vpnState is VpnTunnelState.Connecting ||
      vpnState is VpnTunnelState.Reconnecting ||
      vpnState is VpnTunnelState.KillSwitchBlocking

  val statusDotColor =
    when (vpnState) {
      is VpnTunnelState.Connected -> MaterialTheme.colorScheme.primary
      is VpnTunnelState.Connecting,
      is VpnTunnelState.Reconnecting -> MaterialTheme.colorScheme.tertiary
      is VpnTunnelState.KillSwitchBlocking,
      is VpnTunnelState.Error -> MaterialTheme.colorScheme.error
      is VpnTunnelState.Disconnected -> MaterialTheme.colorScheme.outline
    }

  val statusLabel =
    when (vpnState) {
      is VpnTunnelState.Connected -> stringResource(R.string.home_status_connected)
      is VpnTunnelState.Connecting -> stringResource(R.string.home_status_connecting)
      is VpnTunnelState.Reconnecting -> stringResource(R.string.home_status_reconnecting)
      is VpnTunnelState.KillSwitchBlocking -> stringResource(R.string.home_status_kill_switch)
      is VpnTunnelState.Error -> stringResource(R.string.home_status_error)
      is VpnTunnelState.Disconnected -> stringResource(R.string.home_status_disconnected)
    }

  val serverFlag =
    remember(selectedServer?.region, selectedServer?.name) {
      ServerPingChecker.resolveServerFlagEmoji(selectedServer)
    }
  val (serverExpirySubtitle, isServerExpired) =
    remember(selectedServer?.expiryDate) {
      ServerPingChecker.formatRemainingExpiry(selectedServer?.expiryDate.orEmpty())
    }
  val (expiryCountdownHms, isExpiryCountdownExpired) =
    remember(selectedServer?.expiryDate, nowEpochMs) {
      ServerPingChecker.formatExpiryCountdownHms(
        expiryDate = selectedServer?.expiryDate.orEmpty(),
        nowMs = nowEpochMs,
      )
    }

  val isPaidOrActiveRedeemed =
    hasValidNoAdsPass ||
      hasActiveRedeemedServer ||
      (selectedServer?.isRedeemedServer() == true && !selectedServer.isExpired())
  val activeFreeTime =
    if (freeTimeStatus != null && !isPaidOrActiveRedeemed && selectedServer != null) {
      freeTimeStatus
    } else {
      null
    }

  val resolvedServerSubtitle = activeFreeTime?.formatSummaryLine(nowEpochMs) ?: serverExpirySubtitle
  val isSubtitleError = activeFreeTime?.isExpired(nowEpochMs) ?: isServerExpired
  val countdownHms = activeFreeTime?.formatRemainingHms(nowEpochMs) ?: expiryCountdownHms
  val isCountdownExpired = activeFreeTime?.isExpired(nowEpochMs) ?: isExpiryCountdownExpired

  Card(
    shape = RoundedCornerShape(24.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(modifier = Modifier.fillMaxWidth()) {
      // Row 1: Network selector (Globe icon + "Network" + selected name + short subtitle)
      Row(
        modifier =
          Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenNetworkPicker)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .testTag("home_network_row"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Surface(
          shape = RoundedCornerShape(12.dp),
          color = MaterialTheme.colorScheme.primaryContainer,
          modifier = Modifier.size(44.dp),
        ) {
          Box(contentAlignment = Alignment.Center) {
            Icon(
              imageVector = Icons.Default.Public,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.onPrimaryContainer,
              modifier = Modifier.size(22.dp),
            )
          }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(
            text = stringResource(R.string.home_network_label),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            text = selectedNetwork?.name ?: stringResource(R.string.status_no_network),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = stringResource(R.string.home_network_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        Icon(
          imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

      // Row 2: Server selector (Country flag + "Server" + selected name + expiry/remaining line)
      Row(
        modifier =
          Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenServerPicker)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .testTag("home_server_row"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Surface(
          shape = RoundedCornerShape(12.dp),
          color = MaterialTheme.colorScheme.surfaceVariant,
          modifier = Modifier.size(44.dp).testTag("home_server_flag"),
        ) {
          Box(contentAlignment = Alignment.Center) {
            Text(text = serverFlag, fontSize = 22.sp)
          }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Text(
              text = stringResource(R.string.home_server_label),
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (selectedServer != null) {
              Text(
                text = selectedServer.formatOnlineLimitLabel(),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = MonoFontFamily,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("home_server_online_limit_line"),
              )
            }
          }
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Text(
              text = selectedServer?.name ?: stringResource(R.string.status_no_server),
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.weight(1f, fill = false),
            )
            if (selectedServer?.isOpenVpnMode() == true) {
              Surface(
                shape = RoundedCornerShape(999.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.testTag("home_server_openvpn_badge"),
              ) {
                Text(
                  text = stringResource(R.string.badge_openvpn),
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSecondaryContainer,
                  fontWeight = FontWeight.SemiBold,
                  modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
              }
            }
          }
          if (selectedServer != null) {
            Text(
              text = resolvedServerSubtitle,
              style = MaterialTheme.typography.bodySmall,
              color =
                if (isSubtitleError) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.testTag("home_server_expiry_line"),
            )
          }
        }
        Icon(
          imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

      // Row 3: Remaining time row (Clock icon + "Remaining time" + HH:MM:SS countdown + Extend Time button)
      val showExtendTimeButton = freeTimeStatus != null && !isPaidOrActiveRedeemed
      val canExtendToday = freeTimeStatus?.canWatchAdForGrant() == true
      val remainingGrantsToday = freeTimeStatus?.remainingGrantsToday() ?: 0

      Column(modifier = Modifier.fillMaxWidth()) {
        Row(
          modifier =
            Modifier
              .fillMaxWidth()
              .padding(horizontal = 16.dp, vertical = 12.dp)
              .testTag("home_remaining_time_row"),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.size(44.dp),
          ) {
            Box(contentAlignment = Alignment.Center) {
              Icon(
                imageVector = Icons.Default.Schedule,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(22.dp),
              )
            }
          }
          Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
              text = stringResource(R.string.home_remaining_time_label),
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
              text = countdownHms,
              style = MaterialTheme.typography.titleLarge,
              fontFamily = MonoFontFamily,
              fontWeight = FontWeight.Bold,
              color =
                if (isCountdownExpired || isServerExpired) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.testTag("home_remaining_time_value"),
            )
          }
          if (showExtendTimeButton) {
            FilledTonalButton(
              onClick = onExtendTimeClick,
              enabled = canExtendToday,
              shape = RoundedCornerShape(14.dp),
              contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
              colors =
                ButtonDefaults.filledTonalButtonColors(
                  containerColor = MaterialTheme.colorScheme.primaryContainer,
                  contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                  disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                  disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                ),
              modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag("home_extend_time_button"),
            ) {
              Icon(
                imageVector = Icons.Default.MoreTime,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
              )
              Spacer(Modifier.width(6.dp))
              Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                  text = stringResource(R.string.home_extend_time_cta),
                  style = MaterialTheme.typography.labelMedium,
                  fontWeight = FontWeight.Bold,
                  maxLines = 1,
                )
                Text(
                  text = stringResource(R.string.home_extend_grants_left_format, remainingGrantsToday),
                  style = MaterialTheme.typography.labelSmall,
                  maxLines = 1,
                  modifier = Modifier.testTag("home_extend_grants_left_text"),
                )
              }
            }
          }
        }
        if (showExtendTimeButton && !canExtendToday) {
          Text(
            text = stringResource(R.string.home_extend_limit_used_message),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            modifier =
              Modifier
                .padding(start = 16.dp, end = 16.dp, bottom = 10.dp)
                .testTag("home_extend_limit_message"),
          )
        }
      }

      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

      // Row 4: Status line + BIG full-width gradient CONNECT / DISCONNECT button
      Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          modifier = Modifier.testTag("home_status_line"),
        ) {
          Box(
            modifier = Modifier.size(10.dp).clip(CircleShape).background(statusDotColor)
          )
          Text(
            text = "AMKT Injector : $statusLabel",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }

        val buttonBrush =
          if (isActive) {
            Brush.horizontalGradient(
              listOf(
                MaterialTheme.colorScheme.error,
                MaterialTheme.colorScheme.error.copy(alpha = 0.82f),
              )
            )
          } else {
            Brush.horizontalGradient(
              listOf(
                MaterialTheme.colorScheme.tertiary,
                MaterialTheme.colorScheme.primary,
                MaterialTheme.colorScheme.secondary,
              )
            )
          }
        val buttonContentColor =
          if (isActive) MaterialTheme.colorScheme.onError
          else MaterialTheme.colorScheme.onPrimary

        Box(
          modifier =
            Modifier
              .fillMaxWidth()
              .height(56.dp)
              .clip(RoundedCornerShape(16.dp))
              .background(buttonBrush)
              .clickable(onClick = onConnectClick)
              .testTag("connect_toggle_button"),
          contentAlignment = Alignment.Center,
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            if (isConnecting) {
              CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.2.dp,
                color = buttonContentColor,
              )
            }
            Text(
              text =
                when {
                  isConnecting -> stringResource(R.string.home_connecting_cta)
                  isActive -> stringResource(R.string.home_disconnect_cta)
                  else -> stringResource(R.string.home_connect_cta)
                },
              style = MaterialTheme.typography.titleMedium,
              fontFamily = DisplayFontFamily,
              fontWeight = FontWeight.ExtraBold,
              letterSpacing = 1.2.sp,
              color = buttonContentColor,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun CompactStatsPillsRow(
  elapsedSeconds: Long,
  trafficStats: VpnTrafficStats,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Surface(
      shape = RoundedCornerShape(999.dp),
      color = MaterialTheme.colorScheme.surface,
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
      modifier = Modifier.testTag("home_duration_pill"),
    ) {
      Row(
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Icon(
          imageVector = Icons.Default.Schedule,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.size(15.dp),
        )
        Text(
          text = "${stringResource(R.string.home_duration_prefix)} ${formatHms(elapsedSeconds)}",
          style = MaterialTheme.typography.labelSmall,
          fontFamily = MonoFontFamily,
          color = MaterialTheme.colorScheme.onSurface,
        )
      }
    }

    Surface(
      shape = RoundedCornerShape(999.dp),
      color = MaterialTheme.colorScheme.surface,
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
      modifier = Modifier.testTag("home_data_usage_pill"),
    ) {
      Row(
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Icon(
          imageVector = Icons.Default.SwapVert,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.size(15.dp),
        )
        Text(
          text =
            "↓ ${AmktVpnService.formatBytes(trafficStats.bytesDown)} · ↑ ${AmktVpnService.formatBytes(trafficStats.bytesUp)}",
          style = MaterialTheme.typography.labelSmall,
          fontFamily = MonoFontFamily,
          color = MaterialTheme.colorScheme.onSurface,
        )
      }
    }
  }
}

@Composable
private fun CompactAlertCard(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  title: String,
  message: String,
  isError: Boolean,
  actionLabel: String? = null,
  onAction: (() -> Unit)? = null,
) {
  val container =
    if (isError) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
    else MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
  val border =
    if (isError) MaterialTheme.colorScheme.error
    else MaterialTheme.colorScheme.secondary
  val content =
    if (isError) MaterialTheme.colorScheme.onErrorContainer
    else MaterialTheme.colorScheme.onSecondaryContainer

  Card(
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = container),
    border = BorderStroke(1.dp, border),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(
      modifier = Modifier.padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Icon(imageVector = icon, contentDescription = null, tint = border, modifier = Modifier.size(18.dp))
        Text(text = title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = content)
      }
      Text(text = message, style = MaterialTheme.typography.bodySmall, color = content)
      if (actionLabel != null && onAction != null) {
        TextButton(onClick = onAction, contentPadding = PaddingValues(0.dp)) {
          Text(text = actionLabel, color = border, fontWeight = FontWeight.Bold)
        }
      }
    }
  }
}

@Composable
private fun OwnerBannerCard(
  banner: OwnerBanner?,
  onOpenLink: () -> Unit,
) {
  val remoteImageUrl = banner?.let { OwnerBanner.sanitizeHttpUrl(it.imageUrl) }.orEmpty()
  var remoteImageFailed by remember(remoteImageUrl) { mutableStateOf(false) }
  val showRemoteImage = remoteImageUrl.isNotBlank() && !remoteImageFailed
  val fallbackTitle = stringResource(R.string.owner_banner_fallback_title)
  val fallbackSubtitle = stringResource(R.string.owner_banner_fallback_subtitle)
  val resolvedTitle = banner?.title?.takeIf { it.isNotBlank() } ?: fallbackTitle
  val resolvedSubtitle =
    banner?.subtitle?.takeIf { it.isNotBlank() }
      ?: banner?.effectiveLinkUrl()?.takeIf { banner.linkUrl.isNotBlank() }
      ?: fallbackSubtitle

  Card(
    shape = RoundedCornerShape(18.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
    modifier =
      Modifier
        .fillMaxWidth()
        .clickable(onClick = onOpenLink)
        .testTag("home_owner_banner_card"),
  ) {
    Column {
      if (showRemoteImage) {
        AsyncImage(
          model = remoteImageUrl,
          contentDescription = stringResource(R.string.cd_owner_banner),
          contentScale = ContentScale.Crop,
          onError = { remoteImageFailed = true },
          modifier =
            Modifier
              .fillMaxWidth()
              .height(104.dp)
              .testTag("home_owner_banner_remote_image"),
        )
        if (!banner?.title.isNullOrBlank() || !banner?.linkUrl.isNullOrBlank()) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Text(
              text = banner.title.ifBlank { banner.effectiveLinkUrl() },
              style = MaterialTheme.typography.bodySmall,
              fontWeight = FontWeight.Medium,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.weight(1f),
            )
            Icon(
              imageVector = Icons.AutoMirrored.Filled.OpenInNew,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(16.dp),
            )
          }
        }
      } else {
        // Built-in fallback card (never displays a broken image) linking to AMKT Telegram or remote link
        Row(
          modifier =
            Modifier
              .fillMaxWidth()
              .padding(horizontal = 14.dp, vertical = 12.dp)
              .testTag("home_owner_banner_fallback"),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Image(
            painter = painterResource(id = R.drawable.app_icon_amkt_1795171749485),
            contentDescription = stringResource(R.string.cd_owner_banner),
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)),
          )
          Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
              text = resolvedTitle,
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onSurface,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            Text(
              text = resolvedSubtitle,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
          Icon(
            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
          )
        }
      }
    }
  }
}

private fun formatHms(totalSeconds: Long): String {
  val hours = totalSeconds / 3_600L
  val mins = (totalSeconds % 3_600L) / 60L
  val secs = totalSeconds % 60L
  return "%02d:%02d:%02d".format(hours, mins, secs)
}
