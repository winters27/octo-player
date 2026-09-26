package app.winters.octo.whatsnew

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsNewTest {
    @Test
    fun aFreshInstallHasNoNews() {
        assertFalse(whatsNewDue(lastSeen = null, current = 3, freshInstall = true))
    }

    @Test
    fun theFirstUpdateSinceTheCardExistedShowsIt() {
        assertTrue(whatsNewDue(lastSeen = null, current = 3, freshInstall = false))
    }

    @Test
    fun itShowsOncePerVersion() {
        assertTrue(whatsNewDue(lastSeen = 2, current = 3, freshInstall = false))
        assertFalse(whatsNewDue(lastSeen = 3, current = 3, freshInstall = false))
    }

    @Test
    fun theListIsPlainWords() {
        assertTrue(WhatsNewItems.isNotEmpty())
        // The long dash, by its code, so this file does not hold one.
        val longDash = Char(0x2014)
        WhatsNewItems.forEach { item ->
            assertTrue(item.title.isNotBlank() && item.detail.isNotBlank())
            assertFalse(longDash in item.title || longDash in item.detail)
        }
    }
}
