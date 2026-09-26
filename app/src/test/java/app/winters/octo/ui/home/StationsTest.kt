package app.winters.octo.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StationsTest {
    private val now = 1_000_000_000L

    @Test
    fun loadsWhenNeverLoaded() {
        assertTrue(stationsDue(now, loadedAt = null, failed = false, loading = false))
    }

    @Test
    fun neverLoadsTwiceAtOnce() {
        assertFalse(stationsDue(now, loadedAt = null, failed = true, loading = true))
        assertFalse(stationsDue(now, loadedAt = now - STATIONS_STALE_MS * 2, failed = false, loading = true))
    }

    @Test
    fun loadsAgainAfterAFailure() {
        assertTrue(stationsDue(now, loadedAt = now - 1_000, failed = true, loading = false))
    }

    @Test
    fun keepsAFreshList() {
        assertFalse(stationsDue(now, loadedAt = now - 1_000, failed = false, loading = false))
        assertFalse(stationsDue(now, loadedAt = now - STATIONS_STALE_MS + 1, failed = false, loading = false))
    }

    @Test
    fun loadsAgainOnceStale() {
        assertTrue(stationsDue(now, loadedAt = now - STATIONS_STALE_MS, failed = false, loading = false))
    }

    @Test
    fun retriesATimeoutAFewTimes() {
        for (failures in 1..STATIONS_RETRIES) {
            assertEquals(STATIONS_RETRY_MS, stationsRetryDelay(unreachable = true, failures = failures))
        }
        assertNull(stationsRetryDelay(unreachable = true, failures = STATIONS_RETRIES + 1))
    }

    @Test
    fun neverRetriesAnAnswer() {
        assertNull(stationsRetryDelay(unreachable = false, failures = 1))
    }

    @Test
    fun retriesCoverASlowFirstList() {
        // Each try waits up to 30 s for an answer; together they outlast
        // the two minutes a first list can take.
        val covered = (STATIONS_RETRIES + 1) * 30_000L + STATIONS_RETRIES * STATIONS_RETRY_MS
        assertTrue(covered >= 2 * 60_000L)
    }
}
