package app.zaid.flow.ui.components.layout.topbar

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zaid.flow.R

/**
 * The Notifications and Settings actions every root destination gets.
 *
 * Renders nothing when the shell has not provided [LocalFlowGlobalActions] — the case in previews
 * and in isolated Compose tests.
 */
@Composable
internal fun FlowGlobalActionsRow(showSettings: Boolean = true) {
    val actions = LocalFlowGlobalActions.current ?: return
    val unreadCount by actions.unreadNotifications.collectAsStateWithLifecycle()

    FlowNotificationsAction(
        unreadCount = unreadCount,
        onClick = actions.onOpenNotifications,
    )
    if (showSettings) {
        IconButton(onClick = actions.onOpenSettings) {
            Icon(
                imageVector = Icons.Outlined.Settings,
                contentDescription = stringResource(R.string.settings),
            )
        }
    }
}

@Composable
private fun FlowNotificationsAction(
    @Suppress("UNUSED_PARAMETER") unreadCount: Int,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        Box(contentAlignment = Alignment.TopEnd) {
            Icon(
                imageVector = Icons.Outlined.Notifications,
                contentDescription = stringResource(R.string.notifications),
            )
            // No unread-count badge: the bell stays a plain icon, as in YouTube.
        }
    }
}

/** One entry in a top bar overflow menu, drawn as a plain Material 3 `DropdownMenuItem`. */
@Immutable
data class FlowTopBarMenuItem(
    val label: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val icon: ImageVector? = null,
)

/**
 * Collapses extra actions behind a single overflow button.
 *
 * A root destination has room for two screen-specific actions before the two global ones; anything
 * past that belongs here rather than competing with the title for width.
 */
@Composable
fun FlowTopBarOverflow(
    items: List<FlowTopBarMenuItem>,
    modifier: Modifier = Modifier,
    contentDescription: String = stringResource(R.string.more_options),
) {
    if (items.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = contentDescription,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.label) },
                    enabled = item.enabled,
                    leadingIcon =
                        item.icon?.let { icon ->
                            { Icon(imageVector = icon, contentDescription = null) }
                        },
                    onClick = {
                        expanded = false
                        item.onClick()
                    },
                )
            }
        }
    }
}
