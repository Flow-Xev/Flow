package app.zaid.flow.player.cache

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StreamCacheKeysTest {
    private fun url(vararg params: String) = "https://rr1---sn-abc.googlevideo.com/videoplayback?" + params.joinToString("&")

    @Test
    fun `the same stream resolved twice shares one key`() {
        val first = url("expire=1700000000", "ei=aaa", "ip=1.2.3.4", "id=o-ABC", "itag=137", "lmt=1650000000", "sig=one", "n=nn1")
        val second = url("expire=1700099999", "ei=bbb", "ip=5.6.7.8", "id=o-ABC", "itag=137", "lmt=1650000000", "sig=two", "n=nn2")
        assertThat(StreamCacheKeys.stableKey(first)).isNotNull()
        assertThat(StreamCacheKeys.stableKey(first)).isEqualTo(StreamCacheKeys.stableKey(second))
    }

    @Test
    fun `different formats of one video get different keys`() {
        val video = url("id=o-ABC", "itag=137", "lmt=1")
        val audio = url("id=o-ABC", "itag=140", "lmt=1")
        assertThat(StreamCacheKeys.stableKey(video)).isNotEqualTo(StreamCacheKeys.stableKey(audio))
    }

    @Test
    fun `a re-uploaded file or another audio track is a different key`() {
        val old = url("id=o-ABC", "itag=140", "lmt=1")
        val reuploaded = url("id=o-ABC", "itag=140", "lmt=2")
        val dub = url("id=o-ABC", "itag=140", "lmt=1", "xtags=acont%3Ddubbed")
        assertThat(StreamCacheKeys.stableKey(old)).isNotEqualTo(StreamCacheKeys.stableKey(reuploaded))
        assertThat(StreamCacheKeys.stableKey(old)).isNotEqualTo(StreamCacheKeys.stableKey(dub))
    }

    @Test
    fun `urls that cannot be keyed safely fall back to the url`() {
        assertThat(StreamCacheKeys.stableKey("https://example.com/thumb.jpg")).isNull()
        assertThat(StreamCacheKeys.stableKey(url("itag=137"))).isNull()
        assertThat(StreamCacheKeys.stableKey(url("id=o-ABC", "itag=137", "range=0-100"))).isNull()
        assertThat(StreamCacheKeys.stableKey(url("id=o-ABC", "itag=137", "sq=5"))).isNull()
        assertThat(StreamCacheKeys.stableKey(url("id=o-ABC", "itag=137", "source=yt_live_broadcast"))).isNull()
    }
}
