package app.winters.octo.desktop.ui

import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.theme.AccentChoice
import app.winters.octo.theme.OctoAccentArgb
import app.winters.octo.theme.ThemeChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ThemeStateTest {
    @get:Rule val folder = TemporaryFolder()

    private fun store() = SettingsStore(File(folder.root, "octo/${SettingsStore.FILE_NAME}"))

    private fun <T> waiting(block: suspend () -> T): T = runBlocking { withTimeout(10_000) { block() } }

    // A pick draws at once; with Match on the computer's mode picks the Day
    // or Night theme, and a flip of the mode is one switch.
    @Test
    fun thePaletteFollowsThePickAndTheDevice() {
        val settings = store()
        val dark = MutableStateFlow(true)
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val theme = ThemeState(settings.state, dark, flowOf(null), scope)
        assertEquals(ThemeChoice.Octo, theme.palette.value.theme)
        settings.pickTheme(ThemeChoice.Paper)
        assertEquals(ThemeChoice.Paper, waiting { theme.palette.first { it.theme == ThemeChoice.Paper } }.theme)
        settings.setThemeMatch(true, dark.value)
        assertEquals(ThemeChoice.Octo, waiting { theme.palette.first { it.theme == ThemeChoice.Octo } }.theme)
        dark.value = false
        assertEquals(ThemeChoice.Paper, waiting { theme.palette.first { it.theme == ThemeChoice.Paper } }.theme)
        assertTrue(theme.palette.value.light)
        dark.value = true
        assertFalse(waiting { theme.palette.first { !it.light } }.light)
        scope.cancel()
    }

    // The playing cover's color moves the accent only while the accent
    // follows the art.
    @Test
    fun theCoverColorMovesOnlyTheArtAccent() {
        val settings = store()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val theme = ThemeState(settings.state, MutableStateFlow(true), flowOf(0xFF3366CC.toInt()), scope)
        assertEquals(OctoAccentArgb, theme.palette.value.accents.accent)
        settings.update { it.copy(appearance = it.appearance.copy(accent = AccentChoice.FromArt)) }
        val followed = waiting { theme.palette.first { it.accents.accent != OctoAccentArgb } }
        assertNotEquals(OctoAccentArgb, followed.accents.accent)
        scope.cancel()
    }

    @Test
    fun theFrameIsDarkOverArtAndOnDarkThemes() {
        val light = paletteFor(store().current.copy(appearance = store().current.appearance.copy(theme = ThemeChoice.Daylight)), true)
        assertFalse(chromeDark(light, fullPlayer = false, signedOut = false))
        assertTrue(chromeDark(light, fullPlayer = true, signedOut = false))
        assertTrue(chromeDark(light, fullPlayer = false, signedOut = true))
        assertTrue(chromeDark(paletteFor(store().current, true), fullPlayer = false, signedOut = false))
    }
}
