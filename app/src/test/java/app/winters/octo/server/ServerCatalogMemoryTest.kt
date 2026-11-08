package app.winters.octo.server

import app.winters.octo.subsonic.Album
import app.winters.octo.subsonic.Artist
import app.winters.octo.subsonic.ArtistRef
import app.winters.octo.subsonic.Library
import app.winters.octo.subsonic.Song
import app.winters.octo.subsonic.slimmed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

// The full library copy ran a phone out of memory on a library of 110,000
// songs (an Android app gets 256 MB, with the rest of the app resident): the
// songs and every row made from them were held at once. These tests keep
// that from coming back, and keep the lighter way of building the rows
// producing the same rows.
class ServerCatalogMemoryTest {
    private val source = "server:10.0.0.5:4533"

    // A library with the awkward things a server sends: a song listed twice,
    // one outside the library, one whose album the album list lacks, extras
    // the catalog does not keep, and an album artist credit.
    private fun library(): Library {
        val albums = listOf(
            Album(id = "a1", name = "One", artist = "Ann", artistId = "r1", coverArt = "al-a1", songCount = 2, duration = 300, year = 2020),
            Album(id = "a2", name = "Two", artist = "Bob", artistId = "r2", coverArt = "al-a2", songCount = 1, duration = 100, year = 2021),
        )
        fun song(id: String, albumId: String?, album: String, track: Int, extra: Song.() -> Song = { this }) = Song(
            id = id, title = "Song $id", album = album, albumId = albumId, artist = "Ann", artistId = "r1",
            track = track, discNumber = 1, duration = 100, coverArt = "mf-$id", suffix = "flac", genre = "Rock",
            created = "2026-09-20T15:51:15Z", path = "/music/$album/$id.flac", parent = "p-$album", sortName = "song $id",
            artists = listOf(ArtistRef("r1", "Ann")), albumArtists = listOf(ArtistRef("r1", "Ann")),
            musicBrainzId = "0e1d2c3b-4a59-4687-9786-00000000000$track",
        ).extra()
        val songs = listOf(
            song("s2", "a1", "One", 2),
            song("s1", "a1", "One", 1),
            song("s1", "a1", "One", 1),
            song("s3", "a2", "Two", 1),
            song("s4", "a3", "Three", 1),
            song("s5", "a1", "One", 3) { copy(isExternal = true) },
        )
        return Library(songs, albums, listOf(Artist(id = "r1", name = "Ann")))
    }

    @Test
    fun lettingGoOfTheSongsAsTheyAreReadMakesTheSameRows() {
        val kept = buildServerCatalog(source, library())
        val consumed = buildServerCatalog(source, library(), consume = true)
        assertEquals(kept.tracks, consumed.tracks)
        assertEquals(kept.albums, consumed.albums)
        assertEquals(kept.artists, consumed.artists)
        // Three real songs on the album list, one song with no listed album, none repeated, none outside.
        assertEquals(listOf("s1", "s2", "s3", "s4").sorted(), kept.tracks.map { it.nativeId }.sorted())
    }

    @Test
    fun theLibraryHoldsNoSongsOnceTheyAreConsumed() {
        val library = library()
        buildServerCatalog(source, library, consume = true)
        assertTrue(library.songs.isEmpty())
    }

    @Test
    fun streamedSongsAreTheSameRowsAndAreCountedBeforeTheyAreRead() {
        val kept = buildServerCatalog(source, library())
        val streamed = streamServerCatalog(source, library(), consume = true)
        assertEquals(kept.tracks.size, streamed.trackCount)
        assertEquals(kept.albums, streamed.albums)
        assertEquals(kept.artists, streamed.artists)
        assertEquals(kept.tracks, streamed.tracks.toList())
    }

    @Test
    fun cuttingSongsDownAsTheyArriveChangesNoRow() {
        val whole = buildServerCatalog(source, library())
        val pool = HashMap<String, String>()
        val cut = library().let { Library(it.songs.map { s -> s.slimmed(pool) }, it.albums, it.artists) }
        val slim = buildServerCatalog(source, cut)
        assertEquals(whole.tracks, slim.tracks)
        assertEquals(whole.albums, slim.albums)
        assertEquals(whole.artists, slim.artists)
    }

    // The real check: 110,000 songs, read the way a sync reads them (a page
    // cut down as it arrives), turned into rows and written 500 at a time,
    // in a JVM with only 128 MB. The same work held whole, as it was, needs
    // more than 192 MB. A child JVM, so the limit holds whatever flags the
    // test run has.
    @Test
    fun aLibraryOfAHundredAndTenThousandSongsSyncsInALittleMemory() {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val child = ProcessBuilder(
            java, "-Xmx128m", "-cp", System.getProperty("java.class.path"), ScaleProbe::class.java.name,
        ).redirectErrorStream(true).start()
        val output = child.inputStream.bufferedReader().readText()
        assertTrue("sync probe timed out", child.waitFor(120, TimeUnit.SECONDS))
        assertEquals("sync probe died in 128 MB:\n$output", 0, child.exitValue())
        assertTrue(output, output.contains("WRITTEN 110000"))
    }
}

// Run in a small JVM by the test above.
object ScaleProbe {
    private fun fresh(text: String) = String(text.toCharArray())

    @JvmStatic
    fun main(args: Array<String>) {
        val catalog = streamServerCatalog("server:scale:4533", pagesRead(), consume = true)
        var written = 0
        catalog.tracks.chunked(500).forEach { written += it.size }
        println("WRITTEN $written")
    }

    // A function of its own, so nothing here is still held once it returns.
    private fun pagesRead(): Library {
        val count = 110_000
        val perAlbum = 11
        val pool = HashMap<String, String>()
        val albums = ArrayList<Album>()
        val songs = ArrayList<Song>(count)
        for (n in 0 until count) {
            val albumNo = n / perAlbum
            if (n % perAlbum == 0) {
                albums += Album(
                    id = fresh("al-$albumNo"), name = fresh("Album number $albumNo of the scale test"),
                    artist = fresh("Artist ${albumNo / 8}"), artistId = fresh("ar-${albumNo / 8}"),
                    coverArt = fresh("al-${albumNo}_6aafffab"), songCount = perAlbum, duration = 2400,
                    year = 2000 + albumNo % 25, created = fresh("2026-09-20T15:51:15.203420216Z"),
                )
            }
            // As the pages of a sync leave them: each song cut down as it arrives.
            songs += Song(
                id = fresh("s$n"), title = fresh("Song $n with a title of ordinary length"),
                album = fresh("Album number $albumNo of the scale test"), albumId = fresh("al-$albumNo"),
                artist = fresh("Artist ${albumNo / 8}"), artistId = fresh("ar-${albumNo / 8}"),
                displayArtist = fresh("Artist ${albumNo / 8} feat. Somebody Else"),
                track = n % perAlbum + 1, discNumber = 1, year = 2000 + albumNo % 25, duration = 200,
                coverArt = fresh("mf-s${n}_6aafffab"), suffix = "flac", contentType = "audio/x-flac",
                bitRate = 1000, samplingRate = 44_100, bitDepth = 16, size = 24_000_000L,
                genre = fresh("Electronic"), created = fresh("2026-09-20T15:51:15.236955739Z"),
                path = fresh("/music/Artist ${albumNo / 8}/Album number $albumNo/${n % perAlbum + 1} - Song $n.flac"),
                parent = fresh("al-$albumNo"), sortName = fresh("Song $n with a title of ordinary length"),
                musicBrainzId = fresh("0e1d2c3b-4a59-4687-9786-${n.toString().padStart(12, '0')}"),
            ).slimmed(pool)
        }
        return Library(songs, albums, listOf(Artist(id = "ar-0", name = "Artist 0")))
    }
}
