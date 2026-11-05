package app.winters.octo.desktop.a11y

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import app.winters.octo.audio.AutomixSettings
import app.winters.octo.audio.DspSettings
import app.winters.octo.audio.EqSettings
import app.winters.octo.audio.ReplayGainSettings
import app.winters.octo.desktop.audio.SoundTarget
import app.winters.octo.desktop.hotkeys.HotkeyAction
import app.winters.octo.desktop.nav.SettingsKey
import app.winters.octo.desktop.nav.settingsPages
import app.winters.octo.desktop.pages.DesktopSettingsIndex
import app.winters.octo.desktop.pages.RowId
import app.winters.octo.desktop.pages.RowTitle
import app.winters.octo.desktop.pages.SettingsHere
import app.winters.octo.desktop.pages.searchDesktopSettings
import app.winters.octo.desktop.pages.settingsCardOf
import app.winters.octo.desktop.pages.settingsIndexFor
import app.winters.octo.desktop.player.DesktopPlayer
import app.winters.octo.desktop.player.SilentPlayer
import app.winters.octo.desktop.settings.AmbienceStyle
import app.winters.octo.desktop.settings.AppSettings
import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.desktop.settings.SHARED_SOUND
import app.winters.octo.sound.Crossfeed
import app.winters.octo.sound.CrossfeedPreset
import app.winters.octo.sound.EqMode
import app.winters.octo.sound.ReplayGainMode
import app.winters.octo.sound.SoundSettings
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// Settings' search index: how it ranks, where a hit lands, what each
// computer and build shows, and that every row the pages draw is in it.
class DesktopSettingsIndexTest {
    @get:Rule val folder = TemporaryFolder()

    // A window with everything: Windows, a tray, notifications, Discord, the
    // sound engine and a family server.
    private val everything = SettingsHere(DesktopOs.Windows, tray = true, notifications = true, discord = true, engine = true, family = true)

    private fun titles(query: String, here: SettingsHere = everything) = searchDesktopSettings(query, here).map { it.entry.title }

    private fun shown(here: SettingsHere) = settingsIndexFor(here).map { it.title }.toSet()

    @Test
    fun aTitleStartBeatsATitleBeatsOtherWordsBeatsTheSummary() {
        val found = titles("speed")
        fun List<String>.at(title: String) = indexOf(title).also { assertTrue("$title is found: $this", it >= 0) }
        // "Speed" starts its title; "Drift speed" holds the word; "Match
        // tempo" has it among its other words.
        assertTrue(found.toString(), found.at("Speed") < found.at("Drift speed"))
        assertTrue(found.toString(), found.at("Drift speed") < found.at("Match tempo"))
        assertEquals("Speed and pitch", found.first())
        // "Find lyrics online" has "words" among its other words; "Brightness
        // cap" only in its line.
        val words = titles("words")
        assertTrue(words.toString(), words.at("Find lyrics online") < words.at("Brightness cap"))
    }

    @Test
    fun theWordsPeopleTypeFindTheirSettings() {
        assertEquals("Accent color", titles("accent").first())
        assertTrue("the colour spelling", "Accent color" in titles("colour"))
        assertTrue("Accent color" in titles("dark"))
        assertTrue("Crossfade" in titles("gap"))
        assertTrue("Crossfade" in titles("fade"))
        assertTrue("Equalizer" in titles("eq"))
        assertTrue(START_WITH in titles("autostart"))
        assertEquals(emptyList<String>(), titles("   "))
    }

    @Test
    fun aHitCarriesItsPageAndRow() {
        val calm = searchDesktopSettings("calm motion", everything).first()
        assertEquals(SettingsKey.Look, calm.entry.page)
        assertEquals("motion.calm-motion", calm.entry.row)
        assertEquals("Appearance · Motion", calm.place)
        val eq = searchDesktopSettings("equalizer", everything).first()
        assertEquals(SettingsKey.Sound to "eq", eq.entry.page to eq.entry.row)
        val page = searchDesktopSettings("keyboard", everything).first { it.entry.row == null }
        assertEquals(SettingsKey.Keys, page.entry.page)
        // A row a setting keeps hidden is still found, by its own id.
        val strength = searchDesktopSettings("strength", everything).first()
        assertEquals("window-background.strength", strength.entry.row)
    }

    @Test
    fun eachComputerAndBuildFindsOnlyWhatItShows() {
        val windows = shown(everything)
        val mac = shown(everything.copy(os = DesktopOs.Mac))
        val linux = shown(everything.copy(os = DesktopOs.Linux))
        assertTrue(START_WITH in windows)
        assertFalse(START_WITH in mac || START_WITH in linux)
        assertTrue("Install updates" in windows && "Install updates" !in linux)
        assertTrue("Use these keys from any app" in windows && "Use these keys from any app" !in mac)
        assertTrue("Use the system title bar" in linux && "Use the system title bar" !in mac)
        assertTrue("Window" in mac && "Window and startup" !in mac && "Window and startup" in windows)

        val noTray = shown(everything.copy(tray = false, notifications = false))
        assertFalse("Start in the tray" in noTray || "Keep playing when the window closes" in noTray || "Now playing notifications" in noTray)
        assertTrue("Start in the tray" in windows && "Keep playing when the window closes" in windows && "Now playing notifications" in windows)

        val noDiscord = shown(everything.copy(discord = false))
        assertTrue("Show what you're playing" in windows && "Discord" in windows)
        assertFalse("Show what you're playing" in noDiscord || "Discord" in noDiscord)

        val noEngine = shown(everything.copy(engine = false))
        assertTrue("Equalizer" in windows && "Longest blend" in windows && "Nothing to shape" !in windows)
        assertFalse("Equalizer" in noEngine || "Longest blend" in noEngine || "Speed" in noEngine)
        assertTrue("Nothing to shape" in noEngine)

        val plain = shown(everything.copy(family = false))
        assertTrue("Away from home" in windows && "Away from home" !in plain)
    }

    @Test
    fun eachRowIsListedOnce() {
        val places = DesktopSettingsIndex.map { it.page to it.row }
        assertEquals(places.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.toString(), places.size, places.toSet().size)
    }

    // The engine's sound settings reach nothing; enough for Settings to
    // offer what the engine shapes.
    private class ShapingPlayer(inner: DesktopPlayer = SilentPlayer()) : DesktopPlayer by inner, SoundTarget {
        override val deviceKey = MutableStateFlow<String?>("spk")
        override fun shape(eq: EqSettings, replayGain: ReplayGainSettings, dsp: DspSettings) = Unit
        override fun setCrossfade(ms: Int) = Unit
        override fun setAutomix(settings: AutomixSettings) = Unit
        override fun setSpeed(speed: Float, pitch: Float) = Unit
    }

    // Every row a setting can hide, shown.
    private val everyRowShown: (AppSettings) -> AppSettings = {
        it.copy(
            appearance = it.appearance.copy(ambientGlow = true, ambience = AmbienceStyle.Immersive, calmMotion = false, wash = it.appearance.wash.copy(moving = true)),
            discord = it.discord.copy(on = true, firstLine = "{title}"),
            hotkeys = it.hotkeys.copy(on = true, keys = mapOf(HotkeyAction.Like.id to "Ctrl+Alt+L")),
            playback = it.playback.copy(crossfadeSeconds = 6, smartTransitions = true, speed = 1.25f),
            sound = it.sound.copy(
                profiles = mapOf(
                    SHARED_SOUND to SoundSettings(
                        mode = EqMode.Parametric,
                        autoPreamp = false,
                        replayGain = ReplayGainMode.Track,
                        mono = true,
                        crossfeed = Crossfeed(on = true, preset = CrossfeedPreset.Custom),
                    ),
                ),
            ),
        )
    }

    // Where a row is found: its own id, or the card holding a title's switch.
    private fun SemanticsNode.placeId(): String? {
        var at: SemanticsNode? = this
        while (at != null) {
            at.config.getOrNull(RowId)?.let { return it }
            at = at.parent
        }
        return null
    }

    @Test(timeout = 300_000)
    fun everyRowIsFindable() {
        // A build that carries a Discord application, so its page shows.
        System.setProperty("octo.discordAppId", "1234567890123456789")
        try {
            val missing = sortedSetOf<String>()
            val seen = mutableSetOf<String>()
            val variants = listOf(
                Triple(DesktopOs.Windows, everyRowShown, true),
                Triple(DesktopOs.Windows, { it: AppSettings -> it }, false),
                Triple(DesktopOs.Mac, everyRowShown, false),
                Triple(DesktopOs.Linux, everyRowShown, true),
            )
            for ((os, look, family) in variants) {
                A11yScene(
                    folder.newFolder(),
                    reduceMotion = true,
                    look = look,
                    extensions = if (family) listOf("octoFamily:1") else listOf(),
                    player = ShapingPlayer(),
                    os = os,
                    withSystem = true,
                ).use { s ->
                    val system = s.system!!
                    val here = SettingsHere(
                        os,
                        tray = system.trayAvailable,
                        notifications = system.notificationsAvailable,
                        discord = system.discord != null,
                        engine = s.app.sound != null,
                        family = s.app.connection?.family == true,
                    )
                    val index = settingsIndexFor(here)
                    for (page in settingsPages(os == DesktopOs.Windows, here.discord)) {
                        s.onUi { s.app.openSettings(page.key) }
                        s.render(4)
                        for (node in s.nodes()) {
                            val title = node.config.getOrNull(RowTitle) ?: continue
                            seen += title.stable
                            val id = node.placeId()
                            if (id == null) {
                                missing += "${page.key}: \"${title.title}\" has no id ($os)"
                                continue
                            }
                            // A row whose title is data is found by its card.
                            val wanted = if (title.stable.startsWith("[")) settingsCardOf(id) ?: id else id
                            if (index.none { it.page == page.key && it.row == wanted }) {
                                missing += "${page.key} $wanted \"${title.stable}\" ($os)"
                            }
                        }
                    }
                }
            }
            assertTrue("rows with no index entry:\n" + missing.joinToString("\n"), missing.isEmpty())
            // The walk reached the rows that need the system, the engine,
            // Discord and every hidden row shown.
            listOf(
                "Mini player", "Media keys", "Use these keys from any app", "[each global shortcut]", "Show what you're playing", "Friends' list shows",
                "Bands", "No filters yet", "Preset", "Longest blend", "Movement", "Drift speed", "Use the system title bar", "Now playing notifications",
            ).forEach { assertTrue("$it was drawn: $seen", it in seen) }
        } finally {
            System.clearProperty("octo.discordAppId")
        }
    }

    private companion object {
        const val START_WITH = "Start with Windows"
    }
}
