package app.winters.octo.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// ISRCs read one way everywhere, the same as on the server: one spelling
// for a valid code, nothing for anything else, and a shared code as one
// recording.
class SongIdentityIsrcTest {
    @Test
    fun anIsrcHasOneSpellingOrIsAbsent() {
        val cases = listOf(
            "USRC17607839" to "USRC17607839",
            "us-rc1-76-07839" to "USRC17607839",
            " US RC1 76 07839 " to "USRC17607839",
            "US.RC1.76.07839" to "USRC17607839",
            "ＵＳＲＣ１７６０７８３９" to "USRC17607839",
            "GBAHT1600302" to "GBAHT1600302",
            "USRC1760783" to null,
            "USRC176078390" to null,
            "1SRC17607839" to null,
            "USRC1760783X" to null,
            "US_RC17607839" to null,
            "ISRC: USRC17607839" to null,
            "" to null,
            null to null,
        )
        for ((value, expected) in cases) assertEquals("'$value'", expected, SongIdentity.normalizeIsrc(value))
    }

    @Test
    fun sharingAnIsrcNeedsAValidCodeOnBothSides() {
        assertTrue(SongIdentity.sharesIsrc(listOf("USRC17607839"), listOf("us-rc1-76-07839")))
        assertTrue(SongIdentity.sharesIsrc(listOf("GBAHT1600302", "USRC17607839"), listOf("USRC17607839")))
        assertFalse(SongIdentity.sharesIsrc(listOf("USRC17607839"), listOf("GBAHT1600302")))
        assertFalse(SongIdentity.sharesIsrc(listOf("USRC17607839"), emptyList()))
        assertFalse(SongIdentity.sharesIsrc(null, listOf("USRC17607839")))
        assertFalse(SongIdentity.sharesIsrc(listOf("junk"), listOf("junk")))
    }

    @Test
    fun oneIsrcIsTheSameRecordingAtFullConfidence() {
        val match = SongIdentity.same(
            SongRef("紅蓮華", "LiSA", isrcs = listOf("JPU901901234")),
            SongRef("Gurenge", "LiSA", isrcs = listOf("JP-U90-19-01234")),
        )

        assertEquals(SongVerdict.Same, match.verdict)
        assertEquals(1.0, match.confidence, 0.0)
        assertEquals("same ISRC", match.reason)
    }

    @Test
    fun differentIsrcsFallBackToTheTextUnchanged() {
        val withCodes = SongIdentity.same(
            SongRef("Song (Remastered 2011)", "Artist", 200.0, listOf("GBAAA0100001")),
            SongRef("Song", "Artist", 201.0, listOf("GBAAA1100002")),
        )
        val without = SongIdentity.same(SongRef("Song (Remastered 2011)", "Artist", 200.0), SongRef("Song", "Artist", 201.0))

        assertEquals(without, withCodes)
    }
}
