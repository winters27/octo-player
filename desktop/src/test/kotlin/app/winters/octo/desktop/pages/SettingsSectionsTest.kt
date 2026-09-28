package app.winters.octo.desktop.pages

import androidx.compose.ui.unit.dp
import app.winters.octo.design.SettingsSize
import app.winters.octo.design.Space
import app.winters.octo.desktop.settings.AmbienceStyle
import app.winters.octo.desktop.settings.Appearance
import app.winters.octo.desktop.ui.PageSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

// The Settings and Sound pages' list of sections, and the choices the
// Settings page folds into one control.
class SettingsSectionsTest {
    // Four sections 300 px tall, the list showing 800 px of them.
    private fun spans(scrolled: Int) = (0 until 4).map { Span(it, it * 300 - scrolled, (it + 1) * 300 - scrolled) }
        .filter { it.bottom > 0 && it.top < 800 }

    @Test
    fun atTheTopTheFirstSectionIsMarked() {
        assertEquals(0, sectionInView(spans(0), 800, atEnd = false, jumped = null))
        // Even when the second section is the one near the top line: a
        // short first section is still the one being read at the top.
        val short = listOf(Span(0, 0, 60), Span(1, 60, 500))
        assertEquals(0, sectionInView(short, 800, atEnd = false, jumped = null))
    }

    @Test
    fun scrollingMarksTheSectionAtTheTop() {
        assertEquals(0, sectionInView(spans(200), 800, atEnd = false, jumped = null))
        assertEquals(1, sectionInView(spans(250), 800, atEnd = false, jumped = null))
        assertEquals(2, sectionInView(spans(620), 800, atEnd = false, jumped = null))
    }

    @Test
    fun atTheEndTheLastSectionIsMarked() {
        assertEquals(3, sectionInView(spans(400), 800, atEnd = true, jumped = null))
    }

    @Test
    fun aSectionPickedInTheListStaysMarkedWhileItShows() {
        // Picked, but the list can go no further than showing it low down.
        assertEquals(2, sectionInView(spans(400), 800, atEnd = true, jumped = 2))
        // Scrolled out of sight, the picking no longer counts.
        assertEquals(3, sectionInView(spans(1000).filter { it.index >= 3 }, 800, atEnd = true, jumped = 1))
    }

    @Test
    fun anEmptyListMarksTheFirst() {
        assertEquals(0, sectionInView(emptyList(), 800, atEnd = false, jumped = null))
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
    fun theServerIsDescribedInPlainWords() {
        assertEquals("Offers synced lyrics and adding songs you find online to your library.", serverOffers(lyrics = true, adds = true))
        assertEquals("Offers synced lyrics.", serverOffers(lyrics = true, adds = false))
        assertNull(serverOffers(lyrics = false, adds = false))
    }
}
