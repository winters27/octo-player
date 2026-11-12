package app.winters.octo.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusScrimTest {
    // Opaque all the way down the status bar, then easing out to nothing
    // over the fade below it.
    @Test
    fun solidToTheBarThenFadingOut() {
        val stops = statusScrimStops(inset = 96f, fade = 64f)
        assertEquals(0f to 1f, stops.first())
        assertEquals(0.6f, stops[1].first, 0.0001f)
        assertEquals(1f, stops[1].second, 0f)
        assertEquals(1f to 0f, stops.last())
        // Each stop further down, and never stronger than the one above.
        stops.zipWithNext().forEach { (a, b) ->
            assertTrue(b.first > a.first)
            assertTrue(b.second <= a.second)
        }
    }

    // With no status bar (a phone in a window, say) it is all fade.
    @Test
    fun noBarIsAllFade() {
        val stops = statusScrimStops(inset = 0f, fade = 64f)
        assertEquals(0f, stops[1].first, 0f)
        assertEquals(1f to 0f, stops.last())
        assertEquals(listOf(0f to 0f, 1f to 0f), statusScrimStops(0f, 0f))
    }
}
