package app.winters.octo.ui.common

import app.winters.octo.catalog.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionTest {
    private fun song(id: String) = TrackEntity(
        id = id, sourceId = "device", nativeId = id, title = id, searchKey = id, sortKey = id,
        artist = "", artistId = "", album = "", albumId = "", trackNo = null, discNo = null, year = null,
        durationMs = 0, addedAt = 0, mimeType = null, sizeBytes = null, artwork = null, uri = null,
    )

    @Test
    fun nothingPickedIsNotPicking() {
        assertFalse(Picks().active)
    }

    @Test
    fun startingPicksJustThatSong() {
        val picks = Picks(setOf("a", "b")).start("c")
        assertEquals(setOf("c"), picks.keys)
        assertTrue(picks.active)
    }

    @Test
    fun aTapTogglesAndTakingTheLastOffEndsPicking() {
        var picks = Picks().start("a")
        picks = picks.toggle("b")
        assertEquals(setOf("a", "b"), picks.keys)
        picks = picks.toggle("a")
        assertEquals(setOf("b"), picks.keys)
        picks = picks.toggle("b")
        assertFalse(picks.active)
    }

    @Test
    fun songsThatLeaveTheListLeaveThePicks() {
        val picks = Picks(setOf("a", "b", "c"))
        assertEquals(setOf("a", "c"), picks.keepOnly(setOf("a", "c", "d")).keys)
        // Nothing gone keeps the same picks.
        assertTrue(picks.keepOnly(setOf("a", "b", "c", "d")) === picks)
        assertFalse(picks.keepOnly(emptySet()).active)
    }

    @Test
    fun pickedSongsComeInTheListsOrderNotThePickingOrder() {
        val list = listOf("a", "b", "c", "d").map { Pickable(it, song(it)) }
        val picks = Picks().start("d").toggle("a").toggle("c")
        assertEquals(listOf("a", "c", "d"), picks.inOrder(list).map { it.key })
    }

    @Test
    fun aSongTwiceOnAPlaylistIsPickedByItsRow() {
        val same = song("t")
        val list = listOf(Pickable("1", same), Pickable("2", same))
        val picks = Picks().start("2")
        assertEquals(listOf("2"), picks.inOrder(list).map { it.key })
    }

    @Test
    fun theHolderFollowsThePicks() {
        val selection = SongSelection()
        assertFalse(selection.active)
        selection.start("a")
        selection.toggle("b")
        assertEquals(2, selection.count)
        assertTrue(selection.isPicked("b"))
        selection.clear()
        assertFalse(selection.active)
    }
}
