package app.zaid.flow.player.cache

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import java.io.File

@OptIn(UnstableApi::class)
class AdjustableLruCacheEvictorTest {
    private val evictor = AdjustableLruCacheEvictor(maxBytes = 100)
    private val cache: Cache =
        mockk<Cache>(relaxed = true).also { mock ->
            every { mock.removeSpan(any()) } answers { evictor.onSpanRemoved(mock, firstArg()) }
        }

    private fun span(
        key: String,
        position: Long,
        length: Long,
        touched: Long,
    ) = CacheSpan(key, position, length, touched, File("/tmp/$key-$position"))

    @Test
    fun filesBeingWrittenCountAgainstTheLimit() {
        evictor.onSpanAdded(cache, span("old1", 0, 40, 1))
        evictor.onSpanAdded(cache, span("old2", 0, 40, 2))

        // Two writes start before either has finished; each used to check the free space alone.
        evictor.onStartFile(cache, "a", 0, 40)
        evictor.onStartFile(cache, "b", 0, 40)

        assertThat(evictor.committedAndReservedBytes).isAtMost(100)
        verify { cache.removeSpan(match { it.key == "old1" }) }
        verify { cache.removeSpan(match { it.key == "old2" }) }
    }

    @Test
    fun aFinishedFileSwapsItsBookingForItsRealSize() {
        evictor.onStartFile(cache, "a", 0, 40)
        assertThat(evictor.committedAndReservedBytes).isEqualTo(40)
        evictor.onSpanAdded(cache, span("a", 0, 30, 5))
        assertThat(evictor.committedAndReservedBytes).isEqualTo(30)
    }

    @Test
    fun anUnknownLengthStillBooksSpace() {
        evictor.onStartFile(cache, "a", 0, C.LENGTH_UNSET.toLong())
        assertThat(evictor.committedAndReservedBytes).isGreaterThan(0)
    }

    @Test
    fun aSpanThatWouldPassTheLimitIsDroppedAtOnce() {
        evictor.onSpanAdded(cache, span("a", 0, 60, 1))
        evictor.onSpanAdded(cache, span("b", 0, 60, 2))
        assertThat(evictor.committedAndReservedBytes).isAtMost(100)
        verify { cache.removeSpan(match { it.key == "a" }) }
    }

    @Test
    fun loweringTheLimitEvictsRightAway() {
        evictor.onSpanAdded(cache, span("a", 0, 50, 1))
        evictor.onSpanAdded(cache, span("b", 0, 40, 2))
        evictor.setMaxBytes(cache, 50)
        assertThat(evictor.committedAndReservedBytes).isAtMost(50)
    }

    @Test
    fun spansNobodyReadForAWhileAreDropped() {
        evictor.onSpanAdded(cache, span("old", 0, 10, 1_000))
        evictor.onSpanAdded(cache, span("fresh", 0, 10, 9_000))
        val freed = evictor.evictIdleSince(cache, cutoffMillis = 5_000)
        assertThat(freed).isEqualTo(10)
        assertThat(evictor.committedAndReservedBytes).isEqualTo(10)
    }
}
