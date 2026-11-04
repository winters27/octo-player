package app.winters.octo.subsonic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryJsonTest {
    private val song = Song(
        id = "s1", title = "Nightcall 🎵", album = "OutRun", albumId = "al1", artist = "Kavinsky", artistId = "ar1",
        displayArtist = "Kavinsky feat. Lovefoxxx", track = 3, discNumber = 2, year = 2013, duration = 258, coverArt = "mf-s1",
        suffix = "flac", contentType = "audio/flac", bitRate = 1032, samplingRate = 44_100, bitDepth = 24, size = 24_740_366,
        starred = "2026-09-23T08:00:00Z", displayAlbumArtist = "Kavinsky", genre = "Electro", created = "2026-09-20T15:51:15Z",
        played = "2026-09-22T13:36:41Z", playCount = 4,
        replayGain = SongReplayGain(trackGain = -7.5f, albumGain = -8.25f, trackPeak = 0.98f, albumPeak = 0.99f, baseGain = 0.5f, fallbackGain = -6f),
        userRating = 4, genres = listOf("Electro", "Synthwave"), artists = listOf(ArtistRef("ar1", "Kavinsky"), ArtistRef("ar9", "Lovefoxxx")),
        albumArtists = listOf(ArtistRef("ar1", "Kavinsky")), sortName = "Nightcall", musicBrainzId = "0a1b2c3d-0000-4000-8000-000000000001",
        bpm = 92, comment = "Drive", displayComposer = "Vincent Belorgey", explicitStatus = "clean", isrc = listOf("FR0W61000010"),
        parent = "dir-1", path = "Kavinsky/OutRun/03 Nightcall.flac", isExternal = true, octoSuggestedBy = "Last.fm", octoPersonal = true,
    )

    private val album = Album(
        id = "al1", name = "OutRun", artist = "Kavinsky", artistId = "ar1", displayArtist = "Kavinsky",
        artists = listOf(ArtistRef("ar1", "Kavinsky")), coverArt = "al-al1", songCount = 13, duration = 3000, year = 2013,
        genre = "Electro", created = "2026-09-20T15:51:15Z", played = "2026-09-22T13:36:41Z", playCount = 9,
        starred = "2026-09-23T08:00:00Z", isCompilation = true, genres = listOf("Electro"), musicBrainzId = "0a1b2c3d-0000-4000-8000-000000000002",
        sortName = "OutRun", discTitles = listOf(DiscTitle(1, "Side A")), originalReleaseDate = ItemDate(2013, 2, 22),
        releaseDate = ItemDate(2023, 2, 22, ), explicitStatus = "explicit", releaseTypes = listOf("Album", "Remaster"),
        isExternal = true, ownedCount = 7, recordLabels = listOf(RecordLabel("Record Makers")),
    )

    private val artist = Artist(
        id = "ar1", name = "Kavinsky", coverArt = "ar-ar1", albumCount = 3, starred = "2026-09-23T08:00:00Z",
        musicBrainzId = "0a1b2c3d-0000-4000-8000-000000000003", sortName = "Kavinsky",
    )

    @Test
    fun aSongComesBackExactlyWithEveryField() {
        val text = LibraryJson.encodeSong(song)
        assertEquals(song, LibraryJson.decodeSong(text))
        // Every field was set, so every field was written: a new field fails here until it is set above.
        assertEquals(Song.serializer().descriptor.elementsCount, Json.parseToJsonElement(text).jsonObject.size)
    }

    @Test
    fun anAlbumComesBackExactlyWithEveryField() {
        val text = LibraryJson.encodeAlbum(album)
        assertEquals(album, LibraryJson.decodeAlbum(text))
        assertEquals(Album.serializer().descriptor.elementsCount, Json.parseToJsonElement(text).jsonObject.size)
    }

    @Test
    fun anArtistComesBackExactlyWithEveryField() {
        val text = LibraryJson.encodeArtist(artist)
        assertEquals(artist, LibraryJson.decodeArtist(text))
        assertEquals(Artist.serializer().descriptor.elementsCount, Json.parseToJsonElement(text).jsonObject.size)
    }

    @Test
    fun unsetFieldsComeBackUnset() {
        val bare = Song(id = "s2")
        assertEquals(bare, LibraryJson.decodeSong(LibraryJson.encodeSong(bare)))
    }
}
