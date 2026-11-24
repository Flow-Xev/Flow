package app.zaid.flow.player.media

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer

class PlayablePrefixTest {
    @get:Rule
    val folder = TemporaryFolder()

    // ===== MP4 builders =====

    private fun box(
        type: String,
        payload: ByteArray,
    ): ByteArray =
        ByteBuffer
            .allocate(8 + payload.size)
            .putInt(8 + payload.size)
            .put(type.toByteArray(Charsets.US_ASCII))
            .put(payload)
            .array()

    /** A sidx (version 0) whose references are [sizes] bytes long and [durations] ticks at 1000 ticks/s. */
    private fun sidx(
        sizes: List<Int>,
        durations: List<Int>,
    ): ByteArray {
        val out = ByteBuffer.allocate(4 + 4 + 4 + 4 + 4 + 2 + 2 + sizes.size * 12)
        out.putInt(0).putInt(1).putInt(1000).putInt(0).putInt(0).putShort(0).putShort(sizes.size.toShort())
        sizes.indices.forEach { out.putInt(sizes[it]).putInt(durations[it]).putInt(0) }
        return box("sidx", out.array())
    }

    private fun fragment(mediaBytes: Int): ByteArray = box("moof", ByteArray(16)) + box("mdat", ByteArray(mediaBytes))

    /** ftyp + moov + sidx + [fragments] fragments of 2000 ms each. */
    private fun mp4(fragments: Int): ByteArray {
        val frag = fragment(100)
        val out = ByteArrayOutputStream()
        out.write(box("ftyp", ByteArray(8)))
        out.write(box("moov", ByteArray(32)))
        out.write(sidx(List(fragments) { frag.size }, List(fragments) { 2000 }))
        repeat(fragments) { out.write(frag) }
        return out.toByteArray()
    }

    private fun file(bytes: ByteArray): File = folder.newFile().apply { writeBytes(bytes) }

    @Test
    fun `an mp4 cut ends at its last complete fragment and says how long that is`() {
        val whole = mp4(fragments = 4)
        val fragmentSize = fragment(100).size
        val firstFragmentAt = whole.size - 4 * fragmentSize

        // Three whole fragments and half of the fourth.
        val available = firstFragmentAt + 3 * fragmentSize + fragmentSize / 2
        val cut = PlayablePrefix.measure(file(whole), available.toLong())!!

        assertThat(cut.bytes).isEqualTo((firstFragmentAt + 3 * fragmentSize).toLong())
        assertThat(cut.durationMs).isEqualTo(6_000L)
    }

    @Test
    fun `an mp4 with no complete fragment yet is not ready`() {
        val whole = mp4(fragments = 2)
        val fragmentSize = fragment(100).size
        val available = whole.size - 2 * fragmentSize + fragmentSize / 2

        assertThat(PlayablePrefix.measure(file(whole), available.toLong())).isNull()
    }

    @Test
    fun `an mp4 whose moov has not fully arrived is not ready`() {
        assertThat(PlayablePrefix.measure(file(mp4(2)), 20L)).isNull()
    }

    @Test
    fun `an mp4 with a moof but its mdat cut short ends before that moof`() {
        val whole = mp4(fragments = 2)
        val fragmentSize = fragment(100).size
        val firstFragmentAt = whole.size - 2 * fragmentSize

        val cut = PlayablePrefix.measure(file(whole), (whole.size - 10).toLong())!!

        assertThat(cut.bytes).isEqualTo((firstFragmentAt + fragmentSize).toLong())
    }

    // ===== WebM builders =====

    private fun vint(value: Long): ByteArray {
        // Always 4 bytes: marker 0x10, 28 value bits.
        return byteArrayOf((0x10 or ((value shr 24) and 0x0FL).toInt()).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte())
    }

    private fun element(
        id: Long,
        payload: ByteArray,
    ): ByteArray {
        val idBytes =
            when {
                id > 0xFFFFFF -> byteArrayOf((id shr 24).toByte(), (id shr 16).toByte(), (id shr 8).toByte(), id.toByte())
                id > 0xFFFF -> byteArrayOf((id shr 16).toByte(), (id shr 8).toByte(), id.toByte())
                id > 0xFF -> byteArrayOf((id shr 8).toByte(), id.toByte())
                else -> byteArrayOf(id.toByte())
            }
        return idBytes + vint(payload.size.toLong()) + payload
    }

    private fun cluster(
        timecode: Int,
        bodyBytes: Int,
    ): ByteArray = element(0x1F43B675L, element(0xE7L, byteArrayOf(0, 0, (timecode shr 8).toByte(), timecode.toByte())) + ByteArray(bodyBytes))

    private fun webm(clusters: Int): ByteArray {
        val header = element(0x1A45DFA3L, ByteArray(4))
        val info = element(0x1549A966L, element(0x2AD7B1L, byteArrayOf(0x0F, 0x42, 0x40))) // 1_000_000 ns
        val tracks = element(0x1654AE6BL, ByteArray(10))
        val children = ByteArrayOutputStream()
        children.write(info)
        children.write(tracks)
        repeat(clusters) { children.write(cluster(timecode = it * 2000, bodyBytes = 50)) }
        val body = children.toByteArray()
        return header + element(0x18538067L, body)
    }

    @Test
    fun `a webm cut ends at its last complete cluster`() {
        val whole = webm(clusters = 5)
        val clusterSize = cluster(0, 50).size

        // Four whole clusters and half of the fifth.
        val available = whole.size - clusterSize / 2
        val cut = PlayablePrefix.measure(file(whole), available.toLong())!!

        assertThat(cut.bytes).isEqualTo((whole.size - clusterSize).toLong())
        // Clusters start at 0, 2000, 4000, 6000 ms; the last is assumed as long as the one before.
        assertThat(cut.durationMs).isEqualTo(8_000L)
    }

    @Test
    fun `a webm with no complete cluster yet is not ready`() {
        val whole = webm(clusters = 2)
        val clusterSize = cluster(0, 50).size

        assertThat(PlayablePrefix.measure(file(whole), (whole.size - 2 * clusterSize + 5).toLong())).isNull()
    }

    @Test
    fun `a file of an unknown format is taken whole`() {
        val cut = PlayablePrefix.measure(file(ByteArray(64) { 7 }), 40L)!!

        assertThat(cut.bytes).isEqualTo(40L)
        assertThat(cut.durationMs).isEqualTo(PlayablePrefix.UNKNOWN)
    }

    @Test
    fun `nothing downloaded is not ready`() {
        assertThat(PlayablePrefix.measure(file(mp4(1)), 0L)).isNull()
    }
}
