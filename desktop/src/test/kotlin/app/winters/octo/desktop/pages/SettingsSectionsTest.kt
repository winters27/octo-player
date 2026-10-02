package app.winters.octo.desktop.pages

import androidx.compose.ui.unit.dp
import app.winters.octo.design.SettingsSize
import app.winters.octo.design.Space
import app.winters.octo.desktop.FakeServer
import app.winters.octo.desktop.settings.AmbienceStyle
import app.winters.octo.desktop.settings.Appearance
import app.winters.octo.desktop.ui.PageSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

// The Settings and Sound pages' sections, and the choices the Settings
// page folds into one control.
class SettingsSectionsTest {
    @Test
    fun aPageOpensOnTheSectionLastShown() {
        val keys = listOf("servers", "look", "discord", "about")
        assertEquals(2, shownSection(keys, "discord"))
    }

    @Test
    fun aPageOpensOnItsFirstSectionAtFirstOrWhenTheLastIsGone() {
        val keys = listOf("servers", "look", "about")
        assertEquals(0, shownSection(keys, null))
        // A build without Discord has no such section any more.
        assertEquals(0, shownSection(keys, "discord"))
    }

    @Test
    fun onAWidePageTheListAndColumnSitTogetherInTheMiddle() {
        // Sidebar 240 on a 1980 window.
        val page = 1740.dp
        val side = groupSide(page)
        // The list's words start `side` in; the column runs its full width
        // after the gap, and the rows' words end `side` from the right.
        val column = page - (side - Space.M) - SettingsSize.Nav - Space.Wide - (side - Space.M)
        assertEquals(SettingsSize.Column.value, column.value, 0.01f)
        val left = side
        val right = page - (side - Space.M) - Space.M
        assertEquals(left.value, (page - right).value, 0.01f)
        // With no room to spare, the page's own margin.
        assertEquals(PageSide, groupSide(PageSide * 2 + SettingsSize.Nav + Space.Wide + SettingsSize.ColumnMin))
    }

    @Test
    fun ambienceIsOneChoiceOverTheSwitchAndTheLook() {
        val glow = Appearance(ambientGlow = true, ambience = AmbienceStyle.Glow)
        assertEquals(AmbienceStyle.Glow, ambienceOf(glow))
        val off = withAmbience(glow, null)
        assertNull(ambienceOf(off))
        assertFalse(off.ambientGlow)
        // Off keeps the look it had, so on again is the same look.
        assertEquals(AmbienceStyle.Glow, off.ambience)
        val immersive = withAmbience(off, AmbienceStyle.Immersive)
        assertEquals(AmbienceStyle.Immersive, ambienceOf(immersive))
        assertEquals(Appearance(ambientGlow = true, ambience = AmbienceStyle.Immersive), immersive)
    }

    @Test
    fun theServerIsDescribedInPlainWords() = FakeServer().use { server ->
        assertEquals("Offers synced lyrics and adding songs you find online to your library.", serverOffers(server.connection(listOf("songLyrics:1", "octoAcquisitions:1"))))
        assertEquals("Offers synced lyrics.", serverOffers(server.connection(listOf("octoLyrics:1"))))
        assertNull(serverOffers(server.connection()))
    }
}
