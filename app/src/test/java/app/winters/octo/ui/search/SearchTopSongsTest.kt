package app.winters.octo.ui.search

import app.winters.octo.catalog.ArtistEntity
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.discovery.OnlineArtist
import app.winters.octo.discovery.rankTracks
import app.winters.octo.subsonic.Song
import app.winters.octo.subsonic.TopSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Search's top songs: which artist a search names, and each ranked song
// kept with its place.
class SearchTopSongsTest {
    private fun artist(id: String, name: String) = ArtistEntity(id, "server:x", name, name.lowercase(), name.lowercase(), 2, 20, null)

    private fun track(id: String, title: String) = TrackEntity(
        id = id, sourceId = "server:x", nativeId = id, title = title, searchKey = title.lowercase(), sortKey = title.lowercase(),
        artist = "Daft Punk", artistId = "a", album = "Discovery", albumId = "al", trackNo = null, discNo = null, year = null,
        durationMs = 200_000, addedAt = 0, mimeType = null, sizeBytes = null, artwork = null, uri = null,
    )

    private fun top(rank: Int, id: String, plays: Long? = null) = TopSong(rank = rank, plays = plays, song = Song(id = id))

    @Test
    fun theLibrarysArtistComesFirst_ThenTheServersFind() {
        val library = listOf(artist("server:x:ar1", "Daft Punk Tribute"), artist("server:x:ar2", "Daft Punk"))
        val online = listOf(OnlineArtist("ext7", "Daft Punk", 40, null), OnlineArtist("ext8", "Justice", 9, null))

        assertEquals(NamedArtist("Daft Punk", libraryId = "server:x:ar2"), namedArtist("daft punk", library, online))
        assertEquals(NamedArtist("Justice", serverId = "ext8"), namedArtist("justice", library, online))
        assertNull(namedArtist("daft", library, online))
    }

    @Test
    fun aRankStaysWithItsSong() {
        val entries = listOf(top(1, "s1", 9_000_000), top(2, "gone"), top(3, "s3", 6_000_000), top(4, "find:s1"), top(0, "s5"))
        val tracks = listOf(track("lib1", "Get Lucky"), null, track("find:s3", "Aerodynamic"), track("lib1", "Get Lucky"), track("lib5", "Veridis Quo"))

        val ranked = rankTracks(entries, tracks)

        // A library song gone is left out; a find that became a song listed
        // above it stands once, at the better place; no rank takes its place.
        assertEquals(listOf("lib1", "find:s3", "lib5"), ranked.map { it.track.id })
        assertEquals(listOf(1, 3, 5), ranked.map { it.rank })
        assertEquals(listOf(9_000_000L, 6_000_000L, null), ranked.map { it.plays })
    }
}
