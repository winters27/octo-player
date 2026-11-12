package app.winters.octo.ui.common

import app.winters.octo.server.CopyRunState
import app.winters.octo.server.CopyStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CopyCoverTest {
    private fun run(step: CopyStep, artwork: String? = "server:h|al-1") =
        CopyRunState(1, 1, "acc", "Home", true, true, step, 0, null, null, artwork, 0, 0, false)

    // While songs are still being read, the ring shows its accent note, not
    // the color of whatever song came first.
    @Test
    fun noCoverBeforeAlbumsAreBuilt() {
        assertNull(copyCoverOf(run(CopyStep.Connect)))
        assertNull(copyCoverOf(run(CopyStep.Read)))
        assertNull(copyCoverOf(null))
    }

    @Test
    fun albumsCoversOnceTheyAreBuilt() {
        assertEquals("server:h|al-1", copyCoverOf(run(CopyStep.Build)))
        assertEquals("server:h|al-1", copyCoverOf(run(CopyStep.Save)))
        assertNull(copyCoverOf(run(CopyStep.Build, artwork = null)))
    }
}
