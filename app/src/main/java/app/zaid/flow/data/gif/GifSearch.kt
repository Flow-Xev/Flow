package app.zaid.flow.data.gif

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** One GIF the picker offers: [previewUrl] is small and shown in the grid, [url] is what gets attached. */
data class GifResult(
    val id: String,
    val url: String,
    val previewUrl: String,
    val width: Int,
    val height: Int,
)

/**
 * GIF search for the comment picker, backed by GIPHY (Tenor's API closed on 30 June 2026).
 * The key comes from [GifSettings]: the one saved in Settings, else the one built into the app. With neither,
 * the picker button is hidden.
 */
object GifSearch {
    private const val PAGE_SIZE = 30
    private val client by lazy { OkHttpClient() }

    /** Tenor's keys as tenor.com's own pages carry them; Tenor's public developer API is closed. */
    @Volatile private var tenorKeys: Pair<String, String>? = null
    private val tenorNext = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Trending GIFs when [query] is blank, otherwise search results. [offset] pages through them.
     * Tenor comes first because YouTube attaches a GIF only by Tenor id; GIPHY (needs a key) is the fallback,
     * and those GIFs go into the comment as a link.
     */
    suspend fun load(
        context: Context,
        query: String,
        offset: Int = 0,
    ): Result<List<GifResult>> {
        val tenor = loadTenor(query, offset)
        if (tenor.isSuccess && tenor.getOrThrow().isNotEmpty()) return tenor
        if (offset > 0 && tenor.isSuccess) return tenor
        val giphy = loadGiphy(context, query, offset)
        return if (giphy.isSuccess || tenor.isSuccess) giphy else tenor
    }

    private suspend fun loadTenor(
        query: String,
        offset: Int,
    ): Result<List<GifResult>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val q = query.trim()
                val cursorKey = "$q"
                val pos = if (offset == 0) "" else tenorNext[cursorKey].orEmpty()
                if (offset > 0 && pos.isEmpty()) return@runCatching emptyList()
                suspend fun call(keys: Pair<String, String>): JSONObject? {
                    val url =
                        "https://tenor.googleapis.com/v2/${if (q.isEmpty()) "featured" else "search"}"
                            .toHttpUrl()
                            .newBuilder()
                            .addQueryParameter("key", keys.first)
                            .addQueryParameter("client_key", keys.second)
                            .addQueryParameter("limit", PAGE_SIZE.toString())
                            .addQueryParameter("contentfilter", "high")
                            .addQueryParameter("media_filter", "tinygif,tinywebp,gif")
                            .apply {
                                if (q.isNotEmpty()) addQueryParameter("q", q)
                                if (pos.isNotEmpty()) addQueryParameter("pos", pos)
                            }.build()
                    return client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                        if (r.code == 400 || r.code == 401 || r.code == 403) null
                        else {
                            check(r.isSuccessful) { "Tenor search failed (HTTP ${r.code})" }
                            JSONObject(r.body.string())
                        }
                    }
                }
                var json = tenorKeys?.let { call(it) }
                if (json == null) {
                    val fresh = fetchTenorKeys()
                    tenorKeys = fresh
                    json = call(fresh) ?: error("Tenor refused its own site key")
                }
                tenorNext[cursorKey] = json.optString("next")
                val results = json.optJSONArray("results") ?: return@runCatching emptyList()
                buildList {
                    for (i in 0 until results.length()) {
                        val item = results.getJSONObject(i)
                        val id = item.optString("id").takeIf { it.length in 6..20 && it.all(Char::isDigit) } ?: continue
                        val rating = item.optString("content_rating")
                        if (rating.isNotEmpty() && rating != "G") continue
                        val formats = item.optJSONObject("media_formats") ?: continue
                        val f = formats.optJSONObject("tinygif") ?: formats.optJSONObject("tinywebp") ?: formats.optJSONObject("gif") ?: continue
                        val preview = f.optString("url").takeIf { it.startsWith("https://media") } ?: continue
                        val dims = f.optJSONArray("dims")
                        add(
                            GifResult(
                                id = id,
                                // A tenor.com page link: GifSendFormat.tenorIdOf reads the id from it.
                                url = "https://tenor.com/view/x-gif-$id",
                                previewUrl = preview,
                                width = dims?.optInt(0, 220) ?: 220,
                                height = dims?.optInt(1, 124) ?: 124,
                            ),
                        )
                    }
                }
            }
        }

    /** The two values tenor.com's own scripts send to its API, read from the home page's base64 settings block. */
    private fun fetchTenorKeys(): Pair<String, String> {
        val request = Request.Builder().url("https://tenor.com/").header("User-Agent", "Mozilla/5.0").build()
        client.newCall(request).execute().use { r ->
            check(r.isSuccessful) { "Tenor returned HTTP ${r.code}" }
            val html = r.body.string()
            val block =
                Regex("""<script id="data" type="text/x-cache"[^>]*>([\s\S]*?)</script>""").find(html)?.groupValues?.get(1)?.trim()
                    ?: error("Tenor changed its page format")
            val data = JSONObject(String(android.util.Base64.decode(block, android.util.Base64.DEFAULT)))
            val key = data.optString("API_V2_KEY")
            val clientKey = data.optString("API_V2_CLIENT_KEY")
            check(key.isNotBlank() && clientKey.isNotBlank()) { "Tenor changed its page format" }
            return key to clientKey
        }
    }

    private suspend fun loadGiphy(
        context: Context,
        query: String,
        offset: Int,
    ): Result<List<GifResult>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val apiKey = GifSettings.currentKey(context)
                check(apiKey.isNotBlank()) { "Add a GIPHY API key in Settings > Integrations" }
                val path = if (query.isBlank()) "trending" else "search"
                val url =
                    "https://api.giphy.com/v1/gifs/$path"
                        .toHttpUrl()
                        .newBuilder()
                        .addQueryParameter("api_key", apiKey)
                        .addQueryParameter("limit", PAGE_SIZE.toString())
                        .addQueryParameter("offset", offset.toString())
                        .addQueryParameter("rating", "pg-13")
                        .apply { if (query.isNotBlank()) addQueryParameter("q", query.trim()) }
                        .build()
                client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    check(response.isSuccessful) { "GIF search failed (HTTP ${response.code})" }
                    val data = JSONObject(response.body.string()).getJSONArray("data")
                    buildList {
                        for (i in 0 until data.length()) {
                            val item = data.getJSONObject(i)
                            val images = item.optJSONObject("images") ?: continue
                            val original = images.optJSONObject("original") ?: continue
                            val preview = images.optJSONObject("fixed_width") ?: original
                            val gif = original.optString("url").takeIf(String::isNotBlank) ?: continue
                            add(
                                GifResult(
                                    id = item.optString("id"),
                                    url = gif,
                                    previewUrl = preview.optString("url").ifBlank { gif },
                                    width = preview.optString("width").toIntOrNull() ?: 200,
                                    height = preview.optString("height").toIntOrNull() ?: 200,
                                ),
                            )
                        }
                    }
                }
            }
        }
}
