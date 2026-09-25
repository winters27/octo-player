package app.winters.octo.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun unknownIsNull() {
        assertNull(ArtworkRef.decode("ftp:x"))
        assertNull(ArtworkRef.decode(null))
    }
}
