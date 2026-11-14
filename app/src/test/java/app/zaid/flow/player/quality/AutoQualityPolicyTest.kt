package app.zaid.flow.player.quality

import app.zaid.flow.player.quality.LadderTestStreams.h264
import app.zaid.flow.player.quality.LadderTestStreams.vp9
import app.zaid.flow.player.stream.VideoCodecUtils
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AutoQualityPolicyTest {
    private val ladder =
        listOf(
            h264(360, 700_000),
            h264(480, 1_200_000),
            h264(720, 2_500_000),
            h264(1080, 4_500_000),
        )

    private fun height(stream: org.schabi.newpipe.extractor.stream.VideoStream?) = stream?.let(VideoCodecUtils::qualityHeightFromStream)

    @Test
    fun `starts at the height the ladder names for the speed`() {
        assertThat(height(AutoQualityPolicy.initialPick(ladder, 4_000_000L, null))).isEqualTo(1080)
        assertThat(height(AutoQualityPolicy.initialPick(ladder, 2_400_000L, null))).isEqualTo(720)
        assertThat(height(AutoQualityPolicy.initialPick(ladder, 1_200_000L, null))).isEqualTo(480)
    }

    @Test
    fun `a very slow connection still starts at 480p, not lower`() {
        assertThat(height(AutoQualityPolicy.initialPick(ladder, 50_000L, null))).isEqualTo(480)
    }

    @Test
    fun `a fast connection starts at the tallest height the video has`() {
        assertThat(height(AutoQualityPolicy.initialPick(ladder, 7_000_000L, null))).isEqualTo(1080)
    }

    @Test
    fun `the lowest height is used when the video has nothing at or under the target`() {
        val tall = listOf(h264(1080, 4_500_000), h264(1440, 9_000_000))

        assertThat(height(AutoQualityPolicy.initialPick(tall, 50_000L, null))).isEqualTo(1080)
    }

    @Test
    fun `the preferred codec wins at the chosen height`() {
        val mixed = ladder + vp9(1080, 3_800_000)

        val pick = AutoQualityPolicy.initialPick(mixed, 8_000_000L, "vp9")

        assertThat(VideoCodecUtils.codecKeyFromStream(pick!!)).isEqualTo("vp9")
    }

    @Test
    fun `steps up when the speed now names a taller height`() {
        val current = ladder[1]

        assertThat(AutoQualityPolicy.stepUp(current, ladder, 400_000L, null)).isNull()
        assertThat(height(AutoQualityPolicy.stepUp(current, ladder, 3_200_000L, null))).isEqualTo(720)
    }

    @Test
    fun `steps down when the speed now names a shorter height`() {
        val current = ladder[3]

        assertThat(AutoQualityPolicy.stepDownForBandwidth(current, ladder, 7_000_000L, null)).isNull()
        assertThat(height(AutoQualityPolicy.stepDownForBandwidth(current, ladder, 2_400_000L, null))).isEqualTo(720)
    }

    @Test
    fun `nothing switches while the speed still names the current height`() {
        val current = ladder[2]
        val estimate = 3_200_000L

        assertThat(AutoQualityPolicy.stepUp(current, ladder, estimate, null)).isNull()
        assertThat(AutoQualityPolicy.stepDownForBandwidth(current, ladder, estimate, null)).isNull()
    }

    @Test
    fun `a step keeps the current codec when the next height has it`() {
        val mixed = ladder + vp9(720, 2_000_000)

        val next = AutoQualityPolicy.stepUp(ladder[1], mixed, 3_200_000L, "vp9")

        assertThat(VideoCodecUtils.codecKeyFromStream(next!!)).isEqualTo("h264")
    }

    @Test
    fun `a floor lifts the start and a ceiling holds it`() {
        try {
            AutoQualityBounds.floor = 1080
            assertThat(height(AutoQualityPolicy.initialPick(ladder, 50_000L, null))).isEqualTo(1080)

            AutoQualityBounds.floor = 0
            AutoQualityBounds.ceiling = 720
            assertThat(height(AutoQualityPolicy.initialPick(ladder, 7_000_000L, null))).isEqualTo(720)
            assertThat(AutoQualityPolicy.stepUp(ladder[2], ladder, 7_000_000L, null)).isNull()
        } finally {
            AutoQualityBounds.floor = 0
            AutoQualityBounds.ceiling = 0
        }
    }

    @Test
    fun `a stream above a lowered ceiling steps down to it`() {
        try {
            AutoQualityBounds.ceiling = 720
            assertThat(height(AutoQualityPolicy.stepDownForBandwidth(ladder[3], ladder, 7_000_000L, null))).isEqualTo(720)
        } finally {
            AutoQualityBounds.ceiling = 0
        }
    }

    @Test
    fun `buffering steps down but never under a floor the viewer set`() {
        val floored = AutoQualityBounds.resolve(userFloor = 720, userCeiling = 0, dataSaver = false)

        assertThat(height(AutoQualityPolicy.stepDown(ladder[3], ladder, null, floored))).isEqualTo(720)
        assertThat(AutoQualityPolicy.stepDown(ladder[2], ladder, null, floored)).isNull()
        assertThat(height(AutoQualityPolicy.stepDown(ladder[2], ladder, null, AutoQualityRange.DEFAULT))).isEqualTo(480)
    }

    @Test
    fun `a lean codec earns a taller height at the same speed`() {
        val mixed = listOf(h264(720, 2_500_000), h264(1080, 4_500_000), vp9(1080, 2_000_000))

        val pick = AutoQualityPolicy.initialPick(mixed, 3_200_000L, null)

        assertThat(height(pick)).isEqualTo(1080)
        assertThat(VideoCodecUtils.codecKeyFromStream(pick!!)).isEqualTo("vp9")
    }
}
