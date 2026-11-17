package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.ui.TermuxCastViewModel
import com.example.ui.screens.ControlStudioScreen
import com.example.ui.screens.ScreenShareGateScreen
import com.example.ui.screens.ServerFilesScreen
import com.example.ui.screens.TermuxSetupScreen
import com.example.ui.theme.TermuxCastTheme
import kotlinx.coroutines.launch

const val ROUTE_CONTROL_STUDIO = "control_studio"
const val ROUTE_TERMUX_SETUP = "termux_setup"
const val ROUTE_SERVER_FILES = "server_files"

private data class TopNavItem(
  val route: String,
  val labelRes: Int,
  val selectedIcon: ImageVector,
  val unselectedIcon: ImageVector,
  val testTag: String,
)

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent { TermuxCastTheme { TermuxCastApp() } }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TermuxCastApp(viewModel: TermuxCastViewModel = viewModel()) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()

  // Mandatory Screen Share Gate: app remains locked until MediaProjection casting starts
  if (!uiState.nativeHostState.isHosting) {
    ScreenShareGateScreen(
      state = uiState,
      onRefreshTelemetry = viewModel::refreshTelemetry,
    )
    return
  }

  val navController = rememberNavController()
  val navBackStackEntry by navController.currentBackStackEntryAsState()
  val currentDestination = navBackStackEntry?.destination
  val snackbarHostState = remember { SnackbarHostState() }
  val scope = rememberCoroutineScope()

  val showMessage: (String) -> Unit = { msg ->
    scope.launch {
      snackbarHostState.currentSnackbarData?.dismiss()
      snackbarHostState.showSnackbar(msg)
    }
  }

  val navItems = remember {
    listOf(
      TopNavItem(
        route = ROUTE_CONTROL_STUDIO,
        labelRes = R.string.nav_studio,
        selectedIcon = Icons.Rounded.TouchApp,
        unselectedIcon = Icons.Outlined.TouchApp,
        testTag = "nav_control",
      ),
      TopNavItem(
        route = ROUTE_TERMUX_SETUP,
        labelRes = R.string.nav_setup,
        selectedIcon = Icons.Rounded.Terminal,
        unselectedIcon = Icons.Outlined.Terminal,
        testTag = "nav_setup",
      ),
      TopNavItem(
        route = ROUTE_SERVER_FILES,
        labelRes = R.string.nav_files,
        selectedIcon = Icons.Rounded.Code,
        unselectedIcon = Icons.Outlined.Code,
        testTag = "nav_files",
      ),
    )
  }

  val navigateTo: (String) -> Unit = { route ->
    navController.navigate(route) {
      popUpTo(navController.graph.findStartDestination().id) { saveState = true }
      launchSingleTop = true
      restoreState = true
    }
  }

  BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    val useRail = maxWidth >= 600.dp

    Scaffold(
      modifier = Modifier.fillMaxSize(),
      contentWindowInsets = WindowInsets.safeDrawing,
      containerColor = MaterialTheme.colorScheme.background,
      snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
      topBar = {
        CenterAlignedTopAppBar(
          title = {
            Text(
              text = stringResource(R.string.app_name),
              style = MaterialTheme.typography.titleLarge,
              fontWeight = FontWeight.Bold,
            )
          },
          colors =
            TopAppBarDefaults.centerAlignedTopAppBarColors(
              containerColor = MaterialTheme.colorScheme.background,
              titleContentColor = MaterialTheme.colorScheme.onBackground,
            ),
        )
      },
      bottomBar = {
        if (!useRail) {
          NavigationBar(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
          ) {
            navItems.forEach { item ->
              val selected = currentDestination?.hierarchy?.any { it.route == item.route } == true
              val label = stringResource(item.labelRes)
              NavigationBarItem(
                selected = selected,
                onClick = { navigateTo(item.route) },
                icon = {
                  Icon(
                    imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                    contentDescription = label,
                  )
                },
                label = { Text(label) },
                modifier = Modifier.testTag(item.testTag),
              )
            }
          }
        }
      },
    ) { innerPadding ->
      Row(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
        if (useRail) {
          NavigationRail(
            containerColor = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxHeight(),
          ) {
            navItems.forEach { item ->
              val selected = currentDestination?.hierarchy?.any { it.route == item.route } == true
              val label = stringResource(item.labelRes)
              NavigationRailItem(
                selected = selected,
                onClick = { navigateTo(item.route) },
                icon = {
                  Icon(
                    imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                    contentDescription = label,
                  )
                },
                label = { Text(label) },
                modifier = Modifier.testTag(item.testTag),
              )
            }
          }
        }

        Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
          NavHost(
            navController = navController,
            startDestination = ROUTE_CONTROL_STUDIO,
            modifier = Modifier.widthIn(max = 760.dp).fillMaxSize(),
          ) {
            composable(ROUTE_CONTROL_STUDIO) {
              ControlStudioScreen(
                state = uiState,
                onRefreshTelemetry = viewModel::refreshTelemetry,
                onUpdateRelayUrl = viewModel::updateRelayUrlInput,
                onToggleInternetRelay = viewModel::applyInternetRelay,
                onRegenerateRoomId = viewModel::regenerateRoomId,
                onOpenRelayServerFile = {
                  viewModel.selectFile("relay_py")
                  navigateTo(ROUTE_SERVER_FILES)
                },
                onUpdateServerUrl = viewModel::updateServerUrl,
                onProbeServer = viewModel::probeTermuxServer,
                onSetControlMode = viewModel::setControlMode,
                onSimulatedTap = viewModel::recordSimulatedTap,
                onSimulatedSwipe = viewModel::recordSimulatedSwipe,
                onKeyEvent = viewModel::recordKeyEvent,
                onShowMessage = showMessage,
              )
            }
            composable(ROUTE_TERMUX_SETUP) {
              TermuxSetupScreen(
                state = uiState,
                onToggleStep = viewModel::toggleSetupStep,
                onShowMessage = showMessage,
              )
            }
            composable(ROUTE_SERVER_FILES) {
              ServerFilesScreen(
                state = uiState,
                onSelectFile = viewModel::selectFile,
                onShowMessage = showMessage,
              )
            }
          }
        }
      }
    }
  }
}
