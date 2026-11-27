package app.winters.octo.discovery

import app.winters.octo.catalog.ReleaseGroup
import app.winters.octo.subsonic.Album
import app.winters.octo.subsonic.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtistExtrasTest {
    @Test
    fun theServersIdComesFromItsOwnArtistRows() {
        val source = "server:music.example"
        assertEquals("ar1", serverArtistIdOf("server:music.example:ar1", source))
        // The library named this artist itself; the server gave no id.
        assertNull(serverArtistIdOf("server:music.example:artist:kavinsky", source))
        assertNull(serverArtistIdOf("device:artist:kavinsky", source))
        assertNull(serverArtistIdOf("server:other.example:ar1", source))
        assertNull(serverArtistIdOf("server:music.example:", source))
    }

    @Test
    fun topSongsByNameKeepOnlyTheArtistsOwn() {
        val songs = listOf(
            Song(id = "1", title = "Nightcall", artist = "Kavinsky"),
            Song(id = "2", title = "Odd Look", artist = "Kavinsky feat. The Weeknd"),
            Song(id = "3", title = "Something", artist = "Kavinsky Tribute Band"),
            Song(id = "4", title = "Nothing", artist = null),
        )
        assertEquals(listOf("1", "2"), ownSongs(songs, "Kavinsky").map { it.id })
    }

    @Test
    fun outsideReleasesAreTheOnesTheServerMarksOnShelvesByKind() {
        val albums = listOf(
            Album(id = "lib", name = "Owned", isExternal = false, releaseTypes = listOf("album")),
            Album(id = "x1", name = "Brat", isExternal = true, releaseTypes = listOf("album"), songCount = 15, ownedCount = 2),
            Album(id = "x2", name = "Boys", isExternal = true, releaseTypes = listOf("single")),
            Album(id = "x3", name = "Vroom Vroom", isExternal = true, releaseTypes = listOf("ep")),
        )
        val shelves = outsideAlbumShelves(albums, "server:music.example")
        assertEquals(listOf(ReleaseGroup.Albums, ReleaseGroup.SinglesAndEps), shelves.map { it.first })
        assertEquals(listOf("x1"), shelves[0].second.map { it.id })
        assertEquals(2, shelves[0].second.single().ownedCount)
        assertEquals(listOf("x2", "x3"), shelves[1].second.map { it.id })
    }

    @Test
    fun aServerThatMarksNothingOutsideHasNoOutsideShelves() {
        val albums = listOf(Album(id = "a", name = "One"), Album(id = "b", name = "Two"))
        assertEquals(emptyList<Any>(), outsideAlbumShelves(albums, "server:music.example"))
    }

    @Test
    fun libraryOnlyLeavesOutTheOutsideReleases() {
        val extras = ArtistExtras(outside = listOf(ReleaseGroup.Albums to listOf(OnlineAlbum("x1", "Brat", "Charli xcx", null, 2024, 15, null))))
        assertEquals(emptyList<Any>(), extras.inLibraryOnly().outside)
    }
}
