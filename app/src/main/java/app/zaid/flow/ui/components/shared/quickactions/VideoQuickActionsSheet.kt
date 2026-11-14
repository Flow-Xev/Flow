package app.zaid.flow.ui.components.shared.quickactions

import android.content.ClipData
import android.os.Build
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.WatchLater
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.PlaylistRemove
import androidx.compose.material.icons.outlined.QueuePlayNext
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.WatchLater
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zaid.flow.R
import app.zaid.flow.data.local.PlayerPreferences
import app.zaid.flow.data.model.Video
import app.zaid.flow.data.model.VideoCollaborator
import app.zaid.flow.notification.UpcomingVideoReminderWorker
import app.zaid.flow.ui.components.layout.navigation.LocalMediaNavigator
import app.zaid.flow.ui.components.shared.ChannelAvatarStack
import app.zaid.flow.ui.components.shared.FlowNavRow
import app.zaid.flow.ui.components.shared.FlowSubscribeButton
import app.zaid.flow.ui.components.shared.FlowSubscribeButtonSize
import app.zaid.flow.ui.components.shared.MediaThumbnail
import app.zaid.flow.ui.components.shared.SaveVideoSheet
import app.zaid.flow.ui.components.shared.card.LocalVideoCardPreferences
import app.zaid.flow.ui.components.shared.card.channelAvatarUrls
import app.zaid.flow.ui.components.shared.card.isWatchedProgress
import app.zaid.flow.ui.components.shared.card.rememberCollaboratorChannelDisplayName
import app.zaid.flow.ui.components.shared.card.rememberCollaboratorItems
import app.zaid.flow.ui.components.shared.card.rememberWatchProgress
import app.zaid.flow.ui.components.shared.collaboratorRows
import app.zaid.flow.ui.components.shared.rememberVideoShareAction
import app.zaid.flow.ui.screens.player.state.UpcomingPremierePolicy
import app.zaid.flow.utils.youtubeWatchUrl
import kotlinx.coroutines.launch

private enum class VideoMenuPage { Actions, Details, Collaborators, Save }

/**
 * A video's menu. [title] and [thumbnailUrl] are what the card shows, so a DeArrow title stays the
 * same in the menu. [showChannel] is false where the screen already is the channel. A playlist that
 * can drop the video passes [onRemoveFromCollection] with its [removeFromCollectionLabel] and icon;
 * it is the last row of the Options group.
 */
@Composable
fun VideoQuickActionsBottomSheet(
    video: Video,
    onDismiss: () -> Unit,
    title: String = video.title,
    thumbnailUrl: String = video.thumbnailUrl,
    showChannel: Boolean = true,
    onRemoveFromCollection: (() -> Unit)? = null,
    removeFromCollectionLabel: String? = null,
    removeFromCollectionIcon: ImageVector = Icons.Outlined.PlaylistRemove,
    viewModel: QuickActionsViewModel = sharedQuickActionsViewModel(),
) {
    var page by rememberSaveable(video.id) { mutableStateOf(VideoMenuPage.Actions) }
    val collaborators = rememberCollaboratorItems(video)
    val toActions = { page = VideoMenuPage.Actions }

    if (page == VideoMenuPage.Save) {
        SaveVideoSheet(video = video, onDismiss = onDismiss)
        return
    }

    QuickActionsSheet(
        onDismiss = onDismiss,
        page = page,
        onBack = if (page == VideoMenuPage.Actions) null else toActions,
    ) { sheet ->
        val close = sheet::close
        when (page) {
            VideoMenuPage.Details -> {
                QuickActionsPageHeader(title = stringResource(R.string.details_metadata), onBack = toActions, onClose = close)
                MediaDetailsPage(subject = video.toDetailsSubject(title))
            }

            VideoMenuPage.Collaborators -> {
                QuickActionsPageHeader(title = stringResource(R.string.collaborators), onBack = toActions, onClose = close)
                QuickActionsGroup(title = null, rows = collaboratorRows(collaborators, onOpened = close, viewModel = viewModel))
            }

            else -> {
                QuickActionsHeader(
                    title = title,
                    subtitle = rememberCollaboratorChannelDisplayName(video.channelName, collaborators),
                ) {
                    MediaThumbnail(
                        videoId = video.id,
                        thumbnailUrl = thumbnailUrl,
                        width = QuickActionsDefaults.VideoArtworkWidth,
                        shape = MaterialTheme.shapes.medium,
                        showWatchProgress = LocalVideoCardPreferences.current.showWatchProgress,
                    )
                }
                VideoPrimaryActions(video, viewModel, onSave = { sheet.hideThen { page = VideoMenuPage.Save } }, onDismiss = close)
                if (!video.isShort) {
                    QuickActionsGroup(
                        title = stringResource(R.string.playback_header),
                        rows = playbackRows(video, viewModel, close) + listOfNotNull(reminderRow(video, close)),
                    )
                }
                if (showChannel && video.channelId.isNotBlank()) {
                    QuickActionsGroup(
                        title = stringResource(R.string.section_channel),
                        rows =
                            listOf(
                                channelRow(video, collaborators, viewModel, close) {
                                    page = VideoMenuPage.Collaborators
                                },
                            ),
                    )
                }
                QuickActionsGroup(title = stringResource(R.string.section_algorithm), rows = feedRows(video, viewModel, close))
                val removeRow =
                    if (onRemoveFromCollection != null && removeFromCollectionLabel != null) {
                        actionRow("remove", removeFromCollectionIcon, removeFromCollectionLabel, destructive = true) {
                            onRemoveFromCollection()
                            close()
                        }
                    } else {
                        null
                    }
                QuickActionsGroup(
                    title = stringResource(R.string.section_options),
                    rows = moreRows(video, viewModel, close) { page = VideoMenuPage.Details } + listOfNotNull(removeRow),
                )
            }
        }
    }
}

@Composable
private fun VideoPrimaryActions(
    video: Video,
    viewModel: QuickActionsViewModel,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val watchLaterIds by viewModel.watchLaterIds.collectAsStateWithLifecycle()
    val share = rememberVideoShareAction()
    QuickActionsPrimaryGroup(
        listOf(
            QuickPrimaryAction(icon = Icons.AutoMirrored.Outlined.PlaylistAdd, label = stringResource(R.string.save), onClick = onSave),
            QuickPrimaryAction(
                icon = Icons.Outlined.WatchLater,
                checkedIcon = Icons.Filled.WatchLater,
                label = stringResource(R.string.watch_later),
                checked = video.id in watchLaterIds,
                onClick = { viewModel.toggleWatchLater(video) },
            ),
            QuickPrimaryAction(
                icon = Icons.Outlined.Share,
                label = stringResource(R.string.share),
                onClick = {
                    share(video.id, video.title, video.isShort)
                    onDismiss()
                },
            ),
        ),
    )
}

@Composable
private fun playbackRows(
    video: Video,
    viewModel: QuickActionsViewModel,
    onDismiss: () -> Unit,
): List<QuickActionRow> =
    listOf(
        actionRow(
            "play_next",
            Icons.Outlined.QueuePlayNext,
            stringResource(R.string.play_next_video),
            stringResource(R.string.play_next_video_desc),
        ) {
            viewModel.playVideoNext(video)
            onDismiss()
        },
        actionRow(
            "add_to_queue",
            Icons.AutoMirrored.Outlined.PlaylistAdd,
            stringResource(R.string.add_video_to_queue),
            stringResource(R.string.add_video_to_queue_desc),
        ) {
            viewModel.addVideoToQueue(video)
            onDismiss()
        },
    )

/**
 * "Remind me" for a stream or premiere that has not started, so a reminder can be set from a list
 * without opening the video. Null for anything else, or when its start time is unknown. Tapping a
 * row that is already set takes the reminder back.
 */
@Composable
private fun reminderRow(
    video: Video,
    onDismiss: () -> Unit,
): QuickActionRow? {
    val releaseTimeMs = remember(video) { UpcomingPremierePolicy.releaseTimeFor(video) } ?: return null
    val context = LocalContext.current
    val preferences = remember(context) { PlayerPreferences(context) }
    val scope = rememberCoroutineScope()
    val reminderIds by preferences.upcomingVideoReminderIds.collectAsStateWithLifecycle(initialValue = emptySet())
    val isSet = video.id in reminderIds
    return actionRow(
        "upcoming_reminder",
        if (isSet) Icons.Filled.NotificationsActive else Icons.Outlined.NotificationsNone,
        stringResource(if (isSet) R.string.upcoming_video_reminder_enabled else R.string.upcoming_video_reminder_action),
        if (isSet) stringResource(R.string.upcoming_video_reminder_cancel_hint) else null,
    ) {
        // Finish the work before the sheet goes, or leaving it would cancel the scope mid-write.
        scope.launch {
            preferences.setUpcomingVideoReminder(video.id, !isSet)
            if (isSet) {
                UpcomingVideoReminderWorker.cancelReminder(context, video.id)
            } else {
                UpcomingVideoReminderWorker.scheduleReminder(
                    context = context,
                    videoId = video.id,
                    releaseTimeMs = releaseTimeMs,
                    title = video.title,
                    channelName = video.channelName,
                    thumbnailUrl = video.thumbnailUrl,
                )
            }
            onDismiss()
        }
    }
}

/** The channel as one row, avatars stacked like the card's for a collaboration, with its subscribe button. */
@Composable
private fun channelRow(
    video: Video,
    collaborators: List<VideoCollaborator>,
    viewModel: QuickActionsViewModel,
    onDismiss: () -> Unit,
    onOpenCollaborators: () -> Unit,
): QuickActionRow {
    val channelLabel = rememberCollaboratorChannelDisplayName(video.channelName, collaborators)
    val subscribedChannelIds by viewModel.subscribedChannelIds.collectAsStateWithLifecycle()
    val navigator = LocalMediaNavigator.current
    val isCollaboration = collaborators.size > 1
    val ringColor = MaterialTheme.colorScheme.surfaceContainerHigh
    LaunchedEffect(video.channelId) { viewModel.loadSubscriptionState(video.channelId) }
    val knownAvatars = remember(video, collaborators) { video.channelAvatarUrls(collaborators) }
    // History, Liked and Downloads rows carry no avatar, so those look the channel up by id; the
    // repository caches each one.
    val avatars by produceState(knownAvatars, knownAvatars) {
        if (knownAvatars.isNotEmpty()) return@produceState
        val channelIds = if (isCollaboration) collaborators.map { it.channelId } else listOf(video.channelId)
        val fetched = viewModel.channelAvatars(channelIds)
        value = channelIds.mapNotNull { fetched[it]?.takeIf(String::isNotBlank) }
    }

    return QuickActionRow("channel") { shape ->
        FlowNavRow(
            title = channelLabel,
            onClick = {
                if (isCollaboration) {
                    onOpenCollaborators()
                } else {
                    navigator.openChannel(video.channelId)
                    onDismiss()
                }
            },
            shape = shape,
            leadingContent = {
                ChannelAvatarStack(
                    urls = avatars,
                    contentDescription = null,
                    avatarSize = QuickActionsDefaults.AvatarSize,
                    ringColor = ringColor,
                )
            },
            trailingContent =
                if (isCollaboration) {
                    { Icon(imageVector = Icons.Outlined.ChevronRight, contentDescription = null) }
                } else {
                    {
                        val toggle = { viewModel.toggleSubscription(video.channelId, video.channelName, video.channelThumbnailUrl) }
                        FlowSubscribeButton(
                            isSubscribed = video.channelId in subscribedChannelIds,
                            onSubscribeClick = toggle,
                            onUnsubscribeClick = toggle,
                            size = FlowSubscribeButtonSize.Compact,
                        )
                    }
                },
        )
    }
}

@Composable
private fun feedRows(
    video: Video,
    viewModel: QuickActionsViewModel,
    onDismiss: () -> Unit,
): List<QuickActionRow> {
    val haptics = LocalHapticFeedback.current
    val isWatched = isWatchedProgress(rememberWatchProgress(video.id))
    return listOf(
        actionRow("interested", Icons.Outlined.ThumbUp, stringResource(R.string.i_like_this)) {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            viewModel.markAsInteresting(video)
            onDismiss()
        },
        actionRow("not_interested", Icons.Outlined.ThumbDown, stringResource(R.string.not_interested)) {
            haptics.performHapticFeedback(HapticFeedbackType.Reject)
            viewModel.markNotInterested(video)
            onDismiss()
        },
        QuickActionRow("watched") { shape ->
            FlowNavRow(
                title = stringResource(if (isWatched) R.string.mark_as_unwatched else R.string.mark_as_watched),
                leadingIcon = if (isWatched) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                    if (isWatched) viewModel.markAsUnwatched(video) else viewModel.markAsWatched(video)
                    onDismiss()
                },
                enabled = true,
                showChevron = false,
                shape = shape,
            )
        },
        actionRow(
            key = "block",
            icon = Icons.Outlined.Block,
            title = stringResource(R.string.dont_show_channel),
            supporting = stringResource(R.string.dont_show_channel_desc),
            destructive = true,
        ) {
            viewModel.blockChannel(video)
            onDismiss()
        },
    )
}

@Composable
private fun moreRows(
    video: Video,
    viewModel: QuickActionsViewModel,
    onDismiss: () -> Unit,
    onOpenDetails: () -> Unit,
): List<QuickActionRow> {
    val downloadedIds by viewModel.downloadedVideoIds.collectAsStateWithLifecycle()
    val isDownloaded = video.id in downloadedIds
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val downloadedLabel = stringResource(R.string.downloaded)
    return listOf(
        QuickActionRow("download") { shape ->
            FlowNavRow(
                title = if (isDownloaded) downloadedLabel else stringResource(R.string.download),
                leadingIcon = if (isDownloaded) Icons.Filled.CheckCircle else Icons.Outlined.Download,
                onClick = {
                    viewModel.requestDownload(video)
                    onDismiss()
                },
                enabled = !isDownloaded,
                showChevron = false,
                stateDescription = if (isDownloaded) downloadedLabel else null,
                shape = shape,
            )
        },
        actionRow("copy_link", Icons.Outlined.ContentCopy, stringResource(R.string.copy_video_link)) {
            scope.launch {
                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(video.title, youtubeWatchUrl(video.id))))
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) viewModel.announce(R.string.link_copied)
                onDismiss()
            }
        },
        QuickActionRow("details") { shape ->
            FlowNavRow(
                title = stringResource(R.string.details_metadata),
                leadingIcon = Icons.Outlined.Info,
                onClick = onOpenDetails,
                shape = shape,
            )
        },
    )
}

/** A row that runs an action and closes the menu, so it carries no chevron. */
internal fun actionRow(
    key: String,
    icon: ImageVector,
    title: String,
    supporting: String? = null,
    destructive: Boolean = false,
    onClick: () -> Unit,
): QuickActionRow =
    QuickActionRow(key) { shape ->
        FlowNavRow(
            title = title,
            supportingText = supporting,
            leadingIcon = icon,
            onClick = onClick,
            showChevron = false,
            destructive = destructive,
            shape = shape,
        )
    }

private fun Video.toDetailsSubject(displayTitle: String) =
    MediaDetailsSubject(
        videoId = id,
        title = displayTitle,
        author = channelName,
        channelId = channelId,
        viewCount = viewCount,
        likeCount = likeCount,
        uploadDate = uploadDate,
        timestamp = timestamp,
        timestampIsExact = timestampIsExact,
        durationSeconds = duration,
    )
