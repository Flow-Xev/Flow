package app.zaid.flow.data.translate

import android.util.LruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** [text] in the target language, and the language Google detected the original to be in ([sourceLang], e.g. "hi"). */
data class CommentTranslation(
    val text: String,
    val sourceLang: String,
)

/**
 * Translates comment text with Google Translate's public web endpoint (`translate.googleapis.com`,
 * `client=gtx`): no API key, automatic source-language detection, the same engine as translate.google.com.
 *
 * It is an unofficial, unversioned endpoint: Google can change or rate-limit it without notice. The
 * supported alternative is the Cloud Translation API (needs a key and billing). Results are cached per
 * (language, text), so each comment is requested at most once per target language.
 */
object CommentTranslator {
    private const val ENDPOINT = "https://translate.googleapis.com/translate_a/single"
    private const val MAX_CHUNK = 1000

    private val client by lazy {
        OkHttpClient
            .Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val cache = LruCache<String, CommentTranslation>(200)

    suspend fun translate(
        text: String,
        target: String,
    ): Result<CommentTranslation> =
        withContext(Dispatchers.IO) {
            val key = "$target\u0000$text"
            cache.get(key)?.let { return@withContext Result.success(it) }
            try {
                var result = translateChunks(text, target, "auto")
                if (target == "en") result = refineForEnglish(text, result)
                Result.success(result.also { cache.put(key, it) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private suspend fun translateChunks(
        text: String,
        target: String,
        source: String,
    ): CommentTranslation {
        val out = StringBuilder()
        var detected = ""
        for (part in splitForTranslation(text, MAX_CHUNK)) {
            currentCoroutineContext().ensureActive()
            val piece = requestChunk(part, target, source)
            out.append(piece.text)
            if (detected.isEmpty()) detected = piece.sourceLang
        }
        return CommentTranslation(out.toString(), detected)
    }

    /**
     * Google's auto-detect often calls romanised Hindi ("ye video bahut achhi hai") English and hands it back
     * unchanged, so such text is retried with Hindi as the source. Chat-speak English ("gud vdo, u r osm")
     * has nothing to translate, so it is rewritten into proper English locally.
     */
    private suspend fun refineForEnglish(
        text: String,
        first: CommentTranslation,
    ): CommentTranslation {
        val detectedEnglish = first.sourceLang.substringBefore('-') == "en"
        if (isRomanizedHindi(text) && (detectedEnglish || sameText(text, first.text))) {
            val forced =
                try {
                    translateChunks(text, "en", "hi")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            if (forced != null && forced.text.isNotBlank() && !sameText(text, forced.text)) return forced
        }
        if (detectedEnglish && isInformalEnglish(text)) {
            return CommentTranslation(normalizeInformalEnglish(text), "en")
        }
        return first
    }

    private fun requestChunk(
        chunk: String,
        target: String,
        source: String,
    ): CommentTranslation {
        // The text goes in the POST body: a long non-Latin comment would not fit in a URL.
        val url =
            ENDPOINT
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("client", "gtx")
                .addQueryParameter("sl", source)
                .addQueryParameter("tl", target)
                .addQueryParameter("dt", "t")
                .build()
        val request =
            Request
                .Builder()
                .url(url)
                .post(FormBody.Builder().add("q", chunk).build())
                .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Translate failed (HTTP ${response.code})")
            val body = response.body?.string().orEmpty()
            return parseTranslateResponse(body) ?: throw IOException("Unreadable translate response")
        }
    }
}

/** Reads the `[[["translated","original",...],...],null,"<detected lang>",...]` shape; null when it is not that. */
internal fun parseTranslateResponse(body: String): CommentTranslation? =
    runCatching {
        val root = Json.parseToJsonElement(body).jsonArray
        val text =
            root[0].jsonArray.joinToString("") { segment ->
                (segment as? JsonArray)
                    ?.getOrNull(0)
                    ?.jsonPrimitive
                    ?.contentOrNull
                    .orEmpty()
            }
        if (text.isBlank()) {
            null
        } else {
            CommentTranslation(
                text,
                root
                    .getOrNull(2)
                    ?.jsonPrimitive
                    ?.contentOrNull
                    .orEmpty(),
            )
        }
    }.getOrNull()

/** Pieces of at most [max] characters that join back into [text] exactly, cut at line or sentence ends where possible. */
internal fun splitForTranslation(
    text: String,
    max: Int,
): List<String> {
    val parts = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
        if (text.length - start <= max) {
            parts += text.substring(start)
            break
        }
        val window = text.substring(start, start + max)
        var cut =
            window.lastIndexOfAny(charArrayOf('\n', '.', '!', '?', '\u0964')).takeIf { it > max / 2 }?.plus(1)
                ?: window.lastIndexOf(' ').takeIf { it > max / 2 }?.plus(1)
                ?: max
        // Never cut an emoji in half.
        if (cut > 1 && text[start + cut - 1].isHighSurrogate()) cut--
        parts += text.substring(start, start + cut)
        start += cut
    }
    return parts
}

/** True when [a] and [b] differ at most in case, spacing and punctuation. */
internal fun sameText(
    a: String,
    b: String,
): Boolean {
    fun norm(t: String) = t.lowercase().filter { it.isLetterOrDigit() }
    return norm(a) == norm(b)
}
