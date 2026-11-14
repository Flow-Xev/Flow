package app.zaid.flow.player.cache

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheKeyFactory

/**
 * Cache keys that identify a stream by what it is, not by the signed URL it was fetched from.
 *
 * A `videoplayback` URL carries `expire`, `sig`, `ip`, `n`, `pot`, `c` and friends, all of which change
 * every time the stream is resolved. Keyed by the whole URL (Media3's default), watching the same
 * video twice, or reopening a Short, filled the cache with a second full copy that nothing could ever
 * read again. That is why the cache only ever grew and never produced a hit. The bytes behind a
 * `videoplayback` URL are fixed by `id`, `itag` and `lmt` (plus `xtags` for dubbed audio), so those
 * make the key and a re-resolved URL lands on the data already on disk.
 */
@UnstableApi
object StreamCacheKeys : CacheKeyFactory {
    override fun buildCacheKey(dataSpec: DataSpec): String = dataSpec.key ?: stableKey(dataSpec.uri.toString()) ?: dataSpec.uri.toString()

    /**
     * The stable key of a `videoplayback` [url], or null when it should be keyed by the URL as is:
     * not a media URL, missing an identifying parameter, a live or segment-addressed stream, or one
     * that selects its byte range in the query rather than the request.
     */
    fun stableKey(url: String): String? {
        val queryStart = url.indexOf('?')
        if (queryStart < 0) return null
        val path = url.substring(0, queryStart)
        if (!path.contains("/videoplayback")) return null

        val params = HashMap<String, String>(24)
        for (pair in url.substring(queryStart + 1).substringBefore('#').split('&')) {
            if (pair.isEmpty()) continue
            val separator = pair.indexOf('=')
            if (separator <= 0) continue
            params.putIfAbsent(pair.substring(0, separator), pair.substring(separator + 1))
        }

        if (params.containsKey("range") || params.containsKey("sq") || params.containsKey("rn")) return null
        if (params["source"]?.contains("live", ignoreCase = true) == true) return null
        if (params["live"] == "1" || params.containsKey("otf")) return null

        val id = params["id"]?.takeIf { it.isNotBlank() } ?: return null
        val itag = params["itag"]?.takeIf { it.isNotBlank() } ?: return null
        return buildString {
            append("yt:").append(id).append(':').append(itag)
            params["lmt"]?.takeIf { it.isNotBlank() }?.let { append(":l").append(it) }
            params["xtags"]?.takeIf { it.isNotBlank() }?.let { append(":x").append(it) }
            params["clen"]?.takeIf { it.isNotBlank() }?.let { append(":c").append(it) }
        }
    }
}
