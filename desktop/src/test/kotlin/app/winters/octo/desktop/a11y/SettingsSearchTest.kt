package app.winters.octo.desktop.a11y

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import app.winters.octo.desktop.nav.SettingsKey
import app.winters.octo.desktop.nav.Shortcut
import app.winters.octo.desktop.pages.LocalRowFlashMs
import app.winters.octo.desktop.pages.RowFlash
import app.winters.octo.desktop.pages.RowTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// Settings' search in the real window: what is typed shows its results in
// the list's place, Up, Down and Enter (or a click) open one, its row
// lights and takes the keyboard, a screen reader hears the count, and
// Escape clears the words before it leaves.
class SettingsSearchTest {
    @get:Rule val folder = TemporaryFolder()

    private fun scene() = A11yScene(
        folder.newFolder(),
        reduceMotion = true,
        height = 640,
        wrap = { content -> CompositionLocalProvider(LocalRowFlashMs provides 60_000L) { content() } },
    )

    private fun A11yScene.until(what: String, check: () -> Boolean) {
        val end = System.currentTimeMillis() + 8_000
        while (!check()) {
            check(System.currentTimeMillis() < end) { "waited too long for $what" }
            render(1)
        }
    }

    // Settings open, the keyboard in its search, `words` typed there.
    private fun A11yScene.search(words: String) {
        onUi { app.openSettings(SettingsKey.Playback) }
        render(4)
        onUi { app.perform(Shortcut.Filter) }
        render(2)
        assertTrue("the keyboard is in the search", app.settingsUi.searchHasKeyboard)
        onUi { app.settingsUi.query = words }
        render(4)
    }

    private fun A11yScene.results(): List<SemanticsNode> =
        nodes(merged = true).filter { it.config.getOrNull(SemanticsProperties.Role) == Role.Button && it.config.getOrNull(SemanticsProperties.Selected) != null }

    private fun A11yScene.marked(): String? = results().firstOrNull { it.config.getOrNull(SemanticsProperties.Selected) == true }?.name()

    private fun A11yScene.count(): SemanticsNode? = nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.LiveRegion) == LiveRegionMode.Polite }

    private fun A11yScene.groupHeadings(): List<String> =
        nodes().filter { it.config.getOrNull(SemanticsProperties.Heading) != null }.map { it.name() }.filter { it in listOf("Listening", "Your music", "This computer") }

    private fun A11yScene.lit(): List<Pair<SemanticsNode, Long>> = nodes().mapNotNull { node -> node.config.getOrNull(RowFlash)?.let { node to it } }

    @Test(timeout = 120_000)
    fun typingShowsResultsAndEnterOpensLightsAndGivesTheRowTheKeyboard() {
        scene().use { s ->
            s.search("calm motion")
            assertEquals("the results take the groups' place", emptyList<String>(), s.groupHeadings())
            assertEquals(listOf("Calm motion, Appearance · Motion"), s.results().map { it.name() })
            assertEquals("Calm motion, Appearance · Motion", s.marked())
            s.press(Key.Enter)
            s.until("the row to light") { s.lit().isNotEmpty() }
            assertEquals(SettingsKey.Look, s.app.settingsUi.page)
            val (row, _) = s.lit().single()
            assertEquals("Calm motion", row.config.getOrNull(RowTitle)?.title)
            s.until("the keyboard to reach the row") { s.focused()?.let { row.boundsInRoot.contains(it.boundsInRoot.center) } == true }
            assertFalse("the search let go of the keyboard", s.app.settingsUi.searchHasKeyboard)
            assertEquals("the words stay, so another result is a click away", "calm motion", s.app.settingsUi.query)
        }
    }

    @Test(timeout = 120_000)
    fun theCountIsHeardAsItChanges() {
        scene().use { s ->
            s.search("calm motion")
            assertEquals("1 result", s.count()?.name())
            s.onUi { s.app.settingsUi.query = "background" }
            s.render(4)
            val many = s.results().size
            assertTrue("several results for background: $many", many > 1)
            assertEquals("$many results", s.count()?.name())
            // Without the sound engine its settings are not offered.
            s.onUi { s.app.settingsUi.query = "longest blend" }
            s.render(4)
            assertEquals("No settings match", s.count()?.name())
            assertTrue(s.results().isEmpty())
        }
    }

    @Test(timeout = 120_000)
    fun upAndDownMoveTheMarkAndAClickOpensAResult() {
        scene().use { s ->
            s.search("motion")
            val names = s.results().map { it.name() }
            assertTrue("several results for motion: $names", names.size > 2)
            assertEquals(names.first(), s.marked())
            s.press(Key.DirectionDown)
            assertEquals(names[1], s.marked())
            s.press(Key.DirectionDown)
            s.press(Key.DirectionUp)
            assertEquals(names[1], s.marked())
            s.press(Key.DirectionUp)
            s.press(Key.DirectionUp)
            assertEquals("the mark stops at the first", names.first(), s.marked())
            val calm = s.results().first { it.name().startsWith("Calm motion,") }
            s.onUi { calm.config[SemanticsActions.OnClick].action?.invoke() }
            s.render(4)
            assertEquals(SettingsKey.Look, s.app.settingsUi.page)
            assertTrue(s.marked()!!.startsWith("Calm motion,"))
            s.until("the clicked row to light") { s.lit().singleOrNull()?.first?.config?.getOrNull(RowTitle)?.title == "Calm motion" }
        }
    }

    @Test(timeout = 120_000)
    fun theSameHitTwiceLightsTwice() {
        scene().use { s ->
            s.search("calm motion")
            s.press(Key.Enter)
            s.until("the first light") { s.lit().isNotEmpty() }
            val first = s.lit().single().second
            s.onUi { s.app.perform(Shortcut.Filter) }
            s.render(2)
            s.press(Key.Enter)
            s.until("the second light") { s.lit().singleOrNull()?.second?.let { it != first } == true }
            assertNotEquals(first, s.lit().single().second)
        }
    }

    @Test(timeout = 120_000)
    fun escapeClearsTheWordsThenLeavesSettings() {
        scene().use { s ->
            s.search("calm motion")
            assertTrue(s.groupHeadings().isEmpty())
            s.onUi { s.app.perform(Shortcut.CloseLayer, typing = true) }
            s.render(4)
            assertEquals("", s.app.settingsUi.query)
            assertTrue(s.app.inSettings)
            assertEquals("the groups come back", listOf("Listening", "Your music", "This computer"), s.groupHeadings())
            assertNotNull(s.nodes(merged = true).firstOrNull { it.config.getOrNull(SemanticsProperties.Role) == Role.Tab })
            s.onUi { s.app.perform(Shortcut.CloseLayer, typing = true) }
            s.render(4)
            assertFalse(s.app.inSettings)
        }
    }
}
