package app.winters.octo.offline

import app.winters.octo.catalog.exec
import app.winters.octo.catalog.song
import app.winters.octo.listening.timed
import app.winters.octo.testing.openTestCatalog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// A relink writes only the downloads whose song changed: one whose server
// song is still the same library song, and one whose server song the last
// sync no longer has, are left as they are, so a walk with nothing to
// follow writes nothing and moves no counter. Today's relink rewrote every
// download each time.
@RunWith(RobolectricTestRunner::class)
class RelinkWritesOnlyMovedRowsTest {
    private val db = openTestCatalog()
    private val book = DownloadBook(db.downloadDao())

    @After
    fun close() = db.close()

    @Test
    fun aRelinkWritesOnlyTheRowsThatMoved() = timed {
        db.sourceDao().replaceSource(
            "server:h",
            listOf(song("server:h", "same", "Same", "al"), song("server:h", "moved", "Moved", "al")),
            emptyList(),
            emptyList(),
        )
        db.exec("UPDATE source_track SET mergedId = 'device:same' WHERE id = 'server:h:same'")
        book.upsert(
            listOf(
                downloadRow("device:same", serverId = "same"),
                downloadRow("device:moved", serverId = "moved"),
                downloadRow("device:gone", serverId = "gone"),
            ),
        )
        val dao = db.downloadDao()

        // Nothing changed for the first; the server no longer has the third.
        db.exec("UPDATE source_track SET mergedId = 'device:moved' WHERE id = 'server:h:moved'")
        val done = book.doneVersion.value
        book.relink()
        assertEquals("the marks were told of a relink that moved nothing", done, book.doneVersion.value)
        assertEquals(0, dao.relink())

        db.exec("UPDATE source_track SET mergedId = 'device:moved2' WHERE id = 'server:h:moved'")
        assertEquals(1, dao.relink())
        assertEquals("device:moved2", dao.get("server:h", "moved")!!.trackId)
        assertEquals("device:same", dao.get("server:h", "same")!!.trackId)
        assertEquals("device:gone", dao.get("server:h", "gone")!!.trackId)
        assertEquals(0, dao.relink())
    }
}
