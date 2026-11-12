package app.winters.octo.discovery

import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.discovery.today.libraryAlbumVote
import app.winters.octo.listening.timed
import app.winters.octo.testing.counted
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.ui.album.albumTrack
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// A library album's album on the server is the one most of its songs'
// copies from that server belong to, as today's vote over every copy found
// it (a labeled pin, discovery/today), but counted a page of songs at a
// time: no statement reads more than a page of 500 rows, whatever the
// album's size. A tie goes to the smallest album id.
@RunWith(RobolectricTestRunner::class)
class ServerAlbumOfTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    private val home = "server:home"
    private val other = "server:other"

    // Song `song` of album "al" copied from `source`'s album `album`.
    private fun CatalogDatabase.copy(source: String, album: String, song: Int) {
        openHelper.writableDatabase.execSQL(
            "INSERT INTO source_track (id, sourceId, nativeId, title, searchKey, sortKey, artist, artistId, album, albumId, durationMs, addedAt, " +
                "albumOrder, relinkKey, genre, mergedId) VALUES (?, ?, ?, 'Song', 'song', 'song', 'Band', '', 'Big', ?, 0, 0, 0, '', '', ?)",
            arrayOf<Any?>("$source:$album:$song", source, "$album-$song", "$source:$album", "t%05d".format(song)),
        )
    }

    private fun CatalogDatabase.album(source: String, album: String) {
        openHelper.writableDatabase.execSQL(
            "INSERT INTO source_album (id, sourceId, nativeId, title, searchKey, sortKey, artist, artistId, songCount, durationMs, addedAt) " +
                "VALUES (?, ?, ?, 'Big', 'big', 'big', 'Band', '', 0, 0, 0)",
            arrayOf<Any?>("$source:$album", source, "native-$album"),
        )
    }

    // Today's vote, over every copy of the album's songs read whole.
    private suspend fun today(source: String): String? {
        val ids = db.dao().albumTrackIds("al")
        return libraryAlbumVote(ids.chunked(900).flatMap { db.sourceDao().copiesOf(it) }, source)?.let { db.sourceDao().albumNativeId(it) }
    }

    @Test
    fun theAlbumMostSongsCameFromIsCountedAPageAtATime() = timed(60) {
        db.dao().insertTracks((0 until 3_000).map { albumTrack(it, 1) })
        listOf("a", "b").forEach { db.album(home, it) }
        db.album(other, "c")
        db.openHelper.writableDatabase.beginTransaction()
        try {
            for (i in 0 until 3_000) {
                // 1,800 songs from album a, 1,200 from b; 400 of a's also
                // copied from b; every song from another server's album c.
                db.copy(home, if (i < 1_800) "a" else "b", i)
                if (i < 400) db.copy(home, "b", i)
                db.copy(other, "c", i)
            }
            db.openHelper.writableDatabase.setTransactionSuccessful()
        } finally {
            db.openHelper.writableDatabase.endTransaction()
        }
        db.counted.reset()
        db.counted.recording = true
        val found = serverAlbumOf(db, "al", home)
        db.counted.recording = false
        assertEquals("native-a", found)
        assertEquals(today(home), found)
        assertEquals("native-c", serverAlbumOf(db, "al", other))
        assertEquals(today(other), serverAlbumOf(db, "al", other))
        val reads = db.counted.own
        assertTrue("the vote read the album", reads.isNotEmpty())
        val most = reads.maxBy { it.rowsRead }
        assertTrue("no statement reads past a page: $most", most.rowsRead <= 500)
    }

    @Test
    fun aTieGoesToTheSmallestAlbumIdAndNoCopyFindsNone() = timed(30) {
        db.dao().insertTracks((0 until 20).map { albumTrack(it, 1) })
        listOf("y", "x").forEach { db.album(home, it) }
        for (i in 0 until 20) db.copy(home, if (i % 2 == 0) "y" else "x", i)
        assertEquals("native-x", serverAlbumOf(db, "al", home))
        assertNull(serverAlbumOf(db, "al", other))
        assertNull(today(other))
    }
}
