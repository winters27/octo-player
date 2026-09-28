package app.winters.octo.desktop.library

import app.winters.octo.desktop.nav.FolderStep
import app.winters.octo.subsonic.Album
import app.winters.octo.subsonic.Artist
import app.winters.octo.subsonic.ArtistRef
import app.winters.octo.subsonic.DiscTitle
import app.winters.octo.subsonic.Song
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// What the album, artist, genre and folder pages work out.
class EntityFactsTest {
    private fun flac(id: String, bits: Int = 24, rate: Int = 96_000) = Song(id, suffix = "flac", bitDepth = bits, samplingRate = rate)

    @Test
    fun oneFormatIsNamedInFull() {
        assertEquals("FLAC 24/96", formatSummary(listOf(flac("a"), flac("b"))))
    }

    @Test
    fun onlyTheQualityDifferingNamesTheFormat() {
        assertEquals("FLAC", formatSummary(listOf(flac("a"), flac("b", 16, 44_100))))
        val mp3s = listOf(Song("a", suffix = "mp3", bitRate = 245), Song("b", suffix = "mp3", bitRate = 320))
        assertEquals("MP3", formatSummary(mp3s))
    }

    @Test
    fun differentFormatsAreMixed() {
        assertEquals("Mixed formats", formatSummary(listOf(flac("a"), Song("b", suffix = "mp3", bitRate = 320))))
    }

    @Test
    fun noFormatsSayNothing() {
        assertNull(formatSummary(listOf(Song("a"), Song("b"))))
        assertNull(formatSummary(emptyList()))
    }

    @Test
    fun discsCarryTheAlbumsOwnNames() {
        val titles = listOf(DiscTitle(2, "Live at Wembley"), DiscTitle(3, " "))
        assertEquals("Disc 1", discHeading(1, titles))
        assertEquals("Disc 2 · Live at Wembley", discHeading(2, titles))
        assertEquals("Disc 3", discHeading(3, titles))
    }

    @Test
    fun aHeadingStartsEachDisc() {
        val songs = listOf(Song("a", discNumber = 1), Song("b", discNumber = 1), Song("c", discNumber = 2))
        assertEquals(listOf("Disc 1", null, "Disc 2 · Encore"), discHeadings(songs, listOf(DiscTitle(2, "Encore"))))
    }

    @Test
    fun oneUnnamedDiscHasNoHeadings() {
        val songs = listOf(Song("a", discNumber = 1), Song("b", discNumber = 1))
        assertEquals(listOf(null, null), discHeadings(songs, emptyList()))
        // Named, even one disc shows its name.
        assertEquals(listOf("Disc 1 · Demos", null), discHeadings(songs, listOf(DiscTitle(1, "Demos"))))
    }

    @Test
    fun appearsOnIsOtherPeoplesAlbumsWithTheirSongs() {
        val index = LibraryIndex(
            songs = listOf(
                Song("1", albumId = "own", artistId = "r1"),
                Song("2", albumId = "duet", artistId = "x", artists = listOf(ArtistRef("x"), ArtistRef("r1"))),
                Song("3", albumId = "mix", artistId = "r1"),
                Song("4", albumId = "other", artistId = "x"),
            ),
            albums = listOf(
                Album("own", artistId = "r1", year = 2000),
                Album("duet", artistId = "x", year = 2001),
                Album("mix", artistId = "va", year = 2010),
                Album("other", artistId = "x"),
            ),
            artists = emptyList(),
        )
        assertEquals(listOf("mix", "duet"), appearsOn(index, "r1", setOf("own")).map { it.id })
    }

    @Test
    fun anArtistsSongsGoAlbumByAlbumInDiscAndTrackOrder() {
        val songs = listOf(
            Song("b2", albumId = "b", track = 2),
            Song("a1", albumId = "a", track = 1),
            Song("b1", albumId = "b", track = 1),
            Song("a3", albumId = "a", discNumber = 2, track = 1),
            Song("a2", albumId = "a", track = 2),
            Song("elsewhere", albumId = "z"),
        )
        assertEquals(listOf("b1", "b2", "a1", "a2", "a3"), songsAlbumByAlbum(songs, listOf(Album("b"), Album("a"))).map { it.id })
    }

    @Test
    fun theMonogramIsTheFirstLetter() {
        assertEquals("R", monogramOf("radiohead"))
        assertEquals("?", monogramOf("!!!"))
        assertEquals("9", monogramOf("(9) Inch"))
        assertEquals("?", monogramOf(""))
    }

    @Test
    fun aMosaicNeedsFourDifferentCovers() {
        val four = listOf(Album("a", coverArt = "1", playCount = 1), Album("b", coverArt = "2", playCount = 9), Album("c", coverArt = "3"), Album("d", coverArt = "4"), Album("e", coverArt = "5"))
        // Most played first.
        assertEquals(listOf("2", "1", "3", "4"), mosaicCovers(four))
        val repeats = listOf(Album("a", coverArt = "1"), Album("b", coverArt = "1"), Album("c", coverArt = "2"), Album("d"))
        assertEquals(listOf("1"), mosaicCovers(repeats))
        assertEquals(emptyList<String>(), mosaicCovers(listOf(Album("a"))))
    }

    @Test
    fun aGenreGathersItsSongsAlbumsAndArtists() {
        val index = LibraryIndex(
            songs = listOf(
                Song("1", albumId = "k", artistId = "r1", artist = "Radiohead", genre = "Rock"),
                Song("2", albumId = "k", artistId = "r1", artist = "Radiohead", genre = "rock"),
                Song("3", albumId = "d", artistId = "p", artist = "Portishead", genres = listOf("Trip Hop", "Rock")),
                Song("4", albumId = "x", artistId = "p", artist = "Portishead", genre = "Jazz"),
            ),
            albums = listOf(Album("k", name = "Kid A", coverArt = "ck"), Album("d", name = "Dummy", coverArt = "cd"), Album("x", name = "X")),
            artists = listOf(Artist("r1", "Radiohead", coverArt = "ar")),
        )
        val rock = genreContents(index, "ROCK")
        assertEquals(listOf("1", "2", "3"), rock.songs.map { it.id })
        assertEquals(listOf("Dummy", "Kid A"), rock.albums.map { it.name })
        // The most songs first; an artist the library does not list is made from the songs.
        assertEquals(listOf("Radiohead" to "ar", "Portishead" to null), rock.artists.map { it.name to it.coverArt })
        assertEquals(mapOf("rock" to listOf("ck"), "trip hop" to listOf("cd"), "jazz" to emptyList()), genreCovers(index))
    }

    @Test
    fun theWayUpIsFoundFromTheTopDown() = runTest {
        val folders = mapOf("album" to ("OK Computer" to "artist"), "artist" to ("Radiohead" to "music"), "music" to ("Music" to null))
        assertEquals(
            listOf(FolderStep("music", "Music"), FolderStep("artist", "Radiohead"), FolderStep("album", "OK Computer")),
            climbFolders("album", { folders[it] }),
        )
    }

    @Test
    fun theClimbStopsWhereTheServerStops() = runTest {
        // A folder the server will not list ends the way up there.
        val folders = mapOf("album" to ("OK Computer" to "artist"))
        assertEquals(listOf(FolderStep("album", "OK Computer")), climbFolders("album", { folders[it] }))
        // A loop is walked once.
        val loop = mapOf("a" to ("A" to "b"), "b" to ("B" to "a"))
        assertEquals(listOf(FolderStep("b", "B"), FolderStep("a", "A")), climbFolders("a", { loop[it] }))
        // And never past the limit.
        assertEquals(3, climbFolders("0", { n -> "F$n" to "${n.toInt() + 1}" }, limit = 3).size)
        assertEquals(emptyList<FolderStep>(), climbFolders(null, { null }))
    }

    @Test
    fun aFoldersPathComesFromItsSongs() {
        assertEquals("Radiohead/Kid A", folderPath(listOf(Song("1", path = "Radiohead/Kid A/01 Everything.flac"), Song("2", path = "Radiohead/Kid A/02 Kid A.flac"))))
        assertEquals("Music/Air", folderPath(listOf(Song("1", path = "Music\\Air\\01.mp3"))))
        assertNull(folderPath(listOf(Song("1", path = "a/1.mp3"), Song("2", path = "b/2.mp3"))))
        assertNull(folderPath(listOf(Song("1"))))
    }

    @Test
    fun aFolderCountsSongsElseAlbums() {
        assertEquals("12 songs", folderCount(12, 3))
        assertEquals("1 album", folderCount(null, 1))
        assertNull(folderCount(0, null))
    }

    @Test
    fun cardsFillTheWidth() {
        assertEquals(4, gridColumns(700f, 168f))
        assertEquals(1, gridColumns(100f, 168f))
        assertEquals(1, gridColumns(700f, 0f))
    }
}
