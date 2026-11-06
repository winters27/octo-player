package app.winters.octo.ui.settings

import app.winters.octo.backup.Backup
import app.winters.octo.backup.BackupRead
import app.winters.octo.backup.PlayerBackup
import app.winters.octo.backup.decodeBackup
import app.winters.octo.backup.encodeBackup
import app.winters.octo.player.PlayerPrefs
import app.winters.octo.theme.AccentChoice
import app.winters.octo.theme.FixedAccents
import app.winters.octo.theme.ThemeChoice
import app.winters.octo.theme.resolvePalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class GlassTintTest {
    @Test
    fun theGlassKeepsTheThemesOwnAccentUntilItIsAskedToTakeTheAccent() {
        for (theme in ThemeChoice.entries) {
            val own = resolvePalette(theme, AccentChoice.Octo, null, false).accents.accent
            for (choice in FixedAccents.filter { it != AccentChoice.Octo }) {
                val off = resolvePalette(theme, choice, null, false)
                val on = resolvePalette(theme, choice, null, true)
                assertNotEquals("${theme.label} ${choice.label} accent", own, off.accents.accent)
                assertEquals("${theme.label} ${choice.label} glass off", own, off.accents.glassAccent)
                assertEquals("${theme.label} ${choice.label} glass on", on.accents.accent, on.accents.glassAccent)
            }
        }
    }

    @Test
    fun theGlassIsLeftAloneByDefaultAndTheChoiceIsBackedUp() {
        assertFalse(PlayerPrefs().glassTint)
        val read = decodeBackup(encodeBackup(Backup(player = PlayerBackup(PlayerPrefs(glassTint = true))))) as BackupRead.Read
        assertEquals(true, read.backup.player?.prefs?.glassTint)
    }
}
