package app.winters.octo.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogMergerTest {
    private fun track(
        id: String,
        source: String,
        title: String,
        album: String,
        artist: String = "$source:artist",
        ms: Long = 200_000,
        no: Int? = null,
        order: Int = 0,
        year: Int? = null,
        genre: String = "",
    ) = SourceTrackEntity(
        id = id, sourceId = source, nativeId = id, title = title, searchKey = title.lowercase(), sortKey = title.lowercase(),
        artist = "Drake", artistId = artist, album = "Views", albumId = album, trackNo = no, discNo = null, year = year,
        durationMs = ms, addedAt = 0, mimeType = null, sizeBytes = null, artwork = null, uri = null,
        albumOrder = order, relinkKey = "", genre = genre,
    )

    private fun album(id: String, source: String, title: String = "Views", artist: String = "$source:artist", songs: Int = 0, art: String? = null) =
        SourceAlbumEntity(id, source, id, title, title.lowercase(), title.lowercase(), "Drake", artist, null, songs, 0, 0, art)

    private fun artist(id: String, source: String, name: String = "Drake") =
        SourceArtistEntity(id, source, name, name.lowercase(), name.lowercase(), 1, 1, null)

    private fun phone(tracks: List<SourceTrackEntity>, albums: List<SourceAlbumEntity>, artists: List<SourceArtistEntity>) =
        SourceCatalog("device", onPhone = true, tracks, albums, artists)

    private fun server(tracks: List<SourceTrackEntity>, albums: List<SourceAlbumEntity>, artists: List<SourceArtistEntity>) =
        SourceCatalog("server", onPhone = false, tracks, albums, artists)

    private val phoneOnly = phone(
        tracks = listOf(
            track("p1", "device", "Hotline Bling", "device:album", order = 1),
            track("p2", "device", "One Dance", "device:album", order = 0),
        ),
        albums = listOf(album("device:album", "device", songs = 2)),
        artists = listOf(artist("device:artist", "device")),
    )

    @Test
    fun oneSourcePassesThroughUnchanged() {
        val merged = mergeCatalogs(listOf(phoneOnly))
        val before = phoneOnly.tracks.associateBy { it.id }
        assertEquals(2, merged.tracks.size)
        merged.tracks.forEach { t ->
            val s = before.getValue(t.id)
            assertEquals(s.albumId, t.albumId)
            assertEquals(s.artistId, t.artistId)
            assertEquals(s.albumOrder, t.albumOrder)
            assertTrue(t.onPhone)
        }
        assertEquals(listOf("device:album"), merged.albums.map { it.id })
        assertEquals(2, merged.albums.single().songCount)
        assertEquals(mapOf("p1" to "p1", "p2" to "p2"), merged.mergedIds)
    }

    @Test
    fun albumsOfTheSameNameInOneSourceStayApart() {
        val twin = phone(
            tracks = listOf(track("a", "device", "Intro", "device:a"), track("b", "device", "Intro", "device:b")),
            albums = listOf(album("device:a", "device"), album("device:b", "device")),
            artists = listOf(artist("device:artist", "device"), artist("device:other", "device", name = "DRAKE")),
        )
        val merged = mergeCatalogs(listOf(twin))
        assertEquals(2, merged.albums.size)
        assertEquals(2, merged.tracks.size)
        assertEquals(2, merged.artists.size)
    }

    @Test
    fun theSameAlbumOnPhoneAndServerShowsOnce() {
        val serverSide = server(
            tracks = listOf(
                track("s1", "server", "One Dance (feat. Wizkid)", "server:album", ms = 201_500, no = 2, year = 2016, genre = "Hip-Hop/Rap"),
                track("s2", "server", "Hotline Bling", "server:album", no = 20),
                track("s3", "server", "Views", "server:album", no = 1),
            ),
            albums = listOf(album("server:album", "server", songs = 3, art = "server:art")),
            artists = listOf(artist("server:artist", "server")),
        )
        val merged = mergeCatalogs(listOf(phoneOnly, serverSide))

        // One album, keeping the phone's id, with the server's cover filling in.
        val album = merged.albums.single()
        assertEquals("device:album", album.id)
        assertEquals("server:art", album.artwork)
        assertEquals(3, album.songCount)

        // Songs on both keep the phone id; the server-only one streams.
        val byTitle = merged.tracks.associateBy { it.title }
        assertEquals("p2", byTitle.getValue("One Dance").id)
        assertTrue(byTitle.getValue("One Dance").onPhone)
        assertFalse(byTitle.getValue("Views").onPhone)
        assertEquals("device:album", byTitle.getValue("Views").albumId)
        assertEquals("device:artist", byTitle.getValue("Views").artistId)

        // The server's track numbers, year and genre fill what the phone lacks,
        // and the album is now in track number order.
        assertEquals(2, byTitle.getValue("One Dance").trackNo)
        assertEquals(2016, byTitle.getValue("One Dance").year)
        assertEquals("Hip-Hop/Rap", byTitle.getValue("One Dance").genre)
        assertEquals(listOf("Views", "One Dance", "Hotline Bling"), merged.tracks.sortedBy { it.albumOrder }.map { it.title })

        // Each server copy points at its library song.
        assertEquals("p2", merged.mergedIds["s1"])
        assertEquals("p1", merged.mergedIds["s2"])
        assertEquals("s3", merged.mergedIds["s3"])

        // One artist, with counts over everything.
        val drake = merged.artists.single()
        assertEquals("device:artist", drake.id)
        assertEquals(3, drake.songCount)
        assertEquals(1, drake.albumCount)
    }

    @Test
    fun songsOfDifferentLengthsAreDifferentSongs() {
        val serverSide = server(
            tracks = listOf(track("s1", "server", "One Dance", "server:album", ms = 260_000)),
            albums = listOf(album("server:album", "server")),
            artists = listOf(artist("server:artist", "server")),
        )
        val merged = mergeCatalogs(listOf(phoneOnly, serverSide))
        assertEquals(3, merged.tracks.size)
        assertEquals(2, merged.tracks.count { it.title == "One Dance" })
    }

    @Test
    fun anAlbumOnlyOnTheServerStreams() {
        val serverSide = server(
            tracks = listOf(track("s9", "server", "Gods Plan", "server:scorpion")),
            albums = listOf(album("server:scorpion", "server", title = "Scorpion", songs = 1)),
            artists = listOf(artist("server:artist", "server")),
        )
        val merged = mergeCatalogs(listOf(phoneOnly, serverSide))
        val scorpion = merged.albums.single { it.title == "Scorpion" }
        assertEquals("server:scorpion", scorpion.id)
        assertEquals("device:artist", scorpion.artistId)
        assertFalse(merged.tracks.single { it.id == "s9" }.onPhone)
    }

    @Test
    fun matchKeysIgnoreCasePunctuationAndBracketedExtras() {
        assertEquals(matchKey("One Dance"), matchKey("one dance (feat. Wizkid & Kyla)"))
        assertEquals(matchKey("Jay-Z"), matchKey("JAY Z"))
        assertEquals(matchKey("Views"), matchKey("Views [Deluxe]"))
    }

    @Test
    fun anAlbumFiledUnderAnotherArtistStillShowsOnce() {
        // The server credits the album to someone else, so the albums do not
        // match, but every song is the same song by the same artist.
        val serverSide = server(
            tracks = listOf(
                track("s1", "server", "Hotline Bling", "server:deluxe", no = 1),
                track("s2", "server", "One Dance", "server:deluxe", no = 2),
            ),
            albums = listOf(album("server:deluxe", "server", title = "Views (Deluxe)", artist = "server:ovo", songs = 2).copy(artist = "OVO Sound")),
            artists = listOf(artist("server:ovo", "server", name = "OVO Sound")),
        )
        val merged = mergeCatalogs(listOf(phoneOnly, serverSide))
        assertEquals(listOf("device:album"), merged.albums.map { it.id })
        assertEquals(2, merged.tracks.size)
        assertEquals("p1", merged.mergedIds["s1"])
        assertEquals("p2", merged.mergedIds["s2"])
        // The label artist has nothing of its own left.
        assertEquals(listOf("device:artist"), merged.artists.map { it.id })
    }

    @Test
    fun aDeluxeEditionKeepsOnlyItsExtraSongs() {
        val serverSide = server(
            tracks = listOf(
                track("s1", "server", "Hotline Bling", "server:deluxe"),
                track("s2", "server", "Bonus Track", "server:deluxe", ms = 180_000),
            ),
            albums = listOf(album("server:deluxe", "server", title = "Views (Deluxe)", artist = "server:ovo", songs = 2).copy(artist = "OVO Sound")),
            artists = listOf(artist("server:ovo", "server", name = "OVO Sound")),
        )
        val merged = mergeCatalogs(listOf(phoneOnly, serverSide))
        val deluxe = merged.albums.single { it.id == "server:deluxe" }
        assertEquals(1, deluxe.songCount)
        assertEquals(listOf("Bonus Track"), merged.tracks.filter { it.albumId == "server:deluxe" }.map { it.title })
        assertEquals("p1", merged.mergedIds["s1"])
    }

    @Test
    fun theSameTitleByAnotherArtistIsNotTheSameSong() {
        val cover = track("s1", "server", "One Dance", "server:covers").copy(artist = "Someone Else")
        val serverSide = server(
            tracks = listOf(cover),
            albums = listOf(album("server:covers", "server", title = "Covers", artist = "server:someone")),
            artists = listOf(artist("server:someone", "server", name = "Someone Else")),
        )
        val merged = mergeCatalogs(listOf(phoneOnly, serverSide))
        assertEquals("s1", merged.mergedIds["s1"])
        assertEquals(3, merged.tracks.size)
    }
}
