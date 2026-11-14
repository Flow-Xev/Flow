package app.zaid.flow.network

import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkLogInterceptorTest {
    @Test
    fun `a request is described by method, host and path only`() {
        val request =
            Request
                .Builder()
                .url("https://example.com/watch/v1?key=secret&token=abc#frag")
                .header("Authorization", "Bearer secret")
                .build()
        assertEquals("GET example.com/watch/v1", NetworkLogInterceptor.describe(request))
    }
}
