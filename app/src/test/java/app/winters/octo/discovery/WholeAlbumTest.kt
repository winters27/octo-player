package app.winters.octo.discovery

import app.winters.octo.catalog.TrackEntity
import app.winters.octo.subsonic.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// A library album the library has only some of, as Octo lists it whole:
// the songs it lacks join in their places as finds, and nothing else does.
class WholeAlbumTest {
    private fun track(id: String, title: String, trackNo: Int? = null, disc: Int? = null) = TrackEntity(
        id = id, sourceId = "server:x", nativeId = id, title = title, searchKey = title.lowercase(), sortKey = title.lowercase(),
        artist = "Radiohead", artistId = "a", album = "Amnesiac", albumId = "al", trackNo = trackNo, discNo = disc, year = null,
        durationMs = 200_000, addedAt = 0, mimeType = null, sizeBytes = null, artwork = null, uri = null,
    )

    private fun sent(id: String, track: Int, outside: Boolean, disc: Int = 1) =
        Song(id = id, title = "Song $id", artist = "Radiohead", track = track, discNumber = disc, isExternal = outside)

    // The library has songs 2 and 6.
    private val two = track("t2", "Pyramid Song", 2)
    private val six = track("t6", "Knives Out", 6)

    @Test
    fun songsTheServerMarksBecomeFindsNumberedAsTheAlbumNumbersThem() {
        val server = listOf(sent("s1", 1, outside = true), sent("s2", 2, outside = false), sent("s3", 3, outside = true, disc = 2))
        val resolved = listOf(track("find:s1", "Song s1"), two, track("find:s3", "Song s3"))
        val out = outsideAlbumSongs(server, resolved)
        assertEquals(listOf("find:s1", "t2", "find:s3"), out.map { it.id })
        assertEquals(1, out[0].trackNo)
        assertEquals(2, out[2].discNo)
    }

    @Test
    fun anUnmarkedSongTheLibraryLacksIsLeftForTheNextCopy() {
        // Added to the server since the library was copied: not a find.
        val out = outsideAlbumSongs(listOf(sent("s9", 9, outside = false)), listOf(track("find:s9", "Song s9")))
        assertEquals(emptyList<TrackEntity>(), out)
    }

    @Test
    fun theWholeAlbumKeepsTheServersOrder() {
        val server = listOf(track("find:s1", "One", 1), two, track("find:s3", "Three", 3), six)
        assertEquals(listOf("find:s1", "t2", "find:s3", "t6"), wholeAlbum(listOf(two, six), server).map { it.id })
    }

    @Test
    fun withNoFindsTheAlbumIsTheLibrarysOwn() {
        assertEquals(listOf(two, six), wholeAlbum(listOf(two, six), listOf(six, two)))
        assertEquals(listOf(two, six), wholeAlbum(listOf(two, six), emptyList()))
    }

    @Test
    fun aFindTheLibraryTookInShowsAsItsLibrarySongInItsPlace() {
        val arrived = track("t1", "One", 1)
        val server = listOf(track("find:s1", "One", 1), two, six)
        val out = wholeAlbum(listOf(arrived, two, six), server, mapOf("find:s1" to "t1"))
        assertEquals(listOf("t1", "t2", "t6"), out.map { it.id })
    }

    @Test
    fun aLibrarySongTheServerDidNotListFollows() {
        val phoneOnly = track("p7", "Bonus", 12)
        val server = listOf(track("find:s1", "One", 1), two)
        assertEquals(listOf("find:s1", "t2", "p7"), wholeAlbum(listOf(two, phoneOnly), server).map { it.id })
    }

    @Test
    fun aFindKeepsNoNumberTheServerDidNotGive() {
        val out = outsideAlbumSongs(listOf(Song(id = "s1", title = "x", isExternal = true)), listOf(track("find:s1", "x", trackNo = 5)))
        assertNull(out.single().trackNo)
    }
}
