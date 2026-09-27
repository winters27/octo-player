package app.winters.octo.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// A find's row turning into the library song it became.
class FindSwapTest {
    // Runs a row through what it is told, in turn, from when it opens.
    private fun faces(vararg adopted: Boolean?, offersAdd: Boolean = true): List<FindFace?> {
        var face: FindFace? = null
        return adopted.map { known ->
            face = nextFindFace(face, known, offersAdd)
            face
        }
    }

    @Test
    fun aFindAddedInViewShowsTheCheckThenTheLibrarySong() {
        assertEquals(listOf(FindFace.Find, FindFace.Find, FindFace.Check, FindFace.Check), faces(false, false, true, true))
        // Once the check has had its moment, the row is the library song, and stays it.
        assertEquals(FindFace.Library, afterCheck(FindFace.Check))
        assertEquals(FindFace.Library, nextFindFace(FindFace.Library, true, offersAdd = true))
        // The fade that turns it, after the hold.
        assertEquals(SWAP_FADE_MS, swapFadeMs(sawFind = true, calm = false))
        assertEquals(1_500L, CHECK_HOLD_MS + SWAP_FADE_MS)
    }

    @Test
    fun aSongAlreadyInTheLibraryOpensAsTheLibrarySong() {
        assertEquals(listOf(FindFace.Library, FindFace.Library), faces(true, true))
        // No check, and nothing fades: the row never showed the find.
        assertEquals(0, swapFadeMs(sawFind = false, calm = false))
    }

    @Test
    fun nothingIsDecidedUntilTheLibraryIsKnown() {
        // Not known yet, then already in the library: no check for a song
        // that arrived before the row opened.
        assertEquals(listOf(null, FindFace.Library), faces(null, true))
        assertEquals(listOf(null, FindFace.Find), faces(null, false))
        assertNull(afterCheck(null))
    }

    @Test
    fun aRowWithoutTheAddButtonTurnsAtOnce() {
        assertEquals(listOf(FindFace.Find, FindFace.Library), faces(false, true, offersAdd = false))
    }

    @Test
    fun reducedMotionKeepsTheCheckAndSwapsWithoutAFade() {
        // The check still says it landed; the turn itself does not fade.
        assertEquals(listOf(FindFace.Find, FindFace.Check), faces(false, true))
        assertEquals(FindFace.Library, afterCheck(FindFace.Check))
        assertEquals(0, swapFadeMs(sawFind = true, calm = true))
    }

    @Test
    fun onlyTheCheckMovesOnWhenItsMomentEnds() {
        assertEquals(FindFace.Find, afterCheck(FindFace.Find))
        assertEquals(FindFace.Library, afterCheck(FindFace.Library))
    }
}
