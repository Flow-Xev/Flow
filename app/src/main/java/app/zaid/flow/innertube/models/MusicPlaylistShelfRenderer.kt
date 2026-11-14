package app.zaid.flow.innertube.models

import kotlinx.serialization.Serializable

@Serializable
data class MusicPlaylistShelfRenderer(
    val playlistId: String?,
    val contents: List<MusicShelfRenderer.Content>?,
    val collapsedItemCount: Int,
)
