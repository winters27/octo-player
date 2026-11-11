package app.winters.octo.catalog

import app.winters.octo.device.DEVICE
import app.winters.octo.testing.counted
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// What the merge writes while it places songs (CatalogMerger).
@RunWith(RobolectricTestRunner::class)
class MergerWritesTest {
    private fun copy(source: String, native: String, title: String, artist: String, artists: String) = SourceTrackEntity(
        id = "$source:$native", sourceId = source, nativeId = native, title = title, searchKey = title.lowercase(),
        sortKey = title.lowercase(), artist = artist, artistId = "$source:artist", album = "Views", albumId = "$source:album",
        trackNo = null, discNo = null, year = null, durationMs = 200_000, addedAt = 0, mimeType = null, sizeBytes = null,
        artwork = null, uri = null, albumOrder = 0, relinkKey = "", genre = "", artists = artists,
    )

    // Every song the merge makes files under its title's lookup keys, read
    // with its artist; a copy whose credit names more artists gives its
    // song that artist, and the keys follow it. The library's songs carry
    // the merge's keys. The server's copy is titled without the dash, so it
    // joins the phone's song whatever the credit, while the phone's title
    // read with the fuller credit loses the name in front.
    @Test
    fun everyMergedSongFilesUnderItsTitlesLookupKeys() = runBlocking {
        val title = "Drake - Too Good"
        val credit = "Drake & Rihanna"
        assertTrue(titleLookup(title, "Rihanna").lookupKey != titleLookup(title, credit).lookupKey)
        assertEquals("the two copies are one song", SongIdentity.songKeys("Rihanna", title).title, SongIdentity.songKeys(credit, "Drake Too Good").title)
        val db = openTestCatalog()
        try {
            val album = { source: String -> SourceAlbumEntity("$source:album", source, "album", "Views", "views", "views", "Drake", "$source:artist", null, 2, 0, 0, null) }
            val artist = { source: String -> SourceArtistEntity("$source:artist", source, "Drake", "drake", "drake", 1, 2, null) }
            db.put(
                SourceCatalog(
                    DEVICE, true,
                    listOf(copy(DEVICE, "1", title, "Rihanna", ""), copy(DEVICE, "2", "Song (Live) [2011 Remaster]", "Drake", "")),
                    listOf(album(DEVICE)), listOf(artist(DEVICE)),
                ),
            )
            db.put(
                SourceCatalog(
                    "server:x", false,
                    listOf(copy("server:x", "1", "Drake Too Good", credit, "Drake\nRihanna"), copy("server:x", "3", "", "Drake", "")),
                    listOf(album("server:x")), listOf(artist("server:x")),
                ),
            )
            db.testMerge("server:x").rebuild()
            val songs = db.library().tracks.associateBy { it.id }
            assertEquals(setOf("$DEVICE:1", "$DEVICE:2", "server:x:3"), songs.keys)
            assertEquals("the fuller credit's artist", credit, songs.getValue("$DEVICE:1").artist)
            assertEquals(title, songs.getValue("$DEVICE:1").title)
            for (song in songs.values) {
                val keys = titleLookup(song.title, song.artist)
                assertEquals(song.id, keys.lookupKey, song.lookupKey)
                assertEquals(song.id, keys.looseKey, song.looseKey)
                val written = SongIdentity.titleLookupKeys(song.title, song.artist)
                assertEquals(song.id, written, if (written.isEmpty()) emptySet() else setOf(song.lookupKey, song.looseKey))
            }
            assertEquals("a title with no key has none", "", songs.getValue("server:x:3").lookupKey)
        } finally {
            db.close()
        }
    }

    // From the third source on, every song placed is open to the source
    // again, a page of songs a transaction: no reopening write runs outside
    // a transaction or reopens more than a page, every song is reopened, and
    // the third source's copies still join the songs they match.
    @Test
    fun aThirdSourceReopensTheSongsAPageATransaction() = runBlocking {
        val songs = MERGE_PAGE * 2 + 37
        val db = openTestCatalog()
        try {
            for (source in listOf(DEVICE, "other", "server:x")) db.put(library(source, songs))
            val (_, ran) = db.counted.record { runBlocking { db.testMerge("server:x").rebuild() } }
            val reopens = ran.filter { "SET lastSourceRank = 0" in it.sql }
            assertTrue("reopened in ${reopens.size} writes", reopens.size >= 3)
            assertTrue("a reopening write outside a transaction", reopens.all { it.inTransaction })
            assertTrue("a reopening write over a page: ${reopens.map { it.rowsChanged }}", reopens.all { it.rowsChanged <= MERGE_PAGE })
            assertEquals(songs, reopens.sumOf { it.rowsChanged })
            val merged = db.library()
            assertEquals(songs, merged.tracks.size)
            val third = merged.mergedIds.filterKeys { it.startsWith("server:x:") }
            assertEquals(songs, third.size)
            assertTrue("a third source's copy did not join its song", third.values.all { it.startsWith("$DEVICE:") })
        } finally {
            db.close()
        }
    }
}
