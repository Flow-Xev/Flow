package app.zaid.flow.ui

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import app.zaid.flow.data.subscriptions.refreshSubscriptionsAtStartup
import app.zaid.flow.player.DeepFlowManager
import app.zaid.flow.player.EnhancedMusicPlayerManager
import app.zaid.flow.player.SleepTimerManager
import app.zaid.flow.ui.components.layout.navigation.FlowTab
import kotlinx.coroutines.flow.collectLatest

/** App-wide effects with no UI of their own: preference propagation and the global snackbar feeds. */
@Composable
internal fun FlowAppSideEffects(
    snackbarHostState: SnackbarHostState,
    sleepTimerCloseAppOnExpiry: Boolean,
    subscriptionRefreshOnStartup: Boolean,
) {
    val context = LocalContext.current

    LaunchedEffect(sleepTimerCloseAppOnExpiry) {
        SleepTimerManager.updatePreferredCloseAppOnExpiry(sleepTimerCloseAppOnExpiry)
    }

    LaunchedEffect(subscriptionRefreshOnStartup) {
        if (subscriptionRefreshOnStartup) {
            refreshSubscriptionsAtStartup(context.applicationContext)
        }
    }

    LaunchedEffect(snackbarHostState) {
        DeepFlowManager.messages.collectLatest { message ->
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Short,
            )
        }
    }

    LaunchedEffect(snackbarHostState) {
        EnhancedMusicPlayerManager.playbackWarnings.collectLatest { message ->
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Long,
            )
        }
    }
}

/** Detail screens keep the tab they were opened from, so this holds the last tab root navigated to. */
@Composable
internal fun rememberSelectedFlowTab(
    navController: NavHostController,
    defaultTab: FlowTab,
): State<FlowTab> {
    val selectedTab = remember { mutableStateOf(defaultTab) }
    LaunchedEffect(navController) {
        navController.currentBackStackEntryFlow.collect { entry -> entry.flowTab()?.let { selectedTab.value = it } }
    }
    return selectedTab
}

/** Follows a changed default tab, and leaves Home for the default tab once the user hides Home. */
@Composable
internal fun FlowStartTabEffects(
    navController: NavHostController,
    currentRoute: MutableState<String>,
    defaultTab: FlowTab,
    isHomeNavigationEnabled: Boolean,
    needsOnboarding: Boolean?,
) {
    LaunchedEffect(defaultTab) {
        currentRoute.value = defaultTab.route
    }

    LaunchedEffect(isHomeNavigationEnabled, currentRoute.value, defaultTab, needsOnboarding) {
        if (needsOnboarding == false && !isHomeNavigationEnabled && currentRoute.value == "home") {
            currentRoute.value = defaultTab.route
            navController.navigate(defaultTab.route) {
                popUpTo("home") { inclusive = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }
}
