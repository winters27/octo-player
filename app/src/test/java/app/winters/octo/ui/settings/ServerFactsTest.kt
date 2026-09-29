package app.winters.octo.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The Server page's facts about the server, and finding its new rows.
class ServerFactsTest {
    @Test
    fun whatTheServerOffersComesFromItsExtensionsInPlainWords() {
        assertEquals(
            "Offers synced lyrics and adding songs you find online to your library.",
            offersOf(setOf("songLyrics:1", "octoAcquisitions:1", "formPost:1")),
        )
        assertEquals("Offers synced lyrics.", offersOf(setOf("octoLyrics:1")))
        assertNull(offersOf(setOf("formPost:1")))
    }

    @Test
    fun changingThePasswordIsFoundBySearch() {
        val found = searchSettings("password", SettingsIndex.all).map { it.title }
        assertTrue(found.toString(), found.contains("Change password"))
        assertTrue(searchSettings("version", SettingsIndex.all).any { it == SettingsIndex.ServerKind })
    }
}
