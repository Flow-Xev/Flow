package app.zaid.flow.ui.components.shared

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SeekFractionTest {
    @Test
    fun nanFromZeroOverZeroBecomesStartOfTrack() {
        assertThat((0f / 0f).toSeekFraction()).isEqualTo(0f)
    }

    @Test
    fun infinitiesBecomeStartOfTrack() {
        assertThat((1f / 0f).toSeekFraction()).isEqualTo(0f)
        assertThat((-1f / 0f).toSeekFraction()).isEqualTo(0f)
    }

    @Test
    fun finiteValuesAreClampedToUnitRange() {
        assertThat((-0.5f).toSeekFraction()).isEqualTo(0f)
        assertThat(0.42f.toSeekFraction()).isEqualTo(0.42f)
        assertThat(3f.toSeekFraction()).isEqualTo(1f)
    }
}
