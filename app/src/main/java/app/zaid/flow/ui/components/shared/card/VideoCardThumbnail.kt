package app.zaid.flow.ui.components.shared.card

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.zIndex
import app.zaid.flow.R
import app.zaid.flow.ui.components.shared.MediaIconBadge
import app.zaid.flow.ui.components.shared.MediaThumbnail
import app.zaid.flow.ui.components.shared.MediaThumbnailDefaults
import app.zaid.flow.ui.components.shared.VideoStatusBadge

/**
 * A card's thumbnail: the shared [MediaThumbnail] with the status, reminder and DeArrow badges on it.
 * The image is decorative because the card's title already names the video.
 *
 * [width] is unspecified for a stacked card, whose [modifier] fills the width instead.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun VideoCardThumbnail(
    state: VideoCardState,
    isUpcoming: Boolean,
    shape: Shape,
    modifier: Modifier = Modifier,
    width: Dp = Dp.Unspecified,
    previewActive: Boolean = false,
    preview: (@Composable BoxScope.() -> Unit)? = null,
) {
    val video = state.video
    MediaThumbnail(
        videoId = video.id,
        thumbnailUrl = state.thumbnailUrl,
        width = width,
        shape = shape,
        showWatchProgress = LocalVideoCardPreferences.current.showWatchProgress,
        modifier =
            modifier.zIndex(if (previewActive) 1f else 0f),
    ) {
        preview?.invoke(this)
        val badgeModifier = Modifier.padding(MediaThumbnailDefaults.BadgePadding)
        if (!previewActive) {
            VideoStatusBadge(
                isLive = video.isLive,
                isUpcoming = isUpcoming,
                durationSeconds = video.duration,
                modifier = badgeModifier.align(Alignment.BottomEnd),
            )
            if (state.showReminderBadge) {
                MediaIconBadge(
                    icon = Icons.Rounded.NotificationsActive,
                    contentDescription = stringResource(R.string.upcoming_video_reminder_badge),
                    modifier = badgeModifier.align(Alignment.TopStart),
                )
            }
            if (state.showDeArrowBadge) {
                MediaIconBadge(
                    icon = Icons.Outlined.AutoFixHigh,
                    contentDescription = stringResource(R.string.dearrow_badge),
                    modifier = badgeModifier.align(Alignment.TopEnd),
                )
            }
        }
    }
}
