package app.winters.octo.output

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ByteRangesTest {
    @Test
    fun theThreeFormsAreRead() {
        assertEquals(ByteRange(start = 0, end = 499), parseByteRange("bytes=0-499"))
        assertEquals(ByteRange(start = 500), parseByteRange("bytes=500-"))
        assertEquals(ByteRange(suffix = 128), parseByteRange("bytes=-128"))
        assertEquals(ByteRange(start = 10, end = 20), parseByteRange("  BYTES= 10 - 20 "))
    }

    @Test
    fun anythingElseMeansTheWholeFile() {
        assertNull(parseByteRange(null))
        assertNull(parseByteRange(""))
        assertNull(parseByteRange("items=0-1"))
        assertNull(parseByteRange("bytes=0-1,5-6"))
        assertNull(parseByteRange("bytes=5-2"))
        assertNull(parseByteRange("bytes=-"))
        assertNull(parseByteRange("bytes=-0"))
        assertNull(parseByteRange("bytes=a-b"))
        assertNull(parseByteRange("bytes=-5-6"))
    }

    @Test
    fun aRangeIsHeldToTheFile() {
        assertEquals(0L..499L, ByteRange(start = 0, end = 499).within(1000))
        assertEquals(500L..999L, ByteRange(start = 500).within(1000))
        // Past the end is cut to the end.
        assertEquals(900L..999L, ByteRange(start = 900, end = 5000).within(1000))
        assertEquals(872L..999L, ByteRange(suffix = 128).within(1000))
        // More than the whole file is the whole file.
        assertEquals(0L..999L, ByteRange(suffix = 5000).within(1000))
    }

    @Test
    fun aRangeWhollyPastTheEndCannotBeMet() {
        assertNull(ByteRange(start = 1000).within(1000))
        assertNull(ByteRange(start = 0).within(0))
    }
}
