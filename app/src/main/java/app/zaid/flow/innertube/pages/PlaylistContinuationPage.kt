package app.zaid.flow.innertube.pages

import app.zaid.flow.innertube.models.SongItem

data class PlaylistContinuationPage(
    val songs: List<SongItem>,
    val continuation: String?,
)
