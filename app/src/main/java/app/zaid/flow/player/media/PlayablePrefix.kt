package app.zaid.flow.player.media

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Finds how much of a half-downloaded media file can be played as if it were a complete file.
 *
 * Handing a player the raw bytes received so far ends in the middle of a box or a cluster, and the
 * extractor fails on that ("Failed to load video") instead of stopping cleanly. The fix is to cut at
 * a boundary the container itself defines:
 *  - fragmented MP4 (every YouTube MP4 stream): the end of the last complete `moof` + `mdat` pair,
 *    once `moov` has fully arrived;
 *  - WebM / Matroska: the end of the last complete `Cluster`, once `Tracks` has fully arrived.
 *
 * Cut like that, the prefix is a valid, shorter file: the player plays it and reaches a clean end.
 * The covered duration is read from the container's own index (`sidx`, cluster timecodes) so the
 * audio and video parts can be clipped to the same moment without guessing from byte counts.
 */
internal object PlayablePrefix {
    /** [bytes] of the file that form a valid media file, covering [durationMs] (or [UNKNOWN]). */
    class Cut(
        val bytes: Long,
        val durationMs: Long,
    )

    const val UNKNOWN = -1L
    const val UNLIMITED = Long.MAX_VALUE

    private const val FTYP = 0x66747970
    private const val STYP = 0x73747970
    private const val MOOV = 0x6D6F6F76
    private const val SIDX = 0x73696478
    private const val MOOF = 0x6D6F6F66
    private const val MDAT = 0x6D646174

    private const val EBML = 0x1A45DFA3L
    private const val SEGMENT = 0x18538067L
    private const val INFO = 0x1549A966L
    private const val TIMECODE_SCALE = 0x2AD7B1L
    private const val TRACKS = 0x1654AE6BL
    private const val CLUSTER = 0x1F43B675L
    private const val CLUSTER_TIMECODE = 0xE7L

    private const val DEFAULT_TIMECODE_SCALE_NS = 1_000_000L
    private const val MAX_INDEX_BYTES = 4L * 1024 * 1024

    /**
     * The largest clean cut of the first [available] bytes of [file], or null while none exists yet
     * (the header or the first fragment is still incomplete). A format that is not recognised is
     * returned whole, as before.
     */
    fun measure(
        file: File,
        available: Long,
        limitMs: Long = UNLIMITED,
    ): Cut? {
        if (available <= 0L) return null
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val head = readAt(raf, 0L, 12)
                when {
                    head.size >= 8 && (int(head, 4) == FTYP || int(head, 4) == STYP) -> {
                        mp4(raf, available, limitMs)
                    }

                    head.size >= 4 && (int(head, 0).toLong() and 0xFFFFFFFFL) == EBML -> {
                        matroska(raf, available, limitMs)
                    }

                    else -> {
                        Cut(available, UNKNOWN)
                    }
                }
            }
        } catch (e: IOException) {
            null
        }
    }

    // ===== MP4 =====

    private class Sidx(
        val timescale: Long,
        val firstReferenceAt: Long,
        val sizes: LongArray,
        val durations: LongArray,
    )

    private fun mp4(
        raf: RandomAccessFile,
        available: Long,
        limitMs: Long,
    ): Cut? {
        var pos = 0L
        var moovDone = false
        var waitingForMdat = false
        var cut = 0L
        var sidx: Sidx? = null
        var stop = false
        while (pos + 8L <= available) {
            val header = readAt(raf, pos, 16)
            if (header.size < 8) break
            var size = uint(header, 0)
            val type = int(header, 4)
            var headerSize = 8L
            if (size == 1L) {
                if (header.size < 16) break
                size = long(header, 8)
                headerSize = 16L
            } else if (size == 0L) {
                // "Runs to the end of the file": never complete while the file is still growing.
                break
            }
            if (size < headerSize) return null
            val end = pos + size
            if (end > available) break
            when (type) {
                MOOV -> moovDone = true
                SIDX -> if (sidx == null) sidx = readSidx(raf, pos + headerSize, end)
                MOOF -> waitingForMdat = true
                MDAT ->
                    if (waitingForMdat) {
                        waitingForMdat = false
                        // Past the limit: this fragment would make the part outlast its partner.
                        if (sidx != null && durationUpTo(sidx, end) > limitMs) stop = true else cut = end
                    }
            }
            if (stop) break
            pos = end
        }
        if (!moovDone || cut == 0L) return null
        return Cut(cut, sidx?.let { durationUpTo(it, cut) } ?: UNKNOWN)
    }

    private fun readSidx(
        raf: RandomAccessFile,
        payloadStart: Long,
        boxEnd: Long,
    ): Sidx? {
        val length = boxEnd - payloadStart
        if (length < 12L || length > MAX_INDEX_BYTES) return null
        val b = readAt(raf, payloadStart, length.toInt())
        if (b.size.toLong() != length) return null
        val version = b[0].toInt() and 0xFF
        var o = 8 // version + flags, reference_ID
        val timescale = uint(b, o)
        o += 4
        val firstOffset: Long
        if (version == 0) {
            if (o + 8 > b.size) return null
            firstOffset = uint(b, o + 4)
            o += 8
        } else {
            if (o + 16 > b.size) return null
            firstOffset = long(b, o + 8)
            o += 16
        }
        if (o + 4 > b.size) return null
        val count = ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)
        o += 4
        if (timescale <= 0L || o + count.toLong() * 12L > b.size) return null
        val sizes = LongArray(count)
        val durations = LongArray(count)
        for (i in 0 until count) {
            val word = uint(b, o)
            // Reference type 1 points at another sidx instead of media: not a flat index.
            if (word and 0x80000000L != 0L) return null
            sizes[i] = word and 0x7FFFFFFFL
            durations[i] = uint(b, o + 4)
            o += 12
        }
        return Sidx(timescale, boxEnd + firstOffset, sizes, durations)
    }

    private fun durationUpTo(
        sidx: Sidx,
        cut: Long,
    ): Long {
        var at = sidx.firstReferenceAt
        var ticks = 0L
        for (i in sidx.sizes.indices) {
            at += sidx.sizes[i]
            if (at > cut) break
            ticks += sidx.durations[i]
        }
        return if (ticks <= 0L) UNKNOWN else ticks * 1000L / sidx.timescale
    }

    // ===== Matroska / WebM =====

    private class Element(
        val id: Long,
        val headerLength: Int,
        /** The payload size, or -1 when the element is of unknown size. */
        val size: Long,
    )

    private fun matroska(
        raf: RandomAccessFile,
        available: Long,
        limitMs: Long,
    ): Cut? {
        val ebml = element(raf, 0L, available) ?: return null
        if (ebml.id != EBML || ebml.size < 0L) return null
        var pos = ebml.headerLength + ebml.size
        if (pos > available) return null
        val segment = element(raf, pos, available) ?: return null
        if (segment.id != SEGMENT) return null
        pos += segment.headerLength

        var tracksDone = false
        var timecodeScaleNs = DEFAULT_TIMECODE_SCALE_NS
        var cut = 0L
        var lastStart = UNKNOWN
        var previousStart = UNKNOWN
        var stop = false
        while (true) {
            val child = element(raf, pos, available) ?: break
            if (child.size < 0L) break
            val start = pos + child.headerLength
            val end = start + child.size
            if (end > available) break
            when (child.id) {
                INFO -> timecodeScaleNs = readTimecodeScale(raf, start, end) ?: timecodeScaleNs
                TRACKS -> tracksDone = true
                CLUSTER -> {
                    val clusterStart = readClusterTimecode(raf, start, end)
                    val gap = if (lastStart >= 0L && clusterStart > lastStart) clusterStart - lastStart else 0L
                    val endsAtMs = if (clusterStart >= 0L) (clusterStart + gap) * timecodeScaleNs / 1_000_000L else UNKNOWN
                    if (limitMs != UNLIMITED && endsAtMs > limitMs) {
                        stop = true
                    } else {
                        cut = end
                        previousStart = lastStart
                        lastStart = clusterStart
                    }
                }
            }
            if (stop) break
            pos = end
        }
        if (!tracksDone || cut == 0L) return null
        val durationMs =
            if (lastStart >= 0L && previousStart >= 0L && lastStart > previousStart) {
                // The last cluster is assumed as long as the one before it.
                (lastStart + (lastStart - previousStart)) * timecodeScaleNs / 1_000_000L
            } else {
                UNKNOWN
            }
        return Cut(cut, durationMs)
    }

    /** The element header at [pos], or null when it is incomplete or not valid EBML. */
    private fun element(
        raf: RandomAccessFile,
        pos: Long,
        available: Long,
    ): Element? {
        if (pos >= available) return null
        val b = readAt(raf, pos, minOf(12L, available - pos).toInt())
        if (b.isEmpty()) return null
        val idFirst = b[0].toInt() and 0xFF
        if (idFirst == 0) return null
        val idLength = Integer.numberOfLeadingZeros(idFirst) - 24 + 1
        if (idLength > 4 || idLength >= b.size) return null
        var id = 0L
        for (i in 0 until idLength) id = (id shl 8) or (b[i].toLong() and 0xFF)

        val sizeFirst = b[idLength].toInt() and 0xFF
        if (sizeFirst == 0) return null
        val sizeLength = Integer.numberOfLeadingZeros(sizeFirst) - 24 + 1
        if (idLength + sizeLength > b.size) return null
        var size = (sizeFirst and (0xFF shr sizeLength)).toLong()
        var allOnes = size == (0xFF shr sizeLength).toLong()
        for (i in 1 until sizeLength) {
            val octet = b[idLength + i].toLong() and 0xFF
            if (octet != 0xFFL) allOnes = false
            size = (size shl 8) or octet
        }
        return Element(id, idLength + sizeLength, if (allOnes) -1L else size)
    }

    private fun readTimecodeScale(
        raf: RandomAccessFile,
        start: Long,
        end: Long,
    ): Long? {
        if (end - start > MAX_INDEX_BYTES) return null
        var pos = start
        while (pos < end) {
            val child = element(raf, pos, end) ?: return null
            if (child.size < 0L) return null
            val payload = pos + child.headerLength
            if (child.id == TIMECODE_SCALE) return readUnsigned(raf, payload, child.size)?.takeIf { it > 0L }
            pos = payload + child.size
        }
        return null
    }

    /** The cluster's own timecode (its first child), in timecode units; [UNKNOWN] when not found. */
    private fun readClusterTimecode(
        raf: RandomAccessFile,
        start: Long,
        end: Long,
    ): Long {
        val child = element(raf, start, end) ?: return UNKNOWN
        if (child.id != CLUSTER_TIMECODE || child.size < 0L) return UNKNOWN
        return readUnsigned(raf, start + child.headerLength, child.size) ?: UNKNOWN
    }

    private fun readUnsigned(
        raf: RandomAccessFile,
        pos: Long,
        length: Long,
    ): Long? {
        if (length < 1L || length > 8L) return null
        val b = readAt(raf, pos, length.toInt())
        if (b.size.toLong() != length) return null
        var value = 0L
        for (octet in b) value = (value shl 8) or (octet.toLong() and 0xFF)
        return value
    }

    // ===== Byte helpers =====

    /** Up to [length] bytes at [pos]; shorter only at the end of the file. */
    private fun readAt(
        raf: RandomAccessFile,
        pos: Long,
        length: Int,
    ): ByteArray {
        if (length <= 0) return ByteArray(0)
        raf.seek(pos)
        val out = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = raf.read(out, read, length - read)
            if (n < 0) break
            read += n
        }
        return if (read == length) out else out.copyOf(read)
    }

    private fun int(
        b: ByteArray,
        at: Int,
    ): Int =
        ((b[at].toInt() and 0xFF) shl 24) or
            ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or
            (b[at + 3].toInt() and 0xFF)

    private fun uint(
        b: ByteArray,
        at: Int,
    ): Long = int(b, at).toLong() and 0xFFFFFFFFL

    private fun long(
        b: ByteArray,
        at: Int,
    ): Long = (uint(b, at) shl 32) or uint(b, at + 4)
}
