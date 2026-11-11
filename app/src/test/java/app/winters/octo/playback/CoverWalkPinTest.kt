package app.winters.octo.playback

import app.winters.octo.catalog.PlaylistSets
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.catalog.exec
import app.winters.octo.catalog.longOf
import app.winters.octo.listening.timed
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList

// A cover walk pins the item set it counted, in the same transaction, and
// walks that set to its end: a new list of songs written and made current
// at its seventh page leaves the walk on the set it began with, and its pin
// goes when the walk ends, through the one way pins are let go. The next
// read takes the new set.
@RunWith(RobolectricTestRunner::class)
class CoverWalkPinTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    @Test
    fun aFlipMidWalkLeavesTheWalkOnItsSet() = timed(30) {
        // Set 0: 40 songs, covers only from the 36th on. Set 1: other songs,
        // each with a cover.
        db.dao().insertTracks((0 until 40).map { songRow("device:s$it", "al$it", if (it >= 35) "art:$it" else null) })
        db.dao().insertTracks((0 until 3).map { songRow("device:n$it", "new$it", "new:$it") })
        db.playlist("p", (0 until 40).map { "device:s$it" })
        val released = CopyOnWriteArrayList<Pair<String, String>>()
        val pins = CopyOnWriteArrayList<Long>()
        var pages = 0
        val sets = PlaylistSets(db)
        val summaries = PlaylistSummaries(
            db,
            WorkOwners(),
            MutableStateFlow(null),
            releasePin = { owner, id ->
                released += owner to id
                sets.releasePin(owner, id)
            },
            page = 5,
        )
        summaries.hooks = object : SummaryHooks() {
            override suspend fun betweenPages(playlistId: String) {
                pins += db.longOf("SELECT COUNT(*) FROM playlist_set_pin WHERE playlistId = 'p' AND itemSet = 0")
                if (++pages == 6) {
                    // A whole new list of songs, written as set 1 and made current.
                    db.putItems("p", 1, (0 until 3).map { "device:n$it" })
                    db.exec("UPDATE playlist SET itemSet = 1 WHERE id = 'p'")
                }
            }
        }
        val first = summaries.all.first().single()
        assertEquals("counted on the set it pinned", 40, first.songCount)
        assertEquals(listOf("art:35", "art:36", "art:37", "art:38"), first.covers)
        assertTrue("the set stayed pinned through the walk: $pins", pins.size >= 7 && pins.all { it == 1L })
        assertEquals(1, released.size)
        assertEquals("walk", WorkOwners.kindOf(released.single().first))
        assertEquals(0L, db.longOf("SELECT COUNT(*) FROM playlist_set_pin"))

        val next = summaries.all.first().single()
        assertEquals(3, next.songCount)
        assertEquals(listOf("new:0", "new:1", "new:2"), next.covers)
    }
}
