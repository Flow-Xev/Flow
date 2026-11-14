package app.zaid.flow.network

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPOutputStream

class InnerTubeSaverInterceptorTest {
    private val json = """{"channel":"ok"}"""

    private fun gzip(text: String): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(text.toByteArray()) } }.toByteArray()

    private fun deflate(text: String): ByteArray =
        ByteArrayOutputStream().also { out -> DeflaterOutputStream(out).use { it.write(text.toByteArray()) } }.toByteArray()

    private fun call(
        requestBody: String,
        encoding: String?,
        payload: ByteArray,
        hits: IntArray,
    ): Response {
        val request =
            Request
                .Builder()
                .url("https://www.youtube.com/youtubei/v1/browse")
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()
        val chain = mockk<Interceptor.Chain>()
        every { chain.request() } returns request
        every { chain.proceed(any()) } answers {
            hits[0]++
            Response
                .Builder()
                .request(request)
                .protocol(Protocol.HTTP_2)
                .code(200)
                .message("OK")
                .apply { if (encoding != null) header("Content-Encoding", encoding) }
                .header("Content-Length", payload.size.toString())
                .body(payload.toResponseBody("application/json".toMediaType()))
                .build()
        }
        return InnerTubeSaverInterceptor.intercept(chain)
    }

    @Test
    fun gzipLiveAndReplayBothArrivePlainWithoutEncodingHeader() {
        val hits = intArrayOf(0)
        val payload = gzip(json)
        val live = call("gzip-case", "gzip", payload, hits)
        val replay = call("gzip-case", "gzip", payload, hits)

        assertThat(hits[0]).isEqualTo(1)
        assertThat(replay.header("X-Flow-Saved")).isEqualTo("1")
        listOf(live, replay).forEach {
            assertThat(it.header("Content-Encoding")).isNull()
            assertThat(it.body.string()).isEqualTo(json)
        }
    }

    @Test
    fun deflateAndIdentityBodiesAreCachedPlain() {
        val hits = intArrayOf(0)
        val deflated = call("deflate-case", "deflate", deflate(json), hits)
        val replayed = call("deflate-case", "deflate", deflate(json), hits)
        assertThat(deflated.body.string()).isEqualTo(json)
        assertThat(replayed.body.string()).isEqualTo(json)

        val plain = call("plain-case", null, json.toByteArray(), hits)
        val plainReplay = call("plain-case", null, json.toByteArray(), hits)
        assertThat(plain.body.string()).isEqualTo(json)
        assertThat(plainReplay.body.string()).isEqualTo(json)
    }

    @Test
    fun unknownEncodingIsPassedThroughUntouchedAndNotCached() {
        val hits = intArrayOf(0)
        val payload = byteArrayOf(1, 2, 3)
        val first = call("br-case", "br", payload, hits)
        val second = call("br-case", "br", payload, hits)

        assertThat(hits[0]).isEqualTo(2)
        assertThat(first.header("Content-Encoding")).isEqualTo("br")
        assertThat(first.body.bytes()).isEqualTo(payload)
        assertThat(second.header("X-Flow-Saved")).isNull()
    }

    @Test
    fun decodeRejectsCorruptGzipInsteadOfThrowing() {
        assertThat(InnerTubeSaverInterceptor.decode(byteArrayOf(1, 2, 3), "gzip")).isNull()
    }
}
