package app.zaid.flow.data.local

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MediaCacheSizesTest {
    @Test
    fun `media sizes convert megabytes to bytes`() {
        assertThat(MediaCacheSizes.mediaBytes(500)).isEqualTo(500L * 1024 * 1024)
        assertThat(MediaCacheSizes.mediaBytes(5120)).isEqualTo(5120L * 1024 * 1024)
    }

    @Test
    fun `a stored unlimited value reads as the default so the cache always evicts`() {
        assertThat(MediaCacheSizes.mediaBytes(MediaCacheSizes.UNLIMITED_MB)).isEqualTo(500L * 1024 * 1024)
        assertThat(MediaCacheSizes.mediaBytes(-1)).isEqualTo(500L * 1024 * 1024)
        assertThat(MediaCacheSizes.normalizeMedia(0)).isEqualTo(MediaCacheSizes.DEFAULT_MEDIA_MB)
    }

    @Test
    fun `automatic artwork leaves the size to Coil`() {
        assertThat(MediaCacheSizes.artworkBytes(MediaCacheSizes.ARTWORK_AUTOMATIC_MB)).isNull()
        assertThat(MediaCacheSizes.artworkBytes(200)).isEqualTo(200L * 1024 * 1024)
    }

    @Test
    fun `the defaults are 500 MB per media cache and Coil's own artwork size`() {
        assertThat(MediaCacheLimits.DEFAULT.videoBytes).isEqualTo(500L * 1024 * 1024)
        assertThat(MediaCacheLimits.DEFAULT.musicBytes).isEqualTo(500L * 1024 * 1024)
        assertThat(MediaCacheLimits.DEFAULT.artworkBytes).isNull()
    }

    @Test
    fun `every media option is a real limit and unlimited is not offered`() {
        assertThat(MediaCacheSizes.MEDIA_OPTIONS_MB).doesNotContain(MediaCacheSizes.UNLIMITED_MB)
        assertThat(MediaCacheSizes.MEDIA_OPTIONS_MB.all { MediaCacheSizes.mediaBytes(it) > 0 }).isTrue()
    }
}
