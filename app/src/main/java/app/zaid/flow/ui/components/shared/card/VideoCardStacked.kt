package app.zaid.flow.ui.components.shared.card

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.zaid.flow.data.model.Video
import app.zaid.flow.ui.components.shared.ChannelAvatarStack
import app.zaid.flow.ui.theme.amoledCardOutline
import app.zaid.flow.ui.components.shared.videoMetadataLine

/** The thumbnail across the full width with the details beneath: feeds and grids. */
@Composable
internal fun VideoCardStacked(
    video: Video,
    onClick: () -> Unit,
    showChannel: Boolean,
    useInternalPadding: Boolean,
    reason: String?,
    previewActive: Boolean,
    thumbnailPreview: (@Composable BoxScope.() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val state = rememberVideoCardState(video)
    val cardPreferences = LocalVideoCardPreferences.current
    val actions = LocalVideoCardActions.current

    val previewController = LocalInlineVideoPreviewController.current
    // Opening a previewing card hands its playhead to the main player, which then starts there.
    val openVideo: () -> Unit = {
        previewController?.handOffToPlayer(video.id)
        onClick()
    }
    // While previewing, the card expands into a rounded container: edge-to-edge video, the seekbar
    // beneath it, then the title row, like YouTube's feed preview.
    val expandedShape = RoundedCornerShape(24.dp)
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .then(
                    if (previewActive) {
                        Modifier
                            .padding(horizontal = if (useInternalPadding) VideoCardDefaults.Inset else 8.dp)
                            .clip(expandedShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .amoledCardOutline(expandedShape)
                    } else {
                        Modifier
                            .then(if (useInternalPadding) Modifier.padding(horizontal = 8.dp) else Modifier)
                            .padding(vertical = 6.dp)
                            .clip(RoundedCornerShape(28.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .amoledCardOutline(RoundedCornerShape(28.dp))
                    },
                ).videoCardClickable(state, openVideo)
                .animateContentSize(),
    ) {
        VideoCardThumbnail(
            state = state,
            isUpcoming = video.isUpcoming,
            shape = if (previewActive) RectangleShape else RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth().then(if (previewActive) Modifier else Modifier.padding(8.dp)),
            previewActive = previewActive,
            preview = thumbnailPreview,
        )
        if (previewActive && previewController != null) {
            InlineVideoPreviewSeekbar(previewController)
        }

        // The avatar's 48 dp target overhangs its 40 dp image by 4 dp, so the row gives those 4 dp back
        // and the avatar still lines up where it always has.
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = if (showChannel) 8.dp else 12.dp, top = if (previewActive) 0.dp else 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(if (showChannel) 8.dp else 12.dp),
        ) {
            if (showChannel) {
                ChannelAvatarStack(
                    urls = state.avatarUrls,
                    contentDescription = state.channelName,
                    avatarSize = VideoCardDefaults.AvatarSize,
                    modifier =
                        Modifier
                            .minimumInteractiveComponentSize()
                            .clip(CircleShape)
                            .videoCardChannelClickable(state),
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = state.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                Text(
                    text =
                        videoMetadataLine(
                            video = video,
                            isUpcoming = video.isUpcoming,
                            channelName = state.channelName,
                            includeChannel = showChannel,
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (video.isUpcoming) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                MembersOnlyLabel(video)
                reason?.let { CardReasonLabel(it) }
            }

            VideoCardSideActions(
                state = state,
                showWatched = false,
                onWatched = actions.onWatched,
                alignTo = 12.dp,
            )
        }

        if (cardPreferences.actionsEnabled || cardPreferences.markWatchedEnabled) {
            VideoCardFeedback(
                state = state,
                showRating = cardPreferences.actionsEnabled,
                showWatched = cardPreferences.markWatchedEnabled,
                actions = actions,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = VideoCardDefaults.Inset)
                        .padding(bottom = VideoCardDefaults.Inset),
            )
        }
    }

    VideoCardSheets(state = state, showChannel = showChannel)
}
