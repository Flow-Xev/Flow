package app.zaid.flow.player.media

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import app.zaid.flow.data.video.downloader.transfer.RangeDownloader
import app.zaid.flow.data.video.downloader.transfer.StreamRole
import app.zaid.flow.data.video.downloader.transfer.TransferState
import java.io.File

/**
 * Plays a download that has not finished (running, paused or failed) from its staging parts, with
 * no network and no merge: the video part and the audio part play side by side.
 *
 * Only the bytes that are really on disk from the start of each part count (a single-file stream
 * grows in order, so its length is that prefix; a block-parallel part uses the blocks done from the
 * start, never the zero-filled space in front of the blocks still missing). That prefix is then cut
 * back to the last complete fragment or cluster ([PlayablePrefix]), so each part is a valid, shorter
 * file that ends cleanly instead of in the middle of a box. Both parts are clipped to the moment
 * the shorter one reaches, so picture and sound always stop together.
 */
object PartialDownload {
    private const val PREFIX = "partial:"
    private const val MIN_PLAYABLE_MS = 2_000L
    private const val ESTIMATE_SAFETY = 0.9
    private const val REQUEST_WINDOW_MS = 5 * 60_000L

    class Spec(
        val video: File?,
        /** Bytes of [video] that form a complete, valid file. */
        val videoBytes: Long,
        val audio: File?,
        /** Bytes of [audio] that form a complete, valid file. */
        val audioBytes: Long,
        /** How much plays, or 0 when neither part could say; the player still shows the full length. */
        val playableMs: Long,
    )

    fun encode(
        stem: String,
        durationMs: Long,
    ): String = "$PREFIX$durationMs:$stem"

    fun isPartial(path: String?): Boolean = path?.startsWith(PREFIX) == true

    @Volatile private var requestedId: String? = null

    @Volatile private var requestedAt = 0L

    /** The user tapped an unfinished download to watch it; online, only that tap plays a partial copy. */
    fun request(videoId: String) {
        requestedId = videoId
        requestedAt = System.currentTimeMillis()
    }

    fun isRequested(videoId: String): Boolean = requestedId == videoId && System.currentTimeMillis() - requestedAt < REQUEST_WINDOW_MS

    /** Where the snapshot now playing ends, so a seek or the end of it can tell it has left the downloaded part. */
    @Volatile var playingEndMs = 0L
        private set

    /** The user left the preview (to watch the real stream), so an online load no longer picks it. */
    fun clearRequest() {
        requestedId = null
        playingEndMs = 0L
    }

    /** Whether [positionMs] lies beyond what the playing snapshot holds. */
    fun isPastEdge(positionMs: Long): Boolean = playingEndMs > 0L && positionMs > playingEndMs

    /** What of the download at [path] can be played now, or null while there is too little of it. */
    fun read(path: String): Spec? {
        if (!isPartial(path)) return null
        val body = path.removePrefix(PREFIX)
        val sep = body.indexOf(':')
        if (sep <= 0) return null
        val durationMs = body.substring(0, sep).toLongOrNull() ?: return null
        val stem = body.substring(sep + 1)
        val state = TransferState.read(File("$stem.state.json"))
        val video = part(File("$stem.video.part"), state?.streams?.firstOrNull { it.role == StreamRole.VIDEO })
        val audio = part(File("$stem.audio.part"), state?.streams?.firstOrNull { it.role == StreamRole.AUDIO })
        val parts = listOfNotNull(video, audio)
        if (parts.isEmpty()) return null
        val whole = parts.map { PlayablePrefix.measure(it.file, it.bytes) ?: return null }
        // The part that reaches further is trimmed to the other's end, so neither plays on alone.
        val common = whole.map { it.durationMs }.filter { it > 0L }.minOrNull()
        val cuts =
            parts.mapIndexed { i, part ->
                if (common != null && whole[i].durationMs > common) {
                    PlayablePrefix.measure(part.file, part.bytes, common) ?: return null
                } else {
                    whole[i]
                }
            }
        val videoCut = if (video != null) cuts[0] else null
        val audioCut = if (audio != null) cuts[parts.size - 1] else null
        var playableMs = Long.MAX_VALUE
        parts.forEachIndexed { i, part ->
            val cut = cuts[i]
            val coveredMs =
                when {
                    cut.durationMs > 0L -> cut.durationMs
                    durationMs > 0L && part.total > 0L -> (durationMs * (cut.bytes.toDouble() / part.total) * ESTIMATE_SAFETY).toLong()
                    else -> PlayablePrefix.UNKNOWN
                }
            if (coveredMs > 0L) playableMs = minOf(playableMs, coveredMs)
        }
        if (playableMs == Long.MAX_VALUE) playableMs = 0L
        if (playableMs in 1L until MIN_PLAYABLE_MS) return null
        return Spec(
            video = video?.file,
            videoBytes = videoCut?.bytes ?: 0L,
            audio = audio?.file,
            audioBytes = audioCut?.bytes ?: 0L,
            playableMs = playableMs,
        )
    }

    private class Part(
        val file: File,
        val bytes: Long,
        val total: Long,
    )

    private fun part(
        file: File,
        saved: TransferState.StreamState?,
    ): Part? {
        if (!file.isFile) return null
        val sequentialTotal = File(file.path + ".total").takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()
        return if (sequentialTotal != null && sequentialTotal > 0L) {
            // Written in order and never pre-sized: its length is the bytes received.
            Part(file, minOf(file.length(), sequentialTotal), sequentialTotal)
        } else {
            val stream = saved?.takeIf { it.totalBytes > 0L } ?: return null
            val done = stream.completedBlocks.toHashSet()
            var block = 0
            while (block in done) block++
            val bytes = minOf(block * RangeDownloader.BLOCK_SIZE, stream.totalBytes, file.length())
            Part(file, bytes, stream.totalBytes)
        }
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    fun buildSource(
        spec: Spec,
        mediaId: String,
        mediaMetadata: MediaMetadata,
    ): MediaSource {
        val extractors = DefaultExtractorsFactory()
        playingEndMs = spec.playableMs

        fun source(
            file: File,
            bytes: Long,
        ): MediaSource =
            ProgressiveMediaSource
                .Factory(DataSource.Factory { PrefixLimitedDataSource(bytes) }, extractors)
                .createMediaSource(
                    MediaItem
                        .Builder()
                        .setUri(Uri.fromFile(file))
                        .setMediaId(mediaId)
                        .setMediaMetadata(mediaMetadata)
                        .build(),
                )

        val parts =
            listOfNotNull(
                spec.video?.let { source(it, spec.videoBytes) },
                spec.audio?.let { source(it, spec.audioBytes) },
            )
        // Not clipped: clipping would shrink the player's duration (and what history saves) to the
        // downloaded part. Each part ends cleanly where its data does, and both end together.
        return if (parts.size == 1) parts.first() else MergingMediaSource(*parts.toTypedArray())
    }
}

/** Reads a file only up to [limit] bytes; anything past that reads as the end of the file. */
@UnstableApi
internal class PrefixLimitedDataSource(
    private val limit: Long,
) : DataSource {
    private val inner = FileDataSource()

    override fun addTransferListener(transferListener: TransferListener) = inner.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val room = (limit - dataSpec.position).coerceAtLeast(0L)
        val wanted = if (dataSpec.length == C.LENGTH_UNSET.toLong() || dataSpec.length > room) room else dataSpec.length
        return inner.open(dataSpec.subrange(0L, wanted))
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int = inner.read(buffer, offset, length)

    override fun getUri(): Uri? = inner.uri

    override fun close() = inner.close()
}
