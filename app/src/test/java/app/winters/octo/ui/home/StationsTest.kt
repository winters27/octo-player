package app.winters.octo.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun aStationTheServerNoLongerHasSaysSo() {
        assertEquals("Chill mix isn't on the server any more", stationGoneLine("Chill mix"))
    }
}
