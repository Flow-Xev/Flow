package app.zaid.flow.player.quality

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MeasuredSpeedTest {
    @Test
    fun `first sample is taken as is`() {
        assertThat(MeasuredSpeed.blend(0, 4_000_000)).isEqualTo(4_000_000)
    }

    @Test
    fun `later samples move the figure gently`() {
        assertThat(MeasuredSpeed.blend(4_000_000, 1_000_000)).isEqualTo(3_100_000)
    }
}
