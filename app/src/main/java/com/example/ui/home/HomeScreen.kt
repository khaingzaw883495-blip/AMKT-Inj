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
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import coil.compose.AsyncImage
import com.example.BuildConfig
import com.example.R
import com.example.data.LogEntry
import com.example.data.LogLevel
import com.example.data.NetworkConfigEntity
import com.example.data.ServerAccountEntity
import com.example.data.ServerPingChecker
import com.example.membership.OwnerBanner
import com.example.membership.PassStatus
import com.example.ui.RemoteSyncFeedback
import com.example.ui.theme.DisplayFontFamily
import com.example.ui.theme.MonoFontFamily
import com.example.vpn.AmktVpnService
import com.example.vpn.VpnProtectionSettings
import com.example.vpn.VpnTrafficStats
import com.example.vpn.VpnTunnelState
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
  networks: List<NetworkConfigEntity>,
  servers: List<ServerAccountEntity>,
  selectedNetwork: NetworkConfigEntity?,
  selectedServer: ServerAccountEntity?,
  recentLogs: List<LogEntry>,
  vpnState: VpnTunnelState,
  trafficStats: VpnTrafficStats,
  vpnSettings: VpnProtectionSettings,
  passStatus: PassStatus,
  ownerBanner: OwnerBanner?,
  isValidatingCode: Boolean,
  isDarkTheme: Boolean,
  onToggleTheme: () -> Unit,
  onConnectClick: () -> Unit,
  onReconnectClick: () -> Unit,
  onToggleKillSwitch: (Boolean) -> Unit,
  onToggleAutoReconnect: (Boolean) -> Unit,
  onSelectNetwork: (NetworkConfigEntity) -> Unit,
  onSelectServer: (ServerAccountEntity) -> Unit,
  onImportClick: () -> Unit,
  onOpenNetworksClick: () -> Unit,
  onOpenServersClick: () -> Unit,
  onOpenLogsClick: () -> Unit,
  onOpenRedeemClick: () -> Unit = {},
  isFetchingRemoteServers: Boolean = false,
  remoteSyncFeedback: RemoteSyncFeedback? = null,
  onRefreshServersClick: (onMissingUrl: () -> Unit) -> Unit = {},
  onDismissRemoteSyncFeedback: () -> Unit = {},
  onValidateCode: (String) -> Unit,
  onClearCode: () -> Unit,
  onOpenBannerLink: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  var showNetworkSheet by remember { mutableStateOf(false) }
  var showServerSheet by remember { mutableStateOf(false) }
  var showPassDialog by remember { mutableStateOf(false) }

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
      onRefreshServers = {
        onRefreshServersClick {
          showServerSheet = false
        }
      },
      onDismissSyncFeedback = onDismissRemoteSyncFeedback,
    )
  }

  if (showPassDialog) {
    HomeAccessCodeDialog(
      passStatus = passStatus,
      isValidating = isValidatingCode,
      onDismiss = { if (!isValidatingCode) showPassDialog = false },
      onSubmitCode = onValidateCode,
      onClearCode = {
        onClearCode()
        showPassDialog = false
      },
    )
  }

  // Live ticking duration when VPN is connected
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

  Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    LazyColumn(
      modifier = Modifier.fillMaxWidth().widthIn(max = 600.dp),
      contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      // 1. Minimal airy top header bar (AMKT branding left + version pill & theme toggle right)
      item {
        HomeTopHeaderBar(
          isDarkTheme = isDarkTheme,
          onToggleTheme = onToggleTheme,
        )
      }

      // 2. Centered circular AMKT emblem with live connection status ring
      item {
        AmktCenterEmblemRing(vpnState = vpnState)
      }

      // 3. Unified clean main connection card (Network row, Server row, Expiry + Redeem row, Connect CTA)
      item {
        UnifiedConnectionCard(
          selectedNetwork = selectedNetwork,
          selectedServer = selectedServer,
          vpnState = vpnState,
          isFetchingRemoteServers = isFetchingRemoteServers,
          onOpenNetworkPicker = { showNetworkSheet = true },
          onOpenServerPicker = { showServerSheet = true },
          onRefreshServers = { onRefreshServersClick {} },
          onOpenRedeem = onOpenRedeemClick,
          onConnectClick = onConnectClick,
        )
      }

      // 4. Compact horizontal status pills (Duration + Data RX/TX) directly beneath the main card
      item {
        CompactStatsPillsRow(
          elapsedSeconds = elapsedSeconds,
          trafficStats = trafficStats,
        )
      }

      // 5. Contextual alerts (only shown when active / needed)
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

      // 6. Owner Announcement Banner (only when configured)
      if (ownerBanner != null) {
        item {
          OwnerBannerCard(
            banner = ownerBanner,
            onOpenLink = {
              if (ownerBanner.linkUrl.isNotBlank()) onOpenBannerLink(ownerBanner.linkUrl)
            },
          )
        }
      }

      // 7. Compact Quick Actions strip (.myvpn File Import / Access Code)
      item {
        QuickUtilityRow(
          onImportClick = onImportClick,
          onPassCodeClick = { showPassDialog = true },
        )
      }

      // 8. VPN Protection toggles (Kill Switch + Auto-Reconnect)
      item {
        VpnProtectionCompactCard(
          settings = vpnSettings,
          onToggleKillSwitch = onToggleKillSwitch,
          onToggleAutoReconnect = onToggleAutoReconnect,
        )
      }

      // 9. Compact Recent Logs preview
      item {
        RecentLogsPreviewCard(
          recentLogs = recentLogs.takeLast(4).reversed(),
          onOpenLogs = onOpenLogsClick,
        )
      }
    }
  }
}

@Composable
private fun HomeTopHeaderBar(
  isDarkTheme: Boolean,
  onToggleTheme: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Text(
      text =
        buildAnnotatedString {
          withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onBackground)) {
            append("AMKT ")
          }
          withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)) {
            append("Injector")
          }
        },
      style = MaterialTheme.typography.titleLarge,
      fontFamily = DisplayFontFamily,
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
        onClick = onToggleTheme,
        modifier = Modifier.size(40.dp).testTag("theme_toggle_button"),
      ) {
        Icon(
          imageVector = if (isDarkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
          contentDescription = "Theme",
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
      modifier = Modifier.size(132.dp),
      contentAlignment = Alignment.Center,
    ) {
      Canvas(modifier = Modifier.fillMaxSize()) {
        val outerStroke = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f)))
        drawCircle(color = ringColor.copy(alpha = 0.45f), radius = size.minDimension / 2f - 2.dp.toPx(), style = outerStroke)
        drawCircle(
          color = ringColor.copy(alpha = 0.14f),
          radius = size.minDimension / 2f - 10.dp.toPx(),
        )
        drawCircle(
          color = ringColor.copy(alpha = 0.75f),
          radius = size.minDimension / 2f - 10.dp.toPx(),
          style = Stroke(width = 2.dp.toPx()),
        )
      }
      Image(
        painter = painterResource(id = R.drawable.app_icon_amkt_1795171749485),
        contentDescription = stringResource(R.string.app_name),
        contentScale = ContentScale.Crop,
        modifier = Modifier.size(88.dp).clip(CircleShape),
      )
    }
  }
}

@Composable
private fun UnifiedConnectionCard(
  selectedNetwork: NetworkConfigEntity?,
  selectedServer: ServerAccountEntity?,
  vpnState: VpnTunnelState,
  isFetchingRemoteServers: Boolean,
  onOpenNetworkPicker: () -> Unit,
  onOpenServerPicker: () -> Unit,
  onRefreshServers: () -> Unit,
  onOpenRedeem: () -> Unit,
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
      is VpnTunnelState.Connected -> stringResource(R.string.status_connected)
      is VpnTunnelState.Connecting -> stringResource(R.string.status_connecting)
      is VpnTunnelState.Reconnecting -> stringResource(R.string.status_reconnecting)
      is VpnTunnelState.KillSwitchBlocking -> stringResource(R.string.status_kill_switch_blocking)
      is VpnTunnelState.Error -> stringResource(R.string.status_error)
      is VpnTunnelState.Disconnected -> stringResource(R.string.status_disconnected)
    }

  val (expiryText, isServerExpired) =
    remember(selectedServer?.expiryDate) {
      ServerPingChecker.formatRemainingExpiry(selectedServer?.expiryDate.orEmpty())
    }

  Card(
    shape = RoundedCornerShape(24.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(modifier = Modifier.fillMaxWidth()) {
      // Row 1: Network selector
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
            text = stringResource(R.string.status_network_selected),
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
        }
        Icon(
          imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

      // Row 2: Server selector (NAME ONLY + Refresh/Update action)
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
          color = MaterialTheme.colorScheme.tertiaryContainer,
          modifier = Modifier.size(44.dp),
        ) {
          Box(contentAlignment = Alignment.Center) {
            Icon(
              imageVector = Icons.Default.Dns,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.onTertiaryContainer,
              modifier = Modifier.size(22.dp),
            )
          }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(
            text = stringResource(R.string.status_server_selected),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            text = selectedServer?.name ?: stringResource(R.string.status_no_server),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
          IconButton(
            onClick = onRefreshServers,
            enabled = !isFetchingRemoteServers,
            modifier = Modifier.testTag("home_refresh_servers_button"),
          ) {
            if (isFetchingRemoteServers) {
              CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
              )
            } else {
              Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = stringResource(R.string.cd_refresh_servers),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
              )
            }
          }
          Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

      // Row 3: Remaining Time / Expiry + Redeem Code ("ကုတ်ထည့်ရန်") pill button
      Row(
        modifier =
          Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .testTag("home_redeem_row"),
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
            text = expiryText,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color =
              if (isServerExpired) MaterialTheme.colorScheme.error
              else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("home_remaining_time_value"),
          )
        }
        OutlinedButton(
          onClick = onOpenRedeem,
          shape = RoundedCornerShape(12.dp),
          border = BorderStroke(1.2.dp, MaterialTheme.colorScheme.secondary),
          contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
          modifier = Modifier.testTag("home_redeem_button"),
        ) {
          Text(
            text = stringResource(R.string.redeem_button_short),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.secondary,
          )
        }
      }

      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

      // Row 4: Live status line + Full-width prominent Connect / Disconnect pill button
      Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
              )
            )
          } else {
            Brush.horizontalGradient(
              listOf(
                MaterialTheme.colorScheme.primary,
                MaterialTheme.colorScheme.tertiary,
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
              .height(54.dp)
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
                  isConnecting -> stringResource(R.string.action_connecting)
                  isActive -> stringResource(R.string.action_disconnect)
                  else -> stringResource(R.string.action_connect)
                },
              style = MaterialTheme.typography.titleMedium,
              fontFamily = DisplayFontFamily,
              fontWeight = FontWeight.Bold,
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
          text = "${stringResource(R.string.vpn_duration_label)} ${formatHms(elapsedSeconds)}",
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
private fun QuickUtilityRow(
  onImportClick: () -> Unit,
  onPassCodeClick: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    UtilityActionChip(
      icon = Icons.Default.FileDownload,
      label = "Import .myvpn",
      onClick = onImportClick,
      modifier = Modifier.weight(1f).testTag("quick_import_button"),
    )
    UtilityActionChip(
      icon = Icons.Default.Key,
      label = "Pass",
      onClick = onPassCodeClick,
      modifier = Modifier.weight(1f).testTag("quick_pass_button"),
    )
  }
}

@Composable
private fun UtilityActionChip(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  label: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
) {
  Surface(
    shape = RoundedCornerShape(14.dp),
    color = MaterialTheme.colorScheme.surface,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    modifier = modifier.clickable(enabled = enabled, onClick = onClick),
  ) {
    Column(
      modifier = Modifier.padding(vertical = 10.dp, horizontal = 6.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Icon(
        imageVector = icon,
        contentDescription = label,
        tint =
          if (enabled) MaterialTheme.colorScheme.primary
          else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.size(20.dp),
      )
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color =
          if (enabled) MaterialTheme.colorScheme.onSurface
          else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
      )
    }
  }
}

@Composable
private fun VpnProtectionCompactCard(
  settings: VpnProtectionSettings,
  onToggleKillSwitch: (Boolean) -> Unit,
  onToggleAutoReconnect: (Boolean) -> Unit,
) {
  Card(
    shape = RoundedCornerShape(18.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Icon(
          imageVector = Icons.Default.Security,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(18.dp),
        )
        Text(
          text = stringResource(R.string.vpn_protection_title),
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.Bold,
        )
      }
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
          Text(text = stringResource(R.string.vpn_kill_switch_title), style = MaterialTheme.typography.bodyMedium)
          Text(
            text = stringResource(R.string.vpn_kill_switch_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Switch(
          checked = settings.killSwitchEnabled,
          onCheckedChange = onToggleKillSwitch,
          modifier = Modifier.testTag("kill_switch_toggle"),
        )
      }
      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
          Text(text = stringResource(R.string.vpn_auto_reconnect_title), style = MaterialTheme.typography.bodyMedium)
          Text(
            text = stringResource(R.string.vpn_auto_reconnect_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Switch(
          checked = settings.autoReconnectEnabled,
          onCheckedChange = onToggleAutoReconnect,
          modifier = Modifier.testTag("auto_reconnect_toggle"),
        )
      }
    }
  }
}

@Composable
private fun OwnerBannerCard(
  banner: OwnerBanner,
  onOpenLink: () -> Unit,
) {
  Card(
    shape = RoundedCornerShape(18.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
    modifier = Modifier.fillMaxWidth().clickable(enabled = banner.linkUrl.isNotBlank(), onClick = onOpenLink),
  ) {
    Column {
      if (banner.imageUrl.isNotBlank()) {
        AsyncImage(
          model = banner.imageUrl,
          contentDescription = stringResource(R.string.cd_owner_banner),
          contentScale = ContentScale.Crop,
          modifier = Modifier.fillMaxWidth().height(110.dp),
        )
      }
      if (banner.title.isNotBlank() || banner.linkUrl.isNotBlank()) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Text(
            text = banner.title.ifBlank { banner.linkUrl },
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
          )
          if (banner.linkUrl.isNotBlank()) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.OpenInNew,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(16.dp),
            )
          }
        }
      }
    }
  }
}

@Composable
private fun RecentLogsPreviewCard(
  recentLogs: List<LogEntry>,
  onOpenLogs: () -> Unit,
) {
  Card(
    shape = RoundedCornerShape(18.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Icon(
            imageVector = Icons.Default.Terminal,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
          )
          Text(
            text = stringResource(R.string.logs_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
          )
        }
        TextButton(
          onClick = onOpenLogs,
          contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
          modifier = Modifier.testTag("home_open_logs_button"),
        ) {
          Text(stringResource(R.string.nav_logs))
        }
      }
      if (recentLogs.isEmpty()) {
        Text(
          text = stringResource(R.string.logs_empty),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      } else {
        recentLogs.forEach { entry ->
          val levelColor =
            when (entry.level) {
              LogLevel.SUCCESS -> MaterialTheme.colorScheme.primary
              LogLevel.WARNING -> MaterialTheme.colorScheme.secondary
              LogLevel.ERROR -> MaterialTheme.colorScheme.error
              LogLevel.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
            }
          Text(
            text = "[${entry.tag}] ${entry.message}",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = MonoFontFamily,
            color = levelColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
    }
  }
}

@Composable
private fun HomeAccessCodeDialog(
  passStatus: PassStatus,
  isValidating: Boolean,
  onDismiss: () -> Unit,
  onSubmitCode: (String) -> Unit,
  onClearCode: () -> Unit,
) {
  var codeInput by remember(passStatus.code) { mutableStateOf(passStatus.code) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.pass_dialog_title)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(text = stringResource(R.string.pass_dialog_desc), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
          value = codeInput,
          onValueChange = { codeInput = it },
          enabled = !isValidating,
          singleLine = true,
          label = { Text(stringResource(R.string.pass_code_label)) },
          modifier = Modifier.fillMaxWidth().testTag("access_code_input"),
        )
        Text(
          text = "${stringResource(R.string.pass_device_id_label)}: ${passStatus.deviceId}",
          style = MaterialTheme.typography.labelSmall,
          fontFamily = MonoFontFamily,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    },
    confirmButton = {
      Button(
        onClick = { onSubmitCode(codeInput) },
        enabled = !isValidating && codeInput.isNotBlank(),
      ) {
        Text(
          if (isValidating) stringResource(R.string.pass_validating)
          else stringResource(R.string.pass_verify_button)
        )
      }
    },
    dismissButton = {
      Row {
        if (passStatus.code.isNotBlank()) {
          TextButton(onClick = onClearCode, enabled = !isValidating) {
            Text(stringResource(R.string.pass_clear_action))
          }
        }
        TextButton(onClick = onDismiss, enabled = !isValidating) {
          Text(stringResource(R.string.action_cancel))
        }
      }
    },
  )
}

private fun formatHms(totalSeconds: Long): String {
  val hours = totalSeconds / 3_600L
  val mins = (totalSeconds % 3_600L) / 60L
  val secs = totalSeconds % 60L
  return "%02d:%02d:%02d".format(hours, mins, secs)
}
