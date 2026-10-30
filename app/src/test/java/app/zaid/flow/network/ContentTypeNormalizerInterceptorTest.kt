package app.zaid.flow.network

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

class ContentTypeNormalizerInterceptorTest {
    private fun run(
        url: String,
        type: String?,
        payload: String,
    ): Response {
        val request = Request.Builder().url(url).build()
        val chain = mockk<Interceptor.Chain>()
        every { chain.request() } returns request
        every { chain.proceed(any()) } returns
            Response
                .Builder()
                .request(request)
                .protocol(Protocol.HTTP_2)
                .code(200)
                .message("OK")
                .body(payload.toResponseBody(type?.toMediaTypeOrNull()))
                .build()
        return ContentTypeNormalizerInterceptor.intercept(chain)
    }

    private val search = "https://www.youtube.com/youtubei/v1/search?prettyPrint=false"

    @Test
    fun `missing content type on json becomes application json and body is intact`() {
        val r = run(search, null, """{"contents":{}}""")
        assertThat(r.header("Content-Type")).contains("application/json")
        assertThat(r.body.string()).isEqualTo("""{"contents":{}}""")
    }

    @Test
    fun `text plain json is relabelled`() {
        assertThat(run(search, "text/plain", "[1,2]").header("Content-Type")).contains("application/json")
    }

    @Test
    fun `html is never relabelled`() {
        assertThat(run(search, null, "<html>blocked</html>").header("Content-Type")).isNull()
    }

    @Test
    fun `media hosts are untouched`() {
        assertThat(run("https://rr1.googlevideo.com/videoplayback", null, "{}").header("Content-Type")).isNull()
    }

    @Test
    fun `correct json type is left alone`() {
        assertThat(run(search, "application/json; charset=UTF-8", "{}").body.contentType().toString())
            .isEqualTo("application/json; charset=UTF-8")
    }
}
