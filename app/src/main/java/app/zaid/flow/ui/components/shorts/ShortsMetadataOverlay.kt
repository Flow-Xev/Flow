package app.zaid.flow.ui.components.shorts

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.zaid.flow.R
import app.zaid.flow.data.model.ShortLinkedVideo
import app.zaid.flow.data.model.ShortVideo
import app.zaid.flow.data.model.Video
import app.zaid.flow.ui.components.layout.navigation.LocalMediaNavigator
import app.zaid.flow.ui.components.shared.ChannelAvatarImage
import app.zaid.flow.ui.components.shared.FlowSubscribeButton
import app.zaid.flow.ui.components.shared.FlowSubscribeButtonSize
import app.zaid.flow.ui.theme.PlayerScrim
import app.zaid.flow.ui.theme.PlayerScrimContent
import app.zaid.flow.ui.theme.PlayerScrimPanel
import app.zaid.flow.utils.YouTubeLink

private val AvatarSize = 28.dp
private val ChannelRowSpacing = 8.dp
private val TitleSpacing = 0.dp

/**
 * The channel row always has this height, whether or not a subscribe toggle or bag is beside the
 * name, so the avatar keeps the same clear gap above the title in every state. It matches the
 * 40 dp touch targets, and the avatar is centred in it.
 */
private val ChannelRowHeight = 40.dp
private val LinkedVideoSpacing = 4.dp
private val LinkedVideoHeight = 22.dp
private val LinkedVideoMaxWidth = 170.dp
private val LinkedVideoIconSize = 14.dp
private val SubscribeToggleTouchSize = 40.dp
private val SubscribeToggleSize = 28.dp
private val SubscribeToggleIconSize = 18.dp
private val SubscribeToggleBorder = 1.dp
private val ProductsButtonTouchSize = 40.dp
private val ProductsButtonSize = 28.dp
private val ProductsButtonBorder = 1.dp
private val ProductsBadgeHeight = 15.dp
private val ProductsBadgeHorizontalPadding = 3.dp
private val ProductsBadgeRing = 1.dp
private const val PRODUCTS_BADGE_MAX = 99
private val ProductsButtonIconSize = 16.dp

/** Channel, title and the video the reel links to, down a reel's left edge. */
@Composable
internal fun ShortsMetadataOverlay(
    short: ShortVideo,
    video: Video,
    isSubscribed: Boolean,
    style: ShortsOverlayStyle,
    onChannelClick: () -> Unit,
    onSubscribeToggle: () -> Unit,
    onDescriptionClick: () -> Unit,
    modifier: Modifier = Modifier,
    taggedProductCount: Int = 0,
    onTaggedProductsClick: () -> Unit = {},
) {
    Column(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.height(ChannelRowHeight).clickable(onClick = onChannelClick),
        ) {
            ChannelAvatarImage(
                url = short.channelThumbnailUrl,
                contentDescription = short.channelName,
                modifier =
                    Modifier
                        .size(AvatarSize)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Spacer(modifier = Modifier.width(ChannelRowSpacing))
            Text(
                text = short.channelName,
                style = MaterialTheme.typography.titleSmall.copy(shadow = ShortsOverlayDefaults.TextShadow),
                fontWeight = FontWeight.SemiBold,
                color = PlayerScrimContent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            // Once subscribed the reel shows just the channel name, with no subscribe control.
            if (!isSubscribed) {
                if (style.subscribeControl == ShortsSubscribeControl.Button) Spacer(modifier = Modifier.width(ChannelRowSpacing))
                when (style.subscribeControl) {
                    ShortsSubscribeControl.IconToggle -> {
                        ShortsSubscribeIconToggle(
                            isSubscribed = false,
                            channelName = short.channelName,
                            onToggle = onSubscribeToggle,
                        )
                    }

                    ShortsSubscribeControl.Button -> {
                        FlowSubscribeButton(
                            isSubscribed = false,
                            onSubscribeClick = onSubscribeToggle,
                            onUnsubscribeClick = onSubscribeToggle,
                            size = FlowSubscribeButtonSize.Compact,
                        )
                    }
                }
            }
            if (taggedProductCount > 0) {
                // The icon toggle and the bag each carry transparent touch padding, which is gap enough.
                if (isSubscribed || style.subscribeControl == ShortsSubscribeControl.Button) {
                    Spacer(modifier = Modifier.width(ChannelRowSpacing / 2))
                }
                ShortsProductsButton(count = taggedProductCount, onClick = onTaggedProductsClick)
            }
        }

        Spacer(modifier = Modifier.height(TitleSpacing))

        // #tags are not drawn on the reel; tapping the title opens the description sheet.
        val titleWithoutTags = remember(short.title) { stripHashtags(short.title) }
        Text(
            text = titleWithoutTags.ifBlank { short.title },
            style = MaterialTheme.typography.bodyMedium.copy(shadow = ShortsOverlayDefaults.TextShadow),
            color = PlayerScrimContent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clickable(onClick = onDescriptionClick),
        )

        short.linkedVideo?.let { linkedVideo ->
            Spacer(modifier = Modifier.height(LinkedVideoSpacing))
            ShortsLinkedVideoChip(linkedVideo)
        }
    }
}

private val HashtagRegex = Regex("""#\S+""")

/** The title with its #tags removed and the leftover whitespace tidied. */
internal fun stripHashtags(title: String): String = title.replace(HashtagRegex, "").replace(Regex("\\s+"), " ").trim()

/** The compact subscribe control of the simple layout: a square that flips from a plus to a tick. */
@Composable
private fun ShortsSubscribeIconToggle(
    isSubscribed: Boolean,
    channelName: String,
    onToggle: () -> Unit,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val description = stringResource(if (isSubscribed) R.string.unsubscribe else R.string.action_subscribe)
    val contentColor = if (isSubscribed) PlayerScrimContent else MaterialTheme.colorScheme.onPrimary
    Surface(
        onClick = {
            onToggle()
            val toast =
                if (isSubscribed) {
                    context.getString(R.string.unsubscribed_from, channelName)
                } else {
                    context.getString(R.string.subscribed_to, channelName)
                }
            Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        },
        shape = MaterialTheme.shapes.small,
        color = Color.Transparent,
        contentColor = contentColor,
        modifier = Modifier.size(SubscribeToggleTouchSize),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = if (isSubscribed) Color.Transparent else MaterialTheme.colorScheme.primary,
                contentColor = contentColor,
                border = if (isSubscribed) BorderStroke(SubscribeToggleBorder, PlayerScrimContent) else null,
                modifier = Modifier.size(SubscribeToggleSize),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isSubscribed) Icons.Default.Check else Icons.Default.Add,
                        contentDescription = description,
                        modifier = Modifier.size(SubscribeToggleIconSize),
                    )
                }
            }
        }
    }
}

/**
 * Round, outline-only shopping-bag button that sits after Subscribe, with the number of tagged
 * products on a small badge at its top-right corner.
 */
@Composable
private fun ShortsProductsButton(
    count: Int,
    onClick: () -> Unit,
) {
    val label = pluralStringResource(R.plurals.video_products_count, count, count)
    Box(
        modifier = Modifier.size(ProductsButtonTouchSize).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = Color.Transparent,
            contentColor = PlayerScrimContent,
            border = BorderStroke(ProductsButtonBorder, PlayerScrimContent),
            modifier = Modifier.size(ProductsButtonSize),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.ShoppingBag, contentDescription = null, modifier = Modifier.size(ProductsButtonIconSize))
            }
        }
        // A small pill pinned to the ring's top-right corner. The thin dark ring around it keeps
        // it legible on any footage, and it grows sideways (99+) instead of covering the reel.
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            border = BorderStroke(ProductsBadgeRing, PlayerScrim),
            modifier = Modifier.align(Alignment.TopEnd).height(ProductsBadgeHeight).widthIn(min = ProductsBadgeHeight),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = ProductsBadgeHorizontalPadding)) {
                Text(
                    text = if (count > PRODUCTS_BADGE_MAX) "$PRODUCTS_BADGE_MAX+" else count.toString(),
                    style =
                        MaterialTheme.typography.labelSmall.copy(
                            fontSize = 11.sp,
                            lineHeight = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFeatureSettings = "tnum",
                        ),
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * The video the reel links to: a slim, narrow pill. It is drawn by hand rather than as an
 * AssistChip, whose 32 dp body is padded out to a 48 dp touch target and so covered a wide band of
 * the picture; this one is 22 dp tall, capped in width, and its whole body is still tappable.
 */
@Composable
private fun ShortsLinkedVideoChip(linkedVideo: ShortLinkedVideo) {
    val navigator = LocalMediaNavigator.current
    val shape = RoundedCornerShape(percent = 50)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .widthIn(max = LinkedVideoMaxWidth)
                .height(LinkedVideoHeight)
                .clip(shape)
                .background(PlayerScrimPanel)
                .clickable { navigator.openLink(YouTubeLink.Video(linkedVideo.id, isMusic = false)) }
                .padding(start = 5.dp, end = 8.dp),
    ) {
        Icon(
            imageVector = Icons.Default.PlayArrow,
            contentDescription = null,
            tint = PlayerScrimContent,
            modifier = Modifier.size(LinkedVideoIconSize),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = linkedVideo.title ?: stringResource(R.string.shorts_linked_video),
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp, lineHeight = 13.sp),
            color = PlayerScrimContent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
