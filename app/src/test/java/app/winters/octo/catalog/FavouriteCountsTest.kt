package app.winters.octo.catalog

import androidx.sqlite.db.SimpleSQLiteQuery
import app.winters.octo.listening.copy
import app.winters.octo.listening.exec
import app.winters.octo.listening.song
import app.winters.octo.listening.sourceAlbum
import app.winters.octo.listening.sourceArtist
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// The copy counts read a page of albums at a time and give the rows the
// one-statement form gave.
@RunWith(RobolectricTestRunner::class)
class FavouriteCountsTest {
    private lateinit var db: CatalogDatabase
    private val source = "server:h"

    // A server on the same host with a port: its ids also start "server:h:".
    private val other = "server:h:8080"

    @Before
    fun open() {
        db = openTestCatalog()
    }

    @After
    fun close() = db.close()

    private val oldAlbumCounts = """
        SELECT sa.id AS serverRowId, t.albumId AS libraryId, COUNT(*) AS songs
        FROM source_album sa JOIN source_track st ON st.albumId = sa.id JOIN track t ON t.id = st.mergedId
        WHERE sa.sourceId = ?
        GROUP BY sa.id, t.albumId
    """

    private val oldArtistCounts = """
        SELECT sr.id AS serverRowId, t.artistId AS libraryId, COUNT(*) AS songs
        FROM source_artist sr JOIN source_track st ON st.artistId = sr.id JOIN track t ON t.id = st.mergedId
        WHERE sr.sourceId = ?
        GROUP BY sr.id, t.artistId
        UNION ALL
        SELECT sr.id, sr.id, 0 FROM source_artist sr JOIN artist r ON r.id = sr.id
        WHERE sr.sourceId = ?
    """

    private fun old(sql: String, vararg args: String): Set<CopyCount> =
        db.query(SimpleSQLiteQuery(sql, args)).use { c ->
            buildSet { while (c.moveToNext()) add(CopyCount(c.getString(0), c.getString(1), c.getInt(2))) }
        }

    private fun fill() = db.runInTransaction {
        var n = 0
        fun songIn(album: String, artist: String, library: String, libraryArtist: String, merged: Boolean = true) {
            val id = "t${n++}"
            if (merged) db.song(id, albumId = library, artistId = libraryArtist)
            db.copy(album.substringBeforeLast(':'), "n$n", if (merged) id else "", albumId = album, artistId = artist)
        }
        db.sourceArtist("$source:r1", source)
        db.sourceArtist("$source:r2", source)
        db.exec("INSERT INTO artist (id, sourceId, name, searchKey, sortKey, albumCount, songCount) VALUES ('$source:r2', '$source', 'Kept', 'kept', 'kept', 0, 0)")
        // 250 one-song albums, past one page.
        for (a in 0 until 250) {
            db.sourceAlbum("$source:a$a", source, "$source:r1")
            songIn("$source:a$a", "$source:r1", "lib-a$a", "lib-r1")
        }
        // An album split over three library albums, unevenly.
        db.sourceAlbum("$source:split", source, "$source:r1")
        repeat(3) { songIn("$source:split", "$source:r1", "lib-x", "lib-r1") }
        repeat(2) { songIn("$source:split", "$source:r1", "lib-y", "lib-r9") }
        songIn("$source:split", "$source:r1", "lib-z", "lib-r1")
        // An album none of whose songs is a library song.
        db.sourceAlbum("$source:none", source, "$source:r1")
        songIn("$source:none", "$source:r1", "", "", merged = false)
        // The other server's album and artist.
        db.sourceArtist("$other:r1", other)
        db.sourceAlbum("$other:b1", other, "$other:r1")
        repeat(2) { songIn("$other:b1", "$other:r1", "lib-b1", "lib-o1") }
    }

    @Test
    fun albumCountsMatchTheOneStatementForm() = runBlocking {
        fill()
        val paged = db.favouritesDao().serverAlbumCounts(source)
        assertEquals(old(oldAlbumCounts, source), paged.toSet())
        assertEquals(paged.size, paged.toSet().size)
    }

    @Test
    fun artistCountsMatchTheOneStatementForm() = runBlocking {
        fill()
        val paged = db.favouritesDao().serverArtistCounts(source)
        assertEquals(old(oldArtistCounts, source, source), paged.toSet())
    }
}
