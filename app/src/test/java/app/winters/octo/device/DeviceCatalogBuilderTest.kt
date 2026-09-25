package app.winters.octo.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCatalogBuilderTest {
    private fun row(
        id: Long,
        album: String? = "Album",
        title: String? = "Song $id",
        artist: String? = "Artist",
        albumArtist: String? = null,
        track: Int? = null,
        disc: Int? = null,
        year: Int? = null,
        fileName: String = "Song $id.flac",
        folder: String? = "Music/Album/",
        compilation: Boolean = false,
        mbAlbumId: String? = null,
    ) = DeviceRow(
        id = id,
        uri = "content://media/external/audio/media/$id",
        fileName = fileName,
        folder = folder,
        title = title,
        artist = artist,
        albumArtist = albumArtist,
        album = album,
        track = track,
        disc = disc,
        year = year,
        compilation = compilation,
        mbAlbumId = mbAlbumId,
        durationMs = 180_000,
        addedAtSeconds = 1_700_000_000 + id,
        mimeType = "audio/flac",
        sizeBytes = 30_000_000,
    )

    @Test
    fun sameAlbumInTwoFoldersIsOneAlbum() {
        val catalog = buildDeviceCatalog(
            listOf(
                row(1, album = "#1 Hits of Garth Brooks", folder = "Music/A/"),
                row(2, album = "#1 Hits of Garth Brooks", folder = "Music/B/"),
            ),
        )
        assertEquals(1, catalog.albums.size)
        assertEquals(2, catalog.albums.single().songCount)
    }

    @Test
    fun sameNameSplitsWhenEveryTrackNamesItsAlbumArtist() {
        val catalog = buildDeviceCatalog(
            listOf(
                row(1, album = "Greatest Hits", albumArtist = "Queen"),
                row(2, album = "Greatest Hits", albumArtist = "ABBA"),
            ),
        )
        assertEquals(2, catalog.albums.size)
    }

    @Test
    fun partlyTaggedSameNameStaysTogether() {
        val catalog = buildDeviceCatalog(
            listOf(row(1, album = "X", albumArtist = "Queen"), row(2, album = "X", albumArtist = null)),
        )
        assertEquals(1, catalog.albums.size)
        assertEquals("Queen", catalog.albums.single().artist)
    }

    @Test
    fun releaseIdsSplitOnlyWithFullCoverage() {
        val full = buildDeviceCatalog(listOf(row(1, mbAlbumId = "a"), row(2, mbAlbumId = "b")))
        assertEquals(2, full.albums.size)
        val partial = buildDeviceCatalog(listOf(row(1, mbAlbumId = "a"), row(2)))
        assertEquals(1, partial.albums.size)
    }

    @Test
    fun albumArtistKeepsFeaturedTracksTogether() {
        val catalog = buildDeviceCatalog(
            listOf(
                row(1, artist = "Kavinsky", albumArtist = "Kavinsky"),
                row(2, artist = "Kavinsky feat. Angèle", albumArtist = "Kavinsky"),
            ),
        )
        assertEquals(1, catalog.artists.size)
        assertEquals("Kavinsky", catalog.artists.single().name)
    }

    @Test
    fun withoutAlbumArtistMostCommonArtistWins() {
        val catalog = buildDeviceCatalog(listOf(row(1, artist = "A"), row(2, artist = "A"), row(3, artist = "B")))
        assertEquals("A", catalog.albums.single().artist)
    }

    @Test
    fun compilationWithoutAlbumArtistIsVariousArtists() {
        val catalog = buildDeviceCatalog(listOf(row(1, artist = "A", compilation = true), row(2, artist = "B")))
        assertEquals("Various Artists", catalog.albums.single().artist)
    }

    @Test
    fun tagTrackNumbersOrderTheAlbum() {
        val catalog = buildDeviceCatalog(listOf(row(1, title = "b", track = 2), row(2, title = "a", track = 1)))
        assertEquals(listOf("a", "b"), catalog.tracks.sortedBy { it.albumOrder }.map { it.title })
    }

    @Test
    fun fileNameNumbersOrderAnUntaggedAlbum() {
        val catalog = buildDeviceCatalog(
            listOf(row(1, title = "Zed", fileName = "02 - Zed.flac"), row(2, title = "Alpha", fileName = "01. Alpha.flac")),
        )
        val ordered = catalog.tracks.sortedBy { it.albumOrder }
        assertEquals(listOf("Alpha", "Zed"), ordered.map { it.title })
        assertEquals(listOf(1, 2), ordered.map { it.trackNo })
    }

    @Test
    fun artistNamesThatStartWithNumbersAreNotTrackNumbers() {
        val catalog = buildDeviceCatalog(
            listOf(
                row(1, title = "In Da Club", fileName = "50 Cent - In Da Club.flac"),
                row(2, title = "Candy Shop", fileName = "50 Cent - Candy Shop.flac"),
            ),
        )
        assertTrue(catalog.tracks.all { it.trackNo == null })
        assertEquals(listOf("Candy Shop", "In Da Club"), catalog.tracks.sortedBy { it.albumOrder }.map { it.title })
    }

    @Test
    fun missingTagsFallBackToFileAndFolderNames() {
        val catalog = buildDeviceCatalog(
            listOf(row(1, title = null, artist = null, album = null, fileName = "Intro.flac", folder = "Music/Live Tapes/")),
        )
        assertEquals("Intro", catalog.tracks.single().title)
        assertEquals("Live Tapes", catalog.albums.single().title)
        assertEquals("Unknown artist", catalog.artists.single().name)
    }

    @Test
    fun albumYearIsTheLatestTrackYear() {
        val catalog = buildDeviceCatalog(listOf(row(1, year = 2001), row(2, year = 2003), row(3, year = null)))
        assertEquals(2003, catalog.albums.single().year)
    }

    @Test
    fun idsAreStableAcrossScans() {
        val first = buildDeviceCatalog(listOf(row(42, album = "Nightcall", albumArtist = "Kavinsky")))
        val second = buildDeviceCatalog(listOf(row(42, album = "Nightcall", albumArtist = "Kavinsky")))
        assertEquals("device:42", first.tracks.single().id)
        assertEquals(first.albums.single().id, second.albums.single().id)
        assertTrue(first.albums.single().id.startsWith("device:album:"))
    }

    @Test
    fun albumSearchKeyHoldsTitleAndArtist() {
        val catalog = buildDeviceCatalog(listOf(row(1, album = "Nightcall", albumArtist = "Kavinsky")))
        assertEquals("nightcall kavinsky", catalog.albums.single().searchKey)
    }
}
