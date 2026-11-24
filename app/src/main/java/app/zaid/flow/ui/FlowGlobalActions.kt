package app.zaid.flow.ui.components.layout.topbar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * App-shell destinations that must stay reachable from every root screen.
 *
 * Cast and Settings must stay reachable from every root destination. Root tabs 3 (Subscriptions)
 * and 4 (Library) can never be hidden, so rendering these actions on every backless destination
 * guarantees reachability for any navigation configuration.
 */
@Immutable
data class FlowGlobalActions(
    val onOpenCast: () -> Unit,
    val onOpenSettings: () -> Unit,
)

/**
 * Static because the provided value is remembered for the lifetime of the shell and never changes;
 * reading it must never invalidate a screen.
 */
val LocalFlowGlobalActions = staticCompositionLocalOf<FlowGlobalActions?> { null }

/**
 * Publishes the shell's global actions to every [FlowTopBar] below it. Call once, around the
 * `NavHost`.
 */
@Composable
fun ProvideFlowGlobalActions(
    onOpenCast: () -> Unit,
    onOpenSettings: () -> Unit,
    content: @Composable () -> Unit,
) {
    val actions =
        remember(onOpenCast, onOpenSettings) {
            FlowGlobalActions(
                onOpenCast = onOpenCast,
                onOpenSettings = onOpenSettings,
            )
        }
    CompositionLocalProvider(LocalFlowGlobalActions provides actions, content = content)
}
