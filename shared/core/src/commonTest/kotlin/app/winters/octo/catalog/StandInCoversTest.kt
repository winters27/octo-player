package app.winters.octo.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StandInCoversTest {
    private val record = ByteArray(40) { (it * 7).toByte() }
    private val standIns = StandIns(mapOf(record.size to setOf(sha256(record))))

    @Test
    fun theStandInIsKnownByItsBytes() {
        assertTrue(standIns.isStandIn(record.copyOf()))
    }

    @Test
    fun aPictureOfTheSameLengthIsARealOne() {
        val cover = record.copyOf().also { it[10] = 1 }
        assertFalse(standIns.isStandIn(cover))
    }

    @Test
    fun onlyAPictureOfAStandInsLengthIsReadThrough() {
        assertTrue(standIns.mayBe(40))
        assertFalse(standIns.mayBe(39))
        assertFalse(standIns.mayBe(41))
        assertFalse(standIns.mayBe(0))
        assertEquals(40, standIns.longest)
    }

    @Test
    fun navidromesRecordIsKnown() {
        assertTrue(StandInCovers.mayBe(69_228))
        assertEquals(69_228, StandInCovers.longest)
        // A real cover that happens to be as long is still a cover.
        assertFalse(StandInCovers.isStandIn(ByteArray(69_228)))
    }

    @Test
    fun digestsAreLowerCaseHex() {
        // The SHA-256 of "abc", from FIPS 180-2.
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256("abc".encodeToByteArray()))
    }
}
