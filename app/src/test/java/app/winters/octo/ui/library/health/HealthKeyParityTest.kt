package app.winters.octo.ui.library.health

import app.winters.octo.catalog.SongIdentity
import app.winters.octo.catalog.read
import app.winters.octo.listening.timed
import app.winters.octo.testing.openTestCatalog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// The song keys a build buckets copies by are the ones the checks work out:
// each copy's stored match key, refilled at start, is SongIdentity's match
// key of its artist and title, a blank or spaced artist read as none.
@RunWith(RobolectricTestRunner::class)
class HealthKeyParityTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    @Test
    fun storedKeysAreTheChecksKeys() = timed(60) {
        db.healthLibrary(albums = 50, seed = 1)
        HealthRig(db).keys()
        val rows = db.read("SELECT id, artist, title, matchKey FROM source_track") { listOf(it.getString(0), it.getString(1), it.getString(2), it.getString(3)) }
        val wrong = rows.filter { (_, artist, title, key) -> SongIdentity.matchKey(artist.ifBlank { null }, title) != key }
        assertEquals(emptyList<List<String>>(), wrong)
    }
}
