package app.winters.octo.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// Back closes one page and never the tab's first: a second Back while the
// page is leaving used to empty the tab, and an empty tab ends the app.
class BackOneTest {
    @Test
    fun backClosesTheTopPage() {
        val tab = mutableListOf("Library", "Songs", "Album")
        tab.backOne()
        assertEquals(listOf("Library", "Songs"), tab)
    }

    @Test
    fun aSecondBackAtTheFirstPageKeepsIt() {
        val tab = mutableListOf("Search", "Found online")
        tab.backOne()
        tab.backOne()
        tab.backOne()
        assertEquals(listOf("Search"), tab)
    }

    // Every Back in the shell goes through backOne, so no way back can empty
    // a tab.
    @Test
    fun theShellNeverRemovesAPageItself() {
        val shell = File(System.getProperty("octo.mainSources"), "app/winters/octo/ui/nav/MainShell.kt").readText()
        assertTrue("MainShell pops a page without backOne", "removeLastOrNull" !in shell && "removeLast()" !in shell)
    }
}
