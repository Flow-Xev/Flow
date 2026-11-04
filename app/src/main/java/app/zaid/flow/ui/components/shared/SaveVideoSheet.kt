package app.zaid.flow.ui.components.shared

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zaid.flow.R
import app.zaid.flow.data.local.PlaylistRepository
import app.zaid.flow.data.model.Video

@Composable
fun SaveVideoSheet(
    video: Video,
    onDismiss: () -> Unit,
    viewModel: SaveVideoViewModel = hiltViewModel(),
) {
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val watchLaterVideos by viewModel.watchLaterVideos.collectAsStateWithLifecycle()
    val savedIds by viewModel.savedIds.collectAsStateWithLifecycle()
    val youtubeOptions by viewModel.youtubeOptions.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(message) {
        message?.let {
            android.widget.Toast
                .makeText(context, it, android.widget.Toast.LENGTH_SHORT)
                .show()
            viewModel.messageShown()
        }
    }
    var showCreateDialog by remember { mutableStateOf(false) }

    LaunchedEffect(video.id) { viewModel.loadMembership(video.id) }

    val watchLaterEntry =
        CollectionSheetEntry(
            id = PlaylistRepository.WATCH_LATER_ID,
            name = stringResource(R.string.watch_later),
            supporting =
                pluralStringResource(
                    R.plurals.videos_count_template,
                    watchLaterVideos.size,
                    watchLaterVideos.size,
                ),
            thumbnailUrl = watchLaterVideos.firstOrNull()?.thumbnailUrl.orEmpty(),
            isSaved = PlaylistRepository.WATCH_LATER_ID in savedIds,
        )
    // The account's own YouTube playlists (including its Watch later), after the on-device ones.
    val youtubeEntries =
        youtubeOptions.map { option ->
            CollectionSheetEntry(
                id = YT_PREFIX + option.id,
                name = "YouTube · " + option.title,
                supporting = "",
                thumbnailUrl = "",
                isSaved = (YT_PREFIX + option.id) in savedIds,
            )
        }
    val entries =
        listOf(watchLaterEntry) +
            playlists.map { playlist ->
                CollectionSheetEntry(
                    id = playlist.id,
                    name = playlist.name,
                    supporting =
                        pluralStringResource(
                            R.plurals.videos_count_template,
                            playlist.videoCount,
                            playlist.videoCount,
                        ),
                    thumbnailUrl = playlist.thumbnailUrl,
                    isSaved = playlist.id in savedIds,
                )
            } + youtubeEntries

    SaveToCollectionSheet(
        title = stringResource(R.string.save_to),
        entries = entries,
        placeholderIcon = Icons.AutoMirrored.Outlined.PlaylistPlay,
        createLabel = stringResource(R.string.create_new_playlist),
        emptyLabel = stringResource(R.string.no_playlists_found),
        onToggle = { entry -> viewModel.toggle(video, entry.id) },
        onCreateNew = { showCreateDialog = true },
        onDismiss = onDismiss,
    )

    if (showCreateDialog) {
        CollectionEditDialog(
            title = stringResource(R.string.create_new_playlist),
            confirmLabel = stringResource(R.string.create),
            icon = Icons.Default.PlaylistAdd,
            onDismiss = { showCreateDialog = false },
            onConfirm = { name, description ->
                viewModel.createAndAdd(video, name, description)
                showCreateDialog = false
            },
        )
    }
}
