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
        assertTrue(WhatsNewGroups.isNotEmpty())
        // The long dashes, by their codes, so this file does not hold one.
        val dashes = listOf(Char(0x2014), Char(0x2013))
        val text = listOf(WhatsNewSummary) + WhatsNewGroups.flatMap { listOf(it.title) + it.lines }
        text.forEach { line ->
            assertTrue(line.isNotBlank())
            assertFalse(line, dashes.any { it in line })
        }
        WhatsNewGroups.forEach { assertTrue(it.title, it.lines.isNotEmpty()) }
    }

    @Test
    fun theHomeCardLineIsShort() {
        assertTrue(WhatsNewSummary.length <= 70)
    }
}
