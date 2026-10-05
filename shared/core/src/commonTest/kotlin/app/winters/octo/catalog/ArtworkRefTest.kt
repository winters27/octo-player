package app.winters.octo.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkRefTest {
    @Test
    fun roundTrips() {
        val art = ArtworkRef.Device("album:5", "content://media/external/audio/media/9")
        assertEquals(art, ArtworkRef.decode(art.encode()))
    }

    @Test
    fun serverCoverRoundTripsWithAPortInItsSource() {
        val art = ArtworkRef.Server("server:10.0.0.5:4533", "al-27olR01OlsFec4YYFzCF6M_6ab000f2")
        assertEquals("server:server:10.0.0.5:4533|al-27olR01OlsFec4YYFzCF6M_6ab000f2", art.encode())
        assertEquals(art, ArtworkRef.decode(art.encode()))
    }

    @Test
    fun aCoverFoundOnlineRoundTripsAndStaysOnline() {
        val stored = onlineArtwork("server:10.0.0.5:4533", "tr-9")
        assertEquals("online:server:10.0.0.5:4533|tr-9", stored)
        val art = ArtworkRef.decode(stored) as ArtworkRef.Server
        assertTrue(art.online)
        assertEquals("server:10.0.0.5:4533", art.sourceId)
        assertEquals("tr-9", art.coverId)
    }

    @Test
    fun aLibraryCoverIsNotOnline() {
        assertFalse((ArtworkRef.decode("server:server:x|al-1") as ArtworkRef.Server).online)
    }

    @Test
    fun aStoredStationCoverDecodesAsItWasAndIsDrawn() {
        // As the phone has always written a station's cover.
        val stored = "server:server:10.0.0.5:4533|or3kZ9QpLmN2xY7wV1bC0a"
        val art = ArtworkRef.decode(stored) as ArtworkRef.Server
        assertEquals(ArtworkRef.Server("server:10.0.0.5:4533", "or3kZ9QpLmN2xY7wV1bC0a"), art)
        assertTrue(art.drawn)
        assertFalse(art.online)
        assertEquals(stored, art.encode())
    }

    @Test
    fun libraryAndOnlineCoversAreNotDrawn() {
        assertFalse((ArtworkRef.decode("server:server:x|al-1") as ArtworkRef.Server).drawn)
        assertFalse((ArtworkRef.decode("online:server:x|tr-9") as ArtworkRef.Server).drawn)
        // Something found online is never Octo's own list, whatever its id.
        assertFalse(ArtworkRef.Server("server:x", "og8Hf2Kd9LzQw3Rt5Yu7Ip", online = true).drawn)
    }

    @Test
    fun noCoverIdMeansNoOnlineCover() {
        assertNull(onlineArtwork("server:x", null))
        assertNull(onlineArtwork("server:x", ""))
    }

    @Test
    fun aCoverWithAFallbackRoundTrips() {
        val art = ArtworkRef.Server("server:10.0.0.5:4533", "al-27olR01OlsFec4YYFzCF6M_6ab000f2", fallbackId = "mf-6vmwLNYasJeYulqr0ZxrZJ_2298d43e")
        assertEquals("server:server:10.0.0.5:4533|al-27olR01OlsFec4YYFzCF6M_6ab000f2|mf-6vmwLNYasJeYulqr0ZxrZJ_2298d43e", art.encode())
        assertEquals(art, ArtworkRef.decode(art.encode()))
    }

    @Test
    fun aFallbackThatIsTheCoverItselfIsNotWritten() {
        assertEquals("server:server:x|al-1", ArtworkRef.Server("server:x", "al-1", fallbackId = "al-1").encode())
        assertEquals("server:server:x|al-1", ArtworkRef.Server("server:x", "al-1", fallbackId = "").encode())
    }

    @Test
    fun aCoverStoredBeforeFallbacksHasNone() {
        assertNull((ArtworkRef.decode("server:server:x|al-1") as ArtworkRef.Server).fallbackId)
        assertNull((ArtworkRef.decode("online:server:x|tr-9") as ArtworkRef.Server).fallbackId)
    }

    @Test
    fun unknownIsNull() {
        assertNull(ArtworkRef.decode("ftp:x"))
        assertNull(ArtworkRef.decode(null))
    }
}
