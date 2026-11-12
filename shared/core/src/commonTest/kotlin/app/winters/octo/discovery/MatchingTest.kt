package app.winters.octo.discovery

import app.winters.octo.subsonic.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchingTest {
    private fun song(id: String, title: String, artist: String, seconds: Int = 180, suffix: String = "m4a") =
        Song(id = id, title = title, artist = artist, album = title, albumId = "alb$id", duration = seconds, suffix = suffix, bitRate = 128, coverArt = id)

    @Test
    fun aGuessedLengthCountsAsUnknown() {
        assertEquals(0, knownLengthMs(song("x", "A", "B", seconds = 180)))
        assertEquals(0, knownLengthMs(song("x", "A", "B", seconds = 0)))
        assertEquals(215_000, knownLengthMs(song("x", "A", "B", seconds = 215)))
    }

    @Test
    fun titleKeysIncludeTheBareTitle() {
        assertEquals(listOf("one dance (feat. wizkid)", "one dance"), titleKeys("One Dance (feat. Wizkid)"))
    }

    @Test
    fun titleKeysIncludeTheTitleAPersonWouldSay() {
        assertEquals(listOf("01 - teardrop - remastered 2011", "teardrop"), titleKeys("01 - Teardrop - Remastered 2011"))
    }

    @Test
    fun lengthsOnlyCountWhenBothAreKnown() {
        assertFalse(sameSong("Around the World", "Daft Punk", 238_000, "Around the World", "Daft Punk", 429_000))
        assertTrue(sameSong("Around the World", "Daft Punk", 0, "Around the World", "Daft Punk", 429_000))
        assertFalse(sameSong("Nightcall - Live", "Kavinsky", 0, "Nightcall", "Kavinsky", 179_000))
    }

    @Test
    fun oneIsrcOnBothSidesIsOneRecording() {
        val code = listOf("JPU901901234")
        assertTrue(sameSong("紅蓮華", "LiSA", 239_000, "Gurenge", "LiSA", 239_000, code, listOf("JP-U90-19-01234")))
        assertTrue(sameRecording("紅蓮華", "LiSA", "Gurenge", "LiSA", code, code))
        assertTrue(sameSongAnyVersion("紅蓮華", "LiSA", "Gurenge", "LiSA", code, code))
        // A code on one side only, or none at all, leaves it to the titles.
        assertFalse(sameSong("紅蓮華", "LiSA", 0, "Gurenge", "LiSA", 0, code))
        assertFalse(sameRecording("紅蓮華", "LiSA", "Gurenge", "LiSA"))
        // Different codes decide nothing: the titles still do.
        assertFalse(sameSong("Nightcall - Live", "Kavinsky", 0, "Nightcall", "Kavinsky", 0, listOf("FR0000000001"), listOf("FR0000000002")))
        assertTrue(sameSong("Nightcall", "Kavinsky", 0, "Nightcall", "Kavinsky", 0, listOf("FR0000000001"), listOf("FR0000000002")))
    }

    private class Side(val title: String, val artist: String, val lengthMs: Long, val isrcs: List<String> = emptyList()) {
        val parsed = parsedSong(title, artist, lengthMs, isrcs)
    }

    private fun sameByFields(a: Side, b: Side) = sameSong(a.title, a.artist, a.lengthMs, b.title, b.artist, b.lengthMs, a.isrcs, b.isrcs)

    // sameSong over songs parsed once answers as over their fields: the
    // cases above, then every first side against every second side, each
    // parsed song reused for every comparison it is in, twice over.
    @Test
    fun songsParsedOnceMatchAsTheirFields() {
        val code = listOf("JPU901901234")
        val cases = listOf(
            Side("Around the World", "Daft Punk", 238_000) to Side("Around the World", "Daft Punk", 429_000),
            Side("Around the World", "Daft Punk", 0) to Side("Around the World", "Daft Punk", 429_000),
            Side("Nightcall - Live", "Kavinsky", 0) to Side("Nightcall", "Kavinsky", 179_000),
            Side("紅蓮華", "LiSA", 239_000, code) to Side("Gurenge", "LiSA", 239_000, listOf("JP-U90-19-01234")),
            Side("紅蓮華", "LiSA", 0, code) to Side("Gurenge", "LiSA", 0),
            Side("Nightcall - Live", "Kavinsky", 0, listOf("FR0000000001")) to Side("Nightcall", "Kavinsky", 0, listOf("FR0000000002")),
            Side("Nightcall", "Kavinsky", 0, listOf("FR0000000001")) to Side("Nightcall", "Kavinsky", 0, listOf("FR0000000002")),
        )
        val answers = listOf(false, true, false, true, false, false, true)
        repeat(2) {
            cases.forEachIndexed { i, (a, b) ->
                assertEquals("case $i", answers[i], sameByFields(a, b))
                assertEquals("case $i", answers[i], sameSong(a.parsed, b.parsed))
                for ((j, other) in cases.withIndex()) {
                    assertEquals("case $i against $j", sameByFields(a, other.second), sameSong(a.parsed, other.second.parsed))
                }
            }
        }
    }

    @Test
    fun anIsrcIndexFindsSongsByAnyOfTheirCodes() {
        val library = listOf(
            song("a", "Gurenge", "LiSA").copy(isrc = listOf("jp-u90-19-01234")),
            song("b", "Get Lucky", "Daft Punk").copy(isrc = listOf("not a code")),
            song("c", "Homura", "LiSA").copy(isrc = listOf("JPU902003065", "JPU901901234")),
        )
        val index = IsrcIndex(library) { it.isrc }
        assertEquals(listOf("a", "c"), index.candidates(listOf("JPU901901234")).map { it.id })
        assertEquals(emptyList<Song>(), index.candidates(listOf("not a code")))
        assertEquals(emptyList<Song>(), index.candidates(emptyList()))
    }
}
