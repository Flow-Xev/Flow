package app.zaid.flow.data.shorts

import app.zaid.flow.data.model.Video
import app.zaid.flow.innertube.YouTube
import app.zaid.flow.innertube.models.YouTubeClient
import app.zaid.flow.innertube.pages.reel.reelPosterUrl
import app.zaid.flow.player.stream.InnerTubeVideoStreamExtractor
import app.zaid.flow.utils.PerformanceDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * The reel sequence names a reel and nothing else: no title, channel or view count. Every shelf
 * that is built from a sequence (home, the video page) therefore drew empty titles and "0 views".
 * This fills those in from the player endpoint, one lightweight request per reel, cached for the
 * process and capped to a few concurrent requests so a shelf scrolling in cannot flood the network.
 */
object ShortDetailsResolver {
    private data class Details(
        val title: String,
        val channelName: String,
        val channelId: String,
        val viewCount: Long,
    )

    private val cache = ConcurrentHashMap<String, Details>()

    /** When a lookup last failed, so a card scrolling in and out does not hammer the network. */
    private val failedAt = ConcurrentHashMap<String, Long>()

    /** Lets the Shorts player share what it already learned, so the home shelf never asks twice. */
    fun remember(
        videoId: String,
        title: String,
        channelName: String,
        channelId: String,
        viewCount: Long?,
    ) {
        if (title.isBlank() && (viewCount ?: 0L) <= 0L) return
        cache[videoId] = Details(title, channelName, channelId, viewCount ?: 0L)
        failedAt.remove(videoId)
    }

    private val gate = Semaphore(MAX_CONCURRENT)

    /** True when [video] still lacks something the card shows. */
    fun needsDetails(video: Video): Boolean = video.title.isBlank() || video.viewCount <= 0L

    /** [video] with whatever the player endpoint knew filled in; unchanged if the lookup fails. */
    suspend fun resolve(video: Video): Video {
        if (!needsDetails(video)) return video
        val details =
            cache[video.id] ?: run {
                val last = failedAt[video.id]
                if (last != null && System.currentTimeMillis() - last < RETRY_AFTER_MS) return video
                fetch(video.id)
                    ?.also {
                        cache[video.id] = it
                        failedAt.remove(video.id)
                    }
                    ?: return video.also { failedAt[video.id] = System.currentTimeMillis() }
            }
        return video.copy(
            title = video.title.ifBlank { details.title },
            channelName = video.channelName.ifBlank { details.channelName },
            channelId = video.channelId.ifBlank { details.channelId },
            viewCount = if (video.viewCount > 0L) video.viewCount else details.viewCount,
        )
    }

    /**
     * Tries the light player clients first, then the extractor Shorts playback itself uses (which
     * is known to work whenever a short plays), so one blocked client no longer leaves a card bare.
     */
    private suspend fun fetch(videoId: String): Details? =
        withContext(PerformanceDispatcher.networkIO) {
            gate.withPermit {
                fromPlayerClients(videoId) ?: fromExtractor(videoId)
            }
        }

    private suspend fun fromPlayerClients(videoId: String): Details? =
        withTimeoutOrNull(FETCH_TIMEOUT_MS) {
            val response =
                runCatching { YouTube.player(videoId, client = YouTubeClient.ANDROID).getOrNull() }
                    .getOrNull()
                    ?.takeIf { it.videoDetails?.title?.isNotBlank() == true }
                    ?: runCatching { YouTube.player(videoId, client = YouTubeClient.MOBILE).getOrNull() }.getOrNull()
                    ?: return@withTimeoutOrNull null
            val details = response.videoDetails ?: return@withTimeoutOrNull null
            Details(
                title = details.title.orEmpty(),
                channelName = details.author.orEmpty(),
                channelId = details.channelId,
                viewCount = details.viewCount?.toLongOrNull() ?: 0L,
            ).takeIf { it.title.isNotBlank() || it.viewCount > 0L }
        }

    private suspend fun fromExtractor(videoId: String): Details? =
        withTimeoutOrNull(EXTRACTOR_TIMEOUT_MS) {
            val details =
                runCatching { InnerTubeVideoStreamExtractor.extract(videoId) }
                    .getOrNull()
                    ?.playerResponse
                    ?.videoDetails
                    ?: return@withTimeoutOrNull null
            Details(
                title = details.title.orEmpty(),
                channelName = details.author.orEmpty(),
                channelId = details.channelId,
                viewCount = details.viewCount?.toLongOrNull() ?: 0L,
            ).takeIf { it.title.isNotBlank() || it.viewCount > 0L }
        }

    /**
     * The reels YouTube suggests after [videoId], for the row under a video's related list. Empty
     * on any failure: the row is an extra, never a reason for the page to show an error.
     */
    suspend fun relatedShorts(
        videoId: String,
        limit: Int = RELATED_LIMIT,
    ): List<Video> =
        withContext(PerformanceDispatcher.networkIO) {
            runCatching {
                YouTube
                    .shortsFromVideo(videoId)
                    .getOrNull()
                    ?.entries
                    .orEmpty()
                    .filter { it.videoId != videoId }
                    .distinctBy { it.videoId }
                    .take(limit)
                    .map { entry ->
                        Video(
                            id = entry.videoId,
                            title = "",
                            channelName = "",
                            channelId = "",
                            thumbnailUrl = entry.posterUrl ?: reelPosterUrl(entry.videoId),
                            duration = 0,
                            viewCount = 0,
                            uploadDate = "",
                            isShort = true,
                        )
                    }.let { stubs ->
                        // Resolve in parallel so titles appear with the row.
                        // The Semaphore inside resolve caps concurrency.
                        coroutineScope {
                            stubs.map { stub -> async { resolve(stub) } }.map { it.await() }
                        }
                    }
            }.getOrDefault(emptyList())
        }

    private const val MAX_CONCURRENT = 4
    private const val FETCH_TIMEOUT_MS = 6_000L
    private const val EXTRACTOR_TIMEOUT_MS = 9_000L
    private const val RETRY_AFTER_MS = 4_000L
    private const val RELATED_LIMIT = 12
}
