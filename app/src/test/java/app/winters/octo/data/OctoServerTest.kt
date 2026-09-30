package app.winters.octo.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OctoServerTest {
    @Test
    fun octoAwayFromHomeIsKnownByItsExtensions() {
        assertTrue(isOctoServer(probed = false, serverType = "navidrome", extensions = setOf("octoAcquisitions:1")))
        assertTrue(isOctoServer(probed = false, serverType = "navidrome", extensions = setOf("octoLyrics:1")))
    }

    @Test
    fun octoAtHomeIsKnownByItsStatusPage() {
        assertTrue(isOctoServer(probed = true, serverType = "navidrome", extensions = emptySet()))
    }

    @Test
    fun aServerCallingItselfOctoIsOcto() {
        assertTrue(isOctoServer(probed = false, serverType = "Octo", extensions = emptySet()))
    }

    @Test
    fun plainNavidromeIsNotOcto() {
        val navidrome = setOf("songLyrics:1", "transcodeOffset:1", "formPost:1", "apiKeyAuthentication:1")
        assertFalse(isOctoServer(probed = false, serverType = "navidrome", extensions = navidrome))
        assertFalse(isOctoServer(probed = false, serverType = null, extensions = emptySet()))
    }
}
