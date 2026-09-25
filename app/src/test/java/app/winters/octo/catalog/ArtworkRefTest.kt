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
    fun unknownIsNull() {
        assertNull(ArtworkRef.decode("ftp:x"))
        assertNull(ArtworkRef.decode(null))
    }
}
