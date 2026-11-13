package app.winters.octo.desktop.a11y

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import app.winters.octo.audio.AutomixSettings
import app.winters.octo.audio.DspSettings
import app.winters.octo.audio.EqSettings
import app.winters.octo.audio.ReplayGainSettings
import app.winters.octo.desktop.audio.SoundTarget
import app.winters.octo.desktop.nav.Page
import app.winters.octo.desktop.nav.SettingsKey
import app.winters.octo.desktop.nav.soundSectionPlace
import app.winters.octo.desktop.pages.LocalRowFlashMs
import app.winters.octo.desktop.pages.RowFlash
import app.winters.octo.desktop.pages.RowId
import app.winters.octo.desktop.pages.RowTitle
import app.winters.octo.desktop.player.DesktopPlayer
import app.winters.octo.desktop.player.SilentPlayer
import app.winters.octo.desktop.settings.AppSettings
import app.winters.octo.playback.BetweenSongs
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// Settings' pages in the real window: each a title, its line and titled
// cards; a row asked for brought into view and lit (or its card, or the
// row that shows a hidden one); the cards that need the sound engine; and
// Remove all asking first.
class SettingsPagesTest {
    @get:Rule val folder = TemporaryFolder()

    // The engine's sound settings reach nothing; enough for Settings to
    // offer what the engine shapes.
    private class ShapingPlayer(inner: DesktopPlayer = SilentPlayer()) : DesktopPlayer by inner, SoundTarget {
        override val deviceKey = MutableStateFlow<String?>("spk")
        override fun shape(eq: EqSettings, replayGain: ReplayGainSettings, dsp: DspSettings) = Unit
        override fun setCrossfade(ms: Int) = Unit
        override fun setAutomix(settings: AutomixSettings) = Unit
        override fun setSpeed(speed: Float, pitch: Float) = Unit
    }

    private fun scene(
        engine: Boolean = false,
        flashMs: Long = 60_000,
        look: (AppSettings) -> AppSettings = { it },
    ) = A11yScene(
        folder.newFolder(),
        reduceMotion = true,
        // Short, so a page's last cards start below the window.
        height = 640,
        look = look,
        player = if (engine) ShapingPlayer() else SilentPlayer(),
        wrap = { content -> CompositionLocalProvider(LocalRowFlashMs provides flashMs) { content() } },
    )

    private fun A11yScene.until(what: String, check: () -> Boolean) {
        val end = System.currentTimeMillis() + 8_000
        while (!check()) {
            check(System.currentTimeMillis() < end) { "waited too long for $what" }
            render(1)
        }
    }

    private fun A11yScene.open(page: String, row: String? = null) {
        onUi { app.openSettings(page, row) }
        render(4)
    }

    private fun A11yScene.headings(): List<String> =
        nodes().filter { it.config.getOrNull(SemanticsProperties.Heading) != null }.map { it.name() }

    private fun A11yScene.rowTitles(): List<String> = nodes().mapNotNull { it.config.getOrNull(RowTitle)?.title }

    private fun A11yScene.stableTitles(): List<String> = nodes().mapNotNull { it.config.getOrNull(RowTitle)?.stable }

    private fun A11yScene.row(title: String): SemanticsNode = nodes().first { it.config.getOrNull(RowTitle)?.title == title }

    // What is lit now: the nonce, by the node's row title or card heading.
    private fun A11yScene.lit(): List<Pair<SemanticsNode, Long>> = nodes().mapNotNull { node -> node.config.getOrNull(RowFlash)?.let { node to it } }

    // Whether the whole node is on screen; one scrolled out has no bounds.
    private fun A11yScene.inView(node: SemanticsNode): Boolean =
        node.boundsInRoot.let { it.height > 0f && it.top >= 0f && it.bottom <= height }

    private fun A11yScene.click(node: SemanticsNode) {
        onUi { node.config[SemanticsActions.OnClick].action?.invoke() }
        render(4)
    }

    @Test(timeout = 120_000)
    fun eachPageIsItsTitleItsLineAndItsTitledCards() {
        scene().use { s ->
            s.open(SettingsKey.Look)
            val headings = s.headings()
            val start = headings.indexOf("Appearance")
            assertTrue("the page's title is a heading: $headings", start >= 0)
            val cards = listOf("Accent and text", "Window background", "Full player", "Covers", "Motion")
            assertEquals("the cards follow the title, in order", cards, headings.drop(start + 1).filter { it in cards })
            assertTrue("the line under the title", s.nodes().any { it.name() == "How Octo looks: colors, backgrounds and text." })
            s.open(SettingsKey.Offline)
            assertTrue(s.headings().containsAll(listOf("Streaming and downloads", "Streaming", "Kept on this computer")))
            s.open(SettingsKey.Servers)
            assertTrue(s.headings().containsAll(listOf("Servers", "Your servers", "This server", "Maintenance")))
            s.open(SettingsKey.Keys)
            val keys = s.headings()
            assertTrue("Octo's own keys are a card: $keys", "Octo's keys" in keys)
        }
    }

    @Test(timeout = 120_000)
    fun everyRowCarriesItsTitleAndAStableFormForFilledInTitles() {
        scene().use { s ->
            s.open(SettingsKey.Look)
            val titles = s.rowTitles()
            listOf("Accent color", "Text size", "Ambience", "Strength", "Animated art", "Brightness cap", "Playlist covers", "Moving full player background", "Drift speed", "Calm motion").forEach {
                assertTrue("$it carries its title: $titles", it in titles)
            }
            s.open(SettingsKey.Servers)
            val stable = s.stableTitles()
            assertTrue(stable.toString(), "Signed in as ..." in stable)
            assertTrue(stable.toString(), "[the server's kind]" in stable)
            assertTrue(s.rowTitles().any { it.startsWith("Signed in as ") })
        }
    }

    @Test(timeout = 120_000)
    fun aRowAskedForComesIntoViewAndLights() {
        scene().use { s ->
            s.open(SettingsKey.Look)
            assertFalse("Calm motion starts below the window", s.inView(s.row("Calm motion")))
            s.open(SettingsKey.Look, "motion.calm-motion")
            s.until("the row to light") { s.lit().isNotEmpty() }
            val (node, _) = s.lit().single()
            assertEquals("Calm motion", node.config.getOrNull(RowTitle)?.title)
            assertTrue("brought into view", s.inView(s.row("Calm motion")))
            assertNull("the ask is answered", s.app.settingsUi.target)
        }
    }

    @Test(timeout = 120_000)
    fun aRowNeverLaidOutLightsItsCard() {
        scene().use { s ->
            s.open(SettingsKey.Look, "covers.no-such-row")
            s.until("the card to light") { s.lit().isNotEmpty() }
            val (node, _) = s.lit().single()
            assertNull("a card, not a row", node.config.getOrNull(RowTitle))
            assertEquals(listOf("Covers"), s.headingsIn(node))
        }
    }

    // The headings drawn inside a node.
    private fun A11yScene.headingsIn(node: SemanticsNode): List<String> =
        nodes().filter { it.config.getOrNull(SemanticsProperties.Heading) != null && node.boundsInRoot.contains(it.boundsInRoot.center) }.map { it.name() }

    @Test(timeout = 120_000)
    fun aHiddenRowLightsTheRowThatShowsIt() {
        // Glow, the usual ambience, has no Movement row: Ambience shows it.
        scene().use { s ->
            s.open(SettingsKey.Look)
            assertFalse("Movement" in s.rowTitles())
            s.open(SettingsKey.Look, "window-background.movement")
            s.until("a row to light") { s.lit().isNotEmpty() }
            assertEquals("Ambience", s.lit().single().first.config.getOrNull(RowTitle)?.title)
        }
        // With Calm motion on, drift speed is hidden and Calm motion shows it.
        scene(look = { it.copy(appearance = it.appearance.copy(calmMotion = true)) }).use { s ->
            s.open(SettingsKey.Look, "motion.drift-speed")
            s.until("a row to light") { s.lit().isNotEmpty() }
            assertEquals("Calm motion", s.lit().single().first.config.getOrNull(RowTitle)?.title)
        }
    }

    // Between songs shows the rows of the choice made, picked from its
    // segments, and an ask for a row the choice hides lights the choice,
    // the control that shows it; a row it shows lights itself.
    @Test(timeout = 120_000)
    fun betweenSongsShowsItsChoicesRowsAndAHiddenRowLightsTheChoice() {
        scene(engine = true).use { s ->
            val under = setOf("Length", "Crossfade curve", "Filter sweeps", "Match tempo")
            fun shown() = s.stableTitles().filter { it in under }
            fun pick(name: String) = s.click(s.nodes(merged = true).first { it.name() == name && it.config.getOrNull(SemanticsProperties.Role) == Role.Tab })
            fun litFor(row: String): String? {
                s.open(SettingsKey.Playback, row)
                s.until("$row to light something") { s.lit().isNotEmpty() }
                return s.lit().single().first.config.getOrNull(RowId)
            }
            s.open(SettingsKey.Playback)
            assertEquals("a new install is on Automix", listOf("Filter sweeps", "Match tempo"), shown())
            assertTrue("the choice says what it does", s.nodes().any { it.name() == "Songs blend where they meet best, on the beat when they can." })
            assertEquals("crossfade.choice", litFor("crossfade.longest-blend"))
            assertEquals("crossfade.match-tempo", litFor("crossfade.match-tempo"))
            pick("Crossfade")
            assertEquals(BetweenSongs.Crossfade, s.app.settings.current.playback.betweenSongs)
            assertEquals(listOf("Length", "Crossfade curve"), shown())
            assertEquals("crossfade.choice", litFor("crossfade.filter-sweeps"))
            assertEquals("crossfade.longest-blend", litFor("crossfade.longest-blend"))
            pick("Gapless")
            assertEquals(BetweenSongs.Gapless, s.app.settings.current.playback.betweenSongs)
            assertEquals("Gapless shows nothing more", emptyList<String>(), shown())
            assertTrue(s.nodes().any { it.name() == "Each song plays straight into the next." })
            assertEquals("crossfade.choice", litFor("crossfade.crossfade-curve"))
        }
    }

    @Test(timeout = 120_000)
    fun theSameRowAskedForAgainLightsAgain() {
        scene().use { s ->
            s.open(SettingsKey.Look, "motion.calm-motion")
            s.until("the first light") { s.lit().isNotEmpty() }
            val first = s.lit().single().second
            s.open(SettingsKey.Look, "motion.calm-motion")
            s.until("the second light") { s.lit().singleOrNull()?.second?.let { it != first } == true }
            assertNotEquals(first, s.lit().single().second)
        }
    }

    @Test(timeout = 120_000)
    fun theLightLastsAsLongAsTheWindowSays() {
        scene(flashMs = 700).use { s ->
            s.open(SettingsKey.Look)
            val asked = System.nanoTime()
            s.onUi { s.app.openSettings(SettingsKey.Look, "motion.calm-motion") }
            s.until("the light") { s.lit().isNotEmpty() }
            s.until("the light to go") { s.lit().isEmpty() }
            val ms = (System.nanoTime() - asked) / 1_000_000
            assertTrue("lit about as long as set, not the usual time: $ms ms", ms in 700L until 1_500L)
        }
    }

    @Test(timeout = 120_000)
    fun pickingAPageLetsTheLightGo() {
        scene().use { s ->
            fun tab(name: String) = s.nodes(merged = true).first { it.name() == name && it.config.getOrNull(SemanticsProperties.Role) == Role.Tab }
            s.open(SettingsKey.Look, "motion.calm-motion")
            s.until("the row to light") { s.lit().isNotEmpty() }
            s.click(tab("Home"))
            assertEquals(SettingsKey.Home, s.app.settingsUi.page)
            assertTrue(s.lit().isEmpty())
            s.click(tab("Appearance"))
            assertEquals(SettingsKey.Look, s.app.settingsUi.page)
            assertTrue("nothing is lit on coming back", s.lit().isEmpty())
            assertNull(s.app.settingsUi.target)
        }
    }

    @Test(timeout = 120_000)
    fun theOldSoundSectionsAndCommandsLandOnTheirCards() {
        scene(engine = true).use { s ->
            listOf("output", "eq", "loudness", "balance", "crossfeed", "correction", "playback").forEach { old ->
                val place = soundSectionPlace(old)
                s.open(place.page, place.row)
                s.until("$old to light") { s.lit().isNotEmpty() }
                assertEquals(place.page, s.app.settingsUi.page)
                val expected = mapOf(
                    "output" to "Output", "eq" to "Equalizer", "loudness" to "Loudness", "balance" to "Balance",
                    "crossfeed" to "Crossfeed", "correction" to "Headphone correction", "playback" to "Between songs",
                ).getValue(old)
                val lit = s.lit().single().first
                assertEquals("$old lights its card", listOf(expected), s.headingsIn(lit))
                assertTrue("$old is on screen", lit.boundsInRoot.height > 0f)
            }
        }
    }

    @Test(timeout = 120_000)
    fun withoutTheEngineSoundIsOneNoticeAndPlaybackHasNoBlendOrSpeed() {
        scene().use { s ->
            s.open(SettingsKey.Sound)
            val sound = s.rowTitles()
            assertEquals(listOf("Nothing to shape"), sound)
            assertFalse("Equalizer" in s.headings())
            s.open(SettingsKey.Playback)
            val headings = s.headings()
            assertTrue(headings.containsAll(listOf("When the queue ends", "Songs found online")))
            assertFalse(headings.toString(), "Between songs" in headings || "Speed and pitch" in headings)
        }
        scene(engine = true).use { s ->
            s.open(SettingsKey.Sound)
            assertTrue(s.headings().containsAll(listOf("Output", "Equalizer", "Loudness", "Balance", "Crossfeed", "Headphone correction")))
            assertFalse("Nothing to shape" in s.rowTitles())
            s.open(SettingsKey.Playback)
            assertTrue(s.headings().containsAll(listOf("When the queue ends", "Songs found online", "Between songs", "Speed and pitch")))
            assertTrue("the choice is the card's first row", "Between songs" in s.rowTitles())
        }
    }

    @Test(timeout = 120_000)
    fun removeAllAsksFirst() {
        scene().use { s ->
            s.onUi { s.app.offline.keepLiked(true) }
            s.open(SettingsKey.Offline)
            fun action(name: String) = s.nodes(merged = true).last { it.name() == name && it.clickable }
            // The row's button is Remove; the sheet's go-ahead is Remove all.
            s.click(action("Remove"))
            assertTrue("the sheet asks", s.nodes().any { it.name() == "Remove every kept song?" })
            assertTrue("nothing is gone yet", s.app.offline.choices().liked)
            s.click(action("Cancel"))
            assertFalse(s.nodes().any { it.name() == "Remove every kept song?" })
            assertTrue(s.app.offline.choices().liked)
            s.click(action("Remove"))
            s.click(action("Remove all"))
            assertFalse("Remove all in the sheet lets every kept song go", s.app.offline.choices().liked)
        }
    }

    @Test(timeout = 120_000)
    fun aDetourAndBackKeepsThePagesPlace() {
        scene().use { s ->
            s.open(SettingsKey.Look, "motion.calm-motion")
            s.until("the row to light") { s.lit().isNotEmpty() }
            assertTrue(s.inView(s.row("Calm motion")))
            val before = s.row("Calm motion").boundsInRoot.top
            s.onUi { s.app.navigator.go(Page.Album("a1")) }
            s.render(4)
            s.onUi { s.app.navigator.back() }
            s.render(6)
            assertTrue(s.app.inSettings)
            assertEquals("the same spot", before, s.row("Calm motion").boundsInRoot.top, 1f)
            assertNotNull(s.row("Calm motion"))
        }
    }
}
