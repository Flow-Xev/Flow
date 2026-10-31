package app.zaid.flow.ui.screens.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.zaid.flow.R
import app.zaid.flow.ui.components.shared.FlowEmptyState

@Composable
internal fun HomeFeedDisabledState(modifier: Modifier = Modifier) {
    FlowEmptyState(
        title = stringResource(R.string.content_settings_home_feed_disabled_title),
        subtitle = stringResource(R.string.content_settings_home_feed_disabled_body),
        icon = Icons.Outlined.SmartDisplay,
        modifier = modifier,
    )
}
