package app.winters.octo.discovery

import app.winters.octo.admin.DownloadRecord
import app.winters.octo.catalog.OnlineSongEntity
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.subsonic.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryMatchingTest {
    private fun track(id: String, title: String, artist: String, ms: Long = 200_000) = TrackEntity(
        id = id, sourceId = "device", nativeId = id, title = title, searchKey = title.lowercase(), sortKey = title.lowercase(),
        artist = artist, artistId = "a", album = "Album", albumId = "al", trackNo = null, discNo = null, year = null,
        durationMs = ms, addedAt = 0, mimeType = null, sizeBytes = null, artwork = null, uri = null,
    )

    private fun song(id: String, title: String, artist: String, seconds: Int = 180, suffix: String = "m4a") =
        Song(id = id, title = title, artist = artist, album = title, albumId = "alb$id", duration = seconds, suffix = suffix, bitRate = 128, coverArt = id)

    @Test
    fun aSyncedServerSongIsItsLibrarySong() {
        val out = resolveSongs(listOf(song("s1", "One More Time", "Daft Punk")), "server:x", mapOf("s1" to "p1"), emptyList(), 0)
        assertEquals(listOf(Resolved.InLibrary("p1")), out)
    }

    @Test
    fun aSongTheServerMarksAsOutsideIsAFindWhateverAnOldLinkSays() {
        val online = song("s1", "One More Time", "Daft Punk").copy(isExternal = true)
        val out = resolveSongs(listOf(online), "server:x", mapOf("s1" to "p1"), emptyList(), 0)
        assertEquals("find:s1", (out.single() as Resolved.Found).song.id)
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
    fun aRemixIsNotTheOriginalEvenWithoutALength() {
        val lib = track("p1", "Nightcall", "Kavinsky", ms = 179_000)
        assertFalse(sameSong("Nightcall (Breakbot Remix)", "Kavinsky", 0, lib))
        assertFalse(sameSong("Nightcall - Live", "Kavinsky", 0, lib))
        assertTrue(sameSong("Nightcall [Remastered]", "Kavinsky", 0, lib))
        assertTrue(sameSong("Nightcall (Breakbot remix)", "Kavinsky", 0, track("p2", "Nightcall (Breakbot Remix)", "Kavinsky")))
    }

    @Test
    fun knownLengthsThatDifferAreDifferentRecordings() {
        val lib = track("p1", "Around the World", "Daft Punk", ms = 429_000)
        assertFalse(sameSong("Around the World", "Daft Punk", 238_000, lib))
        assertTrue(sameSong("Around the World", "Daft Punk", 0, lib))
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
        // Its cover is kept apart from library covers.
        assertEquals("online:server:x|e1", t.artwork)
    }

    @Test
    fun aFinishedDownloadIsKnownByTitleAndArtist() {
        val find = OnlineSongEntity("find:e1", "s", "e1", "Genesis", "Justice", "", null, null, 0, null, null, null, 0)
        assertTrue(downloadMatches(find, DownloadRecord(artist = "Justice", title = "Genesis")))
        assertTrue(downloadMatches(find, DownloadRecord(artist = "Justice feat. Someone", title = "Genesis [Remastered]")))
        assertFalse(downloadMatches(find, DownloadRecord(artist = "Justice", title = "Genesis (Live)")))
        assertFalse(downloadMatches(find, DownloadRecord(artist = "Phil Collins", title = "Genesis")))
    }

    @Test
    fun stylizedNamesAreTheSameSong() {
        val lib = track("p1", "Suicide", "Suicideboys", ms = 169_000)
        assertTrue(sameSong("${'$'}UICIDE", "${'$'}uicideboy${'$'}", 170_000, lib))
        assertTrue(sameSong("＄UICIDE", "＄uicideboy＄", 0, lib))
        // Another song that holds the title is not it.
        assertFalse(sameSong("Ultimate ${'$'}uicide", "${'$'}uicideboy${'$'}", 170_000, lib))
    }

    @Test
    fun aCurlyApostropheIsAnApostrophe() {
        assertTrue(sameSong("Huntin’ Wabbitz", "${'$'}uicideboy${'$'}", 0, track("p1", "Huntin' Wabbitz", "${'$'}uicideboy${'$'}")))
    }

    @Test
    fun creditsJoinedAnyWayAreTheSameArtists() {
        assertTrue(sameArtist("Kanye West、Ty Dolla ${'$'}ign", "Kanye West"))
        assertTrue(sameSong("Real Friends (Explicit)", "Kanye West、Ty Dolla ${'$'}ign", 0, track("p1", "Real Friends", "Kanye West")))
        // An alias, with the name in another script beside it.
        assertTrue(sameArtist("Ye (侃爷)", "Kanye West"))
        assertTrue(sameSong("Can't Tell Me Nothing", "Ye (侃爷)", 0, track("p1", "Can’t Tell Me Nothing", "Kanye West")))
    }

    @Test
    fun aKnownNameIsNeverSplit() {
        assertTrue(sameArtist("Tyler, The Creator", "Tyler The Creator"))
        assertTrue(sameArtist("Tyler, The Creator & Kali Uchis", "Tyler, The Creator"))
        assertFalse(sameArtist("Tyler, The Creator", "Tyler"))
        assertFalse(sameSong("EARFQUAKE", "Tyler", 0, track("p1", "EARFQUAKE", "Tyler, The Creator")))
    }

    @Test
    fun differentGuestsAreAnotherCollaboration() {
        assertFalse(sameSong("Song", "A feat. B", 0, track("p1", "Song", "A feat. C")))
        assertTrue(sameSong("Song", "A feat. B", 0, track("p1", "Song", "A")))
    }

    @Test
    fun aServerSongSpelledAnotherWayIsItsLibrarySong() {
        val phone = track("p9", "Suicide", "Suicideboys", ms = 169_000)
        val out = resolveSongs(listOf(song("s9", "${'$'}UICIDE", "${'$'}uicideboy${'$'}", seconds = 170)), "server:x", emptyMap(), listOf(phone), 0)
        assertEquals(listOf(Resolved.InLibrary("p9")), out)
    }

    @Test
    fun aFindIsAdoptedByTheSameRecordingOnly() {
        fun find(id: String, title: String, artist: String) = OnlineSongEntity(id, "s", id, title, artist, "", null, null, 0, null, null, null, 0)
        val library = listOf(
            track("p1", "Movie Star (Clean)", "Jack Harlow"),
            track("p2", "Movie Star", "Jack Harlow"),
            track("p3", "Suicide", "Suicideboys"),
        )
        val adopted = adoptions(
            listOf(find("find:a", "Movie Star", "Jack Harlow"), find("find:b", "${'$'}UICIDE", "${'$'}uicideboy${'$'}"), find("find:c", "Mask Off", "Future")),
            library,
        )
        // The clean edit is another version here, unlike for lyrics.
        assertEquals(mapOf("find:a" to "p2", "find:b" to "p3"), adopted.mapValues { it.value.id })
    }

    @Test
    fun aFinishedDownloadIsKnownThroughAnAlias() {
        val find = OnlineSongEntity("find:e1", "s", "e1", "Stronger", "Kanye West", "", null, null, 0, null, null, null, 0)
        assertTrue(downloadMatches(find, DownloadRecord(artist = "Ye (侃爷)", title = "Stronger (Explicit)")))
        assertFalse(downloadMatches(find, DownloadRecord(artist = "Ye", title = "Stronger (Clean)")))
    }

    @Test
    fun aDownloadAskedForLongAgoCanBeAskedAgain() {
        val row = OnlineSongEntity("find:e1", "s", "e1", "T", "A", "", null, null, 0, null, null, null, 0, requestedAt = 1_000)
        assertEquals(DownloadState.Requested, stateOf(row, 1_000 + 60_000))
        assertEquals(DownloadState.None, stateOf(row, 1_000 + 25L * 60 * 60_000))
        assertEquals(DownloadState.Done, stateOf(row.copy(adoptedId = "p1"), 1_000))
    }
}
