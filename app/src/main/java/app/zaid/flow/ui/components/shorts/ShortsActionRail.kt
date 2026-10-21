package app.zaid.flow.ui.components.shorts

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.zaid.flow.R
import app.zaid.flow.ui.components.shared.ChannelAvatarImage
import app.zaid.flow.ui.components.shared.LikeParticles
import app.zaid.flow.ui.components.shared.likePop
import app.zaid.flow.ui.components.videoplayer.controls.PlayerPillIconButton
import app.zaid.flow.ui.theme.PlayerScrimContent

// Measured from YouTube's Shorts rail: ~68dp between item centres, 24dp icons, 12sp labels,
// and a 36dp rounded-square channel avatar with a thin light outline at the bottom.
private val RailSpacing = 2.dp
private val RailButtonSize = 40.dp
private val RailIconSize = 24.dp
private val RailLabelHeight = 16.dp
private val RailLabelSize = 12.sp
private val AvatarSize = 36.dp
private val AvatarCorner = 8.dp
private val AvatarOutline = 1.5.dp
private val AvatarTopGap = 8.dp

/** The column of actions down a reel's right edge. Labels are the caller's: a count, a name, or nothing. */
@Composable
internal fun ShortsActionRail(
    isLiked: Boolean,
    likeLabel: String,
    onLikeClick: () -> Unit,
    commentLabel: String,
    onCommentsClick: () -> Unit,
    isSaved: Boolean,
    saveLabel: String,
    onSaveClick: () -> Unit,
    channelAvatarUrl: String,
    channelName: String,
    isDiscSpinning: Boolean,
    soundThumbnailUrl: String = "",
    onSoundClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(RailSpacing),
        modifier = modifier,
    ) {
        ShortsRailAction(
            icon = if (isLiked) ShortsHeartFilled else ShortsHeartOutline,
            label = likeLabel,
            contentDescription = stringResource(R.string.action_like),
            contentColor = if (isLiked) ShortsLikeRed else PlayerScrimContent,
            onClick = onLikeClick,
            pop = isLiked,
            burstColor = ShortsLikeRed,
        )
        ShortsRailAction(
            icon = Icons.Outlined.ChatBubbleOutline,
            label = commentLabel,
            contentDescription = stringResource(R.string.action_comments),
            onClick = onCommentsClick,
        )
        ShortsRailAction(
            icon = if (isSaved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
            label = saveLabel,
            contentDescription = stringResource(R.string.action_save),
            contentColor = if (isSaved) MaterialTheme.colorScheme.primary else PlayerScrimContent,
            onClick = onSaveClick,
        )
        Spacer(modifier = Modifier.height(AvatarTopGap))
        ShortsChannelAvatar(
            avatarUrl = soundThumbnailUrl.ifBlank { channelAvatarUrl },
            channelName = channelName,
            onClick = onSoundClick,
        )
    }
}

@Composable
private fun ShortsRailAction(
    icon: ImageVector,
    label: String,
    contentDescription: String,
    onClick: () -> Unit,
    contentColor: Color = PlayerScrimContent,
    /** Non-null on an action that toggles: the icon springs when it turns on. */
    pop: Boolean? = null,
    /** With [pop], the colour of the dots that fly out when it turns on. */
    burstColor: Color? = null,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(modifier = Modifier.size(RailButtonSize), contentAlignment = Alignment.Center) {
            if (pop != null && burstColor != null) {
                LikeParticles(active = pop, color = burstColor, modifier = Modifier.requiredSize(RailButtonSize * 2))
            }
            PlayerPillIconButton(
                onClick = onClick,
                icon = icon,
                contentDescription = contentDescription,
                buttonSize = RailButtonSize,
                iconSize = RailIconSize,
                iconModifier = if (pop != null) Modifier.likePop(pop, tiltDegrees = -14f) else Modifier,
                contentColor = contentColor,
                containerColor = Color.Transparent,
                haptic = HapticFeedbackType.TextHandleMove,
            )
        }
        // Every item reserves the label row so the rail keeps one even pitch, as YouTube's does.
        Box(modifier = Modifier.height(RailLabelHeight), contentAlignment = Alignment.Center) {
            if (label.isNotBlank()) {
                // A changed count rolls: the new value slides up into place as the old one leaves,
                // so liking visibly ticks the number instead of swapping it.
                AnimatedContent(
                    targetState = label,
                    transitionSpec = {
                        (slideInVertically(tween(220)) { it / 2 } + fadeIn(tween(220)))
                            .togetherWith(slideOutVertically(tween(180)) { -it / 2 } + fadeOut(tween(120)))
                    },
                    label = "railLabel",
                ) { text ->
                    Text(
                        text = text,
                        style =
                            MaterialTheme.typography.labelSmall.copy(
                                fontSize = RailLabelSize,
                                shadow = ShortsOverlayDefaults.TextShadow,
                            ),
                        color = PlayerScrimContent,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

/** The channel's avatar as YouTube draws it on a reel: a small rounded square with a thin light outline. */
@Composable
private fun ShortsChannelAvatar(
    avatarUrl: String,
    channelName: String,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(AvatarCorner)
    Box(
        modifier =
            Modifier
                .size(AvatarSize)
                .border(AvatarOutline, PlayerScrimContent, shape)
                .padding(AvatarOutline)
                .clip(shape)
                .clickable(onClick = onClick),
    ) {
        ChannelAvatarImage(
            url = avatarUrl,
            contentDescription = channelName,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
