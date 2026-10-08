package app.winters.octo.desktop

import app.winters.octo.desktop.library.LibraryIndex
import app.winters.octo.radio.RadioDiscovery
import app.winters.octo.radio.RadioTuning
import app.winters.octo.subsonic.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RadioSongsTest {
    private val LibraryOnly = RadioTuning(discovery = RadioDiscovery.LibraryOnly)

    private val library = LibraryIndex(
        listOf(
            Song("s1", "One", albumId = "al1", artist = "A", genre = "Rock", year = 1995, duration = 200),
            Song("s2", "Two", albumId = "al1", artist = "A", genre = "Rock", year = 1995, duration = 200),
            Song("s3", "Three", albumId = "al2", artist = "B", genre = "Rock", year = 1996, duration = 200),
            Song("s4", "Intro", albumId = "al2", artist = "B", genre = "Rock", year = 1996, duration = 50),
            Song("s5", "Five", albumId = "al3", artist = "C", genre = "Jazz", year = 1995, duration = 200),
        ),
        emptyList(),
        emptyList(),
    )

    @Test
    fun aLibrarySongTheServerSuggested_KeepsTheServersSource() {
        val first = library.songs[0]
        val similar = listOf(Song("s3", "Three", artist = "B", genre = "Rock", octoSuggestedBy = "Sounds alike"))
        val picks = radioSongs(first, listOf(first), similar, library, emptySet(), { 0 }, random = Random(1))
        assertEquals("Sounds alike", picks.single { it.id == "s3" }.octoSuggestedBy)
        assertTrue(picks.filter { it.id != "s3" }.all { it.octoSuggestedBy == null })
    }

    @Test
    fun theLibraryAloneMakesARadio() {
        val first = library.songs.first { it.id == "s1" }
        val picks = radioSongs(first, listOf(first), emptyList(), library, emptySet(), { 0 }, random = Random(1)).map { it.id }
        assertEquals(setOf("s2", "s3"), picks.toSet())
        assertFalse("s4" in picks)
    }

    @Test
    fun anAlbumRadioLeavesTheAlbumOut() {
        val first = library.songs.first { it.id == "s1" }
        val album = library.songs.filter { it.albumId == "al1" }
        val picks = radioSongs(first, album, emptyList(), library, album.map { it.id }.toSet(), { 0 }, random = Random(1)).map { it.id }
        assertEquals(listOf("s3"), picks)
    }

    @Test
    fun songsTheServerFoundOnlineGetTheirShare() {
        val first = library.songs.first { it.id == "s1" }
        val found = (1..6).map { Song("f$it", "Found $it", artist = "Out $it", isExternal = true) }
        val picks = radioSongs(first, listOf(first), found, library, emptySet(), { 0 }, random = Random(1)).map { it.id }
        assertTrue(picks.toString(), picks.count { it.startsWith("f") } >= 2)
    }

    @Test
    fun onlyMyLibraryNeverAddsAFoundSong() {
        val first = library.songs.first { it.id == "s1" }
        val found = (1..6).map { Song("f$it", "Found $it", artist = "Out $it", isExternal = true) }
        val picks = radioSongs(first, listOf(first), found, library, emptySet(), { 0 }, LibraryOnly, random = Random(1)).map { it.id }
        assertTrue(picks.toString(), picks.isNotEmpty() && picks.none { it.startsWith("f") })
    }

    @Test
    fun onlyMyLibraryKeepsFoundSongsOutOfTheFallback() {
        // A seed with nothing alike in the library: the server's order
        // stands, less what it found outside the library.
        val first = Song("x", "X", artist = "Z")
        val similar = listOf(
            Song("y1", "Y1", artist = "Y", isExternal = true),
            library.songs.first { it.id == "s5" },
            Song("y2", "Y2", artist = "W", isExternal = true),
        )
        val picks = radioSongs(first, listOf(first), similar, library, emptySet(), { 0 }, LibraryOnly, random = Random(1)).map { it.id }
        assertEquals(listOf("s5"), picks)
        // Only songs from outside: nothing at all, rather than the server's order.
        val outside = radioSongs(first, listOf(first), similar.filter { it.isExternal }, library, emptySet(), { 0 }, LibraryOnly, random = Random(1))
        assertTrue(outside.toString(), outside.isEmpty())
    }

    @Test
    fun withNothingAlikeTheServersOrderStands() {
        val first = Song("x", "X", artist = "Z")
        val similar = listOf(Song("y1", "Y1", artist = "Y", isExternal = true), Song("y2", "Y2", artist = "W", isExternal = true))
        val picks = radioSongs(first, listOf(first), similar, null, emptySet(), { 0 }, random = Random(1)).map { it.id }
        assertEquals(setOf("y1", "y2"), picks.toSet())
    }

    @Test
    fun aBigLibraryIsQuick() {
        val songs = (0 until 50_000).map { i ->
            Song(
                "b$i", "Song $i", albumId = "al${i / 10}", artist = "Artist ${i % 5000}",
                genre = "Genre ${i % 40}", year = 1960 + i % 60, duration = 200,
            )
        }
        val index = LibraryIndex(songs, emptyList(), emptyList())
        val start = System.nanoTime()
        val picks = radioSongs(songs[0], listOf(songs[0]), emptyList(), index, emptySet(), { 0 }, random = Random(1))
        val ms = (System.nanoTime() - start) / 1_000_000
        println("radio over 50,000 songs: $ms ms")
        assertEquals(50, picks.size)
        assertTrue("took $ms ms", ms < 5_000)
    }
}
