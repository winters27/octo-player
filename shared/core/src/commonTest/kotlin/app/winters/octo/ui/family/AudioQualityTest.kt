package app.winters.octo.ui.family

import app.winters.octo.subsonic.FamilyQuality
import app.winters.octo.subsonic.StreamQuality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Original in every quality picker: first, the default, sent untouched, and
// held back under a family limit.
class AudioQualityTest {
    @Test
    fun originalComesFirstAndIsTheDefault() {
        assertEquals(StreamQuality.Original, StreamQuality.entries.first())
        assertEquals(StreamQuality.Original, qualityOptions(0).first().quality)
        assertEquals(StreamQuality.Original, FamilyQuality().home)
        assertEquals(StreamQuality.Original, FamilyQuality().away)
        assertEquals("As the file is, FLAC stays FLAC", qualityLine(StreamQuality.Original))
        assertEquals("Original (as the file is, FLAC stays FLAC)", ORIGINAL_LABEL)
    }

    @Test
    fun originalSendsNoTranscodeAsk() {
        assertEquals(mapOf("format" to "raw"), streamParams(appPicks = true, quality = StreamQuality.Original))
        assertEquals(mapOf("format" to "raw"), streamParams(appPicks = false, quality = StreamQuality.DataSaver))
        assertEquals(mapOf("format" to "opus", "maxBitRate" to "96"), streamParams(appPicks = true, quality = StreamQuality.DataSaver))
    }

    @Test
    fun withoutALimitEveryChoiceCanBePicked() {
        val options = qualityOptions(0)
        assertTrue(options.all { it.enabled })
        assertNull(options.first().blocked)
        assertEquals("As the file is, FLAC stays FLAC", options.first().line)
    }

    @Test
    fun aCappedMemberSeesOriginalOffAndWhy() {
        val options = qualityOptions(192)
        val original = options.first()
        assertEquals(StreamQuality.Original, original.quality)
        assertFalse(original.enabled)
        assertEquals("Your family plan streams up to 192 kbps", original.blocked)
        assertEquals("Your family plan streams up to 192 kbps", original.line)
        // The bitrates stay pickable; one above the limit plays at it.
        assertTrue(options.drop(1).all { it.enabled })
        assertTrue(options.first { it.quality == StreamQuality.High }.limited)
        assertFalse(options.first { it.quality == StreamQuality.Standard }.limited)
        assertEquals("Your family plan streams up to 96 kbps", originalBlockedBy(96))
        assertNull(originalBlockedBy(0))
    }
}
