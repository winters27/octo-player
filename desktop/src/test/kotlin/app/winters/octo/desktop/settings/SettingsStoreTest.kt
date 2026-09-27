package app.winters.octo.desktop.settings

import app.winters.octo.subsonic.AuthMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SettingsStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private fun file() = File(folder.root, "octo/${SettingsStore.FILE_NAME}")

    @Test
    fun everySettingSurvivesARestart() {
        val changed = AppSettings(
            window = WindowSpot(10f, 20f, 1400f, 900f, maximized = true),
            systemTitleBar = true,
            server = SavedServer("http://music.test/", "winters", AuthMode.LegacyPassword, "navidrome", "0.58.0", true, listOf("songLyrics:1")),
            appearance = Appearance(ambientGlow = false, glowStrength = 0.8f),
            playback = PlaybackPrefs(volume = 0.3f, crossfadeSeconds = 6, gapless = false, replayGain = "album"),
            songSort = "Year:desc",
            sidePanel = "queue",
        )
        SettingsStore(file()).update { changed }
        assertEquals(changed, SettingsStore(file()).current)
    }

    @Test
    fun aMissingFileStartsFromTheDefaults() {
        assertEquals(AppSettings(), SettingsStore(file()).current)
        assertFalse("nothing is written until something changes", file().exists())
    }

    @Test
    fun anUnreadableFileIsSetAsideNotDeleted() {
        file().parentFile.mkdirs()
        file().writeText("{ this is not json")
        val store = SettingsStore(file())
        assertEquals(AppSettings(), store.current)
        val aside = file().parentFile.listFiles().orEmpty().filter { it.name.startsWith("settings.unreadable-") }
        assertEquals(1, aside.size)
        assertEquals("{ this is not json", aside.single().readText())
    }

    @Test
    fun settingsFromANewerVersionStillRead() {
        file().parentFile.mkdirs()
        file().writeText("""{"sidePanel":"lyrics","somethingNew":{"a":1},"appearance":{"glowStrength":0.2,"futureKnob":true}}""")
        val read = SettingsStore(file()).current
        assertEquals("lyrics", read.sidePanel)
        assertEquals(0.2f, read.appearance.glowStrength)
        assertTrue(read.appearance.ambientGlow)
    }

    @Test
    fun noPasswordIsEverWritten() {
        SettingsStore(file()).update { it.copy(server = SavedServer("http://music.test/", "winters")) }
        val text = file().readText()
        assertFalse(text.contains("password", ignoreCase = true))
        assertFalse("no temporary file is left behind", File(file().parentFile, "settings.json.tmp").exists())
    }

    @Test
    fun theSettingsFolderFollowsEachSystem() {
        val env = mapOf("APPDATA" to "C:\\Users\\b\\AppData\\Roaming", "LOCALAPPDATA" to "C:\\Users\\b\\AppData\\Local")
        val windows = AppPlaces.forSystem(DesktopOs.Windows, env::get, "C:\\Users\\b")
        assertEquals(File("C:\\Users\\b\\AppData\\Roaming", "Octo"), windows.config)
        assertEquals(File(File("C:\\Users\\b\\AppData\\Local", "Octo"), "Cache"), windows.cache)
        val mac = AppPlaces.forSystem(DesktopOs.Mac, { null }, "/Users/b")
        assertEquals(File("/Users/b", "Library/Application Support/Octo"), mac.config)
        val linux = AppPlaces.forSystem(DesktopOs.Linux, { null }, "/home/b")
        assertEquals(File("/home/b/.config", "octo"), linux.config)
        assertEquals(File("/home/b/.cache", "octo"), linux.cache)
        val xdg = AppPlaces.forSystem(DesktopOs.Linux, mapOf("XDG_CONFIG_HOME" to "/x/conf")::get, "/home/b")
        assertEquals(File("/x/conf", "octo"), xdg.config)
    }

    @Test
    fun systemsAreToldApartByName() {
        assertEquals(DesktopOs.Windows, currentOs("Windows 11"))
        assertEquals(DesktopOs.Mac, currentOs("Mac OS X"))
        assertEquals(DesktopOs.Linux, currentOs("Linux"))
    }
}
