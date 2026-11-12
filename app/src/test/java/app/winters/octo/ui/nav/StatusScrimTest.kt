package app.winters.octo.ui.nav

import app.winters.octo.design.StatusFrost
import app.winters.octo.design.statusFrostStops
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// The status bar's frost covers the bar's whole height at full strength and
// fades out just below it, deep enough that words under the clock blur away.
class StatusScrimTest {
    @Test
    fun theFrostIsFullThroughTheBarThenFades() {
        // A 24 dp bar and a 16 dp fade at 4 px a dp.
        assertEquals(96f / 160f to 1f, statusFrostStops(inset = 96f, fade = 64f))
        // No status bar: the frost only fades.
        assertEquals(0f to 1f, statusFrostStops(inset = 0f, fade = 64f))
        assertEquals(0f to 1f, statusFrostStops(inset = 0f, fade = 0f))
    }

    @Test
    fun theFrostIsDeeperThanTheChromes() {
        assertTrue(StatusFrost > app.winters.octo.design.GlazeFrost * 3)
    }
}
