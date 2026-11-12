package app.winters.octo.offline

import app.winters.octo.catalog.longOf
import app.winters.octo.listening.timed
import app.winters.octo.testing.counted
import app.winters.octo.testing.openTestCatalog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// The rules walk holds a page at any size: with 20k liked songs and Keep
// liked on, no statement's cursor, counted at the database, yields more
// than a page, through the walk that downloads them all and the walk after
// it, which finds nothing to change and writes nothing. Today's walk read
// every download and every liked song at once.
@RunWith(RobolectricTestRunner::class)
class RulesWalkResidencyTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    @Test
    fun theWalkHoldsAPageAtAnySize() = timed(120) {
        val ids = (0 until 20_000).map { "$IN_USE:%05d".format(it) }
        db.putServerSongs(ids)
        db.likeAll(ids)
        val rig = RulesRig(db)

        db.counted.reset()
        rig.rules.walk(keepLiked = true, playlists = emptySet(), refused = null)
        val first = db.counted.maxRead!!
        assertTrue("$first", first.rowsRead <= RULES_PAGE)
        assertEquals(20_000L, db.longOf("SELECT COUNT(*) FROM download WHERE reason = 'liked' AND state = 'Queued'"))

        db.counted.reset()
        rig.rules.walk(keepLiked = true, playlists = emptySet(), refused = null)
        val second = db.counted.maxRead!!
        assertTrue("$second", second.rowsRead <= RULES_PAGE)
        assertEquals("the walk after wrote", null, db.counted.maxChanged?.takeIf { it.rowsChanged > 0 })
    }
}
