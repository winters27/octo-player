package app.winters.octo.desktop.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class SystemTextTest {
    @Test
    fun readsTheSystemsTextSize() {
        assertEquals(1.25f, windowsTextScale(125))
        // Never set, or out of Windows' own range.
        assertEquals(1f, windowsTextScale(null))
        assertEquals(2.25f, windowsTextScale(250))
        assertEquals(1.25f, gnomeTextScale("1.25\n"))
        assertEquals(1f, gnomeTextScale(null))
        assertEquals(1f, gnomeTextScale("nonsense"))
    }

    @Test
    fun theListenersChoiceWinsAndTheLayoutSetsTheMost() {
        // 0 follows the system, up to what the rows hold.
        assertEquals(1.15f, textScale(0, 1.15f))
        assertEquals(MaxTextScale, textScale(0, 2.25f))
        assertEquals(1f, textScale(0, 1f))
        // A size chosen in Octo stands whatever the system says.
        assertEquals(1.3f, textScale(130, 1f))
        assertEquals(1f, textScale(100, 1.5f))
    }
}
