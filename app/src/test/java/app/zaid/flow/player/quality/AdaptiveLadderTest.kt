package app.zaid.flow.player.quality

import app.zaid.flow.player.quality.LadderTestStreams.h264
import app.zaid.flow.player.quality.LadderTestStreams.vp9
import app.zaid.flow.player.stream.VideoCodecUtils
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AdaptiveLadderTest {
    private fun heights(rungs: List<org.schabi.newpipe.extractor.stream.VideoStream>) = rungs.map(VideoCodecUtils::qualityHeightFromStream)

    @Test
    fun `a ladder holds every height of the anchor's codec, lowest bitrate first`() {
        val anchor = h264(720, 2_500_000)
        val streams = listOf(h264(1080, 4_500_000), anchor, h264(360, 700_000), vp9(480, 900_000))

        val rungs = AdaptiveLadder.rungs(anchor, streams)

        assertThat(heights(rungs)).containsExactly(360, 720, 1080).inOrder()
    }

    @Test
    fun `HDR and SDR never share a ladder`() {
        val anchor = vp9(720, 2_000_000)
        val streams = listOf(anchor, vp9(1080, 3_500_000), vp9(1080, 5_000_000, hdr = true), vp9(480, 900_000, hdr = true))

        val rungs = AdaptiveLadder.rungs(anchor, streams)

        assertThat(heights(rungs)).containsExactly(720, 1080).inOrder()
        assertThat(rungs.none(AdaptiveLadder::isHighBitDepth)).isTrue()
    }

    @Test
    fun `streams without byte ranges are left out`() {
        val anchor = h264(720, 2_500_000)

        val rungs = AdaptiveLadder.rungs(anchor, listOf(anchor, h264(1080, 4_500_000, ranged = false)))

        assertThat(rungs).isEmpty()
    }

    @Test
    fun `one height keeps its highest bitrate copy`() {
        val anchor = h264(720, 2_500_000)
        val richer = h264(720, 2_900_000)

        val rungs = AdaptiveLadder.rungs(anchor, listOf(anchor, richer, h264(360, 700_000)))

        assertThat(rungs.last()).isSameInstanceAs(richer)
    }

    @Test
    fun `a pinned preload gets no ladder, an Auto one follows the codec preference`() {
        val streams = listOf(h264(720, 2_500_000), h264(360, 700_000), vp9(720, 2_000_000), vp9(360, 600_000))

        assertThat(AdaptiveLadder.forPreload(streams[0], streams, null)).isEmpty()
        val rungs = AdaptiveLadder.forPreload(null, streams, "vp9")
        assertThat(rungs.map(VideoCodecUtils::codecKeyFromStream).distinct()).containsExactly("vp9")
    }

    @Test
    fun `a ladder keeps to the Auto floor and ceiling`() {
        val anchor = h264(720, 2_500_000)
        val streams = listOf(h264(1080, 4_500_000), anchor, h264(480, 1_200_000), h264(360, 700_000))

        val range = AutoQualityBounds.resolve(userFloor = 0, userCeiling = 720, dataSaver = false)
        assertThat(heights(AdaptiveLadder.rungs(anchor, streams, range))).containsExactly(360, 480, 720).inOrder()

        val banded = AutoQualityBounds.resolve(userFloor = 720, userCeiling = 1080, dataSaver = false)
        assertThat(heights(AdaptiveLadder.rungs(anchor, streams, banded))).containsExactly(720, 1080).inOrder()
    }

    @Test
    fun `a band holding one quality leaves Auto on a single stream`() {
        val anchor = h264(720, 2_500_000)
        val streams = listOf(h264(1080, 4_500_000), anchor, h264(360, 700_000))

        val range = AutoQualityBounds.resolve(userFloor = 1080, userCeiling = 1080, dataSaver = false)

        assertThat(AdaptiveLadder.rungs(anchor, streams, range)).isEmpty()
    }
}
