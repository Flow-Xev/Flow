package app.zaid.flow.ui.screens.widgets

import androidx.lifecycle.ViewModel
import app.zaid.flow.data.local.PlaylistRepository
import app.zaid.flow.data.local.entity.PlaylistEntity
import app.zaid.flow.data.model.Video
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/** Reads what a widget asked to play. It loads nothing until asked, so a route can own one freely. */
@HiltViewModel
class WidgetPlaybackViewModel
    @Inject
    constructor(
        private val playlists: PlaylistRepository,
    ) : ViewModel() {
        suspend fun playlist(id: String): Pair<PlaylistEntity?, List<Video>> =
            playlists.getPlaylistEntity(id) to playlists.getPlaylistVideosFlow(id).first()
    }
