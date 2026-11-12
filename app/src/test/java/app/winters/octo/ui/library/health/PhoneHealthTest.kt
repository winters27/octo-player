package app.winters.octo.ui.library.health

import app.winters.octo.catalog.AlbumEntity
import app.winters.octo.catalog.SourceTrackEntity
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.health.BestReason
import app.winters.octo.health.HealthCheck
import app.winters.octo.listening.timed
import app.winters.octo.testing.openTestCatalog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Library health on the phone: copies found among a source's own, the
// rest on the library as the phone shows it, and the lines its pages show.
@RunWith(RobolectricTestRunner::class)
class PhoneHealthTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    private val server = "server:home"
    private var seq = 0L

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
        relinkKey = "", genre = "Rock", bitrate = bitrate, sampleRate = rate, bitDepth = depth, mergedId = merged, mbRecordingId = mbid, seq = seq++,
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

    // The report of these songs, albums and copies.
    private suspend fun report(tracks: List<TrackEntity>, albums: List<AlbumEntity> = emptyList(), copies: List<SourceTrackEntity> = emptyList()): Pair<HealthRig, HealthShown.Shown> {
        db.putHealth(albums, tracks, copies)
        val rig = HealthRig(db).keys()
        return rig to rig.built()
    }

    @Test
    fun twoFilesOfOneSongOnTheServerAreCopiesTheBestFirst() = timed(60) {
        val tracks = listOf(track("t1", "Holocene", album = "Bon Iver"), track("t2", "Holocene", album = "Holocene"))
        val copies = listOf(
            copy("mp3", "Holocene", merged = "t2", mime = "audio/mpeg", bitrate = 320, depth = null, album = "Holocene"),
            copy("flac", "Holocene", merged = "t1", album = "Bon Iver"),
        )
        val (rig, shown) = report(tracks, copies = copies)

        assertEquals(1, shown.count(HealthCheck.Duplicates))
        val lines = rig.page(HealthCheck.Duplicates, server).map { it.row }
        val heading = lines[0] as HealthRow.Copies
        assertEquals("Holocene by Artist", heading.title)
        assertEquals("2 copies. Same title, artist and length. Keep the first, FLAC, 16-bit, 44.1 kHz.", heading.detail)
        assertEquals(listOf("flac", "mp3"), heading.group.copies.map { it.nativeId })
        assertEquals(BestReason.Sound, heading.group.bestReason)
        assertEquals("FLAC, 16-bit, 44.1 kHz • Bon Iver", (lines[1] as HealthRow.Song).note)
        assertEquals("MP3, 320 kbps • Holocene", (lines[2] as HealthRow.Song).note)
        assertEquals(listOf("t1", "t2"), rig.songs(HealthCheck.Duplicates))
    }

    @Test
    fun theSongOnThePhoneAndOnTheServerIsNotACopy() = timed(60) {
        // Joined into one library song, or kept as two, it is never a copy.
        val (_, joined) = report(listOf(track("t1", "Holocene")), copies = listOf(copy("p", "Holocene", merged = "t1", source = "device"), copy("s", "Holocene", merged = "t1")))
        assertEquals(0, joined.count(HealthCheck.Duplicates))
        db.openHelper.writableDatabase.execSQL("DELETE FROM source_track")
        db.openHelper.writableDatabase.execSQL("DELETE FROM track")
        val (_, apart) = report(
            listOf(track("tp", "Holocene", source = "device"), track("ts", "Holocene")),
            copies = listOf(copy("p", "Holocene", merged = "tp", source = "device"), copy("s", "Holocene", merged = "ts")),
        )
        assertEquals(0, apart.count(HealthCheck.Duplicates))
    }

    @Test
    fun aSharedMusicBrainzRecordingIsOneRecordingOnThePhoneToo() = timed(60) {
        val tracks = listOf(track("t1", "Teardrop"), track("t2", "Tear Drop"))
        val copies = listOf(copy("a", "Teardrop", merged = "t1", mbid = "f200"), copy("b", "Tear Drop", merged = "t2", seconds = 205, mbid = "F200"))
        assertEquals(1, report(tracks, copies = copies).second.count(HealthCheck.Duplicates))
    }

    @Test
    fun missingTagsAreThoseThePhoneShowsAndNeverAnAlbumArtist() = timed(60) {
        val tracks = listOf(
            track("a", "One", albumId = "x", genre = ""),
            track("b", "Two", albumId = "x", year = null, trackNo = null),
            track("c", "Three", albumId = "x", artwork = null),
        )
        val (rig, shown) = report(tracks)

        assertEquals(listOf("a"), rig.songs(HealthCheck.NoGenre))
        assertEquals(listOf("b"), rig.songs(HealthCheck.NoYear))
        assertEquals(listOf("b"), rig.songs(HealthCheck.NoTrackNumber))
        assertEquals(listOf("c"), rig.songs(HealthCheck.NoCover))
        assertEquals(0, shown.count(HealthCheck.NoAlbumArtist))
        assertEquals(listOf("c"), rig.page(HealthCheck.NoCover, server).map { (it.row as HealthRow.Song).track.id })
    }

    @Test
    fun anAlbumSplitByItsAlbumArtistSaysSo() = timed(60) {
        val tracks = listOf(
            track("a", "One", album = "It Was Fun", albumId = "x"),
            track("b", "Two", album = "It Was Fun", albumId = "y"),
        )
        val albums = listOf(album("x", "It Was Fun", "SueCo"), album("y", "It Was Fun", "Sueco"))
        val (rig, _) = report(tracks, albums)

        val lines = rig.page(HealthCheck.SplitAlbums, server).map { it.row }
        val heading = lines[0] as HealthRow.Split
        assertEquals("It Was Fun by SueCo, shown as 2 albums", heading.title)
        assertEquals("The album artist differs: SueCo, Sueco.", heading.detail)
        assertEquals(listOf("a", "b"), lines.drop(1).map { (it as HealthRow.Song).track.id })
        assertEquals(listOf("a", "b"), rig.songs(HealthCheck.SplitAlbums))
    }

    @Test
    fun aTidyLibraryHasNothingToShow() = timed(60) {
        val tracks = listOf(track("a", "One", albumId = "x"), track("b", "Two", albumId = "x", seconds = 180))
        val (rig, shown) = report(tracks, copies = tracks.map { copy(it.id, it.title, merged = it.id, seconds = it.durationMs / 1_000) })

        assertTrue(HealthCheck.entries.all { shown.count(it) == 0 })
        assertTrue(HealthCheck.entries.all { rig.page(it, server).isEmpty() })
    }
}
