package com.example

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.data.NetworkConfigEntity
import com.example.data.ServerAccountEntity
import com.example.data.ServerProfileEntity
import com.example.ui.AmktViewModel
import com.example.ui.home.HomeScreen
import com.example.ui.networks.NetworksScreen
import com.example.ui.profiles.ServersScreen
import com.example.ui.redeem.RedeemCodeScreen
import com.example.ui.settings.SettingsScreen
import com.example.ui.theme.MyApplicationTheme

private const val ROUTE_HOME = "home"
private const val ROUTE_NETWORKS = "networks"
private const val ROUTE_SERVERS = "servers"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_REDEEM = "redeem"

private data class TopLevelDestination(
  val route: String,
  val labelRes: Int,
  val icon: ImageVector,
  val testTag: String,
)

private val topLevelDestinations =
  listOf(
    TopLevelDestination(ROUTE_HOME, R.string.nav_home, Icons.Default.Home, "nav_home"),
    TopLevelDestination(ROUTE_NETWORKS, R.string.nav_networks, Icons.Default.Public, "nav_networks"),
    TopLevelDestination(ROUTE_SERVERS, R.string.nav_profiles, Icons.Default.Dns, "nav_profiles"),
  )

private data class PendingCombinedVpnRequest(
  val network: NetworkConfigEntity,
  val server: ServerAccountEntity,
  val effectiveProfile: ServerProfileEntity,
)

class MainActivity : ComponentActivity() {
  private val viewModel: AmktViewModel by viewModels {
    val app = application as AmktApplication
    AmktViewModel.Factory(
      repository = app.repository,
      membershipRepository = app.membershipRepository,
      vpnPreferencesRepository = app.vpnPreferencesRepository,
      vpnController = app.vpnController,
      configCodec = app.configCodec,
      logStore = app.logStore,
      networkServerRepository = app.networkServerRepository,
      redeemRepository = app.redeemRepository,
    )
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      val isDarkTheme by viewModel.isDarkTheme.collectAsStateWithLifecycle()
      MyApplicationTheme(darkTheme = isDarkTheme) {
        AmktInjectorApp(viewModel = viewModel)
      }
    }
  }
}

@Composable
internal fun AmktInjectorApp(viewModel: AmktViewModel) {
  val context = LocalContext.current
  val navController = rememberNavController()
  val snackbarHostState = remember { SnackbarHostState() }

  val networks by viewModel.networks.collectAsStateWithLifecycle()
  val servers by viewModel.servers.collectAsStateWithLifecycle()
  val selectedNetwork by viewModel.selectedNetwork.collectAsStateWithLifecycle()
  val selectedServer by viewModel.selectedServer.collectAsStateWithLifecycle()
  val vpnState by viewModel.vpnState.collectAsStateWithLifecycle()
  val vpnTrafficStats by viewModel.vpnTrafficStats.collectAsStateWithLifecycle()
  val vpnSettings by viewModel.vpnSettings.collectAsStateWithLifecycle()
  val ownerBanner by viewModel.ownerBanner.collectAsStateWithLifecycle()
  val isFetchingRemoteServers by viewModel.isFetchingRemoteServers.collectAsStateWithLifecycle()
  val remoteSyncFeedback by viewModel.remoteSyncFeedback.collectAsStateWithLifecycle()
  val isRedeemingCode by viewModel.isRedeemingCode.collectAsStateWithLifecycle()
  val redeemOutcome by viewModel.redeemOutcome.collectAsStateWithLifecycle()
  val snackbarMessage by viewModel.snackbarMessage.collectAsStateWithLifecycle()
  val isDarkTheme by viewModel.isDarkTheme.collectAsStateWithLifecycle()

  var pendingVpnRequest by remember { mutableStateOf<PendingCombinedVpnRequest?>(null) }
  var askedNotificationPermission by remember { mutableStateOf(false) }

  val notificationPermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
      // VPN runs regardless of whether user grants notification visibility
    }

  val vpnConsentLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
      val request = pendingVpnRequest
      pendingVpnRequest = null
      if (result.resultCode == Activity.RESULT_OK && request != null) {
        viewModel.startCombinedVpnAfterConsent(
          network = request.network,
          server = request.server,
          effectiveProfile = request.effectiveProfile,
        )
      } else {
        viewModel.onVpnPermissionDenied()
      }
    }

  fun launchVpnConnectFlow() {
    val validated = viewModel.validateSelectionForVpnConnect() ?: return
    val (net, srv, combined) = validated
    if (
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        !askedNotificationPermission &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
          PackageManager.PERMISSION_GRANTED
    ) {
      askedNotificationPermission = true
      notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    val consentIntent = VpnService.prepare(context)
    if (consentIntent != null) {
      pendingVpnRequest = PendingCombinedVpnRequest(net, srv, combined)
      vpnConsentLauncher.launch(consentIntent)
    } else {
      viewModel.startCombinedVpnAfterConsent(net, srv, combined)
    }
  }

  LaunchedEffect(snackbarMessage) {
    snackbarMessage?.let { msg ->
      snackbarHostState.showSnackbar(msg)
      viewModel.clearSnackbar()
    }
  }

  val backStackEntry by navController.currentBackStackEntryAsState()
  val currentRoute = backStackEntry?.destination?.route
  val isTopLevelRoute = topLevelDestinations.any { it.route == currentRoute }

  fun navigateTopLevel(route: String) {
    navController.navigate(route) {
      popUpTo(ROUTE_HOME) { saveState = true }
      launchSingleTop = true
      restoreState = true
    }
  }

  fun triggerRemoteUpdate() {
    if (!isFetchingRemoteServers) {
      viewModel.refreshSavedRemoteServers()
    }
  }

  BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    val useNavigationRail = maxWidth >= 600.dp

    Scaffold(
      contentWindowInsets = WindowInsets.safeDrawing,
      snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
      bottomBar = {
        if (isTopLevelRoute && !useNavigationRail) {
          NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
            topLevelDestinations.forEach { dest ->
              val selected = currentRoute == dest.route
              NavigationBarItem(
                selected = selected,
                onClick = { navigateTopLevel(dest.route) },
                icon = {
                  Icon(
                    imageVector = dest.icon,
                    contentDescription = stringResource(dest.labelRes),
                  )
                },
                label = { Text(stringResource(dest.labelRes), maxLines = 1) },
                modifier = Modifier.testTag(dest.testTag),
              )
            }
            // 4th bottom bar item: Update (fetches Remote JSON directly without leaving current tab)
            NavigationBarItem(
              selected = false,
              onClick = { triggerRemoteUpdate() },
              enabled = !isFetchingRemoteServers,
              icon = {
                if (isFetchingRemoteServers) {
                  CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                  )
                } else {
                  Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.nav_update),
                  )
                }
              },
              label = { Text(stringResource(R.string.nav_update), maxLines = 1) },
              modifier = Modifier.testTag("nav_update"),
            )
          }
        }
      },
      containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
      Row(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
        if (isTopLevelRoute && useNavigationRail) {
          NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
            topLevelDestinations.forEach { dest ->
              val selected = currentRoute == dest.route
              NavigationRailItem(
                selected = selected,
                onClick = { navigateTopLevel(dest.route) },
                icon = {
                  Icon(
                    imageVector = dest.icon,
                    contentDescription = stringResource(dest.labelRes),
                  )
                },
                label = { Text(stringResource(dest.labelRes), maxLines = 1) },
                modifier = Modifier.testTag(dest.testTag),
              )
            }
            NavigationRailItem(
              selected = false,
              onClick = { triggerRemoteUpdate() },
              enabled = !isFetchingRemoteServers,
              icon = {
                if (isFetchingRemoteServers) {
                  CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                  )
                } else {
                  Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.nav_update),
                  )
                }
              },
              label = { Text(stringResource(R.string.nav_update), maxLines = 1) },
              modifier = Modifier.testTag("nav_update"),
            )
          }
        }

        NavHost(
          navController = navController,
          startDestination = ROUTE_HOME,
          modifier = Modifier.weight(1f).fillMaxSize(),
        ) {
          composable(ROUTE_HOME) {
            HomeScreen(
              networks = networks,
              servers = servers,
              selectedNetwork = selectedNetwork,
              selectedServer = selectedServer,
              vpnState = vpnState,
              trafficStats = vpnTrafficStats,
              ownerBanner = ownerBanner,
              onConnectClick = {
                if (viewModel.isVpnActiveOrBusy()) {
                  viewModel.disconnectVpn()
                } else {
                  launchVpnConnectFlow()
                }
              },
              onReconnectClick = { launchVpnConnectFlow() },
              onSelectNetwork = viewModel::selectNetwork,
              onSelectServer = viewModel::selectServer,
              onOpenNetworksClick = { navigateTopLevel(ROUTE_NETWORKS) },
              onOpenServersClick = { navigateTopLevel(ROUTE_SERVERS) },
              onOpenSettingsClick = { navController.navigate(ROUTE_SETTINGS) },
              onOpenRedeemClick = { navController.navigate(ROUTE_REDEEM) },
              isFetchingRemoteServers = isFetchingRemoteServers,
              remoteSyncFeedback = remoteSyncFeedback,
              onRefreshServersClick = { triggerRemoteUpdate() },
              onDismissRemoteSyncFeedback = viewModel::clearRemoteSyncFeedback,
              onOpenBannerLink = { url ->
                try {
                  val parsed = Uri.parse(url)
                  if (parsed.scheme == "https" || parsed.scheme == "http") {
                    context.startActivity(Intent(Intent.ACTION_VIEW, parsed))
                  }
                } catch (_: Exception) {
                  viewModel.notifyMessage("လင့်ခ်ဖွင့်မရပါ")
                }
              },
            )
          }

          composable(ROUTE_NETWORKS) {
            NetworksScreen(
              networks = networks,
              selectedNetworkId = selectedNetwork?.id,
              onSelectNetwork = viewModel::selectNetwork,
              onUpdateClick = { triggerRemoteUpdate() },
              isUpdating = isFetchingRemoteServers,
            )
          }

          composable(ROUTE_SERVERS) {
            ServersScreen(
              servers = servers,
              selectedServerId = selectedServer?.id,
              onSelectServer = viewModel::selectServer,
              onSyncRemoteClick = { triggerRemoteUpdate() },
              isUpdating = isFetchingRemoteServers,
            )
          }

          composable(ROUTE_SETTINGS) {
            SettingsScreen(
              vpnSettings = vpnSettings,
              isDarkTheme = isDarkTheme,
              onToggleKillSwitch = viewModel::setKillSwitchEnabled,
              onToggleAutoReconnect = viewModel::setAutoReconnectEnabled,
              onToggleTheme = viewModel::toggleTheme,
              onOpenRedeemClick = { navController.navigate(ROUTE_REDEEM) },
              onNavigateBack = { navController.popBackStack() },
            )
          }

          composable(ROUTE_REDEEM) {
            RedeemCodeScreen(
              deviceId = viewModel.deviceId,
              isRedeeming = isRedeemingCode,
              lastOutcome = redeemOutcome,
              onRedeemSubmit = viewModel::redeemBuyerCode,
              onClearOutcome = viewModel::clearRedeemOutcome,
              onNavigateBack = { navController.popBackStack() },
            )
          }
        }
      }
    }
  }
}
