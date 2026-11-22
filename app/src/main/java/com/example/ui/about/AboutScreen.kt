package com.example.ui.about

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.EnhancedEncryption
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.BuildConfig
import com.example.R
import com.example.config.NativeKeyBridge
import com.example.ui.theme.DisplayFontFamily
import com.example.ui.theme.MonoFontFamily

private data class OpenSourceCredit(
  val name: String,
  val license: String,
  val purpose: String,
)

private data class MilestoneStatus(
  val number: Int,
  val title: String,
  val done: Boolean,
)

private val openSourceCredits =
  listOf(
    OpenSourceCredit("Kotlin Standard Library & Coroutines", "Apache-2.0", "Core language & async concurrency"),
    OpenSourceCredit("Jetpack Compose & Material 3", "Apache-2.0", "Native Android UI & theming"),
    OpenSourceCredit("AndroidX Navigation Compose", "Apache-2.0", "In-app screen navigation"),
    OpenSourceCredit("AndroidX Room Persistence", "Apache-2.0", "Local SQLite editable profile storage"),
    OpenSourceCredit("AndroidX DataStore Preferences", "Apache-2.0", "First-run seeding flag, remote URLs & pass status"),
    OpenSourceCredit("Square OkHttp", "Apache-2.0", "HTTPS requests for remote server JSON, banner & webhook"),
    OpenSourceCredit("Square Moshi", "Apache-2.0", ".myvpn, server list & webhook JSON serialization"),
    OpenSourceCredit("JSch (mwiede fork)", "BSD-3-Clause", "SSH2 client & dynamic direct-tcpip SOCKS5 tunnel"),
    OpenSourceCredit("hev-socks5-tunnel (heiher / com.zaneschepke:hevtunnel)", "MIT / Apache-2.0", "Native Android tun2socks engine + mapdns routing Android VpnService TUN traffic to local SOCKS5"),
    OpenSourceCredit("Coil Compose", "Apache-2.0", "Remote owner announcement banner image loading"),
    OpenSourceCredit("Noto Sans Myanmar / Space Grotesk / JetBrains Mono", "SIL OFL 1.1", "Bundled Myanmar & technical typography"),
    OpenSourceCredit("dnstt (David Fifield / bamsoftware)", "Public Domain", "DNS tunnel wire protocol (UDP DNS TXT + Base32 + KCP + Noise_NK + smux) for SlowDNS"),
    OpenSourceCredit("KCP (skywind3000 / xtaci)", "MIT", "Reliable low-latency stream protocol over small DNS MTU packets"),
    OpenSourceCredit("Noise Protocol Framework (Trevor Perrin)", "Public Domain", "Noise_NK_25519_ChaChaPoly_BLAKE2s authenticated encryption handshake"),
    OpenSourceCredit("smux (xtaci)", "MIT", "Version 2 stream multiplexer forwarding local TCP SSH connections through DNSTT"),
    OpenSourceCredit("VLESS v0 & WebSocket (Project X / RFC 6455)", "MPL-2.0 / IETF Standard", "VLESS v0 wire protocol over RFC 6455 WebSocket transport for VLESS WS profiles"),
  )

private val milestones =
  listOf(
    MilestoneStatus(1, "App shell + .myvpn AES-256-GCM config စနစ်", done = true),
    MilestoneStatus(2, "SSH engine + local SOCKS5 proxy (127.0.0.1:1080) + Editable/Remote servers", done = true),
    MilestoneStatus(3, "Android VpnService + hev-socks5-tunnel (System-wide VPN, Kill Switch & Auto-Reconnect)", done = true),
    MilestoneStatus(4, "Custom Payload Injector + WebSocket 101 + SSL/SNI handshake", done = true),
    MilestoneStatus(5, "DNSTT (SlowDNS) native client integration + System-wide VPN", done = true),
    MilestoneStatus(6, "VLESS over WebSocket + Remote JSON merge & expiry enforcement", done = true),
    MilestoneStatus(7, "ကွန်ရက် (Network) + ဆာဗာ (Server) ခွဲခြားစနစ် + Access Code + Remote Banner", done = true),
    MilestoneStatus(8, "ဝယ်ယူသူ ကုတ်ထည့်ရန် (Redeem) + Remote JSON v2 + သန့်ရှင်းသော ပင်မ UI", done = true),
  )

@Composable
fun AboutScreen(
  isFetchingRemoteServers: Boolean,
  bannerConfigUrl: String,
  isFetchingBanner: Boolean,
  redeemWebhookUrl: String = com.example.redeem.RedeemRepository.DEFAULT_REDEEM_WEBHOOK_URL,
  onFetchRemoteServers: (url: String, replaceExisting: Boolean) -> Unit,
  onFetchOwnerBanner: (String) -> Unit,
  onSaveRedeemWebhookUrl: (String) -> Unit = {},
  onResetRedeemWebhookUrl: () -> Unit = {},
  onOpenRedeemClick: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  var replaceExistingServers by remember { mutableStateOf(false) }
  var bannerUrlInput by remember(bannerConfigUrl) { mutableStateOf(bannerConfigUrl) }
  var webhookUrlInput by remember(redeemWebhookUrl) { mutableStateOf(redeemWebhookUrl) }

  Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    LazyColumn(
      modifier = Modifier.fillMaxWidth().widthIn(max = 640.dp),
      contentPadding = PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      item {
        Card(
          shape = RoundedCornerShape(22.dp),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Image(
              painter = painterResource(id = R.drawable.app_icon_amkt_1795171749485),
              contentDescription = stringResource(R.string.app_name),
              modifier = Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)),
            )
            Spacer(Modifier.width(16.dp))
            Column {
              Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
                fontFamily = DisplayFontFamily,
                fontWeight = FontWeight.Bold,
              )
              Text(
                text = "v${BuildConfig.VERSION_NAME} • Android 8.0+ (arm64-v8a)",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = MonoFontFamily,
                color = MaterialTheme.colorScheme.primary,
              )
              Spacer(Modifier.height(2.dp))
              Text(
                text = stringResource(R.string.about_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      }

      // Remote JSON Server List URL Setting (No hardcoded servers!)
      item {
        Card(
          shape = RoundedCornerShape(18.dp),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(
                imageVector = Icons.Default.CloudDownload,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
              )
              Spacer(Modifier.width(8.dp))
              Text(
                text = stringResource(R.string.remote_servers_title),
                style = MaterialTheme.typography.titleMedium,
              )
            }
            Text(
              text = stringResource(R.string.remote_servers_desc),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Text(
                text = stringResource(R.string.remote_servers_replace_label),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f).padding(end = 12.dp),
              )
              Switch(
                checked = replaceExistingServers,
                onCheckedChange = { replaceExistingServers = it },
                enabled = !isFetchingRemoteServers,
                modifier = Modifier.testTag("remote_servers_replace_switch"),
              )
            }
            Button(
              onClick = { onFetchRemoteServers("", replaceExistingServers) },
              enabled = !isFetchingRemoteServers,
              shape = RoundedCornerShape(12.dp),
              modifier = Modifier.fillMaxWidth().testTag("remote_servers_fetch_button"),
            ) {
              if (isFetchingRemoteServers) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
              }
              Text(stringResource(R.string.remote_servers_fetch_button))
            }
          }
        }
      }

      // Buyer Redeem Code & Editable Webhook URL Setting
      item {
        Card(
          shape = RoundedCornerShape(18.dp),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(
                  imageVector = Icons.Default.ConfirmationNumber,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.primary,
                  modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                  text = stringResource(R.string.redeem_webhook_title),
                  style = MaterialTheme.typography.titleMedium,
                )
              }
              FilledTonalButton(
                onClick = onOpenRedeemClick,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.testTag("about_open_redeem_button"),
              ) {
                Text(stringResource(R.string.redeem_button_short))
              }
            }
            Text(
              text = stringResource(R.string.redeem_webhook_desc),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
              value = webhookUrlInput,
              onValueChange = { webhookUrlInput = it },
              singleLine = true,
              label = { Text(stringResource(R.string.redeem_webhook_url_label)) },
              placeholder = {
                Text(
                  com.example.redeem.RedeemRepository.DEFAULT_REDEEM_WEBHOOK_URL,
                  fontFamily = MonoFontFamily,
                )
              },
              modifier = Modifier.fillMaxWidth().testTag("redeem_webhook_url_input"),
            )
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Button(
                onClick = { onSaveRedeemWebhookUrl(webhookUrlInput) },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f).testTag("redeem_webhook_save_button"),
              ) {
                Text(stringResource(R.string.redeem_webhook_save_button))
              }
              OutlinedButton(
                onClick = {
                  webhookUrlInput = com.example.redeem.RedeemRepository.DEFAULT_REDEEM_WEBHOOK_URL
                  onResetRedeemWebhookUrl()
                },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.testTag("redeem_webhook_reset_button"),
              ) {
                Text(stringResource(R.string.redeem_webhook_reset_button))
              }
            }
          }
        }
      }

      // Remote Owner Announcement Banner URL
      item {
        Card(
          shape = RoundedCornerShape(18.dp),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(
                imageVector = Icons.Default.Campaign,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
              )
              Spacer(Modifier.width(8.dp))
              Text(
                text = stringResource(R.string.owner_banner_title),
                style = MaterialTheme.typography.titleMedium,
              )
            }
            Text(
              text = stringResource(R.string.owner_banner_empty),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
              value = bannerUrlInput,
              onValueChange = { bannerUrlInput = it },
              singleLine = true,
              enabled = !isFetchingBanner,
              label = { Text(stringResource(R.string.owner_banner_url_label)) },
              placeholder = {
                Text(stringResource(R.string.owner_banner_url_hint), fontFamily = MonoFontFamily)
              },
              modifier = Modifier.fillMaxWidth().testTag("owner_banner_url_input"),
            )
            Button(
              onClick = { onFetchOwnerBanner(bannerUrlInput) },
              enabled = !isFetchingBanner,
              shape = RoundedCornerShape(12.dp),
              modifier = Modifier.fillMaxWidth().testTag("owner_banner_fetch_button"),
            ) {
              if (isFetchingBanner) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
              }
              Text(stringResource(R.string.owner_banner_fetch_button))
            }
          }
        }
      }

      item {
        AboutInfoCard(
          icon = Icons.Default.Shield,
          title = stringResource(R.string.about_privacy_title),
          body = stringResource(R.string.about_privacy_body),
        )
      }

      item {
        val nativeLabel =
          if (NativeKeyBridge.isNativeLibraryLoaded) "Native C++ NDK (libamkt_crypto.so) Active"
          else "JVM Obfuscated Fallback Active (Android Studio NDK ဖြင့် .so ထည့် build နိုင်သည်)"
        AboutInfoCard(
          icon = Icons.Default.EnhancedEncryption,
          title = stringResource(R.string.about_crypto_title),
          body = "${stringResource(R.string.about_crypto_body)}\n\nKey Bridge: $nativeLabel",
        )
      }

      item {
        Card(
          shape = RoundedCornerShape(18.dp),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Text(
              text = stringResource(R.string.about_roadmap_title),
              style = MaterialTheme.typography.titleMedium,
            )
            milestones.forEach { m ->
              Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                  imageVector =
                    if (m.done) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                  contentDescription = null,
                  tint =
                    if (m.done) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                  text = "M${m.number}. ${m.title}",
                  style = MaterialTheme.typography.bodySmall,
                  color =
                    if (m.done) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }
        }
      }

      item {
        Card(
          shape = RoundedCornerShape(18.dp),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(
                imageVector = Icons.Default.Gavel,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
              )
              Spacer(Modifier.width(8.dp))
              Text(
                text = stringResource(R.string.about_licenses_title),
                style = MaterialTheme.typography.titleMedium,
              )
            }
            openSourceCredits.forEachIndexed { index, credit ->
              if (index > 0) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
              }
              Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Text(
                    text = credit.name,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                  )
                  Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                  ) {
                    Text(
                      text = credit.license,
                      style = MaterialTheme.typography.labelSmall,
                      fontFamily = MonoFontFamily,
                      color = MaterialTheme.colorScheme.onPrimaryContainer,
                      modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                  }
                }
                Text(
                  text = credit.purpose,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun AboutInfoCard(
  icon: ImageVector,
  title: String,
  body: String,
) {
  Card(
    shape = RoundedCornerShape(18.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
          imageVector = icon,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(text = title, style = MaterialTheme.typography.titleMedium)
      }
      Text(
        text = body,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}
