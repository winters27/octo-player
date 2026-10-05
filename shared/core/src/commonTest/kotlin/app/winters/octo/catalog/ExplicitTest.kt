package app.winters.octo.catalog

import app.winters.octo.subsonic.Song
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplicitTest {
    @Test
    fun onlyExplicitIsMarked() {
        assertTrue(isExplicit("explicit"))
        assertTrue(isExplicit(" Explicit "))
        assertFalse(isExplicit("clean"))
        assertFalse(isExplicit(""))
        assertFalse(isExplicit(null))
    }

    @Test
    fun aSongReadsItsOwnStatus() {
        assertTrue(Song(id = "1", title = "Dracula", explicitStatus = "explicit").isExplicit)
        assertFalse(Song(id = "2", title = "Dracula", explicitStatus = "clean").isExplicit)
        assertFalse(Song(id = "3", title = "Dracula").isExplicit)
    }
}
