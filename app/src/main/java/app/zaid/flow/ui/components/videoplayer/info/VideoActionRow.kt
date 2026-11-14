package app.zaid.flow.ui.components.videoplayer.info

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.StickyNote2
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.outlined.StickyNote2
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.zaid.flow.R
import app.zaid.flow.ui.components.ai.FlowAiSparkleVector
import app.zaid.flow.ui.components.ai.LocalFlowAiLauncher
import app.zaid.flow.ui.components.shared.likePop
import app.zaid.flow.ui.components.shared.pressScale
import app.zaid.flow.utils.formatViewCount

/** The capsule is exactly four buttons wide (the like button's own width + 3 x 32); the rest wait a swipe away. */
private val CapsuleOtherButtonsWidth = 96.dp
private val CapsuleHeight = 34.dp
private val CapsuleButtonWidth = 32.dp
private val CapsuleIconSize = 20.dp

/**
 * The action capsule under a video: one rounded pill showing four buttons that scrolls sideways
 * (snapping button by button) to the rest. Icon-only; a state a button owns (liked, saved, a note)
 * turns its icon the accent colour and fills it.
 *
 * The ORDER is the app's original one: Like, Dislike, Ask AI, Download, Products, Save, Share,
 * Note, Background.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun VideoActionRow(
    likeState: String,
    likeCount: Long? = null,
    dislikeCount: Long?,
    onLikeClick: () -> Unit,
    onDislikeClick: () -> Unit,
    onShareClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onSaveClick: () -> Unit,
    onBackgroundPlayClick: () -> Unit,
    onCopyLinkClick: () -> Unit = {},
    onCopyLinkAtTimeClick: () -> Unit = {},
    isSaved: Boolean = false,
    isDownloaded: Boolean = false,
    onNoteClick: (() -> Unit)? = null,
    hasNote: Boolean = false,
    isDeviceFile: Boolean = false,
    taggedProductCount: Int = 0,
    onTaggedProductsClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val openAi = LocalFlowAiLauncher.current
    val capsule =
        Modifier
            .height(CapsuleHeight)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)

    if (isDeviceFile) {
        // A file on the device has no likes, link or download; it can play in the background and be shared.
        Row(modifier = modifier.then(capsule), verticalAlignment = Alignment.CenterVertically) {
            CapsuleIconButton(
                icon = Icons.Outlined.Headphones,
                description = stringResource(R.string.player_action_background),
                onClick = onBackgroundPlayClick,
            )
            CapsuleIconButton(
                icon = Icons.Outlined.Share,
                description = stringResource(R.string.share),
                onClick = onShareClick,
            )
        }
        return
    }

    val listState = rememberLazyListState()
    // The like button is a plain 32dp icon button until there is a count to show ("1.2M"); then it grows
    // to fit the count and the capsule follows, so nothing spills out.
    var likeItemWidthPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val capsuleWidth =
        if (likeItemWidthPx == 0) CapsuleButtonWidth + 1.dp + CapsuleOtherButtonsWidth
        else with(density) { likeItemWidthPx.toDp() } + CapsuleOtherButtonsWidth
    LazyRow(
        state = listState,
        flingBehavior = rememberSnapFlingBehavior(listState),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.width(capsuleWidth).then(capsule),
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxHeight().onSizeChanged { likeItemWidthPx = it.width },
            ) {
                CapsuleLikeButton(isLiked = likeState == LIKED, likeCount = likeCount, onClick = onLikeClick)
                // A hairline between like and dislike, as in a split button.
                Box(
                    Modifier
                        .width(1.dp)
                        .height(16.dp)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)),
                )
            }
        }
        item {
            val isDisliked = likeState == DISLIKED
            CapsuleIconButton(
                icon = if (isDisliked) Icons.Filled.ThumbDown else Icons.Outlined.ThumbDown,
                description = stringResource(R.string.player_action_dislike),
                active = isDisliked,
                popTilt = 18f,
                haptic = if (isDisliked) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn,
                onClick = onDislikeClick,
            )
        }
        if (openAi != null) {
            item {
                CapsuleIconButton(
                    icon = FlowAiSparkleVector,
                    description = stringResource(R.string.flow_ai_action),
                    onClick = openAi,
                )
            }
        }
        item {
            CapsuleIconButton(
                icon = if (isDownloaded) Icons.Outlined.CheckCircle else Icons.Outlined.Download,
                description = if (isDownloaded) stringResource(R.string.downloaded) else stringResource(R.string.download),
                haptic = if (isDownloaded) HapticFeedbackType.ToggleOff else HapticFeedbackType.Confirm,
                onClick = onDownloadClick,
            )
        }
        if (taggedProductCount > 0) {
            item {
                CapsuleIconButton(
                    icon = Icons.Outlined.ShoppingBag,
                    description = pluralStringResource(R.plurals.video_products_action, taggedProductCount, taggedProductCount),
                    onClick = onTaggedProductsClick,
                )
            }
        }
        item {
            CapsuleIconButton(
                icon = if (isSaved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                description = if (isSaved) stringResource(R.string.saved) else stringResource(R.string.save),
                active = isSaved,
                haptic = if (isSaved) HapticFeedbackType.ToggleOff else HapticFeedbackType.Confirm,
                onClick = onSaveClick,
            )
        }
        item {
            CapsuleIconButton(
                icon = Icons.Outlined.Share,
                description = stringResource(R.string.share),
                onClick = onShareClick,
            )
        }
        if (onNoteClick != null) {
            item {
                CapsuleIconButton(
                    icon = if (hasNote) Icons.Filled.StickyNote2 else Icons.Outlined.StickyNote2,
                    description = stringResource(if (hasNote) R.string.note_title else R.string.note_add),
                    active = hasNote,
                    haptic = if (hasNote) HapticFeedbackType.ToggleOff else HapticFeedbackType.Confirm,
                    onClick = onNoteClick,
                )
            }
        }
        item {
            CapsuleIconButton(
                icon = Icons.Outlined.Headphones,
                description = stringResource(R.string.player_action_background),
                onClick = onBackgroundPlayClick,
            )
        }
    }
}

/** Pure white, always: every capsule icon and label, whatever its state, with no tint and no transparency. */
@Composable
private fun capsuleContentColor(): Color = Color.White

/** Like: the one wide button of the capsule, with its count beside the thumb. */
@Composable
private fun CapsuleLikeButton(
    isLiked: Boolean,
    likeCount: Long?,
    onClick: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    // No count (hidden or zero) means no label at all: just the thumb, the same size as the other buttons.
    val likeText = if (likeCount != null && likeCount > 0) formatViewCount(likeCount) else null
    val color = capsuleContentColor()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier =
            Modifier
                .animateContentSize()
                .then(if (likeText == null) Modifier.width(CapsuleButtonWidth) else Modifier)
                .fillMaxHeight()
                .pressScale(interaction, pressedScale = 0.95f)
                .padding(horizontal = 2.dp, vertical = 3.dp)
                .clip(CircleShape)
                .clickable(interactionSource = interaction, indication = null, role = Role.Button) {
                    haptics.performHapticFeedback(if (isLiked) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                    onClick()
                }
                .then(if (likeText == null) Modifier else Modifier.padding(start = 8.dp, end = 4.dp)),
    ) {
        Icon(
            imageVector = if (isLiked) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
            contentDescription = stringResource(R.string.like),
            tint = color,
            modifier = Modifier.likePop(isLiked, tiltDegrees = -18f).size(CapsuleIconSize),
        )
        if (likeText != null) {
            Spacer(modifier = Modifier.width(3.dp))
            AnimatedContent(
                targetState = likeText to isLiked,
                transitionSpec = {
                    val up = targetState.second
                    (slideInVertically(tween(220)) { if (up) it else -it } + fadeIn(tween(160))) togetherWith
                        (slideOutVertically(tween(220)) { if (up) -it else it } + fadeOut(tween(120)))
                },
                label = "likeCount",
            ) { (text, _) ->
                Text(
                    text = text,
                    color = color,
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

/** An icon-only capsule button: 32dp wide, the capsule's full height, and a little squeeze on press. */
@Composable
private fun CapsuleIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    active: Boolean = false,
    tint: Color? = null,
    popTilt: Float? = null,
    haptic: HapticFeedbackType = HapticFeedbackType.Confirm,
) {
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val color = tint ?: capsuleContentColor()
    val iconModifier =
        when {
            popTilt != null -> Modifier.likePop(active, tiltDegrees = popTilt)
            else -> Modifier
        }.size(CapsuleIconSize)
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .width(CapsuleButtonWidth)
                .fillMaxHeight()
                .pressScale(interaction, pressedScale = 0.95f)
                .padding(horizontal = 2.dp, vertical = 3.dp)
                .clip(CircleShape)
                .clickable(interactionSource = interaction, indication = null, role = Role.Button) {
                    haptics.performHapticFeedback(haptic)
                    onClick()
                },
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = color,
            modifier = iconModifier,
        )
    }
}

/** An action that happens once and holds no state. */
@Composable
private fun ActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    haptic: HapticFeedbackType = HapticFeedbackType.Confirm,
) {
    val haptics = LocalHapticFeedback.current
    androidx.compose.material3.FilledTonalButton(
        onClick = {
            haptics.performHapticFeedback(haptic)
            onClick()
        },
        shapes = ButtonDefaults.shapes(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        modifier = Modifier.height(36.dp),
    ) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = label, style = MaterialTheme.typography.labelLarge)
    }
}

/** Unchanged wrapper so the Shorts action column keeps its current chip look. */
@Composable
internal fun ActionChip(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) = ActionButton(icon = icon, label = label, onClick = onClick)

private const val LIKED = "LIKED"
private const val DISLIKED = "DISLIKED"
