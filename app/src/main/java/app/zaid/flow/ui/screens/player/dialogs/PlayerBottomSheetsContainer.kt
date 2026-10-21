package app.zaid.flow.ui.screens.player.dialogs

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zaid.flow.R
import app.zaid.flow.data.model.Comment
import app.zaid.flow.data.model.Video
import app.zaid.flow.player.EnhancedMusicPlayerManager
import app.zaid.flow.player.EnhancedPlayerManager
import app.zaid.flow.player.EnhancedPlayerState
import app.zaid.flow.player.SleepTimerManager
import app.zaid.flow.ui.components.shared.CommentSortFilter
import app.zaid.flow.ui.components.shared.FlowAlertDialog
import app.zaid.flow.ui.components.shared.FlowCommentsBottomSheet
import app.zaid.flow.ui.components.videoplayer.sheet.FlowLiveChatBottomSheet
import app.zaid.flow.ui.screens.player.VideoPlayerViewModel
import app.zaid.flow.ui.screens.player.state.PlayerCommentsUiState
import app.zaid.flow.ui.screens.player.state.PlayerScreenState
import app.zaid.flow.ui.screens.player.state.PlayerSheet
import app.zaid.flow.ui.screens.player.state.VideoPlayerUiState
import app.zaid.flow.ui.screens.player.state.transcriptTrackUrl
import app.zaid.flow.ui.screens.player.state.visibleComments

@Composable
internal fun PlayerBottomSheetsContainer(
    screenState: PlayerScreenState,
    uiState: VideoPlayerUiState,
    video: Video,
    completeVideo: Video,
    disableShortsPlayer: Boolean,
    showShortsPlayerPrompt: Boolean,
    viewModel: VideoPlayerViewModel,
    playerState: EnhancedPlayerState,
    commentsUiState: PlayerCommentsUiState,
    commentsEnabled: Boolean = true,
    onLoadMoreComments: (videoId: String) -> Unit = {},
    onReloadComments: ((videoId: String) -> Unit)? = null,
    onPostComment: ((videoId: String, text: String, done: (Result<Unit>) -> Unit) -> Unit)? = null,
    onSelectCommentSort: (CommentSortFilter) -> Unit = {},
    mediaSheetExpandedHeight: Dp? = null,
    mediaSheetCollapsedHeight: Dp = 0.dp,
    context: Context,
    onPlayAsShort: (String) -> Unit,
    onLoadReplies: (Comment) -> Unit = {},
    onLoadMoreReplies: (Comment) -> Unit = {},
    onNavigateToChannel: ((String) -> Unit)? = null,
    hostedInSidePanel: Boolean = false,
    onMediaSheetProgressChange: (Float) -> Unit = {},
) {
    val visibleComments = commentsUiState.visibleComments(screenState)

    val handleSeek: (Long) -> Unit =
        remember {
            { positionMs -> EnhancedPlayerManager.getInstance().seekTo(positionMs) }
        }

    LaunchedEffect(Unit) {
        SleepTimerManager.attachToPlayer(
            player = EnhancedPlayerManager.getInstance().getPlayer(),
        ) {
            EnhancedPlayerManager.getInstance().pause()
        }
        SleepTimerManager.attachExitCallback {
            EnhancedPlayerManager.getInstance().pause()
            EnhancedMusicPlayerManager.stop()
            context.stopService(
                android.content.Intent(context, app.zaid.flow.service.VideoPlayerService::class.java),
            )
            (context as? android.app.Activity)?.finishAndRemoveTask()
        }
    }

    // Comments Bottom Sheet
    if (screenState.activeSheet == PlayerSheet.Comments() && commentsEnabled && !hostedInSidePanel) {
        FlowCommentsBottomSheet(
            comments = visibleComments,
            isLoading = commentsUiState.isLoading,
            selectedFilter = screenState.commentSortFilter,
            totalText = commentsUiState.totalText,
            artworkUrl = video.thumbnailUrl,
            timedOnly = screenState.commentsTimedOnly,
            onTimedChange = { screenState.commentsTimedOnly = it },
            onFilterChanged = onSelectCommentSort,
            onLoadReplies = onLoadReplies,
            onLoadMoreReplies = onLoadMoreReplies,
            onSeekMs = handleSeek,
            isLoadingMore = commentsUiState.isLoadingMore,
            hasMore = commentsUiState.hasMore,
            onLoadMore = { onLoadMoreComments(video.id) },
            onReload = onReloadComments?.let { reload -> { reload(video.id) } },
            onPostComment =
                onPostComment?.let { post ->
                    app.zaid.flow.ui.components.shared
                        .rememberCommentPoster { text, done -> post(video.id, text, done) }
                },
            onAuthorClick = { authorChannelRef ->
                screenState.closeSheet()
                onNavigateToChannel?.invoke(authorChannelRef)
            },
            expandedHeight = mediaSheetExpandedHeight,
            collapsedHeight = mediaSheetCollapsedHeight,
            onSheetProgressChange = onMediaSheetProgressChange,
            onDismiss = { screenState.closeSheet() },
        )
    }

    if (screenState.activeSheet == PlayerSheet.LiveChat() && uiState.isLiveChatAvailable) {
        FlowLiveChatBottomSheet(
            messages = uiState.liveChatMessages,
            isLoading = uiState.isLiveChatLoading,
            expandedHeight = mediaSheetExpandedHeight,
            collapsedHeight = mediaSheetCollapsedHeight,
            onSheetProgressChange = onMediaSheetProgressChange,
            onDismiss = { screenState.closeSheet() },
        )
    }

    // Description Bottom Sheet
    if (screenState.activeSheet == PlayerSheet.Description && !hostedInSidePanel) {
        PlayerDescriptionSheetHost(
            video = video,
            uiState = uiState,
            viewModel = viewModel,
            asSidePanel = false,
            expandedHeight = mediaSheetExpandedHeight,
            onDismiss = { screenState.closeSheet() },
            hasTranscriptTrack = transcriptTrackUrl(playerState, screenState) != null,
            onChaptersClick = { screenState.open(PlayerSheet.Chapters) },
            onTranscriptClick = { screenState.open(PlayerSheet.Transcript) },
            onChannelClick = { channelId ->
                screenState.closeSheet()
                onNavigateToChannel?.invoke(channelId)
            },
            collapsedHeight = mediaSheetCollapsedHeight,
            onSheetProgressChange = onMediaSheetProgressChange,
        )
    }

    if (screenState.activeSheet == PlayerSheet.Transcript && !hostedInSidePanel) {
        PlayerTranscriptSheetHost(
            viewModel = viewModel,
            screenState = screenState,
            trackUrl = transcriptTrackUrl(playerState, screenState),
            tracks = playerState.availableSubtitles,
            chapters = uiState.chapters,
            artworkUrl = video.thumbnailUrl,
            asSidePanel = false,
            expandedHeight = mediaSheetExpandedHeight,
            onDismiss = { screenState.closeSheet() },
            collapsedHeight = mediaSheetCollapsedHeight,
            onSheetProgressChange = onMediaSheetProgressChange,
        )
    }

    // Chapters Bottom Sheet
    if (screenState.activeSheet == PlayerSheet.Chapters && !hostedInSidePanel) {
        PlayerChaptersSheetHost(
            screenState = screenState,
            chapters = uiState.chapters,
            thumbnailUrl = video.thumbnailUrl,
            asSidePanel = false,
            expandedHeight = mediaSheetExpandedHeight,
            onDismiss = { screenState.closeSheet() },
            collapsedHeight = mediaSheetCollapsedHeight,
            onSheetProgressChange = onMediaSheetProgressChange,
        )
    }

    if (screenState.activeSheet == PlayerSheet.Queue && !hostedInSidePanel) {
        PlayerQueueSheetHost(
            asSidePanel = false,
            expandedHeight = mediaSheetExpandedHeight,
            onDismiss = { screenState.closeSheet() },
            collapsedHeight = mediaSheetCollapsedHeight,
            onSheetProgressChange = onMediaSheetProgressChange,
        )
    }

    if (screenState.activeSheet == PlayerSheet.SleepTimer && !hostedInSidePanel) {
        PlayerSleepTimerSheetHost(
            asSidePanel = false,
            expandedHeight = mediaSheetExpandedHeight,
            onDismiss = { screenState.closeSheet() },
            collapsedHeight = mediaSheetCollapsedHeight,
            onSheetProgressChange = onMediaSheetProgressChange,
        )
    }

    // Shorts Suggestion Dialog
    if (screenState.showShortsPrompt && !disableShortsPlayer && showShortsPlayerPrompt) {
        ShortsSuggestionDialog(
            onPlayAsShort = {
                screenState.showShortsPrompt = false
                onPlayAsShort(completeVideo.id)
            },
            onDismiss = { screenState.showShortsPrompt = false },
        )
    }
}

/**
 * Dialog suggesting to play a short video in the Shorts player.
 */
@Composable
private fun ShortsSuggestionDialog(
    onPlayAsShort: () -> Unit,
    onDismiss: () -> Unit,
) {
    FlowAlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.SmartDisplay, null) },
        title = {
            Text(
                text = stringResource(R.string.play_mode_suggestion_title),
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Text(stringResource(R.string.play_mode_suggestion_body))
        },
        confirmButton = {
            TextButton(onClick = onPlayAsShort) {
                Text(stringResource(R.string.shorts_player))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        },
    )
}
