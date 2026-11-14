package app.zaid.flow.player.cache

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import java.util.TreeSet

/**
 * Least-recently-used evictor whose ceiling can change while the cache is open.
 *
 * Media3's own [androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor] fixes its limit at
 * construction, so changing the size setting did nothing until the process restarted, and a limit of
 * zero meant [androidx.media3.datasource.cache.NoOpCacheEvictor], i.e. a cache that only ever grows.
 * This one never runs unbounded: a non-positive limit is clamped to [MIN_BYTES].
 *
 * Besides size pressure it also drops spans nobody has read for a while ([evictIdleSince]), which is
 * what keeps a cache that is under its ceiling from holding on to videos watched months ago.
 *
 * The ceiling is absolute. Media3 only reports a span once its file is complete, so with several
 * files being written at once (Shorts preloading, the player and a prefetch) each start used to
 * check the free space as if it were alone, and together they ended up past the limit. Every file
 * that starts now books the space it may use ([reserved]) until it is committed, so
 * `committed + reserved` never exceeds [maxBytes] when a write begins, and a span is dropped the
 * moment it would push the total over.
 */
@UnstableApi
class AdjustableLruCacheEvictor(
    maxBytes: Long,
) : CacheEvictor {
    @Volatile
    var maxBytes: Long = sanitize(maxBytes)
        private set

    private val lock = Any()
    private val leastRecentlyUsed = TreeSet<CacheSpan>(Comparator { lhs, rhs -> compareSpans(lhs, rhs) })
    private var currentSize = 0L

    /** Space promised to files that are still being written, keyed by "key@position". */
    private val reservations = HashMap<String, Reservation>()
    private var reserved = 0L

    private class Reservation(
        val bytes: Long,
        val bookedAtMillis: Long,
    )

    /** Bytes held by finished spans plus bytes promised to writes in flight. */
    val committedAndReservedBytes: Long get() = synchronized(lock) { currentSize + reserved }

    override fun requiresCacheSpanTouches(): Boolean = true

    override fun onCacheInitialized() {
        // Spans found on disk were reported through onSpanAdded; nothing else to do.
    }

    override fun onStartFile(
        cache: Cache,
        key: String,
        position: Long,
        length: Long,
    ) {
        // An unknown length still writes at most one fragment, so book that rather than nothing.
        val bytes = if (length == C.LENGTH_UNSET.toLong() || length <= 0L) UNKNOWN_LENGTH_RESERVE_BYTES else length
        synchronized(lock) {
            dropStaleReservations()
            reservations.put(reservationKey(key, position), Reservation(bytes, System.currentTimeMillis()))?.let { reserved -= it.bytes }
            reserved += bytes
        }
        evictToFit(cache, 0L)
    }

    override fun onSpanAdded(
        cache: Cache,
        span: CacheSpan,
    ) {
        synchronized(lock) {
            // The file this span came from has landed: swap its booking for the real size.
            reservations.remove(reservationKey(span.key, span.position))?.let { reserved -= it.bytes }
            if (leastRecentlyUsed.add(span)) currentSize += span.length
        }
        evictToFit(cache, 0L)
    }

    override fun onSpanRemoved(
        cache: Cache,
        span: CacheSpan,
    ) {
        synchronized(lock) {
            if (leastRecentlyUsed.remove(span)) currentSize -= span.length
        }
    }

    override fun onSpanTouched(
        cache: Cache,
        oldSpan: CacheSpan,
        newSpan: CacheSpan,
    ) {
        onSpanRemoved(cache, oldSpan)
        onSpanAdded(cache, newSpan)
    }

    /** Applies a new ceiling right away, evicting the oldest spans if the cache is now over it. */
    fun setMaxBytes(
        cache: Cache,
        bytes: Long,
    ) {
        maxBytes = sanitize(bytes)
        evictToFit(cache, 0L)
    }

    /** Re-checks the ceiling now; used by the periodic upkeep as a safety net. */
    fun trimToLimit(cache: Cache) = evictToFit(cache, 0L)

    /** Removes every span last read before [cutoffMillis]; returns how many bytes that freed. */
    fun evictIdleSince(
        cache: Cache,
        cutoffMillis: Long,
    ): Long {
        var freed = 0L
        while (true) {
            val oldest = synchronized(lock) { leastRecentlyUsed.firstOrNull() } ?: break
            if (oldest.lastTouchTimestamp >= cutoffMillis) break
            if (!removeQuietly(cache, oldest)) break
            freed += oldest.length
        }
        return freed
    }

    private fun evictToFit(
        cache: Cache,
        requiredSpace: Long,
    ) {
        while (true) {
            val oldest =
                synchronized(lock) {
                    if (currentSize + reserved + requiredSpace <= maxBytes) null else leastRecentlyUsed.firstOrNull()
                } ?: return
            if (!removeQuietly(cache, oldest)) return
        }
    }

    /** A write that was cancelled never reports a span, so its booking would otherwise leak. */
    private fun dropStaleReservations() {
        val cutoff = System.currentTimeMillis() - RESERVATION_TTL_MS
        val iterator = reservations.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.bookedAtMillis < cutoff) {
                reserved -= entry.value.bytes
                iterator.remove()
            }
        }
    }

    private fun reservationKey(
        key: String,
        position: Long,
    ) = "$key@$position"

    // removeSpan calls back into onSpanRemoved; the lock is never held across it, so the evictor and
    // the cache can't wait on each other from two threads.
    private fun removeQuietly(
        cache: Cache,
        span: CacheSpan,
    ): Boolean =
        try {
            cache.removeSpan(span)
            true
        } catch (_: Cache.CacheException) {
            // A span we cannot delete must not wedge the loop; forget it so eviction moves on.
            synchronized(lock) {
                if (leastRecentlyUsed.remove(span)) currentSize -= span.length
            }
            false
        }

    companion object {
        /** Floor for a limit that was stored as "no limit" by an older version. */
        const val MIN_BYTES = 64L * 1024L * 1024L

        /** What a write of unknown length books; Media3 splits such writes into fragments this size. */
        private const val UNKNOWN_LENGTH_RESERVE_BYTES = 5L * 1024L * 1024L

        /** A booking older than this belongs to a write that was abandoned. */
        private const val RESERVATION_TTL_MS = 2L * 60L * 1000L

        private fun sanitize(bytes: Long): Long = if (bytes <= 0L) MIN_BYTES else bytes

        private fun compareSpans(
            lhs: CacheSpan,
            rhs: CacheSpan,
        ): Int {
            val delta = lhs.lastTouchTimestamp - rhs.lastTouchTimestamp
            return if (delta == 0L) {
                lhs.compareTo(rhs)
            } else if (lhs.lastTouchTimestamp < rhs.lastTouchTimestamp) {
                -1
            } else {
                1
            }
        }
    }
}
