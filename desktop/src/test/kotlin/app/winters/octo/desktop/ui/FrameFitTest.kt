package app.winters.octo.desktop.ui

import androidx.compose.ui.unit.dp
import app.winters.octo.design.FrameSize
import app.winters.octo.design.PageSize
import app.winters.octo.desktop.pages.countText
import app.winters.octo.desktop.pages.headerArt
import app.winters.octo.desktop.library.totalLengthText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// How the frame shares the window at the sizes the desktop is drawn at:
// the smallest window, 1080p, 1440p and an ultrawide.
class FrameFitTest {
    private val sidebar = FrameSize.Sidebar
    private val panel = FrameSize.Panel

    @Test
    fun aSmallWindowWithThePanelOpenFoldsTheSidebarToItsRail() {
        val fit = frameFit(960.dp, sidebar, rail = false, panel = panel)
        assertTrue(fit.rail)
        assertEquals(FrameSize.SidebarRail, fit.sidebar)
        assertEquals(panel, fit.panel)
        assertTrue("page ${fit.page}", fit.page >= FrameSize.PageMin)
    }

    @Test
    fun aSmallWindowWithoutThePanelKeepsTheSidebar() {
        val fit = frameFit(960.dp, sidebar, rail = false, panel = null)
        assertFalse(fit.rail)
        assertEquals(sidebar, fit.sidebar)
        assertEquals(960.dp - sidebar - FrameSize.Hairline, fit.page)
    }

    @Test
    fun aRoomyWindowKeepsEverythingAsChosen() {
        for (width in listOf(1920.dp, 2560.dp, 3440.dp)) {
            val fit = frameFit(width, FrameSize.SidebarMax, rail = false, panel = FrameSize.PanelMax)
            assertFalse("$width", fit.rail)
            assertEquals(FrameSize.SidebarMax, fit.sidebar)
            assertEquals(FrameSize.PanelMax, fit.panel)
        }
    }

    @Test
    fun aWidePanelNarrowsWhenTheRailIsNotEnough() {
        val fit = frameFit(960.dp, sidebar, rail = false, panel = FrameSize.PanelMax)
        assertTrue(fit.rail)
        assertTrue("panel ${fit.panel}", fit.panel!! < FrameSize.PanelMax)
        assertTrue(fit.panel!! >= FrameSize.PanelMin)
        assertEquals(FrameSize.PageMin, fit.page)
    }

    @Test
    fun theListenersRailStaysARail() {
        assertTrue(frameFit(3440.dp, sidebar, rail = true, panel = null).rail)
        assertTrue(frameFit(3440.dp, sidebar, rail = true, panel = panel).rail)
    }

    @Test
    fun thePlayerIsCompactOnlyWhenThePageIsNarrow() {
        val small = frameFit(960.dp, sidebar, rail = false, panel = panel)
        val narrow = playerWidth(small.page, 960.dp)
        assertTrue("fits the page: $narrow", narrow <= small.page - FrameSize.PlayerGap * 2)
        assertTrue(playerIsCompact(narrow))
        val alone = frameFit(960.dp, sidebar, rail = false, panel = null)
        assertFalse(playerIsCompact(playerWidth(alone.page, 960.dp)))
        val hd = frameFit(1920.dp, sidebar, rail = false, panel = panel)
        assertFalse(playerIsCompact(playerWidth(hd.page, 1920.dp)))
    }

    @Test
    fun thePlayerIsAThirdOfAWideWindow() {
        val fit = frameFit(3440.dp, sidebar, rail = false, panel = null)
        assertEquals(3440.dp / 3, playerWidth(fit.page, 3440.dp))
        assertEquals(FrameSize.PlayerMin, playerWidth(fit.page, 1600.dp))
    }

    @Test
    fun aNarrowPageHasASmallerHeadingPicture() {
        assertEquals(PageSize.HeaderArtSmall, headerArt(560.dp))
        assertEquals(PageSize.HeaderArt, headerArt(1200.dp))
    }

    @Test
    fun countsMarkThousandsAndSayOne() {
        assertEquals("1 album", countText(1, "album"))
        assertEquals("1,677 albums", countText(1_677, "album"))
        assertEquals("2,500 songs", countText(2_500, "song"))
        assertEquals("12 s", totalLengthText(12))
        assertEquals("1 min", totalLengthText(40))
    }
}
