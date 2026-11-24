package app.zaid.flow.player.lounge

import androidx.compose.ui.geometry.Offset
import app.zaid.flow.ui.components.videoplayer.zoneAt
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LoungeProfileTest {
    @Test
    fun `manifest profiles are read`() {
        val text = """{"lounge":{"profiles":[{"app":"web","mdx":"3","ver":"9"}]}}"""
        assertThat(LoungeProfile.parseManifest(text)).containsExactly(LoungeProfile("web", "3", "9"))
    }

    @Test
    fun `malformed or unsafe profiles are dropped`() {
        assertThat(LoungeProfile.parseManifest("nope")).isEmpty()
        assertThat(LoungeProfile.parseManifest("""{"lounge":{"profiles":[{"app":"a&b=c","mdx":"3","ver":"8"}]}}""")).isEmpty()
    }

    @Test
    fun `learned profiles come first without duplicates`() {
        val learned = listOf(LoungeProfile("web", "3", "9"), LoungeProfile.FACTORY.first())
        val ladder = LoungeProfile.ladder(learned)
        assertThat(ladder.first()).isEqualTo(learned.first())
        assertThat(ladder).containsNoDuplicates()
    }

    @Test
    fun `dpad zones`() {
        val w = 200f
        assertThat(zoneAt(Offset(100f, 100f), w)).isEqualTo("ENTER")
        assertThat(zoneAt(Offset(100f, 10f), w)).isEqualTo("UP")
        assertThat(zoneAt(Offset(100f, 190f), w)).isEqualTo("DOWN")
        assertThat(zoneAt(Offset(10f, 100f), w)).isEqualTo("LEFT")
        assertThat(zoneAt(Offset(190f, 100f), w)).isEqualTo("RIGHT")
    }
}
