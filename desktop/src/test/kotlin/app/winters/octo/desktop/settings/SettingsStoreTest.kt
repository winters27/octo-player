package app.winters.octo.desktop.settings

import app.winters.octo.desktop.system.SystemPrefs
import app.winters.octo.sound.EqFilter
import app.winters.octo.sound.EqMode
import app.winters.octo.sound.FilterType
import app.winters.octo.sound.ReplayGainMode
import app.winters.octo.sound.SoundSettings
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
            appearance = Appearance(ambientGlow = false, glowStrength = 0.8f, ambience = AmbienceStyle.Immersive, ambienceMotion = AmbienceMotion.Full),
            playback = PlaybackPrefs(volume = 0.3f, crossfadeSeconds = 6, outputDevice = "usb:dac", speed = 1.25f, keepPitch = false, pitchSemitones = -2),
            songSort = "Year:desc",
            sidePanel = "queue",
        )
        SettingsStore(file()).update { changed }
        assertEquals(changed, SettingsStore(file()).current)
    }

    // Every setting the Settings and Sound pages change, away from its
    // default, reads back the same.
    @Test
    fun everySettingOnTheSettingsAndSoundPagesSurvivesARestart() {
        val changed = AppSettings(
            systemTitleBar = true,
            appearance = Appearance(
                ambientGlow = true,
                glowStrength = 0.3f,
                calmMotion = true,
                wash = WashPrefs(moving = false, speed = 60, useBpm = false, brightnessCap = 35),
                ambience = AmbienceStyle.Immersive,
                ambienceMotion = AmbienceMotion.Still,
                textSize = 115,
            ),
            playback = PlaybackPrefs(crossfadeSeconds = 4, speed = 0.75f, keepPitch = false, pitchSemitones = 3, autoplay = false),
            lyrics = LyricsPrefs(online = false),
            listening = ListeningPrefs(reportPlays = false, syncQueue = false),
            system = SystemPrefs(closeToTray = true, nowPlayingNotices = true, miniPlayerOpen = true),
            sound = SoundPrefs(
                perOutput = true,
                profiles = mapOf(
                    "usb:dac" to SoundSettings(
                        eqEnabled = true,
                        mode = EqMode.Parametric,
                        filters = listOf(EqFilter(FilterType.LowShelf, 90f, 3f, 0.7f)),
                        preset = null,
                        preampDb = -3f,
                        autoPreamp = false,
                        replayGain = ReplayGainMode.Smart,
                        replayGainPreampDb = 2f,
                        replayGainFallbackDb = -4f,
                        preventClipping = false,
                        limiter = false,
                        balance = -0.2f,
                        mono = true,
                    ),
                ),
                presets = listOf(SavedCurve("Mine", List(10) { it.toFloat() })),
            ),
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
        // OCTO_PROFILE_DIR puts everything in one folder of its own.
        val separate = AppPlaces.forSystem(DesktopOs.Windows, (env + ("OCTO_PROFILE_DIR" to "D:/octo-test"))::get, "C:/Users/b")
        assertEquals(File("D:/octo-test", "config"), separate.config)
        assertEquals(File("D:/octo-test", "cache"), separate.cache)
        assertTrue(separateProfile(mapOf("OCTO_PROFILE_DIR" to "D:/octo-test")::get))
        assertFalse(separateProfile(env::get))
    }

    @Test
    fun aDragIsOneWriteAndNothingWaitingIsLost() {
        val store = SettingsStore(file(), writeDelayMs = 60_000)
        repeat(60) { step -> store.update { it.copy(playback = it.playback.copy(volume = step / 100f)) } }
        // Kept in memory at once, not yet written.
        assertEquals(0.59f, store.current.playback.volume)
        assertFalse(file().exists())
        store.flush()
        assertEquals(0.59f, SettingsStore(file()).current.playback.volume)
    }

    @Test
    fun systemsAreToldApartByName() {
        assertEquals(DesktopOs.Windows, currentOs("Windows 11"))
        assertEquals(DesktopOs.Mac, currentOs("Mac OS X"))
        assertEquals(DesktopOs.Linux, currentOs("Linux"))
    }

    @Test
    fun theFrameIsKeptAndAnOldFileGetsItsDefaults() {
        file().apply { parentFile.mkdirs() }.writeText("""{"sidePanel":"info"}""")
        val old = SettingsStore(file(), 0).current
        assertEquals(240f, old.frame.sidebarWidth)
        assertTrue(old.frame.showTimeLeft)
        val store = SettingsStore(file(), 0)
        store.update { it.copy(frame = it.frame.copy(sidebarRail = true, panelWidth = 400f, pinnedPlaylists = listOf("p2", "p1"), foldedGroups = setOf("library"))) }
        val read = SettingsStore(file(), 0).current.frame
        assertTrue(read.sidebarRail)
        assertEquals(400f, read.panelWidth)
        assertEquals(listOf("p2", "p1"), read.pinnedPlaylists)
        assertEquals(setOf("library"), read.foldedGroups)
    }

    @Test
    fun theAmbienceIsKeptAndAnOldFileKeepsTheGlow() {
        // A file from before Immersive: the glow, as it was.
        file().apply { parentFile.mkdirs() }.writeText("""{"appearance":{"ambientGlow":true,"glowStrength":0.7}}""")
        val old = SettingsStore(file(), 0).current.appearance
        assertEquals(AmbienceStyle.Glow, old.ambience)
        assertEquals(AmbienceMotion.Gentle, old.ambienceMotion)
        assertEquals(0.7f, old.glowStrength)
        SettingsStore(file(), 0).update { it.copy(appearance = it.appearance.copy(ambience = AmbienceStyle.Immersive, ambienceMotion = AmbienceMotion.Still)) }
        val read = SettingsStore(file(), 0).current.appearance
        assertEquals(AmbienceStyle.Immersive, read.ambience)
        assertEquals(AmbienceMotion.Still, read.ambienceMotion)
        assertEquals(0.7f, read.glowStrength)
        // A choice a newer version added reads as the default.
        file().writeText("""{"appearance":{"ambience":"Aurora","ambienceMotion":"Wild"}}""")
        val newer = SettingsStore(file(), 0).current.appearance
        assertEquals(AmbienceStyle.Glow, newer.ambience)
        assertEquals(AmbienceMotion.Gentle, newer.ambienceMotion)
    }
}
