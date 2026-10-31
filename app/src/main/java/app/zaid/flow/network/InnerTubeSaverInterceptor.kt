package app.zaid.flow.network

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * Data saver for read-only InnerTube calls (`browse`, `next`, `search`).
 *
 * Two things burn data without helping the user: the same request fired twice at once (a screen and
 * its prefetch, a quick back-and-forth) and the same request repeated seconds later. This makes
 * identical requests share one network call (coalescing) and replays the answer for a few seconds.
 * Bodies are cached decompressed and replayed byte-for-byte, so quality is never reduced. `/player`, likes,
 * subscribes and every other write never pass through here.
 */
object InnerTubeSaverInterceptor : Interceptor {
    private class Entry(val bytes: ByteArray, val type: String?, val code: Int, val message: String, val at: Long)

    private const val MAX_ENTRIES = 48
    private const val MAX_BYTES = 1_500_000
    private val locks = ConcurrentHashMap<String, Any>()
    private val cache =
        object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > MAX_ENTRIES
        }

    private fun ttlMs(path: String): Long =
        when {
            path.endsWith("/browse") -> 20_000L
            path.endsWith("/next") -> 30_000L
            path.endsWith("/search") -> 60_000L
            else -> 0L
        }

    /** Drops every replayable answer. Used by self-healing and when incognito turns on. */
    fun clear() {
        synchronized(cache) { cache.clear() }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        // Incognito keeps nothing, not even for a few seconds in memory.
        if (app.zaid.flow.account.AccountIncognito.enabled) return chain.proceed(request)
        val ttl = if (request.method == "POST") ttlMs(request.url.encodedPath) else 0L
        val body = request.body
        if (ttl == 0L || body == null) return chain.proceed(request)

        val key =
            run {
                val buffer = Buffer()
                body.writeTo(buffer)
                val md = MessageDigest.getInstance("SHA-1")
                md.update(request.url.toString().toByteArray())
                md.update((request.header("Cookie") ?: "").toByteArray())
                md.update(buffer.readByteArray())
                md.digest().joinToString("") { "%02x".format(it) }
            }
        val lock = locks.getOrPut(key) { Any() }
        try {
            synchronized(lock) {
                val now = System.currentTimeMillis()
                synchronized(cache) { cache[key] }?.takeIf { now - it.at < ttl }?.let { return replay(request, it) }
                val response = chain.proceed(request)
                if (!response.isSuccessful) return response
                val raw = response.body.bytes()
                // A missing type must never be cached or replayed: that is how "ContentType: null" repeated.
                val type = (response.body.contentType()?.toString() ?: "application/json; charset=utf-8")
                val mediaType = type.toMediaTypeOrNull()
                val bytes = decode(raw, response.header("Content-Encoding"))
                    ?: return response.newBuilder().header("Content-Type", type).body(raw.toResponseBody(mediaType)).build()
                if (bytes.size <= MAX_BYTES) {
                    synchronized(cache) { cache[key] = Entry(bytes, type, response.code, response.message, now) }
                }
                return response
                    .newBuilder()
                    .removeHeader("Content-Encoding")
                    .removeHeader("Content-Length")
                    .header("Content-Type", type)
                    .body(bytes.toResponseBody(mediaType))
                    .build()
            }
        } finally {
            locks.remove(key)
        }
    }

    /**
     * The body as plain bytes, or null for an encoding this cannot undo. Live and replayed responses both
     * leave here without a Content-Encoding header, so what a caller sees never depends on whether the
     * answer came from the network or the cache. A caller that sets Accept-Encoding itself (Ktor does) makes
     * OkHttp skip its own decompression, which is how compressed bytes reached the JSON parser once.
     */
    internal fun decode(raw: ByteArray, encoding: String?): ByteArray? =
        when (encoding?.trim()?.lowercase()) {
            null, "", "identity" -> raw
            "gzip", "x-gzip" -> runCatching { GZIPInputStream(raw.inputStream()).use { it.readBytes() } }.getOrNull()
            "deflate" -> inflate(raw)
            else -> null
        }

    private fun inflate(raw: ByteArray): ByteArray? =
        // HTTP "deflate" is zlib-wrapped, but some servers send a bare deflate stream.
        runCatching { InflaterInputStream(raw.inputStream()).use { it.readBytes() } }.getOrNull()
            ?: runCatching { InflaterInputStream(raw.inputStream(), Inflater(true)).use { it.readBytes() } }.getOrNull()

    private fun replay(request: okhttp3.Request, e: Entry): Response =
        Response
            .Builder()
            .request(request)
            .protocol(okhttp3.Protocol.HTTP_2)
            .code(e.code)
            .message(e.message.ifEmpty { "OK" })
            .header("X-Flow-Saved", "1")
            .header("Content-Type", e.type ?: "application/json; charset=utf-8")
            .body(e.bytes.toResponseBody((e.type ?: "application/json; charset=utf-8").toMediaTypeOrNull()))
            .build()
}
