package app.winters.octo.wayland

import app.winters.octo.desktop.settings.WindowSpot
import org.junit.Test
import kotlin.test.assertEquals

class WindowStateTest {
    @Test fun maximizeKeepsNormalBoundsAndRestoredStateCanUnmaximize() {
        val normal = WindowSpot(0f, 0f, 950f, 650f)
        val maximized = windowSpot(normal, 1920, 1080, true, false)
        assertEquals(normal.copy(maximized = true), maximized)
        // This same saved spot survives closing/recreating the main window.
        assertEquals(maximized, windowSpot(maximized, 2560, 1440, true, false))
        assertEquals(normal, windowSpot(maximized, 950, 650, false, false))
    }

    @Test fun firstMaximizedConfigureDoesNotSaveScreenDimensionsAsNormal() {
        assertEquals(WindowSpot(0f, 0f, 1100f, 760f, true), windowSpot(null, 1920, 1080, true, false))
        assertEquals(WindowSpot(0f, 0f, 520f, 160f, true), windowSpot(null, 1920, 1080, true, true))
    }
}
