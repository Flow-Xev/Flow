package app.zaid.flow.ui.components.library

import app.zaid.flow.data.local.LikedVideoInfo
import app.zaid.flow.data.local.VideoHistoryEntry
import app.zaid.flow.data.model.Video
import app.zaid.flow.data.model.toMusicTrack
import app.zaid.flow.data.model.toVideo
import app.zaid.flow.data.music.DownloadedTrack
import app.zaid.flow.data.music.model.MusicTrack
import app.zaid.flow.data.video.DownloadedVideo

internal const val LIBRARY_SHELF_ITEM_LIMIT = 20

/** How many Shorts share one shelf box (YouTube-style); a trailing box holds the 1 or 2 left over. */
internal const val SHORTS_PER_GROUP = 3

/** Hard ceiling for a box, whatever [SHORTS_PER_GROUP] is tuned to. */
internal const val MAX_SHORTS_PER_GROUP = 4

internal sealed interface LibraryMediaItem {
    val key: String

    data class VideoItem(
        val video: Video,
    ) : LibraryMediaItem {
        override val key: String = "video:${video.id}"
    }

    /** Up to [MAX_SHORTS_PER_GROUP] consecutive Shorts shown together in one shelf box. */
    data class ShortsGroupItem(
        val shorts: List<Video>,
    ) : LibraryMediaItem {
        override val key: String = "shorts:${shorts.first().id}"
    }

    data class MusicItem(
        val track: MusicTrack,
    ) : LibraryMediaItem {
        override val key: String = "music:${track.videoId}"
    }

    data class DownloadedVideoItem(
        val download: DownloadedVideo,
    ) : LibraryMediaItem {
        override val key: String = "downloaded-video:${download.video.id}"
    }

    data class DownloadedMusicItem(
        val download: DownloadedTrack,
    ) : LibraryMediaItem {
        override val key: String = "downloaded-music:${download.track.videoId}"
    }
}

internal fun VideoHistoryEntry.toLibraryMediaItem(): LibraryMediaItem =
    if (isMusic) {
        LibraryMediaItem.MusicItem(toMusicTrack())
    } else {
        LibraryMediaItem.VideoItem(toVideo())
    }

internal fun LikedVideoInfo.toLibraryMediaItem(): LibraryMediaItem =
    if (isMusic) {
        LibraryMediaItem.MusicItem(toMusicTrack())
    } else {
        LibraryMediaItem.VideoItem(toVideo())
    }

/**
 * Turns history entries into shelf items, folding each run of consecutive Shorts into boxes of
 * up to [groupSize] (never more than [MAX_SHORTS_PER_GROUP]) so they sit at the same place in
 * the shelf as the videos around them.
 */
internal fun List<VideoHistoryEntry>.toHistoryShelfItems(groupSize: Int = SHORTS_PER_GROUP): List<LibraryMediaItem> {
    val size = groupSize.coerceIn(1, MAX_SHORTS_PER_GROUP)
    val result = ArrayList<LibraryMediaItem>(this.size)
    val run = ArrayList<Video>()

    fun flushRun() {
        run.distinctBy { it.id }.chunked(size).forEach { result.add(LibraryMediaItem.ShortsGroupItem(it)) }
        run.clear()
    }

    for (entry in this) {
        if (entry.isShort && !entry.isMusic) {
            run.add(entry.toVideo())
        } else {
            flushRun()
            result.add(entry.toLibraryMediaItem())
        }
    }
    flushRun()
    return result
}
