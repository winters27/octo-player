package app.winters.octo.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemesTest {
    @Test
    fun matchUsesTheSlotForTheDeviceMode() {
        val on = ThemeSettings(theme = ThemeChoice.Paper, light = ThemeChoice.Daylight, dark = ThemeChoice.Moss, match = true)
        assertEquals(ThemeChoice.Moss, activeTheme(on, deviceDark = true))
        assertEquals(ThemeChoice.Daylight, activeTheme(on, deviceDark = false))
        val off = on.copy(match = false)
        assertEquals(ThemeChoice.Paper, activeTheme(off, deviceDark = true))
        assertEquals(ThemeChoice.Paper, activeTheme(off, deviceDark = false))
    }

    @Test
    fun pickingFillsTheSlot() {
        val start = ThemeSettings(theme = ThemeChoice.Octo, light = ThemeChoice.Daylight, dark = ThemeChoice.Octo)
        for (choice in ThemeChoice.entries) {
            val picked = pickTheme(start, choice)
            assertEquals(choice, picked.theme)
            if (choice.light) {
                assertEquals(choice, picked.light)
                assertEquals(start.dark, picked.dark)
            } else {
                assertEquals(choice, picked.dark)
                assertEquals(start.light, picked.light)
            }
        }
    }

    @Test
    fun turningMatchOffKeepsWhatShows() {
        val on = ThemeSettings(theme = ThemeChoice.Paper, light = ThemeChoice.Daylight, dark = ThemeChoice.Moss, match = true)
        assertEquals(ThemeChoice.Moss, setMatch(on, on = false, deviceDark = true).theme)
        assertEquals(ThemeChoice.Daylight, setMatch(on, on = false, deviceDark = false).theme)
        assertEquals(false, setMatch(on, on = false, deviceDark = true).match)
        assertEquals(true, setMatch(on.copy(match = false), on = true, deviceDark = true).match)
    }

    @Test
    fun unknownAndWrongKindNamesCoerce() {
        val s = themeSettings("Neon", "Moss", "Paper", false)
        assertEquals(ThemeChoice.Octo, s.theme)
        assertEquals(ThemeChoice.Daylight, s.light)
        assertEquals(ThemeChoice.Octo, s.dark)
    }

    @Test
    fun storedNamesNeverChange() {
        assertEquals(listOf("Octo", "Moss", "Dusk", "Daylight", "Paper"), ThemeChoice.entries.map { it.name })
    }
}
