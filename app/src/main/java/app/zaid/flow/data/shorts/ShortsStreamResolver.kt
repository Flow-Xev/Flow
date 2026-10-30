package app.zaid.flow.data.shorts

import android.util.Log
import app.zaid.flow.data.local.DataSaver
import app.zaid.flow.data.local.PlayerPreferences
import app.zaid.flow.innertube.models.response.PlayerResponse
import app.zaid.flow.player.quality.AutoQualityBounds
import app.zaid.flow.player.shorts.ShortsStartupTrace
import app.zaid.flow.player.stream.InFlightRequestCoalescer
import app.zaid.flow.player.stream.InnerTubeVideoStreamExtractor
import app.zaid.flow.player.stream.InnerTubeVideoStreamExtractor.VideoExtractionResult
import app.zaid.flow.player.stream.VideoCodecUtils
import app.zaid.flow.player.stream.durationMs
import app.zaid.flow.player.stream.playableAudioFormats
import app.zaid.flow.player.stream.playableVideoFormats
import app.zaid.flow.utils.PerformanceDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** What one reel's `/player` response says about it, carried with the streams so nothing asks twice. */
data class ShortDetails(
    val title: String,
    val channelName: String,
    val channelId: String,
    val viewCount: Long?,
    val durationMs: Long?,
)

data class ShortPlaybackStreams(
    val videoUrl: String,
    val audioUrl: String?,
    val durationMs: Long?,
    /** See [ShortsDashManifest]. Null means play the URL progressively. */
    val videoDashManifest: String? = null,
    val audioDashManifest: String? = null,
    val details: ShortDetails? = null,
)

data class ShortVideoQuality(
    val heightClass: Int,
    val label: String,
    val videoUrl: String,
    val codecLabel: String,
    val codecKey: String = "",
    val dashManifest: String? = null,
)

data class ShortAudioTrack(
    val label: String,
    val url: String,
    val dashManifest: String?,
    val isOriginal: Boolean,
    val bitrate: Int,
)

/**
 * Streams for a reel, from the one extraction path the main player uses. The `/player` response is
 * kept for the reel's lifetime on screen so the quality, audio-track and download sheets read it
 * instead of fetching it again, and every URL expires with the response it came from.
 */
@Singleton
class ShortsStreamResolver internal constructor(
    private val playerPreferences: PlayerPreferences,
    private val extract: suspend (String) -> VideoExtractionResult?,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    @Inject
    constructor(playerPreferences: PlayerPreferences) : this(
        playerPreferences = playerPreferences,
        extract = { videoId -> InnerTubeVideoStreamExtractor.extract(videoId) },
    )

    private class Expiring<T>(
        val value: T,
        val expiresAtMillis: Long,
    )

    private val extractions = lruCache<String, Expiring<VideoExtractionResult>>(MAX_EXTRACTIONS)
    private val streams = lruCache<String, Expiring<ShortPlaybackStreams>>(MAX_STREAMS)
    private val scope = CoroutineScope(SupervisorJob() + PerformanceDispatcher.networkIO)
    private val extractionCoalescer = InFlightRequestCoalescer<String, VideoExtractionResult?>(scope)
    private val streamCoalescer = InFlightRequestCoalescer<String, ShortPlaybackStreams?>(scope)

    suspend fun resolve(
        videoId: String,
        targetHeight: Int,
        preferredAudioLanguage: String,
    ): ShortPlaybackStreams? {
        ShortsStartupTrace.onRequested(videoId)
        // Data Saver prefers VP9 (smaller for the same picture); the other codecs stay as the fallback.
        val codecKey = if (DataSaver.enabled) "vp9" else playerPreferences.videoCodecPriority.first()
        // Auto's floor and ceiling shape the manifest, so they are part of what a cached entry was made for.
        val key = "$videoId|$targetHeight|$preferredAudioLanguage|$codecKey|${DataSaver.enabled}|${AutoQualityBounds.currentShorts().cacheKey}"
        cached(streams, key)?.let {
            ShortsStartupTrace.onStreamsResolved(videoId, cached = true, strategy = "cache")
            return it
        }
        return streamCoalescer.run(key) {
            val result = extraction(videoId) ?: return@run null
            val resolved = result.toPlaybackStreams(targetHeight, preferredAudioLanguage, codecKey) ?: return@run null
            synchronized(streams) { streams[key] = Expiring(resolved, expiryOf(result)) }
            ShortsStartupTrace.onStreamsResolved(videoId, cached = false, strategy = result.usedClient.clientName)
            resolved
        }
    }

    /** Drops everything cached for [videoId] so the next [resolve] mints fresh URLs. */
    fun invalidate(videoId: String) {
        synchronized(extractions) { extractions.remove(videoId) }
        synchronized(streams) { streams.keys.removeAll { it.startsWith("$videoId|") } }
    }

    suspend fun availableQualities(videoId: String): List<ShortVideoQuality> {
        val result = extraction(videoId) ?: return emptyList()
        val durationMs = result.durationMs()
        return result
            .playableVideoFormats()
            .groupBy { "${shortsQualityClass(it)}_${VideoCodecUtils.codecKeyFromMimeType(it.mimeType)}" }
            .mapNotNull { (_, group) ->
                val best = group.maxByOrNull { it.averageBitrate ?: it.bitrate } ?: return@mapNotNull null
                val url = best.url ?: return@mapNotNull null
                val heightClass = shortsQualityClass(best)
                val codecKey = VideoCodecUtils.codecKeyFromMimeType(best.mimeType)
                ShortVideoQuality(
                    heightClass = heightClass,
                    label = VideoCodecUtils.qualityLabelWithFrameRate(heightClass, best.fps ?: 0),
                    videoUrl = url,
                    codecLabel = VideoCodecUtils.codecLabelFromKey(codecKey),
                    codecKey = codecKey,
                    dashManifest = ShortsDashManifest.forVideo(best, url, durationMs),
                )
            }.sortedWith(
                compareByDescending<ShortVideoQuality> { it.heightClass }
                    .thenBy { VideoCodecUtils.playbackCodecRank(it.codecKey) },
            )
    }

    /**
     * The "Auto" entry for the quality menu: the same multi-quality manifest Shorts Auto plays, so
     * choosing it returns a pinned reel to adaptive playback. Null when the reel has no ladder.
     */
    suspend fun autoQuality(videoId: String): ShortVideoQuality? {
        val result = extraction(videoId) ?: return null
        val codecKey = if (DataSaver.enabled) "vp9" else playerPreferences.videoCodecPriority.first()
        val resolved = result.toPlaybackStreams(AUTO_ADAPTIVE, "", codecKey) ?: return null
        val manifest = resolved.videoDashManifest ?: return null
        if (REPRESENTATION.findAll(manifest).count() < 2) return null
        return ShortVideoQuality(
            heightClass = 0,
            label = "Auto",
            videoUrl = resolved.videoUrl,
            codecLabel = "",
            dashManifest = manifest,
        )
    }

    /** One entry per audio track, best bitrate of each, the original first. */
    suspend fun availableAudioTracks(videoId: String): List<ShortAudioTrack> {
        val result = extraction(videoId) ?: return emptyList()
        val durationMs = result.durationMs()
        return result
            .playableAudioFormats()
            .groupBy { it.audioTrack?.id ?: it.audioLanguageTag ?: "default" }
            .mapNotNull { (_, group) ->
                val best = group.maxByOrNull { it.averageBitrate ?: it.bitrate } ?: return@mapNotNull null
                val url = best.url ?: return@mapNotNull null
                ShortAudioTrack(
                    label = best.audioTrackLabel() ?: return@mapNotNull null,
                    url = url,
                    dashManifest = ShortsDashManifest.forAudio(best, url, durationMs),
                    isOriginal = best.isOriginal,
                    bitrate = (best.averageBitrate ?: best.bitrate).coerceAtLeast(0),
                )
            }.sortedWith(compareByDescending<ShortAudioTrack> { it.isOriginal }.thenBy { it.label })
    }

    suspend fun downloadFormats(
        videoId: String,
    ): Pair<List<PlayerResponse.StreamingData.Format>, List<PlayerResponse.StreamingData.Format>> {
        val result = extraction(videoId) ?: return emptyList<PlayerResponse.StreamingData.Format>() to emptyList()
        return result.playableVideoFormats() to result.playableAudioFormats()
    }

    suspend fun durationMs(videoId: String): Long? = extraction(videoId)?.durationMs()

    private suspend fun extraction(videoId: String): VideoExtractionResult? {
        cached(extractions, videoId)?.let { return it }
        return extractionCoalescer.run(videoId) {
            val result =
                try {
                    withTimeoutOrNull(EXTRACT_TIMEOUT_MS) { extract(videoId) }
                } catch (e: Exception) {
                    Log.w(TAG, "Extraction failed for $videoId: ${e.message}")
                    null
                } ?: return@run null
            synchronized(extractions) { extractions[videoId] = Expiring(result, expiryOf(result)) }
            result
        }
    }

    private fun <T> cached(
        cache: MutableMap<String, Expiring<T>>,
        key: String,
    ): T? =
        synchronized(cache) {
            val entry = cache[key] ?: return null
            if (nowMillis() < entry.expiresAtMillis) entry.value else null.also { cache.remove(key) }
        }

    /** A googlevideo URL outlives its `expiresInSeconds` by nothing; stop trusting it well before. */
    private fun expiryOf(result: VideoExtractionResult): Long {
        val ttlSeconds =
            result.playerResponse.streamingData
                ?.expiresInSeconds
                ?.takeIf { it > 0 }
                ?.toLong() ?: DEFAULT_TTL_SECONDS
        return nowMillis() + ttlSeconds * 1_000L * EXPIRY_FRACTION_PERCENT / 100L
    }

    private fun VideoExtractionResult.toPlaybackStreams(
        targetHeight: Int,
        preferredAudioLanguage: String,
        codecKey: String,
    ): ShortPlaybackStreams? {
        val videoFormats = playableVideoFormats()
        val adaptive = targetHeight == AUTO_ADAPTIVE
        // Auto stays between the viewer's floor and ceiling (480p up and no ceiling unless they set
        // them, and Data Saver caps it at 720p), as far as the reel has such qualities.
        val autoFormats = AutoQualityBounds.currentShorts().restrict(videoFormats, ::shortsQualityClass)
        val video =
            selectShortVideoFormat(if (adaptive) autoFormats else videoFormats, if (adaptive) 0 else targetHeight, codecKey)
                ?.let { picked -> if (adaptive) leanestSameClass(picked, autoFormats) else picked }
                ?: return null
        val videoUrl = video.url ?: return null
        val audio = selectShortAudioFormat(playableAudioFormats(), preferredAudioLanguage)
        val durationMs = durationMs()
        return ShortPlaybackStreams(
            videoUrl = videoUrl,
            audioUrl = audio?.url,
            durationMs = durationMs,
            videoDashManifest =
                (if (adaptive) ShortsDashManifest.forLadder(autoFormats, video, durationMs) else null)
                    ?: ShortsDashManifest.forVideo(video, videoUrl, durationMs),
            audioDashManifest = audio?.url?.let { ShortsDashManifest.forAudio(audio, it, durationMs) },
            details = playerResponse.videoDetails?.toShortDetails(durationMs),
        )
    }

    /**
     * Same resolution, fewer bytes: among formats of the picked quality class, take the lowest
     * bitrate one whose codec the device has already been asked to prefer or VP9 (never lower
     * resolution, so picture quality class is unchanged).
     */
    private fun leanestSameClass(
        picked: PlayerResponse.StreamingData.Format,
        formats: List<PlayerResponse.StreamingData.Format>,
    ): PlayerResponse.StreamingData.Format {
        val cls = shortsQualityClass(picked)
        val efficient = setOf("vp9")
        return formats
            .filter { shortsQualityClass(it) == cls && VideoCodecUtils.codecKeyFromMimeType(it.mimeType) in efficient }
            .minByOrNull { it.averageBitrate ?: it.bitrate }
            ?.takeIf { (it.averageBitrate ?: it.bitrate) < (picked.averageBitrate ?: picked.bitrate) }
            ?: picked
    }

    private fun PlayerResponse.VideoDetails.toShortDetails(durationMs: Long?) =
        ShortDetails(
            title = title.orEmpty(),
            channelName = author.orEmpty(),
            channelId = channelId,
            viewCount = viewCount?.toLongOrNull(),
            durationMs = durationMs,
        )

    companion object {
        /** Target height meaning Auto: one multi-quality manifest that Media3 adapts over. */
        const val AUTO_ADAPTIVE = -1

        private const val TAG = "ShortsStreamResolver"
        private val REPRESENTATION = Regex("<Representation ")
        private const val MAX_EXTRACTIONS = 20
        private const val MAX_STREAMS = 50
        private const val EXTRACT_TIMEOUT_MS = 8_000L
        private const val DEFAULT_TTL_SECONDS = 60L * 60L
        private const val EXPIRY_FRACTION_PERCENT = 80L

        private fun <K, V> lruCache(maxEntries: Int): MutableMap<K, V> =
            object : LinkedHashMap<K, V>(maxEntries, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > maxEntries
            }
    }
}
