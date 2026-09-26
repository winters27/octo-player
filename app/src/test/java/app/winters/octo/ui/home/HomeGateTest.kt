package app.winters.octo.ui.home

import app.winters.octo.device.Access
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeGateTest {
    @Test
    fun serverOnlyListenerWithAccessDeniedGetsShelvesAndTheQuietCard() {
        assertEquals(HomeLayout(askAccess = true, shelves = true, empty = false), homeLayout(Access.Denied, false, 5_000))
    }

    @Test
    fun notNowPutsTheCardAwayButKeepsTheShelves() {
        assertEquals(HomeLayout(askAccess = false, shelves = true, empty = false), homeLayout(Access.DeniedForever, true, 5_000))
    }

    @Test
    fun accessAllowedNeverShowsTheCard() {
        assertEquals(HomeLayout(askAccess = false, shelves = true, empty = false), homeLayout(Access.Granted, false, 12))
        assertEquals(HomeLayout(askAccess = false, shelves = false, empty = true), homeLayout(Access.Granted, false, 0))
    }

    @Test
    fun anEmptyLibraryShowsTheNoteAndTheCardWhileAccessIsMissing() {
        assertEquals(HomeLayout(askAccess = true, shelves = false, empty = true), homeLayout(Access.NotAsked, false, 0))
        assertEquals(HomeLayout(askAccess = false, shelves = false, empty = true), homeLayout(Access.NotAsked, true, 0))
    }

    @Test
    fun nothingShowsForValuesNotReadYet() {
        assertEquals(HomeLayout(askAccess = false, shelves = false, empty = false), homeLayout(Access.NotAsked, null, null))
        // The count known but not the choice: shelves, no card until it is read.
        assertEquals(HomeLayout(askAccess = false, shelves = true, empty = false), homeLayout(Access.Denied, null, 3))
    }
}
