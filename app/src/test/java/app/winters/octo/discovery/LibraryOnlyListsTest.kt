package app.winters.octo.discovery

import app.winters.octo.catalog.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// What "Library songs only" leaves of an artist's extras and of ranked lists.
class LibraryOnlyListsTest {
    private fun track(id: String) = TrackEntity(
        id = id, sourceId = "s", nativeId = id, title = id, searchKey = id, sortKey = id,
        artist = "", artistId = "", album = "", albumId = "", trackNo = null, discNo = null, year = null,
        durationMs = 0, addedAt = 0, mimeType = null, sizeBytes = null, artwork = null, uri = null,
    )

    @Test
    fun anArtistsExtrasKeepOnlyWhatTheLibraryHas() {
        val extras = ArtistExtras(
            topSongs = listOf(track("find:1"), track("2"), track("find:3")),
            about = "Bio",
            similar = listOf(SimilarArtist("In", null, "ar1", "s1"), SimilarArtist("Out", null, null, "s2")),
        )
        val shown = extras.inLibraryOnly()
        assertEquals(listOf("2"), shown.topSongs.map { it.id })
        assertEquals(listOf("In"), shown.similar.map { it.name })
        assertEquals("Bio", shown.about)
    }

    @Test
    fun aRankedListKeepsItsLibrarySongsAtTheirRanks() {
        val ranked = RankedTracks(null, "deezer", listOf(RankedTrack(1, track("find:1"), null), RankedTrack(2, track("2"), null)))
        assertEquals(listOf(2), ranked.inLibraryOnly()!!.songs.map { it.rank })
        assertNull(RankedTracks(null, "deezer", listOf(RankedTrack(1, track("find:1"), null))).inLibraryOnly())
    }
}
