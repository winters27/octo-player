package app.winters.octo.ui.library.health

import app.winters.octo.catalog.AlbumEntity
import app.winters.octo.catalog.SourceTrackEntity
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.health.BestReason
import app.winters.octo.health.HealthCheck
import app.winters.octo.health.HealthTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Library health on the phone: copies found among a source's own, the
// rest on the library as the phone shows it, and the lines the screens draw.
class PhoneHealthTest {
    private val server = "server:home"

    private fun copy(
        id: String,
        title: String,
        merged: String = id,
        source: String = server,
        seconds: Long = 200,
        mime: String = "audio/flac",
        bitrate: Int? = 900,
        depth: Int? = 16,
        rate: Int? = 44_100,
        album: String = "Album",
        mbid: String? = null,
    ) = SourceTrackEntity(
        id = "$source/$id", sourceId = source, nativeId = id, title = title, searchKey = title.lowercase(), sortKey = title.lowercase(),
        artist = "Artist", artistId = "ar", album = album, albumId = "al-$album", trackNo = 1, discNo = 1, year = 2020,
        durationMs = seconds * 1_000, addedAt = 0, mimeType = mime, sizeBytes = null, artwork = "art", uri = null, albumOrder = 0,
        relinkKey = "", genre = "Rock", bitrate = bitrate, sampleRate = rate, bitDepth = depth, mergedId = merged, mbRecordingId = mbid,
    )

    private fun track(
        id: String,
        title: String,
        album: String = "Album",
        albumId: String = "al-$album",
        seconds: Long = 200,
        genre: String = "Rock",
        year: Int? = 2020,
        trackNo: Int? = 1,
        artwork: String? = "art",
        source: String = server,
    ) = TrackEntity(
        id = id, sourceId = source, nativeId = id, title = title, searchKey = title.lowercase(), sortKey = title.lowercase(),
        artist = "Artist", artistId = "ar", album = album, albumId = albumId, trackNo = trackNo, discNo = 1, year = year,
        durationMs = seconds * 1_000, addedAt = 0, mimeType = "audio/flac", sizeBytes = null, artwork = artwork, uri = null, genre = genre,
    )

    private fun album(id: String, title: String, artist: String = "Artist") = AlbumEntity(
        id = id, sourceId = server, nativeId = id, title = title, searchKey = title.lowercase(), sortKey = title.lowercase(),
        artist = artist, artistId = "ar", year = 2020, songCount = 1, durationMs = 0, addedAt = 0, artwork = null,
    )

    @Test
    fun twoFilesOfOneSongOnTheServerAreCopiesTheBestFirst() {
        val tracks = listOf(track("t1", "Holocene", album = "Bon Iver"), track("t2", "Holocene", album = "Holocene"))
        val copies = listOf(
            copy("mp3", "Holocene", merged = "t2", mime = "audio/mpeg", bitrate = 320, depth = null, album = "Holocene"),
            copy("flac", "Holocene", merged = "t1", album = "Bon Iver"),
        )
        val health = phoneHealth(tracks, emptyList(), copies)

        val group = health.report.duplicates.single()
        assertEquals(listOf("t1", "t2"), group.copies.map { it.id })
        assertEquals(BestReason.Sound, group.bestReason)
        val lines = health.lines(HealthCheck.Duplicates)
        assertEquals(HealthLine.Heading("Holocene by Artist", "2 copies. Same title, artist and length. Keep the first, FLAC, 16-bit, 44.1 kHz."), lines[0])
        assertEquals("FLAC, 16-bit, 44.1 kHz • Bon Iver", (lines[1] as HealthLine.Song).note)
        assertEquals("MP3, 320 kbps • Holocene", (lines[2] as HealthLine.Song).note)
        assertEquals(listOf("t1", "t2"), health.songs(HealthCheck.Duplicates).map { it.id })
    }

    @Test
    fun theSongOnThePhoneAndOnTheServerIsNotACopy() {
        val tracks = listOf(track("t1", "Holocene"))
        val copies = listOf(copy("p", "Holocene", merged = "t1", source = "device"), copy("s", "Holocene", merged = "t1"))

        assertTrue(phoneHealth(tracks, emptyList(), copies).report.duplicates.isEmpty())
    }

    @Test
    fun aSharedMusicBrainzRecordingIsOneRecordingOnThePhoneToo() {
        val tracks = listOf(track("t1", "Teardrop"), track("t2", "Tear Drop"))
        val copies = listOf(copy("a", "Teardrop", merged = "t1", mbid = "f200"), copy("b", "Tear Drop", merged = "t2", seconds = 205, mbid = "F200"))

        assertEquals(1, phoneHealth(tracks, emptyList(), copies).report.duplicates.size)
    }

    @Test
    fun missingTagsAreThoseThePhoneShowsAndNeverAnAlbumArtist() {
        val tracks = listOf(
            track("a", "One", albumId = "x", genre = ""),
            track("b", "Two", albumId = "x", year = null, trackNo = null),
            track("c", "Three", albumId = "x", artwork = null),
        )
        val report = phoneHealth(tracks, emptyList(), emptyList()).report

        assertEquals(listOf("a"), report.missing.getValue(HealthTag.Genre).map { it.id })
        assertEquals(listOf("b"), report.missing.getValue(HealthTag.Year).map { it.id })
        assertEquals(listOf("b"), report.missing.getValue(HealthTag.TrackNumber).map { it.id })
        assertEquals(listOf("c"), report.missing.getValue(HealthTag.Cover).map { it.id })
        assertFalse(HealthTag.AlbumArtist in report.missing)
    }

    @Test
    fun anAlbumSplitByItsAlbumArtistSaysSo() {
        val tracks = listOf(
            track("a", "One", album = "It Was Fun", albumId = "x"),
            track("b", "Two", album = "It Was Fun", albumId = "y"),
        )
        val albums = listOf(album("x", "It Was Fun", "SueCo"), album("y", "It Was Fun", "Sueco"))
        val health = phoneHealth(tracks, albums, emptyList())

        val lines = health.lines(HealthCheck.SplitAlbums)
        assertEquals(HealthLine.Heading("It Was Fun by SueCo, shown as 2 albums", "The album artist differs: SueCo, Sueco."), lines[0])
        assertEquals(listOf("a", "b"), health.songs(HealthCheck.SplitAlbums).map { it.id })
    }

    @Test
    fun aTidyLibraryHasNothingToShow() {
        val tracks = listOf(track("a", "One", albumId = "x"), track("b", "Two", albumId = "x", seconds = 180))
        val health = phoneHealth(tracks, emptyList(), tracks.map { copy(it.id, it.title, merged = it.id, seconds = it.durationMs / 1_000) })

        assertTrue(health.report.clean)
    }
}
