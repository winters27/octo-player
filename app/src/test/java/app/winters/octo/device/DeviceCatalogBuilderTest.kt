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
        genres: List<String> = emptyList(),
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
        genres = genres,
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

    @Test
    fun songsTakeTheirFirstGenre() {
        val catalog = buildDeviceCatalog(listOf(row(1, genres = listOf("Synthwave", "Electronic")), row(2)))
        assertEquals(listOf("Synthwave", ""), catalog.tracks.sortedBy { it.id }.map { it.genre })
    }

    @Test
    fun genreSpellingsMergeToTheMostCommon() {
        val catalog = buildDeviceCatalog(
            listOf(
                row(1, genres = listOf("hip-hop")),
                row(2, genres = listOf("Hip-Hop")),
                row(3, genres = listOf("Hip-Hop")),
                row(4, genres = listOf("Pop", "HIP-HOP")),
            ),
        )
        assertEquals(listOf("Hip-Hop", "Hip-Hop", "Hip-Hop", "Pop"), catalog.tracks.sortedBy { it.id }.map { it.genre })
    }

    @Test
    fun everyDetailOfTheFileReachesItsRow() {
        val tags = FileTags(
            originalYear = 1977, artists = listOf("A", "B"), composer = "C", bpm = 120, comment = "note", explicit = true,
            discTitle = "Side B", mbRecordingId = "rec", mbReleaseGroupId = "grp", mbArtistIds = listOf("x", "y"),
            trackGain = -6f, albumGain = -7f, trackPeak = 0.9f, albumPeak = 1f,
        )
        val track = buildDeviceCatalog(listOf(row(1, year = 2017, mbAlbumId = "rel", genres = listOf("Rock", "Pop")).copy(tags = tags)))
            .tracks.single()
        // The row keeps this edition's year; the library shows the original.
        assertEquals(2017, track.year)
        assertEquals(1977, track.originalYear)
        assertEquals("Rock\nPop", track.genres)
        assertEquals("A\nB", track.artists)
        assertEquals("C", track.composer)
        assertEquals(120, track.bpm)
        assertEquals("note", track.comment)
        assertEquals(true, track.explicit)
        assertEquals("Side B", track.discTitle)
        assertEquals("rec", track.mbRecordingId)
        assertEquals("rel", track.mbAlbumId)
        assertEquals("grp", track.mbReleaseGroupId)
        assertEquals("x\ny", track.mbArtistIds)
        assertEquals(-6f, track.trackGain)
        assertEquals(1f, track.albumPeak)
    }

    @Test
    fun anAlbumShowsItsOriginalYear() {
        val original = buildDeviceCatalog(listOf(row(1, year = 2017).copy(tags = FileTags(originalYear = 1977))))
        assertEquals(1977, original.albums.single().year)
    }

    @Test
    fun sortTagsFileTitlesAlbumsAndArtists() {
        val catalog = buildDeviceCatalog(
            listOf(
                row(1, title = "The Song", album = "The Album", albumArtist = "The Band")
                    .copy(tags = FileTags(sortTitle = "Song, The", sortAlbum = "Album, The", sortAlbumArtist = "Band, The")),
            ),
        )
        assertEquals("song, the", catalog.tracks.single().sortKey)
        assertEquals("album, the", catalog.albums.single().sortKey)
        assertEquals("band, the", catalog.artists.single().sortKey)
        // Without sort tags the names file as before.
        val plain = buildDeviceCatalog(listOf(row(1, title = "The Song", album = "The Album", albumArtist = "The Band")))
        assertEquals("song", plain.tracks.single().sortKey)
        assertEquals("band", plain.artists.single().sortKey)
    }
}
