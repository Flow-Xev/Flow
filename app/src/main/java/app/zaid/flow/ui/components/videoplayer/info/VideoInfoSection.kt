package app.zaid.flow.ui.components.videoplayer.info

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.zaid.flow.R
import app.zaid.flow.data.local.PlayerPreferences
import app.zaid.flow.data.model.Video
import app.zaid.flow.data.model.VideoCollaborator
import app.zaid.flow.ui.components.shared.ChannelAvatarStack
import app.zaid.flow.ui.components.shared.CollaboratorsBottomSheet
import app.zaid.flow.ui.components.shared.FlowSubscribeButton
import app.zaid.flow.ui.components.shared.FlowSubscribeButtonSize
import app.zaid.flow.ui.components.shared.card.rememberCollaboratorChannelDisplayName
import app.zaid.flow.ui.components.shared.rememberDateDisplaySettings
import app.zaid.flow.ui.theme.extendedColors
import app.zaid.flow.utils.DateContext
import app.zaid.flow.utils.avatarImageIdentityKey
import app.zaid.flow.utils.formatSubscriberCount
import app.zaid.flow.utils.formatViewCount

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun VideoInfoSection(
    video: Video,
    title: String,
    viewCount: Long,
    uploadDate: String?,
    description: String?,
    isUpcoming: Boolean = false,
    liveViewers: Long? = null,
    channelName: String,
    channelAvatarUrl: String,
    channelAvatarUrls: List<String> = emptyList(),
    collaborators: List<VideoCollaborator> = emptyList(),
    subscribedCollaboratorIds: Set<String> = emptySet(),
    subscriberCount: Long?,
    isSubscribed: Boolean,
    isNotificationsEnabled: Boolean = false,
    likeState: String,
    likeCount: Long? = null,
    dislikeCount: Long?,
    onSubscribeClick: () -> Unit,
    onUnsubscribeClick: () -> Unit = {},
    onNotificationChange: (Boolean) -> Unit = {},
    onChannelClick: () -> Unit,
    onLikeClick: () -> Unit,
    onDislikeClick: () -> Unit,
    onShareClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onSaveClick: () -> Unit,
    onBackgroundPlayClick: () -> Unit,
    onCopyLinkClick: () -> Unit = {},
    onCopyLinkAtTimeClick: () -> Unit = {},
    onDescriptionClick: () -> Unit,
    isSaved: Boolean = false,
    isDownloaded: Boolean = false,
    onNoteClick: (() -> Unit)? = null,
    hasNote: Boolean = false,
    isDeviceFile: Boolean = false,
    taggedProductCount: Int = 0,
    onTaggedProductsClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var showCollaborators by remember { mutableStateOf(false) }
    val displayChannelName = rememberCollaboratorChannelDisplayName(channelName, collaborators)
    val avatarUrls =
        remember(channelAvatarUrl, channelAvatarUrls, collaborators, video.channelThumbnailUrl) {
            val sources =
                if (collaborators.size > 1) {
                    collaborators.map { it.thumbnailUrl }
                } else {
                    listOf(channelAvatarUrl, video.channelThumbnailUrl) + channelAvatarUrls
                }
            sources
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinctBy { it.avatarImageIdentityKey() }
                .take(if (collaborators.size > 1) 3 else 1)
        }
    val openChannelOrCollaborators = {
        if (collaborators.size > 1) {
            showCollaborators = true
        } else {
            onChannelClick()
        }
    }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 4.dp),
    ) {
        // ============ TITLE + META ============
        val context = LocalContext.current
        val prefs = remember { PlayerPreferences(context) }
        val titleMaxLinesPref by prefs.videoTitleMaxLines.collectAsState(initial = 1)
        val titleMaxLines = if (titleMaxLinesPref <= 0) Int.MAX_VALUE else titleMaxLinesPref
        val dateSettings = rememberDateDisplaySettings()
        // Title and the stats line are one target: tapping either opens the description; the channel name opens the channel.
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .clickable(onClick = onDescriptionClick),
        ) {
            // A title that does not fit fades out at the end of its last line instead of ending in "...".
            FadeEndText(
                text = AnnotatedString(title),
                style =
                    MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        lineHeight = 26.sp,
                    ),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = titleMaxLines,
                modifier =
                    Modifier.combinedClickable(
                        onClick = onDescriptionClick,
                        onLongClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(
                                ClipData.newPlainText(context.getString(R.string.title_label), title),
                            )
                            Toast.makeText(context, context.getString(R.string.title_copied), Toast.LENGTH_SHORT).show()
                        },
                    ),
            )

            Spacer(modifier = Modifier.height(4.dp))

            val statsText =
                buildList {
                    if (!isUpcoming && !isDeviceFile && likeCount != null && likeCount > 0L) {
                        add(stringResource(R.string.likes_count_short_template, formatViewCount(likeCount)))
                    }
                    add(
                        when {
                            isUpcoming && viewCount > 0L -> stringResource(R.string.upcoming_waiting_count, formatViewCount(viewCount))
                            isUpcoming -> stringResource(R.string.upcoming_label)
                            liveViewers != null -> stringResource(R.string.live_watching_now, formatViewCount(liveViewers))
                            else -> stringResource(R.string.views_count_short_template, formatViewCount(viewCount))
                        },
                    )
                    if (!isUpcoming && !uploadDate.isNullOrBlank()) {
                        add(dateSettings.format(uploadDate, DateContext.WATCH, video.timestamp, video.timestampIsExact))
                    }
                }.joinToString(separator = "  ")

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FadeEndText(
                    text = AnnotatedString(displayChannelName),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    fadeWidth = 24.dp,
                    modifier =
                        Modifier
                            .widthIn(max = 150.dp)
                            .clickable(enabled = !isDeviceFile) { openChannelOrCollaborators() },
                )
                Spacer(modifier = Modifier.width(8.dp))
                FadeEndText(
                    text = AnnotatedString(statsText),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                    color = if (isUpcoming) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }

        // ============ ACTIONS: channel on the left, the capsule on the right ============
        Row(
            modifier = Modifier.fillMaxWidth().glassFadeEnd().padding(top = 10.dp, bottom = 0.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ChannelAvatarStack(
                    urls = avatarUrls,
                    contentDescription = displayChannelName,
                    avatarSize = 36.dp,
                    modifier =
                        Modifier
                            .clip(CircleShape)
                            .clickable(enabled = !isDeviceFile) { openChannelOrCollaborators() },
                )
                if (!isDeviceFile && collaborators.size > 1) {
                    // One button cannot name which channel it follows, so it opens the list that can (#1160).
                    FlowSubscribeButton(
                        size = FlowSubscribeButtonSize.Mini,
                        isSubscribed = collaborators.any { it.channelId in subscribedCollaboratorIds },
                        onSubscribeClick = { showCollaborators = true },
                        onSubscribedClick = { showCollaborators = true },
                    )
                } else if (!isDeviceFile) {
                    FlowSubscribeButton(
                        size = FlowSubscribeButtonSize.Mini,
                        isSubscribed = isSubscribed,
                        isNotificationsEnabled = isNotificationsEnabled,
                        onSubscribeClick = onSubscribeClick,
                        onUnsubscribeClick = onUnsubscribeClick,
                        onNotificationChange = onNotificationChange,
                    )
                }
            }

            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            VideoActionRow(
                likeState = likeState,
                likeCount = likeCount,
                dislikeCount = dislikeCount,
                onLikeClick = onLikeClick,
                onDislikeClick = onDislikeClick,
                onShareClick = onShareClick,
                onDownloadClick = onDownloadClick,
                onSaveClick = onSaveClick,
                onBackgroundPlayClick = onBackgroundPlayClick,
                onCopyLinkClick = onCopyLinkClick,
                onCopyLinkAtTimeClick = onCopyLinkAtTimeClick,
                isSaved = isSaved,
                isDownloaded = isDownloaded,
                onNoteClick = onNoteClick,
                hasNote = hasNote,
                isDeviceFile = isDeviceFile,
                taggedProductCount = taggedProductCount,
                onTaggedProductsClick = onTaggedProductsClick,
            )
            }
        }
    }

    if (showCollaborators) {
        CollaboratorsBottomSheet(
            collaborators = collaborators,
            onDismiss = { showCollaborators = false },
        )
    }
}

/**
 * Text that, when it has more than [maxLines] to say, keeps going past the edge of its last visible line:
 * the last line shows the first letters of the words that did not fit and dissolves into the page,
 * instead of stopping at a word boundary or ending on an ellipsis. Nothing changes while the text fits.
 */
@Composable
private fun FadeEndText(
    text: AnnotatedString,
    style: TextStyle,
    color: Color,
    maxLines: Int,
    modifier: Modifier = Modifier,
    fadeWidth: Dp = 56.dp,
) {
    val fadePx = with(LocalDensity.current) { fadeWidth.toPx() }
    // Keyed on the width the PARENT offers, never on this Box's own measured width: the wrapped first pass and
    // the unwrapped second pass have different content widths, so keying on those made the two passes reset
    // each other and the title could settle on the hard-clipped first pass with no fade.
    BoxWithConstraints(modifier = modifier) {
        val availableWidth = constraints.maxWidth
        // Offset where the last visible line starts, once the text is known not to fit; null while it fits / unmeasured.
        var split by remember(text, maxLines, style, availableWidth) { mutableStateOf<Int?>(null) }
        val cut = split
        if (cut == null) {
            Text(
                text = text,
                style = style,
                color = color,
                maxLines = maxLines,
                overflow = TextOverflow.Clip,
                onTextLayout = { r ->
                    if (r.hasVisualOverflow && r.lineCount > 0) split = r.getLineStart(r.lineCount - 1)
                },
            )
        } else {
            Column {
                if (cut > 0) {
                    Text(
                        text = text.subSequence(0, cut),
                        style = style,
                        color = color,
                        maxLines = (maxLines - 1).coerceAtLeast(1),
                        overflow = TextOverflow.Clip,
                    )
                }
                // The remainder on ONE line, unwrapped: letters of the next words run on and are faded out at the edge.
                var overflowsWidth by remember(text, cut) { mutableStateOf(true) }
                Text(
                    text = text.subSequence(cut, text.length),
                    style = style,
                    color = color,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                    onTextLayout = { overflowsWidth = it.didOverflowWidth },
                    modifier =
                        Modifier
                            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                            .drawWithContent {
                                drawContent()
                                if (overflowsWidth) {
                                    val right = size.width
                                    val left = (right - fadePx).coerceAtLeast(0f)
                                    drawRect(
                                        brush =
                                            Brush.horizontalGradient(
                                                0f to Color.Transparent,
                                                0.35f to Color.Black.copy(alpha = 0.12f),
                                                0.65f to Color.Black.copy(alpha = 0.55f),
                                                0.88f to Color.Black.copy(alpha = 0.92f),
                                                1f to Color.Black,
                                                startX = left,
                                                endX = right,
                                            ),
                                        topLeft = Offset(left, 0f),
                                        size = Size(right - left, size.height),
                                        blendMode = BlendMode.DstOut,
                                    )
                                }
                            },
                )
            }
        }
    }
}
