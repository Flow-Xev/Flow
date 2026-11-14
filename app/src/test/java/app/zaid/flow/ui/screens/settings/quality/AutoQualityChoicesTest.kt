package app.zaid.flow.ui.screens.settings.quality

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoQualityChoicesTest {
    @Test
    fun `floors start with off and stop at the ceiling`() {
        assertEquals(listOf(0, 2160, 1440, 1080, 720), autoFloorChoices(ceiling = 0, current = 0))
        assertEquals(listOf(0, 1080, 720), autoFloorChoices(ceiling = 1080, current = 0))
        assertEquals(listOf(0), autoFloorChoices(ceiling = 480, current = 0))
    }

    @Test
    fun `ceilings start with no limit and start at the floor`() {
        assertEquals(listOf(0, 2160, 1440, 1080, 720, 480, 360, 240, 144), autoCeilingChoices(floor = 0, current = 0))
        assertEquals(listOf(0, 2160, 1440, 1080), autoCeilingChoices(floor = 1080, current = 0))
    }

    @Test
    fun `the current choice stays listed when the other bound has moved past it`() {
        assertEquals(listOf(0, 720), autoFloorChoices(ceiling = 480, current = 720))
        assertEquals(listOf(0, 2160, 1440, 1080, 480), autoCeilingChoices(floor = 1080, current = 480))
    }
}
