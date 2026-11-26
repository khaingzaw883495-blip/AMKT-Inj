package com.example.ui.ads

import android.annotation.SuppressLint
import android.net.http.SslError
import android.view.ViewGroup
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.ads.AdsterraConfig
import com.example.ui.theme.MonoFontFamily
import kotlinx.coroutines.delay

/**
 * External-browser 7-second Smartlink_2 connect gate dialog shown when a free user needs a +9h
 * time grant before starting the VPN tunnel (max 2x/day per device).
 *
 * Key guarantees:
 * - NO in-app WebView is used for the connect gate; launches [adUrl] in the device's external browser.
 * - Countdown starts immediately on open (does NOT wait for the browser or page load).
 * - No skip/close button before countdown hits 0.
 * - If launching the external browser fails or no browser is installed, countdown STILL runs and
 *   triggers [onCountdownFinished] so connecting is never blocked.
 * - Displays ONLY [serverName] — never host, port, credentials, payload, or `.ovpn` contents.
 */
@Composable
fun ConnectAdGateDialog(
  serverName: String,
  onCountdownFinished: () -> Unit,
  onOpenExternalBrowser: (String) -> Boolean,
  isTimeExtensionGrant: Boolean = false,
  countdownSeconds: Int = AdsterraConfig.CONNECT_GATE_SECONDS,
  tickIntervalMs: Long = 1_000L,
  adUrl: String = AdsterraConfig.SMARTLINK_URL,
  grantHours: Int = AdsterraConfig.AD_GRANT_HOURS,
) {
  val totalSeconds = countdownSeconds.coerceAtLeast(1)
  var remainingSeconds by remember(totalSeconds) { mutableIntStateOf(totalSeconds) }
  var hasCompleted by remember { mutableStateOf(false) }
  var browserLaunchFailed by remember { mutableStateOf(false) }

  // Open the Smartlink_2 URL automatically in the external browser when the gate opens
  LaunchedEffect(adUrl) {
    val safeUrl = adUrl.takeIf { AdsterraConfig.isAllowedWebViewUrl(it) }
    val launched =
      if (safeUrl != null) {
        runCatching { onOpenExternalBrowser(safeUrl) }.getOrDefault(false)
      } else {
        false
      }
    browserLaunchFailed = !launched
  }

  // Independent 7-second countdown — never paused by external browser launch state
  LaunchedEffect(totalSeconds, tickIntervalMs) {
    var secs = totalSeconds
    remainingSeconds = secs
    while (secs > 0) {
      delay(tickIntervalMs)
      secs -= 1
      remainingSeconds = secs
    }
    if (!hasCompleted) {
      hasCompleted = true
      onCountdownFinished()
    }
  }

  Dialog(
    onDismissRequest = {},
    properties =
      DialogProperties(
        dismissOnBackPress = false,
        dismissOnClickOutside = false,
        usePlatformDefaultWidth = false,
      ),
  ) {
    Surface(
      shape = RoundedCornerShape(22.dp),
      color = MaterialTheme.colorScheme.surface,
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
      tonalElevation = 6.dp,
      modifier =
        Modifier
          .fillMaxWidth(0.92f)
          .widthIn(max = 460.dp)
          .testTag("ad_gate_dialog"),
    ) {
      Column(modifier = Modifier.fillMaxWidth()) {
        // Top status bar: Server display name + 7-second countdown badge (no skip button)
        Column(
          modifier =
            Modifier
              .fillMaxWidth()
              .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f))
              .padding(horizontal = 18.dp, vertical = 16.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              modifier = Modifier.weight(1f),
            ) {
              Icon(
                imageVector = Icons.Default.Shield,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
              )
              Text(
                text = serverName.ifBlank { stringResource(R.string.app_name) },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("ad_gate_server_name"),
              )
            }

            Surface(
              shape = RoundedCornerShape(999.dp),
              color = MaterialTheme.colorScheme.primaryContainer,
              border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
              ) {
                Icon(
                  imageVector = Icons.Default.HourglassTop,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.onPrimaryContainer,
                  modifier = Modifier.size(14.dp),
                )
                Text(
                  text = "${remainingSeconds}s",
                  style = MaterialTheme.typography.labelMedium,
                  fontFamily = MonoFontFamily,
                  fontWeight = FontWeight.ExtraBold,
                  color = MaterialTheme.colorScheme.onPrimaryContainer,
                  modifier = Modifier.testTag("ad_gate_countdown_badge"),
                )
              }
            }
          }

          Text(
            text = stringResource(R.string.ad_gate_countdown_format, remainingSeconds),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.testTag("ad_gate_countdown_text"),
          )

          val progress =
            ((totalSeconds - remainingSeconds).toFloat() / totalSeconds.toFloat()).coerceIn(0f, 1f)
          LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(999.dp)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.outlineVariant,
          )
        }

        // Body: External browser launch info (NO in-app WebView) + +9h free-time grant notice
        Column(
          modifier =
            Modifier
              .fillMaxWidth()
              .padding(horizontal = 20.dp, vertical = 22.dp)
              .testTag(if (browserLaunchFailed) "ad_gate_error_fallback" else "ad_gate_browser_info"),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Surface(
            shape = RoundedCornerShape(16.dp),
            color =
              if (browserLaunchFailed) MaterialTheme.colorScheme.surfaceVariant
              else MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.size(56.dp),
          ) {
            Box(contentAlignment = Alignment.Center) {
              Icon(
                imageVector = if (browserLaunchFailed) Icons.Default.CloudOff else Icons.Default.Public,
                contentDescription = null,
                tint =
                  if (browserLaunchFailed) MaterialTheme.colorScheme.onSurfaceVariant
                  else MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(28.dp),
              )
            }
          }

          Text(
            text =
              if (browserLaunchFailed) stringResource(R.string.ad_gate_load_error_title)
              else stringResource(R.string.ad_gate_browser_opened_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
          )

          Text(
            text =
              when {
                browserLaunchFailed -> stringResource(R.string.ad_gate_load_error_body)
                isTimeExtensionGrant -> stringResource(R.string.ad_gate_browser_opened_body, grantHours)
                else -> stringResource(R.string.ad_gate_browser_opened_connect_body)
              },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
          )

          OutlinedButton(
            onClick = {
              if (AdsterraConfig.isAllowedWebViewUrl(adUrl)) {
                val ok = runCatching { onOpenExternalBrowser(adUrl) }.getOrDefault(false)
                browserLaunchFailed = !ok
              }
            },
            shape = RoundedCornerShape(999.dp),
            modifier = Modifier.testTag("ad_gate_reopen_browser_button"),
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.OpenInNew,
              contentDescription = null,
              modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(text = stringResource(R.string.ad_gate_reopen_browser_cta))
          }
        }
      }
    }
  }
}

/**
 * Compact 320x50 Adsterra banner WebView slot placed at the bottom of Home and, when own-ad is
 * absent for free users, on Network/Server picker screens.
 */
@Composable
fun Adsterra320x50BannerSlot(
  onOpenExternalUrl: (String) -> Unit,
  modifier: Modifier = Modifier,
  bannerTag: String = "home_adsterra_320x50_banner",
) {
  Box(
    modifier = modifier.fillMaxWidth(),
    contentAlignment = Alignment.Center,
  ) {
    Surface(
      shape = RoundedCornerShape(12.dp),
      color = MaterialTheme.colorScheme.surface,
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
      modifier =
        Modifier
          .widthIn(max = 320.dp)
          .fillMaxWidth()
          .height(50.dp)
          .testTag(bannerTag),
    ) {
      HardenedBanner320x50WebView(
        onOpenExternalUrl = onOpenExternalUrl,
        modifier = Modifier.fillMaxSize(),
      )
    }
  }
}

@Composable
private fun HardenedBanner320x50WebView(
  onOpenExternalUrl: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val bannerHtml = remember { AdsterraConfig.buildBanner320x50Html() }

  AndroidView(
    modifier = modifier,
    factory = { ctx ->
      runCatching {
        WebView(ctx).apply {
          layoutParams =
            ViewGroup.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT,
              ViewGroup.LayoutParams.MATCH_PARENT,
            )
          setBackgroundColor(android.graphics.Color.TRANSPARENT)
          isHorizontalScrollBarEnabled = false
          isVerticalScrollBarEnabled = false
          applyHardenedWebSettings(settings)
          webViewClient =
            object : WebViewClient() {
              override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
              ): Boolean {
                val target = request?.url?.toString() ?: return true
                if (!AdsterraConfig.isAllowedWebViewUrl(target)) {
                  return true
                }
                if (request.isForMainFrame && request.hasGesture()) {
                  onOpenExternalUrl(target)
                  return true
                }
                return false
              }

              override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?,
              ) {
                handler?.cancel()
              }
            }
          loadDataWithBaseURL(
            AdsterraConfig.BANNER_320X50_BASE_URL,
            bannerHtml,
            "text/html",
            "UTF-8",
            null,
          )
        }
      }.getOrElse { android.view.View(ctx) }
    },
    onRelease = { view ->
      if (view is WebView) {
        runCatching {
          view.stopLoading()
          view.loadUrl("about:blank")
          view.destroy()
        }
      }
    },
  )
}

@SuppressLint("SetJavaScriptEnabled")
@Suppress("DEPRECATION")
private fun applyHardenedWebSettings(settings: WebSettings) {
  settings.javaScriptEnabled = true
  settings.domStorageEnabled = true
  settings.allowFileAccess = false
  settings.allowContentAccess = false
  settings.allowFileAccessFromFileURLs = false
  settings.allowUniversalAccessFromFileURLs = false
  settings.setGeolocationEnabled(false)
  settings.javaScriptCanOpenWindowsAutomatically = false
  settings.setSupportMultipleWindows(false)
  settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
  settings.useWideViewPort = true
  settings.loadWithOverviewMode = true
}
