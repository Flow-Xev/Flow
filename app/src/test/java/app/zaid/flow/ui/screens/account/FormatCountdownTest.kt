package app.zaid.flow.ui.screens.account

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatCountdownTest {
    @Test
    fun formatsMinutesAndSeconds() {
        assertEquals("15:05", formatCountdown(905))
        assertEquals("0:09", formatCountdown(9))
        assertEquals("0:00", formatCountdown(-3))
    }
}
