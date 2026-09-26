package app.winters.octo.server

import app.winters.octo.catalog.SourceAlbumEntity
import app.winters.octo.catalog.SourceArtistEntity
import app.winters.octo.catalog.SourceTrackEntity
import app.winters.octo.catalog.relinkKey
import app.winters.octo.subsonic.Album
import app.winters.octo.subsonic.Artist
import app.winters.octo.subsonic.Library
import app.winters.octo.subsonic.Song
import app.winters.octo.subsonic.SongReplayGain
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerCatalogTest {
    private val source = "server:10.0.0.5:4533"

    private val album = Album(
        id = "al1",
        name = "Nightcall",
        artist = "Kavinsky",
        artistId = "ar1",
        coverArt = "al-al1_6aafffab",
        songCount = 1,
        duration = 179,
        year = 2024,
        created = "2026-09-20T15:51:15.203420216Z",
    )

    private val song = Song(
        id = "s1",
        title = "Nightcall",
        album = "Nightcall",
        albumId = "al1",
        artist = "Kavinsky",
        artistId = "ar1",
        displayArtist = "Kavinsky feat. Angèle",
        track = 1,
        discNumber = 1,
        year = 2024,
        duration = 179,
        coverArt = "mf-s1_6aafffab",
        suffix = "flac",
        contentType = "audio/x-flac",
        bitRate = 1032,
        samplingRate = 44_100,
        bitDepth = 16,
        size = 24_740_366,
        starred = "2026-09-23T08:00:00Z",
        displayAlbumArtist = "Kavinsky",
        genre = "Electro",
        created = "2026-09-20T15:51:15.236955739Z",
        played = "2026-09-22T13:36:41.43536229Z",
        playCount = 4,
    )

    private fun song(id: String, disc: Int?, track: Int?) =
        Song(id = id, title = "Song $id", albumId = "al1", discNumber = disc, track = track, duration = 200)

    @Test
    fun aSongMapsToItsRow() {
        val catalog = buildServerCatalog(source, Library(listOf(song), listOf(album), emptyList()))
        val expected = SourceTrackEntity(
            id = "$source:s1",
            sourceId = source,
            nativeId = "s1",
            title = "Nightcall",
            searchKey = "nightcall",
            sortKey = "nightcall",
            artist = "Kavinsky feat. Angèle",
            artistId = "$source:ar1",
            album = "Nightcall",
            albumId = "$source:al1",
            trackNo = 1,
            discNo = 1,
            year = 2024,
            durationMs = 179_000,
            addedAt = 1_789_919_475,
            mimeType = "audio/flac",
            sizeBytes = 24_740_366,
            artwork = "server:$source|al-al1_6aafffab",
            uri = null,
            albumOrder = 0,
            relinkKey = relinkKey("Kavinsky", "Nightcall", 1, 1, "Nightcall", 179_000),
            genre = "Electro",
            bitrate = 1_032_000,
            sampleRate = 44_100,
            bitDepth = 16,
            playCount = 4,
            lastPlayedAt = 1_790_084_201_435,
            starredAt = 1_790_150_400_000,
        )
        assertEquals(listOf(expected), catalog.tracks)
    }

    @Test
    fun anAlbumMapsToItsRow() {
        val catalog = buildServerCatalog(source, Library(listOf(song), listOf(album), emptyList()))
        val expected = SourceAlbumEntity(
            id = "$source:al1",
            sourceId = source,
            nativeId = "al1",
            title = "Nightcall",
            searchKey = "nightcall kavinsky",
            sortKey = "nightcall",
            artist = "Kavinsky",
            artistId = "$source:ar1",
            year = 2024,
            songCount = 1,
            durationMs = 179_000,
            addedAt = 1_789_919_475,
            artwork = "server:$source|al-al1_6aafffab",
        )
        assertEquals(listOf(expected), catalog.albums)
    }

    @Test
    fun artistsAreAlbumArtistsNamedByTheArtistList() {
        val newer = album.copy(id = "al2", name = "OutRun", coverArt = "al-al2_1", created = "2026-09-21T10:00:00Z")
        val catalog = buildServerCatalog(
            source,
            Library(listOf(song), listOf(album, newer), listOf(Artist(id = "ar1", name = "KAVINSKY", coverArt = "ar-ar1_0"))),
        )
        val expected = SourceArtistEntity(
            id = "$source:ar1",
            sourceId = source,
            name = "KAVINSKY",
            searchKey = "kavinsky",
            sortKey = "kavinsky",
            albumCount = 2,
            songCount = 1,
            artwork = "server:$source|al-al2_1",
        )
        assertEquals(listOf(expected), catalog.artists)
    }

    @Test
    fun albumOrderFollowsDiscThenTrack() {
        val songs = listOf(song("c", 2, 1), song("b", 1, 2), song("a", 1, 1), song("d", 0, 0))
        val catalog = buildServerCatalog(source, Library(songs, listOf(album), emptyList()))
        val order = catalog.tracks.sortedBy { it.albumOrder }
        assertEquals(listOf("a", "b", "d", "c"), order.map { it.nativeId })
        // A 0 from the server means it does not know.
        assertNull(order[2].trackNo)
        assertNull(order[2].discNo)
    }

    @Test
    fun aSongWhoseAlbumWasNotListedStillGetsOne() {
        val late = song.copy(id = "s9", albumId = "al9", album = "Late Album", displayAlbumArtist = "Someone")
        val catalog = buildServerCatalog(source, Library(listOf(late), emptyList(), emptyList()))
        val made = catalog.albums.single()
        assertEquals("$source:al9", made.id)
        assertEquals("Late Album", made.title)
        assertEquals("Someone", made.artist)
        assertEquals(made.id, catalog.tracks.single().albumId)
        assertEquals(made.artistId, catalog.artists.single().id)
    }

    @Test
    fun aRepeatedSongIsKeptOnce() {
        val catalog = buildServerCatalog(source, Library(listOf(song, song), listOf(album), emptyList()))
        assertEquals(1, catalog.tracks.size)
    }

    @Test
    fun sourceIdNamesThePortOnlyWhenItIsNotTheUsualOne() {
        assertEquals("server:10.0.0.5:4533", serverSourceId("http://10.0.0.5:4533/".toHttpUrl()))
        assertEquals("server:music.example.com", serverSourceId("https://music.example.com/navidrome/".toHttpUrl()))
        assertEquals("server:music.example.com", serverSourceId("http://music.example.com:80/".toHttpUrl()))
        assertEquals("server:music.example.com:80", serverSourceId("https://music.example.com:80/".toHttpUrl()))
    }

    @Test
    fun datesWithAndWithoutAZone() {
        assertEquals(1_790_150_400_000, epochMs("2026-09-23T08:00:00Z"))
        assertEquals(1_790_150_400_000, epochMs("2026-09-23T10:00:00+02:00"))
        assertEquals(1_790_150_400_000, epochMs("2026-09-23T08:00:00"))
        assertNull(epochMs("yesterday"))
        assertNull(epochMs(null))
    }

    @Test
    fun keepsTheServersLoudnessAndRating() {
        val rated = song.copy(
            replayGain = SongReplayGain(trackGain = -8.4f, albumGain = -7.9f, trackPeak = 0.98f, albumPeak = 0f, baseGain = 0f, fallbackGain = -6f),
            userRating = 4,
        )
        val row = buildServerCatalog(source, Library(listOf(rated), listOf(album), emptyList())).tracks.single()
        assertEquals(-8.4f, row.trackGain)
        assertEquals(-7.9f, row.albumGain)
        assertEquals(0.98f, row.trackPeak)
        // A peak of nothing is no peak.
        assertNull(row.albumPeak)
        assertEquals(0f, row.baseGain)
        assertEquals(-6f, row.fallbackGain)
        assertEquals(4, row.rating)

        val plain = buildServerCatalog(source, Library(listOf(song), listOf(album), emptyList())).tracks.single()
        assertNull(plain.trackGain)
        assertNull(plain.rating)
    }
}
