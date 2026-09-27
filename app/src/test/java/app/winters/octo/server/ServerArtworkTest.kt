package app.winters.octo.server

import app.winters.octo.catalog.ArtworkRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerArtworkTest {
    private val library = ArtworkRef.Server("server:music.example", "al-1")
    private val found = ArtworkRef.Server("server:music.example", "al-1", online = true)

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
}
