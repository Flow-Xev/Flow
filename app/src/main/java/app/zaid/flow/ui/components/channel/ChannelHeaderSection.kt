package app.zaid.flow.ui.components.channel

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.zaid.flow.R
import app.zaid.flow.innertube.pages.channel.ChannelHeader
import app.zaid.flow.innertube.pages.channel.ChannelLink
import app.zaid.flow.ui.components.shared.ChannelAvatarImage
import app.zaid.flow.ui.components.shared.FlowNoteCard
import app.zaid.flow.ui.components.shared.FlowSubscribeButton
import app.zaid.flow.ui.components.shared.FlowSubscribeButtonSize
import app.zaid.flow.ui.components.shared.FullSizeImageDialog
import app.zaid.flow.ui.theme.extendedColors
import app.zaid.flow.utils.ThumbnailUrlResolver
import app.zaid.flow.utils.copyPlainText
import kotlinx.coroutines.launch

@Composable
internal fun ChannelHeaderSection(
    header: ChannelHeader,
    isSubscribed: Boolean,
    isNotificationsEnabled: Boolean,
    onSubscribeClick: () -> Unit,
    onUnsubscribeClick: () -> Unit,
    onNotificationChange: (Boolean) -> Unit,
    onManageGroups: (() -> Unit)?,
    note: String?,
    onEditNote: (() -> Unit)?,
    onStoreClick: (() -> Unit)? = null,
    onAboutClick: (() -> Unit)? = null,
) {
    val bannerUrl = remember(header.bannerUrl) { ThumbnailUrlResolver.resolveChannelBanner(header.bannerUrl, targetWidth = 2048) }
    var showFullSizeAvatar by remember(header.id) { mutableStateOf(false) }

    if (showFullSizeAvatar && header.avatarUrl.isNotEmpty()) {
        FullSizeImageDialog(
            imageUrl = header.avatarUrl,
            onDismiss = { showFullSizeAvatar = false },
        )
    }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background),
    ) {
        if (!bannerUrl.isNullOrBlank()) {
            ChannelBanner(imageUrl = bannerUrl)
        }

        // Identity: the avatar leads, and the name, handle and reach stack beside it so the
        // page opens on who this is rather than on a row of controls.
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ChannelAvatarImage(
                url = header.avatarUrl,
                contentDescription = stringResource(R.string.channel_avatar),
                modifier =
                    Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(enabled = header.avatarUrl.isNotEmpty()) { showFullSizeAvatar = true },
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(modifier = Modifier.weight(1f, fill = false)) { ChannelName(header.title) }
                    if (header.isVerified) {
                        Icon(
                            imageVector = Icons.Rounded.Verified,
                            contentDescription = stringResource(R.string.verified),
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                // The handle leads in full ink; the reach recedes, as on YouTube's own channel page.
                header.handle?.takeIf { it.isNotBlank() }?.let { handle ->
                    Text(
                        text = handle,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val reach = remember(header) { listOfNotNull(header.subscriberCountText, header.videoCountText).joinToString(" • ") }
                if (reach.isNotEmpty()) {
                    Text(
                        text = reach,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.extendedColors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // One line of description with an inline "...more" that opens the About tab.
        header.description?.takeIf { it.isNotBlank() }?.let { description ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(top = 14.dp)
                        .clip(MaterialTheme.shapes.small)
                        .clickable(enabled = onAboutClick != null) { onAboutClick?.invoke() },
            ) {
                Text(
                    text = description.replace(Regex("\\s+"), " "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.extendedColors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (onAboutClick != null) {
                    Text(
                        text = stringResource(R.string.channel_description_more),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        }

        // The first link, with a count of the rest.
        if (header.links.isNotEmpty()) {
            ChannelLinksRow(
                links = header.links,
                onOpenAbout = onAboutClick,
                modifier = Modifier.padding(horizontal = 16.dp).padding(top = 6.dp),
            )
        }

        // Actions: subscribe takes the row, the store sits beside it as a round button.
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 16.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FlowSubscribeButton(
                isSubscribed = isSubscribed,
                isNotificationsEnabled = isNotificationsEnabled,
                onSubscribeClick = onSubscribeClick,
                onUnsubscribeClick = onUnsubscribeClick,
                onNotificationChange = onNotificationChange,
                onManageGroups = onManageGroups,
                onAddNote = onEditNote,
                size = FlowSubscribeButtonSize.Wide,
                modifier = Modifier.weight(1f),
            )
            if (onStoreClick != null) {
                val storeDescription = stringResource(R.string.channel_store_open)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier =
                        Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .clickable(role = Role.Button, onClick = onStoreClick),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ShoppingBag,
                        contentDescription = storeDescription,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }

        if (!note.isNullOrBlank() && onEditNote != null) {
            FlowNoteCard(
                text = note,
                onEdit = onEditNote,
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp),
            )
        }
    }
}

@Composable
private fun ChannelLinksRow(
    links: List<ChannelLink>,
    onOpenAbout: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val first = links.first()
    val uriHandler = LocalUriHandler.current
    val label =
        if (links.size > 1) {
            stringResource(R.string.channel_links_summary, first.displayText, links.size - 1)
        } else {
            first.displayText
        }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable {
                    if (links.size > 1 && onOpenAbout != null) {
                        onOpenAbout()
                    } else {
                        runCatching { uriHandler.openUri(first.url) }
                    }
                }.padding(vertical = 4.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Link,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The channel's name, copied on long-press. Not a text selection: the header slides away as the
 * page scrolls, which would leave selection handles floating.
 */
@Composable
private fun ChannelName(title: String) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val clipLabel = stringResource(R.string.channel_name_clip_label)
    val copyLabel = stringResource(R.string.copy_channel_name)

    fun copy() {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        scope.launch {
            clipboard.copyPlainText(clipLabel, title)
            Toast.makeText(context, context.getString(R.string.channel_name_copied), Toast.LENGTH_SHORT).show()
        }
    }
    Text(
        text = title,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier =
            Modifier
                .pointerInput(title) { detectTapGestures(onLongPress = { copy() }) }
                .semantics {
                    onLongClick(label = copyLabel) {
                        copy()
                        true
                    }
                },
    )
}
