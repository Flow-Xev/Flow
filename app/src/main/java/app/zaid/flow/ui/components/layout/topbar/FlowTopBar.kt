package app.zaid.flow.ui.components.layout.topbar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import app.zaid.flow.R

/**
 * Which screens show the app-shell actions ([FlowGlobalActions]).
 *
 * [Auto] is the default and resolves from the bar's own shape: a bar with no back affordance is a
 * root destination, and every root destination must be able to reach Notifications and Settings.
 * A new root tab therefore cannot forget them — it would have to opt out with [None].
 */
enum class FlowGlobalActionsMode {
    Auto,
    Always,
    None,
}

/**
 * The single top bar for every non-player screen, shaped like YouTube's: flat, 56 dp, 20 sp title.
 *
 * Pass [onBack] for a detail screen; leave it null for a root tab. See [FlowTopBarDefaults] for why
 * the colours and window insets differ from the Material defaults.
 *
 * @param leading replaces the default back button. A bar with a [leading] slot is still treated as
 *   a root destination.
 * @param actions screen-specific actions. Keep this to two on a root destination; put the rest in
 *   [FlowTopBarOverflow] so the title keeps its width.
 */
@Composable
fun FlowTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    leading: @Composable (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    globalActions: FlowGlobalActionsMode = FlowGlobalActionsMode.Auto,
    windowInsets: WindowInsets = FlowTopBarDefaults.WindowInsets,
) {
    FlowTopBar(
        modifier = modifier,
        title = {
            FlowTopBarTitle(title = title, subtitle = subtitle)
        },
        onBack = onBack,
        leading = leading,
        actions = actions,
        globalActions = globalActions,
        windowInsets = windowInsets,
    )
}

/**
 * Slot-based overload for bars whose title is not plain text — a branded wordmark, a tab row, or an
 * inline field. Prefer the string overload; this exists so such screens still share the container,
 * colours, insets and global-action behaviour instead of hand-rolling a bar.
 *
 * Drawn as a plain [Row] rather than Material's `TopAppBar`, whose 64 dp height cannot be changed;
 * YouTube's bar is 56 dp ([FlowTopBarDefaults.Height]).
 */
@Composable
fun FlowTopBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    leading: @Composable (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    globalActions: FlowGlobalActionsMode = FlowGlobalActionsMode.Auto,
    windowInsets: WindowInsets = FlowTopBarDefaults.WindowInsets,
) {
    val showGlobalActions =
        when (globalActions) {
            FlowGlobalActionsMode.Always -> true
            FlowGlobalActionsMode.None -> false
            FlowGlobalActionsMode.Auto -> onBack == null
        }
    val hasNavigation = leading != null || onBack != null

    CompositionLocalProvider(LocalContentColor provides FlowTopBarDefaults.iconColor) {
        Row(
            modifier =
                modifier
                    .fillMaxWidth()
                    .background(FlowTopBarDefaults.containerColor)
                    .windowInsetsPadding(windowInsets)
                    .height(FlowTopBarDefaults.Height)
                    .padding(horizontal = FlowTopBarDefaults.HorizontalPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                leading != null -> {
                    leading()
                }

                onBack != null -> {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.btn_back),
                        )
                    }
                }
            }
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(
                            start =
                                if (hasNavigation) {
                                    FlowTopBarDefaults.TitleGap
                                } else {
                                    FlowTopBarDefaults.TitleStartInset - FlowTopBarDefaults.HorizontalPadding
                                },
                        ),
                contentAlignment = Alignment.CenterStart,
            ) {
                CompositionLocalProvider(
                    LocalContentColor provides FlowTopBarDefaults.titleColor,
                    LocalTextStyle provides FlowTopBarDefaults.titleStyle,
                ) {
                    title()
                }
            }
            actions()
            if (showGlobalActions) {
                FlowGlobalActionsRow()
            }
        }
    }
}

@Composable
private fun FlowTopBarTitle(
    title: String,
    subtitle: String?,
) {
    if (subtitle == null) {
        Text(
            text = title,
            style = FlowTopBarDefaults.titleStyle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    } else {
        Column {
            Text(
                text = title,
                style = FlowTopBarDefaults.titleStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = FlowTopBarDefaults.subtitleStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
