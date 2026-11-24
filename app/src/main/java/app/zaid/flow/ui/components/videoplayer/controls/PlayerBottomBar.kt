package app.zaid.flow.ui.components.videoplayer.controls

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CloseFullscreen
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.zaid.flow.R
import app.zaid.flow.ui.theme.PlayerScrim
import app.zaid.flow.ui.theme.PlayerScrimAffordance
import app.zaid.flow.ui.theme.PlayerScrimContent
import app.zaid.flow.ui.theme.PlayerScrimContentSecondary
import coil3.compose.AsyncImage
import org.schabi.newpipe.extractor.stream.StreamSegment

/** A tappable text pill on video: the playback speed and the current quality. */
@Composable
private fun PlayerLabelPill(
    label: String,
    onClick: () -> Unit,
    height: Dp,
) {
    val haptics = LocalHapticFeedback.current
    Surface(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
            onClick()
        },
        color = PlayerScrimAffordance,
        shape = CircleShape,
        modifier =
            Modifier
                .height(height)
                .widthIn(min = height),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 12.dp),
        ) {
            Text(
                text = label,
                color = PlayerScrimContent,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}

/** Sizing shared by the pill row and the seek bar beneath it. */
data class PlayerBottomBarMetrics(
    val pillHeight: Dp,
    val pillsRowMinHeight: Dp,
    val actionSpacing: Dp,
    val horizontalPadding: Dp,
    val seekbarHorizontalPadding: Dp,
    val seekbarBottomPadding: Dp,
    val expandIconSize: Dp,
    val chapterMaxWidth: Dp,
)

/**
 * The pill row — comments, elapsed time, chapter, quality, fullscreen — sitting above the seek bar.
 */
@Composable
internal fun PlayerBottomBar(
    positionProvider: () -> Long,
    duration: Long,
    isLive: Boolean,
    isFullscreen: Boolean,
    isPortraitFullscreen: Boolean,
    showRemainingTime: Boolean,
    showCommentsButton: Boolean,
    isCommentsPanelOpen: Boolean,
    moreVideosThumbnail: String?,
    currentChapter: StreamSegment?,
    compactQualityLabel: String?,
    speedIndicatorLabel: String?,
    seekbarContent: PlayerSeekbarContent,
    metrics: PlayerBottomBarMetrics,
    actions: PlayerControlActions,
    onScrubProgress: (progress: Float, duration: Long) -> Unit,
    onScrubFinished: () -> Unit,
    isScrubbing: Boolean,
    hidePills: Boolean,
    modifier: Modifier = Modifier,
    isLayerVisible: () -> Boolean = { true },
    /** Landscape-only action pill (resize, queue, sleep, lock…) shown bottom-left like YouTube. */
    extraActions: (@Composable RowScope.() -> Unit)? = null,
) {
    val accentColor = MaterialTheme.colorScheme.primary
    // Faded rather than removed: the pills hold the height the seek bar sits at, and a bar that
    // moved out from under the finger mid-drag would break the scrub it is showing.
    val pillsAlpha by animateFloatAsState(
        targetValue = if (hidePills) 0f else 1f,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "hidePillsAlpha",
    )

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .playerEdgeScrim(ScrimEdge.Bottom, PlayerScrim.copy(alpha = BOTTOM_BAR_SCRIM_ALPHA))
                .padding(bottom = metrics.seekbarBottomPadding),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = metrics.pillsRowMinHeight)
                    .zIndex(1f)
                    .graphicsLayer { alpha = pillsAlpha }
                    .padding(
                        start = metrics.horizontalPadding,
                        end = metrics.horizontalPadding,
                        top = if (isFullscreen) 4.dp else 0.dp,
                    ),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(metrics.actionSpacing),
                modifier = Modifier.weight(1f),
            ) {
                PlayerTimePill(
                    positionProvider = positionProvider,
                    duration = duration,
                    isLive = isLive,
                    showRemainingTime = showRemainingTime,
                    onClick = { if (isLive) actions.onLiveClick() else actions.onToggleRemainingTime() },
                    modifier = Modifier.height(metrics.pillHeight),
                    containerColor =
                        if (isPortraitFullscreen) Color.Transparent else PlayerScrimAffordance,
                    isLayerVisible = isLayerVisible,
                )

                if (currentChapter != null) {
                    Surface(
                        onClick = actions.onChapterClick,
                        color = PlayerScrimAffordance,
                        shape = CircleShape,
                        modifier = Modifier.height(metrics.pillHeight),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        ) {
                            Text(
                                text = currentChapter.title,
                                style = MaterialTheme.typography.labelSmall,
                                color = PlayerScrimContent,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = metrics.chapterMaxWidth),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Rounded.ChevronRight,
                                contentDescription = null,
                                tint = PlayerScrimContentSecondary,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(metrics.actionSpacing),
            ) {
                if (speedIndicatorLabel != null) {
                    PlayerLabelPill(
                        label = speedIndicatorLabel,
                        onClick = actions.onSpeedClick,
                        height = metrics.pillHeight,
                    )
                }

                if (compactQualityLabel != null) {
                    PlayerLabelPill(
                        label = compactQualityLabel,
                        onClick = actions.onQualityClick,
                        height = metrics.pillHeight,
                    )
                }

                PlayerPillIconButton(
                    onClick = actions.onFullscreenClick,
                    icon = if (isFullscreen) Icons.Rounded.CloseFullscreen else Icons.Rounded.OpenInFull,
                    contentDescription = stringResource(R.string.fullscreen),
                    buttonSize = metrics.pillHeight,
                    iconSize = metrics.expandIconSize,
                )
            }
        }

        PlayerSeekbarRow(
            positionProvider = positionProvider,
            duration = duration,
            isLive = isLive,
            content = seekbarContent,
            edgeAligned = !isFullscreen,
            thin = false,
            horizontalPadding = metrics.seekbarHorizontalPadding,
            onScrubProgress = onScrubProgress,
            onScrubFinished = onScrubFinished,
            isScrubbing = isScrubbing,
            seekbarZIndex = 2f,
        )

        if (moreVideosThumbnail != null || showCommentsButton || extraActions != null) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .graphicsLayer { alpha = pillsAlpha }
                        .padding(horizontal = metrics.horizontalPadding)
                        .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showCommentsButton) {
                    PlayerPillIconButton(
                        onClick = actions.onCommentsClick,
                        icon = Icons.Outlined.ChatBubbleOutline,
                        contentDescription = stringResource(R.string.comments),
                        buttonSize = 40.dp,
                        iconSize = 20.dp,
                        contentColor = if (isCommentsPanelOpen) accentColor else PlayerScrimContent,
                    )
                }
                if (extraActions != null) {
                    Spacer(modifier = Modifier.width(metrics.actionSpacing))
                    Surface(color = PlayerScrimAffordance, shape = CircleShape) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 4.dp),
                            content = extraActions,
                        )
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                if (moreVideosThumbnail != null) {
                    MoreVideosPill(
                        thumbnailUrl = moreVideosThumbnail,
                        onClick = actions.onMoreVideosClick,
                    )
                }
            }
        }
    }
}

/** "More videos" with the next video's thumbnail, bottom-right under the seek bar like YouTube's landscape player. */
@Composable
private fun MoreVideosPill(
    thumbnailUrl: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        color = PlayerScrimAffordance,
        shape = CircleShape,
        modifier = modifier.height(40.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 4.dp),
        ) {
            Text(
                text = stringResource(R.string.more_videos),
                style = MaterialTheme.typography.labelLarge,
                color = PlayerScrimContent,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            Spacer(modifier = Modifier.width(10.dp))
            AsyncImage(
                model = thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(width = 56.dp, height = 32.dp).clip(RoundedCornerShape(16.dp)),
            )
        }
    }
}
