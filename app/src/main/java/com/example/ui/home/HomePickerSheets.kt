package com.example.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.NetworkConfigEntity
import com.example.data.ServerAccountEntity
import com.example.data.ServerPingChecker
import com.example.ui.RemoteSyncFeedback
import com.example.ui.ads.Adsterra320x50BannerSlot
import com.example.ui.theme.DisplayFontFamily
import com.example.ui.theme.MonoFontFamily

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerPickerBottomSheet(
  servers: List<ServerAccountEntity>,
  selectedServerId: Long?,
  onSelect: (ServerAccountEntity) -> Unit,
  onManageAll: () -> Unit,
  onOpenRedeem: () -> Unit,
  onDismiss: () -> Unit,
  isFetchingRemoteServers: Boolean = false,
  remoteSyncFeedback: RemoteSyncFeedback? = null,
  onRefreshServers: () -> Unit = {},
  onDismissSyncFeedback: () -> Unit = {},
  serverPingsMs: Map<Long, Int?> = emptyMap(),
  onImportMyVpnClick: (() -> Unit)? = null,
  showAdsterraBanners: Boolean = true,
  onOpenBannerLink: (String) -> Unit = {},
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  var query by remember { mutableStateOf("") }

  // Filter ONLY by display name so hidden technical details cannot be probed via search
  val filtered =
    remember(servers, query) {
      val q = query.trim().lowercase()
      if (q.isEmpty()) servers
      else servers.filter { srv -> srv.name.lowercase().contains(q) }
    }

  val selectedServer = servers.firstOrNull { it.id == selectedServerId }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surface,
    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      // Header row: Title + count pill + Update servers button + circular Close button
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Row(
          modifier = Modifier.weight(1f),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text(
            text = stringResource(R.string.action_select_server),
            style = MaterialTheme.typography.titleLarge,
            fontFamily = DisplayFontFamily,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Surface(
            shape = RoundedCornerShape(999.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
          ) {
            Text(
              text = "${servers.size}",
              style = MaterialTheme.typography.labelSmall,
              fontFamily = MonoFontFamily,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier =
                Modifier.padding(horizontal = 8.dp, vertical = 4.dp).testTag("server_sheet_count"),
            )
          }
        }
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          FilledTonalButton(
            onClick = onRefreshServers,
            enabled = !isFetchingRemoteServers,
            shape = RoundedCornerShape(999.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            modifier = Modifier.testTag("server_sheet_refresh"),
          ) {
            if (isFetchingRemoteServers) {
              CircularProgressIndicator(
                modifier = Modifier.size(15.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
              )
            } else {
              Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = stringResource(R.string.cd_refresh_servers),
                modifier = Modifier.size(16.dp),
              )
            }
            Spacer(Modifier.width(5.dp))
            Text(
              text =
                if (isFetchingRemoteServers) stringResource(R.string.action_updating_servers)
                else stringResource(R.string.action_update_servers),
              style = MaterialTheme.typography.labelMedium,
              maxLines = 1,
            )
          }
          Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
          ) {
            IconButton(
              onClick = onDismiss,
              modifier = Modifier.size(40.dp).testTag("server_sheet_close"),
            ) {
              Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.cd_close),
                modifier = Modifier.size(20.dp),
              )
            }
          }
        }
      }

      // Brief inline loading / result status bar (visible while bottom sheet is open)
      if (isFetchingRemoteServers) {
        Surface(
          shape = RoundedCornerShape(12.dp),
          color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
          modifier = Modifier.fillMaxWidth().testTag("server_sheet_refresh_loading"),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            CircularProgressIndicator(
              modifier = Modifier.size(14.dp),
              strokeWidth = 2.dp,
              color = MaterialTheme.colorScheme.primary,
            )
            Text(
              text = stringResource(R.string.action_updating_servers),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
          }
        }
      } else if (remoteSyncFeedback != null) {
        val fbBg =
          if (remoteSyncFeedback.isError) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
          else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        val fbText =
          if (remoteSyncFeedback.isError) MaterialTheme.colorScheme.onErrorContainer
          else MaterialTheme.colorScheme.onPrimaryContainer
        Surface(
          shape = RoundedCornerShape(12.dp),
          color = fbBg,
          modifier = Modifier.fillMaxWidth().testTag("server_sheet_refresh_status"),
        ) {
          Row(
            modifier =
              Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Text(
              text = remoteSyncFeedback.message,
              style = MaterialTheme.typography.bodySmall,
              color = fbText,
              modifier = Modifier.weight(1f),
            )
            IconButton(
              onClick = onDismissSyncFeedback,
              modifier = Modifier.size(32.dp),
            ) {
              Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.cd_close),
                tint = fbText,
                modifier = Modifier.size(16.dp),
              )
            }
          }
        }
      }

      // Search input
      OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        leadingIcon = {
          Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        },
        placeholder = { Text(stringResource(R.string.home_search_servers_hint)) },
        modifier = Modifier.fillMaxWidth().testTag("server_sheet_search"),
      )

      // Quick actions bar (Redeem / Import .myvpn / List)
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        FilledTonalButton(
          onClick = onOpenRedeem,
          shape = RoundedCornerShape(12.dp),
          contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
          modifier = Modifier.weight(1f).testTag("server_sheet_redeem"),
        ) {
          Icon(Icons.Default.ConfirmationNumber, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(4.dp))
          Text(stringResource(R.string.redeem_button_short), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onImportMyVpnClick != null) {
          OutlinedButton(
            onClick = onImportMyVpnClick,
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
            modifier = Modifier.testTag("server_sheet_import_myvpn"),
          ) {
            Icon(
              imageVector = Icons.Default.FileOpen,
              contentDescription = stringResource(R.string.cd_import_myvpn),
              modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.action_import_myvpn), maxLines = 1)
          }
        }
        TextButton(
          onClick = onManageAll,
          modifier = Modifier.testTag("server_sheet_manage"),
        ) {
          Text(stringResource(R.string.action_manage_list))
        }
      }

      // Server list (NAME + ONLINE/LIMIT + USAGE BAR + LIVE PING)
      if (filtered.isEmpty()) {
        Box(
          modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            text = stringResource(R.string.empty_servers_title),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      } else {
        LazyColumn(
          modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          items(filtered, key = { it.id }) { srv ->
            val isSelected = srv.id == selectedServerId
            val borderColor =
              if (isSelected) MaterialTheme.colorScheme.primary
              else MaterialTheme.colorScheme.outlineVariant
            val bgColor =
              if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
              else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            val pingMs = serverPingsMs[srv.id]
            val usageFraction = remember(srv.onlineCount, srv.userLimit) { srv.onlineUsageFraction() }

            Surface(
              shape = RoundedCornerShape(16.dp),
              color = bgColor,
              border = BorderStroke(if (isSelected) 1.5.dp else 1.dp, borderColor),
              modifier =
                Modifier
                  .fillMaxWidth()
                  .clickable { onSelect(srv) }
                  .testTag("server_sheet_item_${srv.id}"),
            ) {
              val flag = remember(srv.region, srv.name) { ServerPingChecker.resolveServerFlagEmoji(srv) }
              val (expiryLine, isExpired) =
                remember(srv.expiryDate) { ServerPingChecker.formatRemainingExpiry(srv.expiryDate) }
              Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
              ) {
                Surface(
                  shape = RoundedCornerShape(12.dp),
                  color = MaterialTheme.colorScheme.surfaceVariant,
                  modifier = Modifier.size(40.dp),
                ) {
                  Box(contentAlignment = Alignment.Center) {
                    Text(text = flag, fontSize = 20.sp)
                  }
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                  Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                  ) {
                    Text(
                      text = srv.name,
                      style = MaterialTheme.typography.titleSmall,
                      fontWeight = FontWeight.Bold,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis,
                      modifier = Modifier.weight(1f, fill = false),
                    )
                    if (srv.isOpenVpnMode()) {
                      Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.testTag("server_sheet_openvpn_badge_${srv.id}"),
                      ) {
                        Text(
                          text = stringResource(R.string.badge_openvpn),
                          style = MaterialTheme.typography.labelSmall,
                          color = MaterialTheme.colorScheme.onSecondaryContainer,
                          fontWeight = FontWeight.SemiBold,
                          modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                        )
                      }
                    }
                  }
                  if (srv.expiryDate.isNotBlank()) {
                    Text(
                      text = expiryLine,
                      style = MaterialTheme.typography.labelSmall,
                      color =
                        if (isExpired) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis,
                    )
                  }
                  Text(
                    text = srv.formatOnlineLimitLabel(),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = MonoFontFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("server_sheet_online_limit_${srv.id}"),
                  )
                  srv.formatLifetimeUsersLabel()?.let { usedByLabel ->
                    Text(
                      text = usedByLabel,
                      style = MaterialTheme.typography.labelSmall,
                      fontFamily = MonoFontFamily,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis,
                      modifier = Modifier.testTag("server_sheet_lifetime_users_${srv.id}"),
                    )
                  }
                  if (usageFraction != null) {
                    val barColor =
                      when {
                        usageFraction >= 0.9f -> MaterialTheme.colorScheme.error
                        usageFraction >= 0.7f -> MaterialTheme.colorScheme.secondary
                        else -> MaterialTheme.colorScheme.primary
                      }
                    LinearProgressIndicator(
                      progress = { usageFraction },
                      color = barColor,
                      trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                      modifier =
                        Modifier
                          .fillMaxWidth()
                          .height(5.dp)
                          .clip(RoundedCornerShape(999.dp))
                          .testTag("server_sheet_usage_bar_${srv.id}"),
                    )
                  }
                }
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                  ServerPingPill(
                    pingMs = pingMs,
                    testTag = "server_sheet_ping_${srv.id}",
                  )
                  if (isSelected) {
                    Icon(
                      imageVector = Icons.Default.Check,
                      contentDescription = stringResource(R.string.badge_active),
                      tint = MaterialTheme.colorScheme.primary,
                      modifier = Modifier.size(18.dp),
                    )
                  }
                }
              }
            }
          }
        }
      }

      // Bottom summary footer (NAME ONLY)
      selectedServer?.let { sel ->
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(
          text = "${stringResource(R.string.home_selected_prefix)} · ${sel.name}",
          style = MaterialTheme.typography.labelSmall,
          fontFamily = MonoFontFamily,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.align(Alignment.CenterHorizontally),
        )
      }

      if (showAdsterraBanners) {
        // Adsterra 320x50 banner pinned outside the scrolling server list (free users only)
        Adsterra320x50BannerSlot(
          onOpenExternalUrl = onOpenBannerLink,
          bannerTag = "server_sheet_banner_adsterra",
        )
      }
    }
  }
}

@Composable
internal fun ServerPingPill(
  pingMs: Int?,
  testTag: String,
  modifier: Modifier = Modifier,
) {
  val bgColor =
    when {
      pingMs == null || pingMs <= 0 -> MaterialTheme.colorScheme.surfaceVariant
      pingMs < 150 -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
      pingMs < 400 -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.75f)
      else -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.75f)
    }
  val textColor =
    when {
      pingMs == null || pingMs <= 0 -> MaterialTheme.colorScheme.onSurfaceVariant
      pingMs < 150 -> MaterialTheme.colorScheme.onPrimaryContainer
      pingMs < 400 -> MaterialTheme.colorScheme.onSecondaryContainer
      else -> MaterialTheme.colorScheme.onErrorContainer
    }
  Surface(
    shape = RoundedCornerShape(999.dp),
    color = bgColor,
    modifier = modifier,
  ) {
    Text(
      text = ServerPingChecker.formatPingBadge(pingMs),
      style = MaterialTheme.typography.labelSmall,
      fontFamily = MonoFontFamily,
      fontWeight = FontWeight.SemiBold,
      color = textColor,
      maxLines = 1,
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp).testTag(testTag),
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkPickerBottomSheet(
  networks: List<NetworkConfigEntity>,
  selectedNetworkId: Long?,
  onSelect: (NetworkConfigEntity) -> Unit,
  onManageAll: () -> Unit,
  onDismiss: () -> Unit,
  onClearNetwork: (() -> Unit)? = null,
  showAdsterraBanners: Boolean = true,
  onOpenBannerLink: (String) -> Unit = {},
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  var query by remember { mutableStateOf("") }

  // Filter ONLY by display name so hidden technical details cannot be probed via search
  val filtered =
    remember(networks, query) {
      val q = query.trim().lowercase()
      if (q.isEmpty()) networks
      else networks.filter { net -> net.name.lowercase().contains(q) }
    }

  val selectedNetwork = networks.firstOrNull { it.id == selectedNetworkId }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surface,
    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Text(
            text = stringResource(R.string.action_select_network),
            style = MaterialTheme.typography.titleLarge,
            fontFamily = DisplayFontFamily,
            fontWeight = FontWeight.Bold,
          )
          Surface(
            shape = RoundedCornerShape(999.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
          ) {
            Text(
              text = "${networks.size}",
              style = MaterialTheme.typography.labelSmall,
              fontFamily = MonoFontFamily,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier =
                Modifier.padding(horizontal = 10.dp, vertical = 4.dp).testTag("network_sheet_count"),
            )
          }
        }
        Surface(
          shape = CircleShape,
          color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        ) {
          IconButton(
            onClick = onDismiss,
            modifier = Modifier.size(40.dp).testTag("network_sheet_close"),
          ) {
            Icon(
              imageVector = Icons.Default.Close,
              contentDescription = stringResource(R.string.cd_close),
              modifier = Modifier.size(20.dp),
            )
          }
        }
      }

      OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        leadingIcon = {
          Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        },
        placeholder = { Text(stringResource(R.string.home_search_networks_hint)) },
        modifier = Modifier.fillMaxWidth().testTag("network_sheet_search"),
      )

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        if (selectedNetworkId != null && onClearNetwork != null) {
          TextButton(
            onClick = onClearNetwork,
            modifier = Modifier.testTag("network_sheet_clear"),
          ) {
            Text(
              text = stringResource(R.string.action_no_network_direct),
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        } else {
          Spacer(Modifier.width(1.dp))
        }
        TextButton(
          onClick = onManageAll,
          modifier = Modifier.testTag("network_sheet_manage"),
        ) {
          Text(stringResource(R.string.action_manage_list))
        }
      }

      // Network list (NAME ONLY)
      if (filtered.isEmpty()) {
        Box(
          modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            text = stringResource(R.string.empty_networks_title),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      } else {
        LazyColumn(
          modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          items(filtered, key = { it.id }) { net ->
            val isSelected = net.id == selectedNetworkId
            val borderColor =
              if (isSelected) MaterialTheme.colorScheme.primary
              else MaterialTheme.colorScheme.outlineVariant
            val bgColor =
              if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
              else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)

            Surface(
              shape = RoundedCornerShape(16.dp),
              color = bgColor,
              border = BorderStroke(if (isSelected) 1.5.dp else 1.dp, borderColor),
              modifier =
                Modifier
                  .fillMaxWidth()
                  .clickable { onSelect(net) }
                  .testTag("network_sheet_item_${net.id}"),
            ) {
              Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
              ) {
                Surface(
                  shape = RoundedCornerShape(12.dp),
                  color = MaterialTheme.colorScheme.primaryContainer,
                  modifier = Modifier.size(40.dp),
                ) {
                  Box(contentAlignment = Alignment.Center) {
                    Icon(
                      imageVector = Icons.Default.Public,
                      contentDescription = null,
                      tint = MaterialTheme.colorScheme.onPrimaryContainer,
                      modifier = Modifier.size(20.dp),
                    )
                  }
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                  Text(
                    text = net.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                  Text(
                    text = stringResource(R.string.home_network_subtitle),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                }
                if (isSelected) {
                  Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = stringResource(R.string.badge_active),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                  )
                }
              }
            }
          }
        }
      }

      // Bottom summary footer (NAME ONLY)
      selectedNetwork?.let { sel ->
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(
          text = "${stringResource(R.string.home_selected_prefix)} · ${sel.name}",
          style = MaterialTheme.typography.labelSmall,
          fontFamily = MonoFontFamily,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.align(Alignment.CenterHorizontally),
        )
      }

      if (showAdsterraBanners) {
        // Adsterra 320x50 banner pinned outside the scrolling network list (free users only)
        Adsterra320x50BannerSlot(
          onOpenExternalUrl = onOpenBannerLink,
          bannerTag = "network_sheet_banner_adsterra",
        )
      }
    }
  }
}
