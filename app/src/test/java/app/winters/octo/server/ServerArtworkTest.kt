package app.winters.octo.server

import app.winters.octo.catalog.ArtworkRef
import app.winters.octo.catalog.DRAWN_COVER_VERSION
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerArtworkTest {
    private val library = ArtworkRef.Server("server:music.example", "al-1")
    private val found = ArtworkRef.Server("server:music.example", "al-1", online = true)
    private val station = ArtworkRef.Server("server:music.example", "or3kZ9QpLmN2xY7wV1bC0a")
    private val day = 24L * 60 * 60 * 1000

    // 2026-09-29T12:00Z, on day 20725.
    private val noon = 1_790_683_200_000L

    @Test
    fun libraryCoversKeepTheKeysTheyWereCachedUnder() {
        // The same keys as before covers found online were kept apart, so
        // nothing already on the phone is fetched again.
        assertEquals("server-art:server:music.example|al-1", serverCoverKey(library))
        assertEquals("server-art:server:music.example|al-1|300", serverCoverDiskKey(library, 300))
    }

    @Test
    fun coversFoundOnlineStartAfreshUnderTheirVersion() {
        assertEquals("online-art:v$ONLINE_COVER_VERSION:server:music.example|al-1", serverCoverKey(found))
        assertEquals("online-art:v$ONLINE_COVER_VERSION:server:music.example|al-1|300", serverCoverDiskKey(found, 300))
        assertTrue(ONLINE_COVER_VERSION >= 2)
    }

    @Test
    fun theSameCoverIdOnlineAndInTheLibraryNeverShareACacheEntry() {
        assertNotEquals(serverCoverKey(library), serverCoverKey(found))
        for (px in listOf(150, 300, 600, 1200)) assertNotEquals(serverCoverDiskKey(library, px), serverCoverDiskKey(found, px))
    }

    @Test
    fun aStationsCoverIsKeptUnderItsVersionAndTheDay() {
        assertEquals("drawn-art:v$DRAWN_COVER_VERSION:20725:server:music.example|or3kZ9QpLmN2xY7wV1bC0a", serverCoverKey(station, noon))
        assertEquals("drawn-art:v$DRAWN_COVER_VERSION:20725:server:music.example|or3kZ9QpLmN2xY7wV1bC0a|300", serverCoverDiskKey(station, 300, noon))
    }

    @Test
    fun aStationsCoverIsAskedForAgainTheNextDay() {
        assertEquals(serverCoverKey(station, 20725 * day), serverCoverKey(station, 20726 * day - 1))
        assertNotEquals(serverCoverKey(station, 20725 * day), serverCoverKey(station, 20726 * day))
    }

    @Test
    fun aStationsCoverNeverUsesTheKeyItWasCachedUnderForever() {
        // What the phone kept a station's cover under before, for good.
        assertNotEquals("server-art:server:music.example|or3kZ9QpLmN2xY7wV1bC0a", serverCoverKey(station, noon))
    }

    @Test
    fun libraryAndOnlineCoversDoNotChangeWithTheDay() {
        assertEquals(serverCoverKey(library, 0), serverCoverKey(library, noon))
        assertEquals(serverCoverKey(found, 0), serverCoverKey(found, noon))
    }
}
