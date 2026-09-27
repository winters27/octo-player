package app.winters.octo.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Which copy each field of a merged song or album comes from.
class MergeRulesTest {
    private fun copy(
        id: String,
        source: String,
        added: Long = 0,
        year: Int? = null,
        originalYear: Int? = null,
        artist: String = "Daft Punk",
        artists: List<String> = emptyList(),
        genre: String = "",
        genres: List<String> = emptyList(),
        composer: String? = null,
        recording: String? = null,
    ) = SourceTrackEntity(
        id = id, sourceId = source, nativeId = id, title = "Get Lucky", searchKey = "get lucky", sortKey = "get lucky",
        artist = artist, artistId = "$source:artist", album = "RAM", albumId = "$source:album", trackNo = null, discNo = null,
        year = year, durationMs = 369_000, addedAt = added, mimeType = null, sizeBytes = null, artwork = null, uri = null,
        albumOrder = 0, relinkKey = "", genre = genre, originalYear = originalYear, genres = joinLines(genres),
        artists = joinLines(artists), composer = composer, mbRecordingId = recording,
    )

    private fun album(source: String, added: Long) =
        SourceAlbumEntity("$source:album", source, "a", "RAM", "ram", "ram", "Daft Punk", "$source:artist", null, 1, 369_000, added, null)

    private fun artist(source: String) = SourceArtistEntity("$source:artist", source, "Daft Punk", "daft punk", "daft punk", 1, 1, null)

    private fun merge(phone: SourceTrackEntity, server: SourceTrackEntity, phoneAlbumAdded: Long = 0, serverAlbumAdded: Long = 0) =
        mergeCatalogs(
            listOf(
                SourceCatalog("device", onPhone = true, listOf(phone), listOf(album("device", phoneAlbumAdded)), listOf(artist("device"))),
                SourceCatalog("server:x", onPhone = false, listOf(server), listOf(album("server:x", serverAlbumAdded)), listOf(artist("server:x"))),
            ),
        )

    @Test
    fun theEarliestRealAddedTimeWins() {
        // The server stamped its first scan; the phone file is from 2020.
        val merged = merge(copy("p", "device", added = 1_583_485_817), copy("s", "server:x", added = 1_786_000_000))
        assertEquals(1_583_485_817L, merged.tracks.single().addedAt)
        // An unknown time never wins.
        val unknown = merge(copy("p", "device", added = 0), copy("s", "server:x", added = 1_786_000_000))
        assertEquals(1_786_000_000L, unknown.tracks.single().addedAt)
    }

    @Test
    fun albumsTakeTheEarliestAddedTimeToo() {
        val merged = merge(copy("p", "device"), copy("s", "server:x"), phoneAlbumAdded = 1_786_000_000, serverAlbumAdded = 1_500_000_000)
        assertEquals(1_500_000_000L, merged.albums.single().addedAt)
        assertEquals(1_500_000_000L, merge(copy("p", "device"), copy("s", "server:x"), 0, 1_500_000_000).albums.single().addedAt)
    }

    @Test
    fun theOriginalYearFromAnyCopyIsShown() {
        val merged = merge(copy("p", "device", year = 2013), copy("s", "server:x", year = 2013, originalYear = 1979))
        assertEquals(1979, merged.tracks.single().year)
    }

    @Test
    fun aYearFromAnyCopyFillsAGap() {
        assertEquals(2013, merge(copy("p", "device"), copy("s", "server:x", year = 2013)).tracks.single().year)
        assertNull(merge(copy("p", "device"), copy("s", "server:x")).tracks.single().year)
    }

    @Test
    fun theFullestCreditWins() {
        val merged = merge(
            copy("p", "device", artist = "Daft Punk", artists = listOf("Daft Punk")),
            copy("s", "server:x", artist = "Daft Punk feat. Pharrell Williams", artists = listOf("Daft Punk", "Pharrell Williams")),
        )
        assertEquals("Daft Punk feat. Pharrell Williams", merged.tracks.single().artist)
        // Equal credits keep the phone's.
        val equal = merge(
            copy("p", "device", artist = "Daft Punk", artists = listOf("Daft Punk")),
            copy("s", "server:x", artist = "DAFT PUNK", artists = listOf("DAFT PUNK")),
        )
        assertEquals("Daft Punk", equal.tracks.single().artist)
    }

    @Test
    fun theFirstCopysGenreIsTheMainOneAndAnotherFillsAGap() {
        assertEquals("Disco", merge(copy("p", "device", genre = "Disco"), copy("s", "server:x", genre = "Funk")).tracks.single().genre)
        assertEquals("Funk", merge(copy("p", "device"), copy("s", "server:x", genre = "Funk")).tracks.single().genre)
    }

    @Test
    fun detailsComeFromEveryCopyPhoneFirst() {
        val details = songDetails(
            listOf(
                copy("s", "server:x", year = 2013, originalYear = 1979, genre = "Funk", genres = listOf("Funk", "Disco", "Hip-Hop"), composer = "Server", recording = "rec-s"),
                copy("p", "device", year = 2014, genre = "Disco", genres = listOf("Disco", "Hip Hop", "Pop"), recording = "rec-p"),
            ),
        )
        assertEquals(2014, details.year)
        assertEquals(1979, details.originalYear)
        // Every genre once, spellings of one genre counted once, phone's first.
        assertEquals(listOf("Disco", "Hip Hop", "Pop", "Funk"), details.genres)
        assertEquals("Server", details.composer)
        assertEquals("rec-p", details.mbRecordingId)
    }

    @Test
    fun aCopyWithOnlyAMainGenreStillCounts() {
        val details = songDetails(listOf(copy("p", "device", genre = "Disco")))
        assertEquals(listOf("Disco"), details.genres)
    }

    @Test
    fun linesListsRoundTrip() {
        assertEquals(listOf("a", "b"), splitLines(joinLines(listOf("a", "b"))))
        assertEquals(emptyList<String>(), splitLines(joinLines(null)))
        assertEquals(emptyList<String>(), splitLines(""))
    }
}
