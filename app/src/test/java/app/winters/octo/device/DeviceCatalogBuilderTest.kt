package app.winters.octo.device

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceCatalogBuilderTest {
    private fun row(
        id: Long,
        albumId: Long = 1,
        title: String? = "Song $id",
        artist: String? = "Artist",
        albumArtist: String? = null,
        album: String? = "Album",
        track: Int? = null,
        disc: Int? = null,
        year: Int? = null,
    ) = DeviceRow(
        id = id,
        uri = "content://media/external/audio/media/$id",
        title = title,
        artist = artist,
        albumArtist = albumArtist,
        album = album,
        albumId = albumId,
        track = track,
        disc = disc,
        year = year,
        durationMs = 180_000,
        addedAtSeconds = 1_700_000_000 + id,
        mimeType = "audio/flac",
        sizeBytes = 30_000_000,
    )

    @Test
    fun groupsTracksIntoAlbumsById() {
        val catalog = buildDeviceCatalog(listOf(row(1, albumId = 1), row(2, albumId = 1), row(3, albumId = 2)))
        assertEquals(2, catalog.albums.size)
        assertEquals(listOf(2, 1), catalog.albums.sortedBy { it.nativeId }.map { it.songCount })
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
        assertEquals(1, catalog.tracks.map { it.artistId }.distinct().size)
    }

    @Test
    fun withoutAlbumArtistMostCommonArtistWins() {
        val catalog = buildDeviceCatalog(listOf(row(1, artist = "A"), row(2, artist = "A"), row(3, artist = "B")))
        assertEquals("A", catalog.albums.single().artist)
    }

    @Test
    fun oldStyleDiscNumbersAreUnpacked() {
        val track = buildDeviceCatalog(listOf(row(1, track = 2003))).tracks.single()
        assertEquals(2, track.discNo)
        assertEquals(3, track.trackNo)
    }

    @Test
    fun blankTagsGetReadableNames() {
        val catalog = buildDeviceCatalog(listOf(row(1, title = null, artist = null, album = " ")))
        assertEquals("Untitled", catalog.tracks.single().title)
        assertEquals("Unknown album", catalog.albums.single().title)
        assertEquals("Unknown artist", catalog.artists.single().name)
    }

    @Test
    fun albumYearIsTheLatestTrackYear() {
        val catalog = buildDeviceCatalog(listOf(row(1, year = 2001), row(2, year = 2003), row(3, year = 0)))
        assertEquals(2003, catalog.albums.single().year)
    }

    @Test
    fun idsAreSourcePrefixed() {
        val catalog = buildDeviceCatalog(listOf(row(42, albumId = 7)))
        assertEquals("device:42", catalog.tracks.single().id)
        assertEquals("device:album:7", catalog.albums.single().id)
    }

    @Test
    fun albumSearchKeyHoldsTitleAndArtist() {
        val catalog = buildDeviceCatalog(listOf(row(1, album = "Nightcall", albumArtist = "Kavinsky")))
        assertEquals("nightcall kavinsky", catalog.albums.single().searchKey)
    }
}
