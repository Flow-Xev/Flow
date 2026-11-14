package app.zaid.flow.ui.screens.player.content

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import app.zaid.flow.R
import app.zaid.flow.data.localmedia.LocalMediaIds
import app.zaid.flow.data.model.Video
import app.zaid.flow.data.model.VideoCollaborator
import app.zaid.flow.data.model.needsCollaboratorResolution
import app.zaid.flow.data.model.toVideo
import app.zaid.flow.data.model.uploadDateMillis
import app.zaid.flow.data.repository.VideoCollaboratorResolver
import app.zaid.flow.ui.components.rememberDeArrowResult
import app.zaid.flow.ui.components.shared.rememberDateDisplaySettings
import app.zaid.flow.ui.screens.player.state.VideoPlayerUiState
import app.zaid.flow.utils.DateContext
import org.schabi.newpipe.extractor.stream.StreamType

/**
 * Display values derived from the played [Video] and the loaded stream info.
 */
@Immutable
internal data class PlayerVideoMetadata(
    val resolvedVideoTitle: String,
    val resolvedCollaborators: List<VideoCollaborator>,
    val resolvedChannelName: String,
    val streamUploadDate: String?,
    val dialogVideo: Video,
)

@Composable
internal fun rememberPlayerVideoMetadata(
    video: Video,
    uiState: VideoPlayerUiState,
    deArrowEnabled: Boolean,
    context: Context,
): PlayerVideoMetadata {
    val deArrowResult = rememberDeArrowResult(video.id, deArrowEnabled && !LocalMediaIds.isLocal(video.id))
    val resolvedVideoTitle = deArrowResult?.title ?: video.title
    val needsCollaboratorResolution = video.needsCollaboratorResolution()
    val resolvedCollaborators by produceState(
        initialValue = video.collaborators,
        key1 = video.id,
        key2 = video.collaborators,
        key3 = needsCollaboratorResolution,
    ) {
        value =
            if (needsCollaboratorResolution) {
                VideoCollaboratorResolver.resolve(video.id)
            } else {
                video.collaborators
            }
    }
    val resolvedChannelName =
        remember(video.channelName, resolvedCollaborators) {
            resolvedCollaborators
                .map { it.name }
                .filter { it.isNotBlank() }
                .takeIf { it.size > 1 }
                ?.joinToString(" ${context.getString(R.string.conjunction_and)} ")
                ?: video.channelName
        }
    val dateSettings = rememberDateDisplaySettings()
    val streamUploadDate =
        remember(video.uploadDate, uiState.isArchivedLivestream, dateSettings) {
            val rawDate = video.uploadDate.takeIf { it.isNotBlank() } ?: return@remember null
            if (uiState.isArchivedLivestream && !rawDate.startsWith("Streamed", ignoreCase = true)) {
                context.getString(R.string.streamed_date_template, rawDate)
            } else {
                rawDate
            }
        }
    val dialogVideo = video

    return PlayerVideoMetadata(
        resolvedVideoTitle = resolvedVideoTitle,
        resolvedCollaborators = resolvedCollaborators,
        resolvedChannelName = resolvedChannelName,
        streamUploadDate = streamUploadDate,
        dialogVideo = dialogVideo,
    )
}
