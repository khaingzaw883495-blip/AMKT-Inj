package com.example.ui.profiles

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import com.example.data.ServerAccountEntity
import com.example.data.ServerPingChecker
import com.example.ui.ads.Adsterra320x50BannerSlot
import com.example.ui.home.ServerPingPill
import com.example.ui.theme.MonoFontFamily

/**
 * Locked-down Servers list: shows flag emoji + Server name (+ small OpenVPN badge when OpenVPN),
 * read-only expiry line, GOOD VPN-style live Online/Limit + usage progress bar + TCP ping in ms,
 * lifetime user count when known, Update (Remote JSON) and Import .myvpn actions, plus Adsterra banner for free users.
 */
@Composable
fun ServersScreen(
  servers: List<ServerAccountEntity>,
  selectedServerId: Long?,
  onSelectServer: (ServerAccountEntity) -> Unit,
  onSyncRemoteClick: () -> Unit,
  isUpdating: Boolean = false,
  onImportMyVpnClick: () -> Unit = {},
  serverPingsMs: Map<Long, Int?> = emptyMap(),
  showAdsterraBanners: Boolean = true,
  onOpenBannerLink: (String) -> Unit = {},
  modifier: Modifier = Modifier,
) {
  Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    Column(modifier = Modifier.fillMaxWidth().widthIn(max = 640.dp).fillMaxSize()) {
      Column(
        modifier =
          Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = stringResource(R.string.servers_title),
              style = MaterialTheme.typography.headlineSmall,
            )
            Text(
              text = stringResource(R.string.servers_subtitle),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }

          FilledTonalButton(
            onClick = onSyncRemoteClick,
            enabled = !isUpdating,
            shape = RoundedCornerShape(999.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            modifier = Modifier.testTag("servers_remote_sync_button"),
          ) {
            if (isUpdating) {
              CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
              )
            } else {
              Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = stringResource(R.string.cd_refresh_servers),
                modifier = Modifier.size(17.dp),
              )
            }
            Spacer(Modifier.width(6.dp))
            Text(
              text =
                if (isUpdating) stringResource(R.string.action_updating_servers)
                else stringResource(R.string.action_update_servers),
              style = MaterialTheme.typography.labelLarge,
            )
          }
        }

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.End,
        ) {
          OutlinedButton(
            onClick = onImportMyVpnClick,
            shape = RoundedCornerShape(999.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            modifier = Modifier.testTag("servers_import_myvpn_button"),
          ) {
            Icon(
              imageVector = Icons.Default.FileOpen,
              contentDescription = stringResource(R.string.cd_import_myvpn),
              modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
              text = stringResource(R.string.action_import_myvpn),
              style = MaterialTheme.typography.labelLarge,
            )
          }
        }
      }

      if (servers.isEmpty()) {
        Box(
          modifier = Modifier.weight(1f).fillMaxWidth().padding(32.dp),
          contentAlignment = Alignment.Center,
        ) {
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Icon(
              imageVector = Icons.Default.Lan,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
              modifier = Modifier.size(52.dp),
            )
            Text(
              text = stringResource(R.string.empty_servers_title),
              style = MaterialTheme.typography.titleMedium,
            )
            Text(
              text = stringResource(R.string.empty_servers_body),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Row(
              horizontalArrangement = Arrangement.spacedBy(10.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Button(
                onClick = onSyncRemoteClick,
                enabled = !isUpdating,
                modifier = Modifier.testTag("servers_empty_update_button"),
              ) {
                if (isUpdating) {
                  CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                  )
                  Spacer(Modifier.width(8.dp))
                  Text(stringResource(R.string.action_updating_servers))
                } else {
                  Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                  )
                  Spacer(Modifier.width(6.dp))
                  Text(stringResource(R.string.action_update_servers))
                }
              }
              OutlinedButton(
                onClick = onImportMyVpnClick,
                modifier = Modifier.testTag("servers_empty_import_myvpn_button"),
              ) {
                Icon(
                  imageVector = Icons.Default.FileOpen,
                  contentDescription = null,
                  modifier = Modifier.size(17.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.action_import_myvpn))
              }
            }
          }
        }
      } else {
        LazyColumn(
          modifier = Modifier.weight(1f).fillMaxWidth(),
          contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          items(servers, key = { it.id }) { server ->
            ServerItemCard(
              server = server,
              isSelected = server.id == selectedServerId,
              pingMs = serverPingsMs[server.id],
              onSelect = { onSelectServer(server) },
            )
          }
        }
      }

      if (showAdsterraBanners) {
        Adsterra320x50BannerSlot(
          onOpenExternalUrl = onOpenBannerLink,
          bannerTag = "servers_banner_adsterra",
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
      }
    }
  }
}

@Composable
private fun ServerItemCard(
  server: ServerAccountEntity,
  isSelected: Boolean,
  pingMs: Int?,
  onSelect: () -> Unit,
) {
  val borderColor =
    if (isSelected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.outlineVariant

  val flag = remember(server.region, server.name) { ServerPingChecker.resolveServerFlagEmoji(server) }
  val (expiryLabel, isExpired) =
    remember(server.expiryDate) { ServerPingChecker.formatRemainingExpiry(server.expiryDate) }
  val usageFraction = remember(server.onlineCount, server.userLimit) { server.onlineUsageFraction() }

  Card(
    shape = RoundedCornerShape(16.dp),
    colors =
      CardDefaults.cardColors(
        containerColor =
          if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.24f)
          else MaterialTheme.colorScheme.surface
      ),
    border = BorderStroke(if (isSelected) 1.5.dp else 1.dp, borderColor),
    modifier =
      Modifier.fillMaxWidth()
        .clickable(onClick = onSelect)
        .testTag("server_card_${server.id}"),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
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
            text = server.name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
          )
          if (server.isOpenVpnMode()) {
            Surface(
              shape = RoundedCornerShape(999.dp),
              color = MaterialTheme.colorScheme.secondaryContainer,
              modifier = Modifier.testTag("server_openvpn_badge_${server.id}"),
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
        if (server.expiryDate.isNotBlank()) {
          Text(
            text = expiryLabel,
            style = MaterialTheme.typography.labelSmall,
            color =
              if (isExpired) MaterialTheme.colorScheme.error
              else MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        Text(
          text = server.formatOnlineLimitLabel(),
          style = MaterialTheme.typography.labelSmall,
          fontFamily = MonoFontFamily,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.testTag("server_card_online_limit_${server.id}"),
        )
        server.formatLifetimeUsersLabel()?.let { usedByLabel ->
          Text(
            text = usedByLabel,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = MonoFontFamily,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("server_card_lifetime_users_${server.id}"),
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
                .testTag("server_card_usage_bar_${server.id}"),
          )
        }
      }
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        ServerPingPill(
          pingMs = pingMs,
          testTag = "server_card_ping_${server.id}",
        )
        if (isSelected) {
          Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = stringResource(R.string.badge_active),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
          )
        }
      }
    }
  }
}
