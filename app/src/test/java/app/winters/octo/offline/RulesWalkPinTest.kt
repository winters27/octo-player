package app.winters.octo.offline

import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.catalog.exec
import app.winters.octo.catalog.longOf
import app.winters.octo.listening.timed
import app.winters.octo.testing.openTestCatalog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// The rules walk keeps one item set of a kept playlist: a new list of
// songs, written and made current at the walk's seventh page of that
// playlist, leaves the walk on the set it pinned at its opening, whole;
// the pin holds that set through the walk and goes, through the one way
// pins are let go, when the walk ends. The next walk, which the flip's own
// change asks for, takes the new set.
@RunWith(RobolectricTestRunner::class)
class RulesWalkPinTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    @Test
    fun theRulesWalkStaysOnTheSetItPinned() = timed(30) {
        val p = PinnedPlaylist(db).apply { put() }
        val rig = RulesRig(db, page = 10)
        val pins = ArrayList<Long>()
        var pages = 0
        rig.rules.hooks = object : RulesHooks() {
            override suspend fun read(stream: RulesStream) {
                if (stream != RulesStream.Playlist) return
                pins += p.oldSetPins()
                if (++pages == 7) p.flip()
            }
        }
        rig.rules.walk(keepLiked = false, playlists = setOf("p"), refused = null)
        assertEquals(p.old.associateWith { setOf(Reasons.playlist("p")) }, db.heldNow())
        assertTrue("the set stayed pinned through the walk: $pins", pins.size >= 10 && pins.all { it == 1L })
        assertEquals(1, rig.released.size)
        assertEquals("walk", WorkOwners.kindOf(rig.released.single().first))
        assertEquals(0L, db.longOf("SELECT COUNT(*) FROM playlist_set_pin"))

        rig.rules.hooks = RulesHooks()
        rig.rules.walk(keepLiked = false, playlists = setOf("p"), refused = null)
        assertEquals(p.new.associateWith { setOf(Reasons.playlist("p")) }, db.heldNow())
        assertEquals(p.old.toSet(), rig.dropped.map { it.trackId }.toSet())
    }
}

// A playlist whose item set 0 holds 100 server songs, and 5 more songs for
// the new list a flip makes current.
internal class PinnedPlaylist(private val db: CatalogDatabase) {
    val old = (0 until 100).map { "$IN_USE:a%03d".format(it) }
    val new = (0 until 5).map { "$IN_USE:b$it" }

    suspend fun put() {
        db.putServerSongs(old + new)
        db.putPlaylist("p", old)
    }

    // A new list of songs, written as item set 1 and made current.
    suspend fun flip() {
        db.putSet("p", 1, new)
        db.exec("UPDATE playlist SET itemSet = 1 WHERE id = 'p'")
    }

    fun oldSetPins() = db.longOf("SELECT COUNT(*) FROM playlist_set_pin WHERE playlistId = 'p' AND itemSet = 0")
}
