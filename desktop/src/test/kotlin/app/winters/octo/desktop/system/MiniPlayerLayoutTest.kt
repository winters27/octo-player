package app.winters.octo.desktop.system

import app.winters.octo.desktop.settings.WindowSpot
import app.winters.octo.desktop.window.ScreenArea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniPlayerLayoutTest {
    private val screens = listOf(ScreenArea(0f, 0f, 1920f, 1040f), ScreenArea(1920f, 0f, 1280f, 1024f))

    @Test
    fun theShapeFollowsTheHeight() {
        assertEquals(MiniShape.Bar, miniShapeFor(MINI_HEIGHT, null))
        assertEquals("a bar stays a bar with a panel chosen", MiniShape.Bar, miniShapeFor(MINI_HEIGHT, MiniPanel.Lyrics))
        assertEquals(MiniShape.Cover, miniShapeFor(MINI_SQUARE_HEIGHT, null))
        assertEquals(MiniShape.Panel, miniShapeFor(MINI_SQUARE_HEIGHT, MiniPanel.Queue))
        assertEquals("too short for a panel: the cover", MiniShape.Cover, miniShapeFor(MINI_COVER_HEIGHT + 10f, MiniPanel.Lyrics))
        assertEquals(MiniShape.Bar, miniShapeFor(MINI_COVER_HEIGHT - 1f, null))
    }

    @Test
    fun theSizeButtonGoesBetweenTheBarAndTheSquare() {
        assertEquals(MINI_SQUARE_WIDTH to MINI_SQUARE_HEIGHT, toggledMiniSize(WindowSpot(0f, 0f, MINI_WIDTH, MINI_HEIGHT)))
        assertEquals(MINI_WIDTH to MINI_HEIGHT, toggledMiniSize(WindowSpot(0f, 0f, MINI_SQUARE_WIDTH, MINI_SQUARE_HEIGHT)))
        assertEquals(MINI_WIDTH to MINI_HEIGHT, toggledMiniSize(WindowSpot(0f, 0f, 400f, 700f)))
    }

    @Test
    fun aPanelGrowsTheWindowOnlyWhenItHasNoRoom() {
        assertEquals(MINI_PANEL_WIDTH to MINI_PANEL_OPEN_HEIGHT, miniSizeForPanel(WindowSpot(0f, 0f, MINI_WIDTH, MINI_HEIGHT).copy(width = 300f)))
        assertEquals("a wider bar keeps its width", 500f to MINI_PANEL_OPEN_HEIGHT, miniSizeForPanel(WindowSpot(0f, 0f, 500f, MINI_HEIGHT)))
        assertEquals("tall enough already", 320f to 488f, miniSizeForPanel(WindowSpot(0f, 0f, 320f, 488f)))
    }

    @Test
    fun inTheBottomRightCornerItGrowsUpAndLeft() {
        val corner = placeMiniPlayer(null, screens)
        val grown = resizeMiniPlayer(corner, MINI_SQUARE_WIDTH, MINI_SQUARE_HEIGHT, screens)
        assertEquals("the right edge stays put", corner.x + corner.width, grown.x + grown.width)
        assertEquals("the bottom edge stays put", corner.y + corner.height, grown.y + grown.height)
        assertEquals(MINI_SQUARE_HEIGHT, grown.height)
        val back = resizeMiniPlayer(grown, MINI_WIDTH, MINI_HEIGHT, screens)
        assertEquals(corner, back)
    }

    @Test
    fun inTheTopLeftItGrowsDownAndRight() {
        val spot = WindowSpot(40f, 30f, MINI_WIDTH, MINI_HEIGHT)
        val grown = resizeMiniPlayer(spot, MINI_PANEL_WIDTH, MINI_PANEL_OPEN_HEIGHT, screens)
        assertEquals(40f, grown.x)
        assertEquals(30f, grown.y)
    }

    @Test
    fun itNeverGrowsOffItsScreen() {
        // On the second screen, near its top right, growing taller than the room below.
        val spot = WindowSpot(3200f - MINI_WIDTH - 10f, 700f, MINI_WIDTH, MINI_HEIGHT)
        val grown = resizeMiniPlayer(spot, MINI_SQUARE_WIDTH, 900f, screens)
        assertTrue(grown.x >= 1920f && grown.x + grown.width <= 3200f)
        assertTrue(grown.y >= 0f && grown.y + grown.height <= 1024f)
        // Taller than the screen: no taller than the screen.
        val huge = resizeMiniPlayer(WindowSpot(100f, 100f, 400f, 400f), 400f, 5000f, listOf(ScreenArea(0f, 0f, 1280f, 720f)))
        assertEquals(720f, huge.height)
        assertEquals(0f, huge.y)
    }

    @Test
    fun itReopensWhereItWasAtItsSize() {
        val spot = WindowSpot(2400f, 200f, MINI_PANEL_WIDTH, MINI_PANEL_OPEN_HEIGHT)
        assertEquals(spot, placeMiniPlayer(spot, screens))
    }

    @Test
    fun theSavedPanelReadsBack() {
        assertEquals(MiniPanel.Lyrics, "lyrics".toMiniPanel())
        assertEquals(MiniPanel.Queue, "Queue".toMiniPanel())
        assertEquals(null, (null as String?).toMiniPanel())
        assertEquals(null, "karaoke".toMiniPanel())
    }
}
