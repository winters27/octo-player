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

    private fun row(key: String, id: String = key) = Pickable(key, song(id)).picked()

    @Test
    fun nothingPickedIsNotPicking() {
        assertFalse(Picks().active)
    }

    @Test
    fun startingPicksJustThatSong() {
        val picks = Picks(mapOf("a" to row("a"), "b" to row("b"))).start(row("c"))
        assertEquals(setOf("c"), picks.keys)
        assertTrue(picks.active)
    }

    @Test
    fun aTapTogglesAndTakingTheLastOffEndsPicking() {
        var picks = Picks().start(row("a"))
        picks = picks.toggle(row("b"))
        assertEquals(setOf("a", "b"), picks.keys)
        picks = picks.toggle(row("a"))
        assertEquals(setOf("b"), picks.keys)
        picks = picks.toggle(row("b"))
        assertFalse(picks.active)
    }

    @Test
    fun songsThatLeaveTheListLeaveThePicks() {
        val picks = Picks(listOf("a", "b", "c").associateWith { row(it) })
        assertEquals(setOf("a", "c"), picks.keepOnly(setOf("a", "c", "d")).keys)
        // Nothing gone keeps the same picks.
        assertTrue(picks.keepOnly(setOf("a", "b", "c", "d")) === picks)
        assertFalse(picks.keepOnly(emptySet()).active)
    }

    @Test
    fun pickedSongsComeInTheListsOrderNotThePickingOrder() {
        val list = listOf("a", "b", "c", "d").map { Pickable(it, song(it)) }
        val picks = Picks().start(row("d")).toggle(row("a")).toggle(row("c"))
        assertEquals(listOf("a", "c", "d"), inListOrder(list, picks.rows.values.toList()).map { it.key })
    }

    @Test
    fun aSongTwiceOnAPlaylistIsPickedByItsRow() {
        val same = song("t")
        val list = listOf(Pickable("1", same), Pickable("2", same))
        val picks = Picks().start(list[1].picked())
        assertEquals(listOf("2"), inListOrder(list, picks.rows.values.toList()).map { it.key })
        assertEquals(listOf("t"), picks.rows.values.map { it.trackId })
    }

    @Test
    fun theHolderFollowsThePicks() {
        val selection = SongSelection()
        selection.resolve = { key -> row(key) }
        assertFalse(selection.active)
        selection.start("a")
        selection.toggle("b")
        assertEquals(2, selection.count)
        assertTrue(selection.isPicked("b"))
        selection.clear()
        assertFalse(selection.active)
    }
}
