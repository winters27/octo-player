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
    fun noCoverIdMeansNoOnlineCover() {
        assertNull(onlineArtwork("server:x", null))
        assertNull(onlineArtwork("server:x", ""))
    }

    @Test
    fun unknownIsNull() {
        assertNull(ArtworkRef.decode("ftp:x"))
        assertNull(ArtworkRef.decode(null))
    }
}
