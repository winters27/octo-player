package app.winters.octo.discovery

import app.winters.octo.catalog.SongIdentity
import app.winters.octo.playlists.M3U_SAME_LENGTH_MS
import app.winters.octo.playlists.M3uQuestion
import app.winters.octo.playlists.M3uSong
import app.winters.octo.playlists.m3uFits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

// sameSong over two songs with no ISRCs never calls two known lengths
// further apart than SAME_SONG_LENGTH_MS one song, whatever their titles
// and artists say, and m3uFits never lets a song answer a question whose
// known length is further than M3U_SAME_LENGTH_MS from its own: so a
// matcher may leave such pairs out unasked. A shared ISRC is the one thing
// that makes far lengths one recording, and sameSongLengths names, for any
// song, ISRCs or none, the lengths sameSong can still accept beside it.
class SameSongLengthTest {
    private val codes = listOf("USRC17607839", "GBAYE0601498", "us-rc1-76-07839", "QZES71982312", "not a code", "")
    private val titles = listOf(
        "Untitled", "Intro", "Song A", "Song A (Live)", "Song A - Remastered 2011", "01 - Song A", "Track 01",
        "Nightcall (feat. Lovefoxxx)", "Ünïcode 😀", "Blue (Da Ba Dee)", "",
    )
    private val artists = listOf("Kavinsky", "Drake feat. Rihanna", "Drake", "", "The Band", "Band", "Ye (侃爷)")

    @Test
    fun knownLengthsFurtherApartAreNeverOneSong() {
        val r = Random(5)
        var same = 0
        repeat(20_000) {
            val title = titles.random(r)
            val otherTitle = if (r.nextBoolean()) title else titles.random(r)
            val artist = artists.random(r)
            val otherArtist = if (r.nextBoolean()) artist else artists.random(r)
            val ms = 1_000L + r.nextLong(600_000)
            val apart = SAME_SONG_LENGTH_MS + 1 + r.nextLong(300_000)
            val other = if (r.nextBoolean() || ms - apart <= 0) ms + apart else ms - apart
            assertFalse("$title / $artist at $ms and $otherTitle / $otherArtist at $other", sameSong(title, artist, ms, otherTitle, otherArtist, other))
            assertFalse("read once: $title / $artist at $ms and $otherTitle / $otherArtist at $other", sameSong(parsedSong(title, artist, ms), parsedSong(otherTitle, otherArtist, other)))
            if (sameSong(title, artist, ms, title, artist, ms + r.nextLong(SAME_SONG_LENGTH_MS + 1))) same++
        }
        assertTrue("songs within the tolerance still match", same > 0)
        assertTrue(sameSong("Song A", "Drake", 200_000, "Song A", "Drake", 200_000 + SAME_SONG_LENGTH_MS))
        assertFalse(sameSong("Song A", "Drake", 200_000, "Song A", "Drake", 200_000 + SAME_SONG_LENGTH_MS + 1))
        assertTrue("an unknown length agrees with any", sameSong("Song A", "Drake", 0, "Song A", "Drake", 900_000))
    }

    @Test
    fun aSharedIsrcMakesFarLengthsOneRecording() {
        assertTrue(sameSong("Song A", "Drake", 200_000, "Song A", "Drake", 400_000, listOf("USRC17607839"), listOf("USRC17607839")))
    }

    // Pairs with ISRCs shared, different, on one side only or none, at
    // lengths near, far and unknown: every pair sameSong accepts has each
    // song's length inside the other's sameSongLengths.
    @Test
    fun everyAcceptedPairIsInsideEachOthersLengths() {
        val r = Random(7)
        var accepted = 0
        var acceptedFar = 0
        var acceptedOneSided = 0
        repeat(40_000) {
            val title = titles.random(r)
            val otherTitle = if (r.nextInt(4) > 0) title else titles.random(r)
            val artist = artists.random(r)
            val otherArtist = if (r.nextInt(4) > 0) artist else artists.random(r)
            val ms = if (r.nextInt(8) == 0) 0L else 1_000L + r.nextLong(600_000)
            val other = when (r.nextInt(8)) {
                0 -> 0L
                in 1..4 -> maxOf(1L, ms + r.nextLong(-SAME_SONG_LENGTH_MS - 2, SAME_SONG_LENGTH_MS + 3))
                else -> 1_000L + r.nextLong(600_000)
            }
            val isrcs = List(r.nextInt(3)) { codes.random(r) }
            val otherIsrcs = when (r.nextInt(4)) {
                0 -> isrcs
                1 -> emptyList()
                else -> List(r.nextInt(3)) { codes.random(r) }
            }
            val a = parsedSong(title, artist, ms, isrcs)
            val b = parsedSong(otherTitle, otherArtist, other, otherIsrcs)
            if (!sameSong(a, b)) return@repeat
            accepted++
            if (ms > 0 && other > 0 && kotlin.math.abs(ms - other) > SAME_SONG_LENGTH_MS) acceptedFar++
            if (SongIdentity.isrcs(isrcs).isEmpty() != SongIdentity.isrcs(otherIsrcs).isEmpty()) acceptedOneSided++
            assertTrue("$otherTitle at $other inside $title at $ms ($isrcs, $otherIsrcs)", other in sameSongLengths(a))
            assertTrue("$title at $ms inside $otherTitle at $other ($otherIsrcs, $isrcs)", ms in sameSongLengths(b))
        }
        assertTrue("pairs were accepted: $accepted", accepted > 1_000)
        assertTrue("a shared ISRC accepted far lengths: $acceptedFar", acceptedFar > 0)
        assertTrue("pairs with an ISRC on one side were accepted: $acceptedOneSided", acceptedOneSided > 0)
    }

    @Test
    fun aSongWithAnIsrcGetsAnyLength() {
        val withCode = sameSongLengths(parsedSong("Song A", "Drake", 200_000, listOf("US-RC1-76-07839")))
        assertNull(withCode.near)
        assertTrue(900_000L in withCode)
        assertTrue(1L in withCode)
        assertNull("no length of its own", sameSongLengths(parsedSong("Song A", "Drake", 0)).near)
        val plain = sameSongLengths(parsedSong("Song A", "Drake", 200_000))
        assertEquals(200_000 - SAME_SONG_LENGTH_MS..200_000 + SAME_SONG_LENGTH_MS, plain.near)
        assertTrue("an unknown length", 0L in plain)
        assertFalse(200_000 + SAME_SONG_LENGTH_MS + 1 in plain)
        assertEquals("a value that is no ISRC counts as none", plain.near, sameSongLengths(parsedSong("Song A", "Drake", 200_000, listOf("not a code"))).near)
    }

    @Test
    fun aQuestionNeverTakesASongOfAKnownLengthFurtherApart() {
        val r = Random(6)
        repeat(5_000) {
            val title = titles.random(r)
            val ms = 1_000L + r.nextLong(600_000)
            val apart = M3U_SAME_LENGTH_MS + 1 + r.nextLong(300_000)
            val other = if (r.nextBoolean() || ms - apart <= 0) ms + apart else ms - apart
            assertFalse(m3uFits(M3uQuestion(title, artists.random(r), ms), versionOf(title), M3uSong("s", title, artists.random(r), other)))
        }
        val question = M3uQuestion("Song A", "Drake", 200_000)
        assertTrue(m3uFits(question, emptySet(), M3uSong("s", "Song A", "Drake", 200_000 + M3U_SAME_LENGTH_MS)))
        assertFalse(m3uFits(question, emptySet(), M3uSong("s", "Song A", "Drake", 200_000 + M3U_SAME_LENGTH_MS + 1)))
        assertTrue("no length on the question", m3uFits(M3uQuestion("Song A", "Drake", 0), emptySet(), M3uSong("s", "Song A", "Drake", 900_000)))
        assertTrue("no length on the song", m3uFits(question, emptySet(), M3uSong("s", "Song A", "Drake", 0)))
    }
}
