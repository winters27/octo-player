package app.winters.octo.discovery

import app.winters.octo.catalog.OnlineSongEntity
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.subsonic.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchingTest {
    private fun track(id: String, title: String, artist: String, ms: Long = 200_000) = TrackEntity(
        id = id, sourceId = "device", nativeId = id, title = title, searchKey = title.lowercase(), sortKey = title.lowercase(),
        artist = artist, artistId = "a", album = "Album", albumId = "al", trackNo = null, discNo = null, year = null,
        durationMs = ms, addedAt = 0, mimeType = null, sizeBytes = null, artwork = null, uri = null,
    )

    private fun song(id: String, title: String, artist: String, seconds: Int = 180, suffix: String = "m4a") =
        Song(id = id, title = title, artist = artist, album = title, albumId = "alb$id", duration = seconds, suffix = suffix, bitRate = 128, coverArt = id)

    @Test
    fun aGuessedLengthCountsAsUnknown() {
        assertEquals(0, knownLengthMs(song("x", "A", "B", seconds = 180)))
        assertEquals(0, knownLengthMs(song("x", "A", "B", seconds = 0)))
        assertEquals(215_000, knownLengthMs(song("x", "A", "B", seconds = 215)))
    }

    @Test
    fun aSyncedServerSongIsItsLibrarySong() {
        val out = resolveSongs(listOf(song("s1", "One More Time", "Daft Punk")), "server:x", mapOf("s1" to "p1"), emptyList(), 0)
        assertEquals(listOf(Resolved.InLibrary("p1")), out)
    }

    @Test
    fun theSameSongOnThePhoneWinsOverAStream() {
        val phone = track("p9", "Digital Love", "Daft Punk", ms = 301_000)
        val out = resolveSongs(listOf(song("s9", "Digital Love", "Daft Punk")), "server:x", emptyMap(), listOf(phone), 0)
        assertEquals(listOf(Resolved.InLibrary("p9")), out)
    }

    @Test
    fun anythingElseIsAFindThatStreams() {
        val out = resolveSongs(listOf(song("e1", "Genesis", "Justice")), "server:x", emptyMap(), listOf(track("p1", "Genesis", "Phil Collins")), 7)
        val find = (out.single() as Resolved.Found).song
        assertEquals("find:e1", find.id)
        assertEquals("e1", find.nativeId)
        assertEquals(0, find.durationMs)
        assertEquals("audio/mp4", find.mimeType)
        assertEquals(128, find.bitrate)
        assertEquals(7, find.seenAt)
    }

    @Test
    fun featuredArtistsAndExtrasStillMatch() {
        val lib = track("p1", "One Dance", "Drake feat. Wizkid & Kyla")
        assertTrue(sameSong("One Dance (feat. Wizkid)", "Drake", 0, lib))
        assertFalse(sameSong("One Dance", "Drake Bell", 0, lib))
    }

    @Test
    fun knownLengthsThatDifferAreDifferentRecordings() {
        val lib = track("p1", "Around the World", "Daft Punk", ms = 429_000)
        assertFalse(sameSong("Around the World", "Daft Punk", 238_000, lib))
        assertTrue(sameSong("Around the World", "Daft Punk", 0, lib))
    }

    @Test
    fun titleKeysIncludeTheBareTitle() {
        assertEquals(listOf("one dance (feat. wizkid)", "one dance"), titleKeys("One Dance (feat. Wizkid)"))
    }

    @Test
    fun aFindShowsAsASongWithNothingToOpen() {
        val row = OnlineSongEntity(
            "find:e1", "server:x", "e1", "Genesis", "Justice", "Cross", "al1", "ar1", 0, "e1", "audio/mp4", 128, 0,
        )
        val t = row.asTrack()
        assertEquals("find:e1", t.id)
        assertEquals("", t.albumId)
        assertEquals("", t.artistId)
        assertFalse(t.onPhone)
        assertEquals("server:server:x|e1", t.artwork)
    }

    @Test
    fun aDownloadAskedForLongAgoCanBeAskedAgain() {
        val row = OnlineSongEntity("find:e1", "s", "e1", "T", "A", "", null, null, 0, null, null, null, 0, requestedAt = 1_000)
        assertEquals(DownloadState.Requested, stateOf(row, 1_000 + 60_000))
        assertEquals(DownloadState.None, stateOf(row, 1_000 + 25L * 60 * 60_000))
        assertEquals(DownloadState.Done, stateOf(row.copy(adoptedId = "p1"), 1_000))
    }
}
