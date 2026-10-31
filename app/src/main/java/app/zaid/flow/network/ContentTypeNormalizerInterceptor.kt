package app.zaid.flow.network

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody

/**
 * Root-cause fix for `NoTransformationFoundException: Expected JsonObject but was SourceByteReadChannel`.
 *
 * Ktor's ContentNegotiation only parses a body when the response names a content type it knows.
 * YouTube sometimes answers `200 OK` with no `Content-Type` at all (and the data-saver cache can
 * replay such an answer), so the body is left as raw bytes and every `.body<JsonObject>()` throws.
 *
 * This sits outermost on every Ktor client: when a body is empty-typed or mislabelled as text/plain
 * or octet-stream but looks like JSON, it is relabelled `application/json`. Real media, HTML and
 * image responses are never touched. Only a small prefix is sniffed, and only when needed.
 */
internal object ContentTypeNormalizerInterceptor : Interceptor {
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private const val SNIFF_BYTES = 64L

    private val untrusted = setOf("text/plain", "application/octet-stream", "binary/octet-stream")

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val body = response.body
        val current = body.contentType()
        val needsFix = current == null || "${current.type}/${current.subtype}".lowercase() in untrusted
        if (!needsFix || response.code == 204 || response.code == 304) return response
        if (!isInnerTubeOrJsonHost(response)) return response

        val source = body.source()
        runCatching { source.request(SNIFF_BYTES) }
        val prefix = source.buffer.snapshot(minOf(source.buffer.size, SNIFF_BYTES).toInt()).utf8().trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        val looksJson = prefix.startsWith("{") || prefix.startsWith("[")
        if (!looksJson) return response
        return response
            .newBuilder()
            .header("Content-Type", JSON.toString())
            .body(body.source().asResponseBody(JSON, body.contentLength()))
            .build()
    }

    /** Only calls the app makes for structured data; never media, thumbnails or downloads. */
    private fun isInnerTubeOrJsonHost(response: Response): Boolean {
        val url = response.request.url
        val host = url.host
        val path = url.encodedPath
        return (host.endsWith("youtube.com") && (path.contains("/youtubei/") || path.contains("/complete/search"))) ||
            host.endsWith("youtube.com") && response.request.header("Accept")?.contains("json") == true ||
            host.endsWith("googleapis.com")
    }
}
