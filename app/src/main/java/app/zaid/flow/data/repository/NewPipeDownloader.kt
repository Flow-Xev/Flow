package app.zaid.flow.data.repository

import android.content.Context
import android.os.SystemClock
import app.zaid.flow.network.AppProxyManager
import app.zaid.flow.utils.FlowTrace
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Downloader implementation for NewPipe Extractor.
 * Uses OkHttp for network requests.
 */
class NewPipeDownloader private constructor(
    context: Context,
) : Downloader() {
    @Volatile
    private var clientHolder: ClientHolder? = null

    private val client: OkHttpClient
        get() {
            val signature = AppProxyManager.currentSignature()
            clientHolder?.takeIf { it.proxySignature == signature }?.let { return it.client }

            return synchronized(this) {
                clientHolder?.takeIf { it.proxySignature == signature }?.client
                    ?: AppProxyManager
                        .applyTo(OkHttpClient.Builder())
                        .readTimeout(30, TimeUnit.SECONDS)
                        .connectTimeout(30, TimeUnit.SECONDS)
                        .writeTimeout(30, TimeUnit.SECONDS)
                        .build()
                        .also { clientHolder = ClientHolder(signature, it) }
            }
        }

    private data class ClientHolder(
        val proxySignature: String,
        val client: OkHttpClient,
    )

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

        @Volatile
        private var instance: NewPipeDownloader? = null

        fun getInstance(context: Context): NewPipeDownloader =
            instance ?: synchronized(this) {
                instance ?: NewPipeDownloader(context.applicationContext).also { instance = it }
            }
    }

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataToSend = request.dataToSend()

        val builder =
            okhttp3.Request
                .Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)

        // Add headers
        for ((key, list) in headers) {
            builder.removeHeader(key)
            for (value in list) {
                builder.addHeader(key, value)
            }
        }

        // Set method and body
        if (httpMethod == "POST") {
            val body =
                if (dataToSend != null) {
                    okhttp3.RequestBody.create(null, dataToSend)
                } else {
                    okhttp3.RequestBody.create(null, ByteArray(0))
                }
            builder.post(body)
        } else {
            builder.get()
        }

        FlowTrace.d(FlowTrace.NEWPIPE) {
            "extractor request: $httpMethod ${url.substringBefore('?')} (query ${if ('?' in url) "present" else "none"}, " +
                "${headers.size} headers, body ${dataToSend?.size ?: 0} bytes)"
        }
        val started = SystemClock.elapsedRealtime()
        return client.newCall(builder.build()).execute().use { response ->
            if (response.code == 429) {
                FlowTrace.w(FlowTrace.NEWPIPE) { "extractor request got 429, asking for reCaptcha: ${url.substringBefore('?')}" }
                throw ReCaptchaException("reCaptcha Challenge requested", url)
            }

            val responseString = response.body?.string() ?: ""
            val responseHeaders = response.headers.toMultimap()
            FlowTrace.d(FlowTrace.NEWPIPE) {
                "extractor response: ${response.code} ${response.message} for ${url.substringBefore('?')} - " +
                    "${responseString.length} chars in ${SystemClock.elapsedRealtime() - started} ms, final url " +
                    response.request.url.let { it.host + it.encodedPath }
            }

            Response(
                response.code,
                response.message,
                responseHeaders,
                responseString,
                url,
            )
        }
    }
}
