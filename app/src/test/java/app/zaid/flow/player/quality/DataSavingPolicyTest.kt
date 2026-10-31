package app.zaid.flow.player.quality

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DataSavingPolicyTest {
    @Test
    fun `portrait phone window needs 720p, not 1080p`() {
        assertThat(DataSavingPolicy.windowHeightCap(1080, 608)).isEqualTo(720)
    }

    @Test
    fun `landscape fullscreen on a 1080p screen caps at 1080p`() {
        assertThat(DataSavingPolicy.windowHeightCap(2400, 1080)).isEqualTo(1080)
    }

    @Test
    fun `small mini player never drops under 240p and unknown window is uncapped`() {
        assertThat(DataSavingPolicy.windowHeightCap(300, 170)).isEqualTo(240)
        assertThat(DataSavingPolicy.windowHeightCap(0, 0)).isEqualTo(DataSavingPolicy.NO_CAP)
    }

    @Test
    fun `metered networks get a shorter forward buffer, free ones are untouched`() {
        assertThat(DataSavingPolicy.forwardBuffer(15_000, 50_000, metered = false)).isEqualTo(15_000 to 50_000)
        assertThat(DataSavingPolicy.forwardBuffer(15_000, 50_000, metered = true)).isEqualTo(10_000 to 20_000)
        assertThat(DataSavingPolicy.forwardBuffer(5_000, 8_000, metered = true)).isEqualTo(5_000 to 8_000)
    }

    @Test
    fun `window cap lowers Auto's ceiling`() {
        val range = AutoQualityBounds.resolve(0, 0, dataSaver = false, windowCap = 720)
        assertThat(range.ceiling).isEqualTo(720)
    }
}
