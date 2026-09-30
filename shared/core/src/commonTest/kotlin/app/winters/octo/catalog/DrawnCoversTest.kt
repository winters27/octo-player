package app.winters.octo.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawnCoversTest {
    private val station = "or3kZ9QpLmN2xY7wV1bC0a"
    private val mix = "og8Hf2Kd9LzQw3Rt5Yu7Ip"
    private val day = 24L * 60 * 60 * 1000

    // 2026-09-29T12:00Z, on day 20725.
    private val noon = 1_790_683_200_000L

    @Test
    fun octoStationsAndMixesAreDrawn() {
        assertTrue(isDrawnCoverId(station))
        assertTrue(isDrawnCoverId(mix))
    }

    @Test
    fun libraryCoversAreNot() {
        // Navidrome's cover ids, a Navidrome playlist's id, and Octo's fixed placeholder.
        for (id in listOf("al-27olR01OlsFec4YYFzCF6M_6ab000f2", "pl-08q5UDZiuD3n5Ct2m6J6Tu_0", "08q5UDZiuD3n5Ct2m6J6Tu", "mf-1", "octo-radio", "tr-9")) {
            assertFalse(id, isDrawnCoverId(id))
        }
    }

    @Test
    fun onlyTheWholeShapeCounts() {
        assertFalse(isDrawnCoverId("or3kZ9QpLmN2xY7wV1bC0"))
        assertFalse(isDrawnCoverId("or3kZ9QpLmN2xY7wV1bC0ab"))
        assertFalse(isDrawnCoverId("or3kZ9QpLmN2xY7wV1bC-a"))
        assertFalse(isDrawnCoverId("oa3kZ9QpLmN2xY7wV1bC0a"))
        assertFalse(isDrawnCoverId(""))
    }

    @Test
    fun aDayIsOneBucketInUtc() {
        assertEquals(0L, drawnCoverDay(0))
        assertEquals(0L, drawnCoverDay(day - 1))
        assertEquals(1L, drawnCoverDay(day))
        assertEquals(20725L, drawnCoverDay(noon))
    }

    @Test
    fun theStampCarriesTheVersionAndTheDay() {
        assertEquals("v$DRAWN_COVER_VERSION:20725", drawnCoverStamp(noon))
        assertEquals(drawnCoverStamp(20725 * day), drawnCoverStamp(20726 * day - 1))
        assertNotEquals(drawnCoverStamp(20725 * day), drawnCoverStamp(20726 * day))
    }
}
