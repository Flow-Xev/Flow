package app.zaid.flow.player.cache

import android.content.Context
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.SimpleCache
import app.zaid.flow.data.local.MediaCacheLimits
import app.zaid.flow.player.config.PlayerConfig
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Owns the app's two player caches: one for videos and Shorts ([PlayerCacheManager], the Shorts
 * pool) and one for songs and animated artwork (DownloadUtil, the music player). Each folder may
 * only ever have one [SimpleCache] open on it, so both are created here and nowhere else.
 */
@UnstableApi
object SharedPlayerCacheProvider {
    private const val TAG = "SharedPlayerCache"

    @Volatile private var cache: SimpleCache? = null

    @Volatile private var musicCache: SimpleCache? = null

    @Volatile private var database: DatabaseProvider? = null

    // Kept so a changed size setting reaches the already-open caches instead of waiting for a restart.
    @Volatile private var videoEvictor: AdjustableLruCacheEvictor? = null

    @Volatile private var musicEvictor: AdjustableLruCacheEvictor? = null

    /**
     * The one database every Media3 cache in the app indexes into. Hilt hands out this same
     * instance, so no second open helper ever works on the same file.
     */
    @Synchronized
    fun databaseProvider(context: Context): DatabaseProvider =
        database ?: StandaloneDatabaseProvider(context.applicationContext).also { database = it }

    /**
     * Returns the video cache, creating it on first call.
     *
     * All callers share the same instance regardless of the [maxCacheSizeBytes] they pass; only the
     * first call's value is used.
     */
    @Synchronized
    fun getOrCreate(
        context: Context,
        maxCacheSizeBytes: Long = PlayerConfig.CACHE_SIZE_BYTES,
    ): SimpleCache = cache ?: create(context, PlayerConfig.CACHE_DIR_NAME, maxCacheSizeBytes) { videoEvictor = it }.also { cache = it }

    /** Returns the song cache, creating it on first call; only the first call's size is used. */
    @Synchronized
    fun getOrCreateMusic(
        context: Context,
        maxCacheSizeBytes: Long,
    ): SimpleCache =
        musicCache ?: create(context, PlayerConfig.MUSIC_CACHE_DIR_NAME, maxCacheSizeBytes) { musicEvictor = it }.also { musicCache = it }

    /**
     * The video cache if it already exists, without creating one.
     *
     * [getOrCreate] opens a SQLite index and scans the cache directory on first call, so callers
     * that run on the main thread (the Shorts pool builds its data sources there, as ExoPlayer
     * requires) must use this and arrange creation elsewhere rather than block.
     */
    fun existing(): SimpleCache? = cache

    /**
     * Applies [limits] to the caches that are already open and drops what has sat unread past its
     * idle limit. Blocking disk work: call it from a background dispatcher.
     */
    fun enforce(limits: MediaCacheLimits) {
        cache?.let(::purgeLegacyStreams)
        enforce(cache, videoEvictor, limits.videoBytes, PlayerConfig.VIDEO_CACHE_MAX_IDLE_HOURS)
        enforce(musicCache, musicEvictor, limits.musicBytes, PlayerConfig.MUSIC_CACHE_MAX_IDLE_HOURS)
    }

    /**
     * Before stable keys, every playback stored its own copy of a stream under its signed URL; none
     * of those copies can be read again, so they are only dead weight.
     */
    private fun purgeLegacyStreams(target: SimpleCache) {
        runCatching {
            target.keys
                .toList()
                .filter { it.contains("/videoplayback") }
                .forEach { target.removeResource(it) }
        }.onFailure { Log.w(TAG, "Could not drop legacy stream copies", it) }
    }

    private fun enforce(
        target: SimpleCache?,
        evictor: AdjustableLruCacheEvictor?,
        maxBytes: Long,
        maxIdleHours: Long,
    ) {
        if (target == null || evictor == null) return
        runCatching {
            evictor.setMaxBytes(target, maxBytes)
            val freed = evictor.evictIdleSince(target, System.currentTimeMillis() - TimeUnit.HOURS.toMillis(maxIdleHours))
            Log.d(TAG, "Enforced cache limit: max=${evictor.maxBytes}, idleFreed=$freed, now=${target.cacheSpace}")
        }.onFailure { Log.w(TAG, "Could not enforce the cache limit", it) }
    }

    /** Release the caches (call only on full app shutdown / tests). */
    @Synchronized
    fun release() {
        cache?.release()
        cache = null
        videoEvictor = null
        musicCache?.release()
        musicCache = null
        musicEvictor = null
        Log.d(TAG, "Player caches released")
    }

    private fun create(
        context: Context,
        folder: String,
        maxCacheSizeBytes: Long,
        onEvictor: (AdjustableLruCacheEvictor) -> Unit,
    ): SimpleCache {
        val cacheDir = File(context.applicationContext.cacheDir, folder)
        // Always bounded: a stored "unlimited" is clamped by the evictor rather than honoured.
        val evictor = AdjustableLruCacheEvictor(maxCacheSizeBytes).also(onEvictor)
        Log.d(TAG, "Creating SimpleCache: dir=$cacheDir, maxBytes=$maxCacheSizeBytes")
        return SimpleCache(cacheDir, evictor, databaseProvider(context))
    }
}
