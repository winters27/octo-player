package app.winters.octo.ui.search

import app.winters.octo.catalog.OnlineSongEntity
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.discovery.Discovered
import app.winters.octo.discovery.Resolved
import app.winters.octo.discovery.adoptionsOf
import app.winters.octo.discovery.asAdopted
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

// A song added from search is listed once: among the songs not in the
// library until the next search, then among the library's.
class SearchSectionsTest {
    private fun track(id: String, title: String) = TrackEntity(
        id = id, sourceId = "device", nativeId = id, title = title, searchKey = title.lowercase(), sortKey = title.lowercase(),
        artist = "Justice", artistId = "a", album = "Cross", albumId = "al", trackNo = null, discNo = null, year = null,
        durationMs = 200_000, addedAt = 0, mimeType = null, sizeBytes = null, artwork = null, uri = null,
    )

    private fun find(id: String, adoptedId: String = "") = OnlineSongEntity(
        id = id, sourceId = "server:x", nativeId = id.removePrefix("find:"), title = "Genesis", artist = "Justice",
        album = "", albumId = null, artistId = null, durationMs = 0, coverId = null, mimeType = "audio/mp4",
        bitrate = 128, seenAt = 1, requestedAt = 1, adoptedId = adoptedId,
    )

    // Every song listed on the page: the library's, then those not in it.
    private fun listed(results: SearchResults, online: Discovered): List<String> =
        results.songs.map { it.id } + online.without(results.listedFinds).songs.map { it.id }

    @Test
    fun aSongAddedWhileShownStaysAmongTheFindsUntilTheNextSearch() {
        val found = Discovered(listOf(track("find:e1", "Genesis"), track("find:e2", "Phantom")), emptyList(), emptyList())
        // Built before the download landed: the library had nothing.
        val before = SearchResults(emptyList(), emptyList(), emptyList(), listedFinds = listedFinds(emptyMap(), emptyList()))
        // It lands; nothing is built again, so the find keeps its place.
        assertEquals(listOf("find:e1", "find:e2"), listed(before, found))
    }

    @Test
    fun aRebuildListsTheAddedSongOnceAsTheLibrarySong() {
        val found = Discovered(listOf(track("find:e1", "Genesis"), track("find:e2", "Phantom")), emptyList(), emptyList())
        val adoptions = adoptionsOf(listOf(find("find:e1", adoptedId = "t-9"), find("find:e2")))
        assertEquals(mapOf("find:e1" to "t-9"), adoptions)
        // The library is searched again, say for another filter, while the
        // server's answer is kept: the library song is listed, the find is not.
        val songs = listOf(track("t-9", "Genesis"))
        val after = SearchResults(emptyList(), emptyList(), songs, listedFinds = listedFinds(adoptions, songs))
        assertEquals(listOf("t-9", "find:e2"), listed(after, found))
    }

    @Test
    fun aFindWhoseLibrarySongIsNotListedStays() {
        // Past the number of songs shown, say: the find is still the only listing.
        val adoptions = mapOf("find:e1" to "t-9")
        val songs = listOf(track("t-1", "Other"))
        assertTrue(listedFinds(adoptions, songs).isEmpty())
        val found = Discovered(listOf(track("find:e1", "Genesis")), emptyList(), emptyList())
        assertSame(found, found.without(listedFinds(adoptions, songs)))
    }

    @Test
    fun theNextSearchFromTheServerAnswersTheLibrarySong() {
        // Asked again, the server still sends the song it found; the app
        // knows it as the library song it became, so it is no find.
        val resolved = asAdopted(Resolved.Found(find("find:e1", adoptedId = "t-9")), setOf("t-9"))
        assertEquals(Resolved.InLibrary("t-9"), resolved)
        // A library song since removed: still the find.
        val gone = Resolved.Found(find("find:e1", adoptedId = "t-9"))
        assertSame(gone, asAdopted(gone, emptySet()))
        // Not added: untouched.
        val plain = Resolved.Found(find("find:e2"))
        assertSame(plain, asAdopted(plain, setOf("t-9")))
    }
}
