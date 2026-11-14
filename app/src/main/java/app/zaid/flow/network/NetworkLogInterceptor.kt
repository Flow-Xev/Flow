package app.zaid.flow.network

import android.os.SystemClock
import android.util.Log
import app.zaid.flow.utils.FlowLogger
import app.zaid.flow.utils.FlowTrace
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okio.Buffer

/**
 * Logs every request the app makes through OkHttp (and so through Ktor, Coil, Media3's OkHttp
 * source and the NewPipe downloader).
 *
 * Every request gets a summary line: method, host and path, the status, and how long it took. A
 * failed request logs the exception. Requests to YouTube (InnerTube, the watch pages, the
 * stream hosts) are tagged by what they are and also get the full picture: the query, the
 * request and response headers, the request body and a preview of the response body.
 *
 * The Diagnostics log gets shared, so credentials never reach it: cookies, authorization
 * headers, tokens, visitor identifiers and signed URL parameters are replaced by `<redacted>`
 * before anything is written. Media payloads are never read.
 *
 * All of it stops, at the cost of one volatile read, while full logging is off in Settings.
 */
internal object NetworkLogInterceptor : Interceptor {
    private const val TAG = "FlowHttp"
    private const val SERVER_ERROR = 500
    private const val CLIENT_ERROR = 400
    private const val REQUEST_BODY_LIMIT = 8_192L
    private const val RESPONSE_BODY_LIMIT = 8_192L
    private const val COMPRESSED_PEEK_LIMIT = 65_536L
    private const val REDACTED = "<redacted>"

    private val sensitiveHeaders =
        setOf(
            "authorization",
            "cookie",
            "set-cookie",
            "proxy-authorization",
            "x-goog-visitor-id",
            "x-goog-authuser",
            "x-goog-pageid",
            "x-youtube-identity-token",
        )

    private val sensitiveQueryKeys =
        setOf("key", "sig", "lsig", "signature", "pot", "token", "access_token", "api_key", "visitordata", "n", "sapisid", "ei")

    private val sensitiveBodyField =
        Regex(
            "\"(visitorData|poToken|serviceIntegrityDimensions|cookie|authorization|accessToken|refreshToken|" +
                "clickTrackingParams|trackingParams|dataSyncId|onBehalfOfUser)\"\\s*:\\s*(\"[^\"]*\"|\\{[^{}]*\\})",
            RegexOption.IGNORE_CASE,
        )

    override fun intercept(chain: Interceptor.Chain): Response {
        if (!FlowLogger.isEnabled) return chain.proceed(chain.request())
        val request = chain.request()
        val what = describe(request)
        val tag = tagFor(request.url)
        val detailed = tag != TAG
        val started = SystemClock.elapsedRealtime()
        if (detailed) logRequest(tag, what, request)
        return try {
            val response = chain.proceed(request)
            val took = SystemClock.elapsedRealtime() - started
            val line = "$what -> ${response.code} ($took ms)"
            when {
                response.code >= SERVER_ERROR -> Log.e(tag, line)
                response.code >= CLIENT_ERROR -> Log.w(tag, line)
                else -> Log.d(tag, line)
            }
            if (detailed) logResponse(tag, what, response)
            response
        } catch (e: Exception) {
            val line = "$what FAILED after ${SystemClock.elapsedRealtime() - started} ms: ${e.javaClass.simpleName}: ${e.message}"
            if (chain.call().isCanceled()) Log.w(tag, line) else Log.e(tag, line)
            throw e
        }
    }

    internal fun describe(request: Request): String = "${request.method} ${request.url.host}${request.url.encodedPath}"

    /** Which trace tag a request belongs under; plain [TAG] for hosts that get only the summary line. */
    private fun tagFor(url: HttpUrl): String {
        val host = url.host
        val path = url.encodedPath
        return when {
            path.contains("/youtubei/") || host.contains("youtubei") -> FlowTrace.INNERTUBE

            host.endsWith("googlevideo.com") -> FlowTrace.YOUTUBE

            host.endsWith("youtube.com") || host.endsWith("youtu.be") || host.endsWith("ytimg.com") ||
                host.endsWith("youtube-nocookie.com") || host.endsWith("googleapis.com") -> FlowTrace.YOUTUBE

            else -> TAG
        }
    }

    private fun logRequest(
        tag: String,
        what: String,
        request: Request,
    ) {
        val query = redactedQuery(request.url)
        if (query.isNotEmpty()) Log.v(tag, "$what query: $query")
        val headers = redactHeaders(request.headers)
        if (headers.isNotEmpty()) Log.v(tag, "$what request headers: $headers")
        val body = request.body ?: return
        if (body.isOneShot() || body.isDuplex()) return
        val length = body.contentLength()
        if (length > REQUEST_BODY_LIMIT) {
            Log.v(tag, "$what request body: $length bytes (not logged)")
            return
        }
        runCatching {
            val buffer = Buffer()
            body.writeTo(buffer)
            val text = buffer.readUtf8()
            Log.v(tag, "$what request body (${text.length} chars): ${redactBody(text)}")
        }
    }

    private fun logResponse(
        tag: String,
        what: String,
        response: Response,
    ) {
        val headers = redactHeaders(response.headers)
        if (headers.isNotEmpty()) Log.v(tag, "$what response headers: $headers")
        val type = response.header("Content-Type").orEmpty().lowercase()
        val readable = "json" in type || "text" in type || "xml" in type || "javascript" in type
        if (!readable) {
            Log.v(
                tag,
                "$what response body: ${response.header("Content-Length") ?: "?"} bytes of ${type.ifEmpty { "unknown type" }} (not logged)",
            )
            return
        }
        runCatching {
            val encoding = response.header("Content-Encoding").orEmpty().lowercase()
            val preview =
                when (encoding) {
                    "", "identity" -> {
                        response.peekBody(RESPONSE_BODY_LIMIT).string()
                    }

                    "gzip" -> {
                        gunzipPreview(response.peekBody(COMPRESSED_PEEK_LIMIT).bytes())
                    }

                    else -> {
                        Log.v(tag, "$what response body: $encoding-encoded (not logged)")
                        return
                    }
                }
            val total = response.header("Content-Length")
            val suffix =
                if (total != null &&
                    total.toLongOrNull()?.let { it > RESPONSE_BODY_LIMIT } == true
                ) {
                    " … ($total bytes total)"
                } else {
                    ""
                }
            Log.v(tag, "$what response body: ${redactBody(preview)}$suffix")
        }
    }

    /** The first [RESPONSE_BODY_LIMIT] decoded characters of a possibly cut-off gzip stream. */
    private fun gunzipPreview(bytes: ByteArray): String {
        val out = java.io.ByteArrayOutputStream()
        runCatching {
            java.util.zip.GZIPInputStream(bytes.inputStream()).use { input ->
                val chunk = ByteArray(1024)
                while (out.size() < RESPONSE_BODY_LIMIT) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    out.write(chunk, 0, n)
                }
            }
        } // a truncated stream throws at the cut; what was decoded before it is still worth logging
        return out.toString(Charsets.UTF_8.name())
    }

    private fun redactedQuery(url: HttpUrl): String =
        url.queryParameterNames.joinToString("&") { name ->
            val value = url.queryParameterValues(name).firstOrNull().orEmpty()
            val shown = if (name.lowercase() in sensitiveQueryKeys) REDACTED else value.take(120)
            "$name=$shown"
        }

    private fun redactHeaders(headers: Headers): String =
        headers.names().joinToString("; ") { name ->
            val values = if (name.lowercase() in sensitiveHeaders) REDACTED else headers.values(name).joinToString(", ")
            "$name: $values"
        }

    private fun redactBody(text: String): String =
        sensitiveBodyField
            .replace(text) { "\"${it.groupValues[1]}\":\"$REDACTED\"" }
            .replace('\n', ' ')
            .replace('\r', ' ')
}
