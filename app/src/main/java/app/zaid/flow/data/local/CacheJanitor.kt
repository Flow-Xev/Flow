package app.zaid.flow.data.local

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Sweeps the small, app-owned scratch folders in the cache directory that nothing else bounds:
 * share exports, subtitle conversions and extracted covers. Each only needs to outlive the action
 * that made it, or a few weeks of reuse, so anything older is deleted. Blocking disk work; call it
 * from a background dispatcher.
 */
object CacheJanitor {
    private const val TAG = "CacheJanitor"

    private val maxAgeDaysByFolder =
        mapOf(
            "recap" to 1L,
            "playlists" to 1L,
            "subtitles" to 30L,
            "local_covers" to 90L,
        )

    fun sweep(context: Context) {
        val now = System.currentTimeMillis()
        maxAgeDaysByFolder.forEach { (folder, days) ->
            val cutoff = now - TimeUnit.DAYS.toMillis(days)
            runCatching {
                File(context.cacheDir, folder)
                    .takeIf { it.isDirectory }
                    ?.walkBottomUp()
                    ?.filter { it.isFile && it.lastModified() < cutoff }
                    ?.forEach { it.delete() }
            }.onFailure { Log.w(TAG, "Could not sweep $folder", it) }
        }
    }
}
