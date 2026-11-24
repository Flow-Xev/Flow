package app.zaid.flow.player.stream

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.media3.common.util.UnstableApi
import app.zaid.flow.FlowApplication
import app.zaid.flow.innertube.models.YouTubeClient
import app.zaid.flow.innertube.models.response.PlayerResponse
import app.zaid.flow.player.cache.PlayerCacheManager
import app.zaid.flow.player.cache.SharedPlayerCacheProvider
import app.zaid.flow.player.cache.StreamCacheKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * What lets a video whose bytes are in the media cache play with no network.
 *
 * The cache holds the bytes under stable keys (id, itag, lmt), but playback only reaches them through
 * a stream list, and that list came from a /player request: offline that request fails, the load ends
 * as "extraction failed", and the cached bytes were never read. Shorts do not hit this because their
 * feed already carries the list. This store keeps the last list a video resolved to, on disk, and
 * [restore] hands it back (cut down to the streams the cache really holds) when YouTube cannot be asked.
 * The URLs inside are expired, which does not matter: a cached range is served without opening them.
 */
@OptIn(UnstableApi::class)
internal object OfflineStreamStore {
    private const val TAG = "OfflineStreamStore"
    private const val DIR = "offline_streams"
    private const val MAX_ENTRIES = 200
    private val MAX_AGE_MS = TimeUnit.DAYS.toMillis(30)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Serializable
    private data class Entry(
        val videoId: String,
        val savedAt: Long,
        val videoFormats: List<PlayerResponse.StreamingData.Format>,
        val audioFormats: List<PlayerResponse.StreamingData.Format>,
        val playerResponse: PlayerResponse,
        val usedClient: YouTubeClient,
    )

    private fun dir(): File = File(FlowApplication.appContext.filesDir, DIR).also { it.mkdirs() }

    private fun fileOf(videoId: String): File = File(dir(), videoId.filter { it.isLetterOrDigit() || it == '-' || it == '_' } + ".json")

    /** Remembers a finished VOD resolution; live streams and SABR sessions have no replayable URLs. */
    fun save(
        videoId: String,
        result: InnerTubeVideoStreamExtractor.VideoExtractionResult,
    ) {
        if (result.isLive || result.sabrInfo != null) return
        if (result.videoFormats.none { !it.url.isNullOrEmpty() } || result.audioFormats.none { !it.url.isNullOrEmpty() }) return
        scope.launch {
            runCatching {
                val entry = Entry(videoId, System.currentTimeMillis(), result.videoFormats, result.audioFormats, result.playerResponse, result.usedClient)
                val target = fileOf(videoId)
                val temp = File(target.parentFile, target.name + ".tmp")
                temp.writeText(json.encodeToString(Entry.serializer(), entry))
                temp.renameTo(target)
                trim()
            }.onFailure { Log.w(TAG, "Could not remember streams of $videoId", it) }
        }
    }

    /**
     * The saved resolution of [videoId], limited to the streams whose bytes the media cache holds, or
     * null when nothing was saved or the cache has no usable video and audio for it.
     */
    fun restore(videoId: String): InnerTubeVideoStreamExtractor.VideoExtractionResult? =
        runCatching {
            val file = fileOf(videoId).takeIf { it.isFile } ?: return null
            val entry = json.decodeFromString(Entry.serializer(), file.readText())
            val cache =
                SharedPlayerCacheProvider.existing()
                    ?: SharedPlayerCacheProvider.getOrCreate(
                        FlowApplication.appContext,
                        PlayerCacheManager.configuredLimits(FlowApplication.appContext).videoBytes,
                    )
            fun cached(format: PlayerResponse.StreamingData.Format): Boolean {
                val url = format.url ?: return false
                val key = StreamCacheKeys.stableKey(url) ?: url
                return cache.getCachedSpans(key).isNotEmpty()
            }
            val video = entry.videoFormats.filter(::cached)
            val audio = entry.audioFormats.filter(::cached)
            if (video.isEmpty() || audio.isEmpty()) {
                Log.d(TAG, "Saved streams of $videoId have no cached bytes (video=${video.size}, audio=${audio.size})")
                // The cache has since let go of this video; its saved list is dead weight.
                file.delete()
                return null
            }
            Log.d(TAG, "Offline playback of $videoId from cache: ${video.size} video, ${audio.size} audio streams")
            InnerTubeVideoStreamExtractor.VideoExtractionResult(
                videoFormats = video,
                audioFormats = audio,
                playerResponse = entry.playerResponse,
                usedClient = entry.usedClient,
                sabrInfo = null,
            )
        }.onFailure { Log.w(TAG, "Could not restore streams of $videoId", it) }.getOrNull()

    /** True when the device has a network that reaches the internet; false means every request will fail. */
    fun isOnline(context: Context = FlowApplication.appContext): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork ?: return false) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun trim() {
        val files = dir().listFiles { f -> f.name.endsWith(".json") }?.sortedBy { it.lastModified() } ?: return
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        files.forEachIndexed { index, file ->
            if (file.lastModified() < cutoff || index < files.size - MAX_ENTRIES) file.delete()
        }
    }
}
