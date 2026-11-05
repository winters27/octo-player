package app.winters.octo.desktop.a11y

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import app.winters.octo.design.FrameSize
import app.winters.octo.desktop.SidePanel
import app.winters.octo.desktop.nav.Page
import app.winters.octo.desktop.nav.SettingsKey
import app.winters.octo.desktop.nav.Shortcut
import app.winters.octo.desktop.nav.settingsPages
import app.winters.octo.desktop.settings.AppSettings
import app.winters.octo.desktop.ui.settingsListWidth
import app.winters.octo.desktop.update.FakeReleases
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// Settings in the sidebar's place, in the real window: the list swaps in
// and out, a folded sidebar comes back folded, the list is as wide as its
// names need (but leaves the player its room), the search shortcuts, the
// account of where the keyboard goes, how a screen reader hears it, and
// the swap's motion.
class SettingsTakeoverTest {
    @get:Rule val folder = TemporaryFolder()

    private val pageNames = settingsPages(windows = true, discord = false).map { it.name }

    private fun SemanticsNode.role(): Role? = config.getOrNull(SemanticsProperties.Role)

    // The tabs' names, without what follows a name (a version, a dot's meaning).
    private fun A11yScene.tabs(): List<String> = nodes(merged = true).filter { it.role() == Role.Tab }.map { it.name().substringBefore(", ") }

    private fun A11yScene.click(name: String) {
        val node = nodes(merged = true).first { it.name() == name && it.clickable }
        onUi { node.config[SemanticsActions.OnClick].action?.invoke() }
        render(4)
    }

    private fun SemanticsNode.insideTab(): Boolean {
        var at = parent
        while (at != null) {
            if (at.role() == Role.Tab) return true
            at = at.parent
        }
        return false
    }

    // How each page's name in the list was laid out.
    private fun A11yScene.nameLayouts(): Map<String, TextLayoutResult> {
        val out = mutableMapOf<String, TextLayoutResult>()
        onUi {
            for (node in nodes()) {
                val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } ?: continue
                if (text !in pageNames || !node.insideTab()) continue
                val got = mutableListOf<TextLayoutResult>()
                node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(got)
                got.firstOrNull()?.let { out[text] = it }
            }
        }
        return out
    }

    // Cut short, ellipsized, or a word broken across two lines.
    private fun TextLayoutResult.cut(): Boolean {
        val words = layoutInput.text.text
        val broken = (0 until lineCount - 1).any { line ->
            val end = getLineEnd(line)
            end in 1 until words.length && !words[end - 1].isWhitespace() && !words[end].isWhitespace()
        }
        return broken || hasVisualOverflow || (0 until lineCount).any { isLineEllipsized(it) }
    }

    // Whether the player shows its panel buttons, which a compact player
    // moves into its More menu.
    private fun A11yScene.playerRoomy(): Boolean =
        nodes(merged = true).any { it.name() == "Lyrics" && it.config.getOrNull(SemanticsProperties.ToggleableState) != null }

    private fun rail(look: AppSettings) = look.copy(frame = look.frame.copy(sidebarRail = true))

    @Test(timeout = 120_000)
    fun openingSettingsSwapsTheSidebarForItsListAndLeavingSwapsBack() {
        A11yScene(folder.root, reduceMotion = true).use { s ->
            s.onUi { s.app.navigator.go(Page.Songs) }
            s.render(4)
            assertTrue(s.tabs().containsAll(listOf("Songs", "Albums", "Settings")))
            assertNotEquals(IntRect.Zero, s.app.omnibox.field)
            s.onUi { s.app.openSettings() }
            s.render(4)
            val tabs = s.tabs()
            assertTrue(tabs.toString(), tabs.containsAll(pageNames))
            assertFalse("the sidebar's rows are gone", tabs.contains("Albums"))
            assertEquals("the sidebar's search field took its place with it", IntRect.Zero, s.app.omnibox.field)
            s.click("Back")
            assertEquals(Page.Songs, s.app.navigator.current.page)
            assertTrue(s.tabs().contains("Albums"))
            assertFalse(s.tabs().contains("Playback"))
            s.onUi { s.app.openSettings() }
            s.render(4)
            s.onUi { s.app.perform(Shortcut.CloseLayer) }
            s.render(4)
            assertEquals("Escape goes back where Settings was opened from", Page.Songs, s.app.navigator.current.page)
            assertTrue(s.tabs().contains("Albums"))
        }
    }

    @Test(timeout = 120_000)
    fun foldComesBack() {
        A11yScene(folder.root, reduceMotion = true, look = ::rail).use { s ->
            assertTrue("folded to its rail", s.nodes(merged = true).any { it.name() == "Search (Ctrl+K)" })
            s.onUi { s.app.openSettings() }
            s.render(4)
            val names = s.nameLayouts()
            assertEquals(pageNames.toSet(), names.keys)
            names.forEach { (name, laid) -> assertEquals("$name on one line", 1, laid.lineCount) }
            assertFalse("no rail in Settings", s.nodes(merged = true).any { it.name() == "Search (Ctrl+K)" })
            var folded = true
            s.onUi { folded = s.app.perform(Shortcut.Sidebar) }
            assertFalse("folding is ignored in Settings", folded)
            assertTrue(s.app.settings.current.frame.sidebarRail)
            s.onUi { s.app.leaveSettings() }
            s.render(4)
            assertTrue("folded again", s.nodes(merged = true).any { it.name() == "Search (Ctrl+K)" })
            assertTrue(s.app.settings.current.frame.sidebarRail)
        }
    }

    @Test(timeout = 120_000)
    fun aNarrowWindowWithLargeTextKeepsThePlayerAndCutsNoName() {
        A11yScene(folder.root, reduceMotion = true, width = 960, height = 640, textScale = 1.3f, look = ::rail).use { s ->
            s.onUi { s.app.play(s.app.library!!.index!!.songs, 0) }
            s.render(4)
            val before = s.playerRoomy()
            s.onUi { s.app.openSettings() }
            s.render(4)
            assertEquals("the player keeps its layout in Settings", before, s.playerRoomy())
            val names = s.nameLayouts()
            assertEquals(pageNames.toSet(), names.keys)
            names.forEach { (name, laid) -> assertFalse("$name is cut", laid.cut()) }
            s.onUi { s.app.leaveSettings() }
            s.render(4)
            assertEquals(before, s.playerRoomy())
        }
    }

    @Test(timeout = 120_000)
    fun theListAtItsLeastWithLargeTextCutsNoName() {
        A11yScene(folder.root, reduceMotion = true, textScale = 1.3f, look = { it.copy(frame = it.frame.copy(sidebarWidth = FrameSize.SidebarMin.value)) }).use { s ->
            s.onUi { s.app.openSettings() }
            s.render(4)
            val names = s.nameLayouts()
            assertEquals(pageNames.toSet(), names.keys)
            names.forEach { (name, laid) ->
                assertEquals("$name on one line", 1, laid.lineCount)
                assertFalse("$name is cut: ${laid.size} in ${laid.layoutInput.constraints}, wide ${laid.didOverflowWidth}, tall ${laid.didOverflowHeight}", laid.cut())
            }
        }
    }

    @Test
    fun theListWidensOnlyAsFarAsThePlayerAllows() {
        assertEquals("names that fit keep the sidebar's width", 240.dp, settingsListWidth(1440.dp, 240.dp, 200.dp))
        assertEquals("a rail widens to what the names need", 250.dp, settingsListWidth(1440.dp, FrameSize.SidebarRail, 250.dp))
        val most = 960.dp - (FrameSize.PlayerMin + FrameSize.PlayerGap * 2) - FrameSize.Hairline
        assertEquals("never past the player's room", most, settingsListWidth(960.dp, FrameSize.SidebarRail, 400.dp))
    }

    // Settings as the first page shown: the title bar's Back still leaves,
    // to Home.
    @Test(timeout = 120_000)
    fun backLeavesSettingsWithNothingBehindIt() {
        A11yScene(folder.root, reduceMotion = true).use { s ->
            s.onUi { s.app.navigator.replace(Page.Settings) }
            s.render(4)
            assertTrue(s.app.inSettings)
            assertFalse(s.app.navigator.canGoBack)
            s.click("Back")
            assertEquals(Page.Home, s.app.navigator.current.page)
        }
    }

    // An update's dot beside About takes room the list already kept, so the
    // page does not move when one is ready.
    @Test(timeout = 120_000)
    fun anUpdateReadyKeepsTheListsWidth() {
        fun listWidth(s: A11yScene): Float {
            s.onUi { s.app.openSettings() }
            s.render(4)
            var width = 0f
            s.onUi { width = s.nodes(merged = true).first { it.config.getOrNull(SemanticsProperties.Role) == Role.Tab }.boundsInRoot.width }
            return width
        }
        val without = A11yScene(folder.newFolder(), reduceMotion = true).use(::listWidth)
        val with = FakeReleases().use { github ->
            A11yScene(folder.newFolder(), reduceMotion = true, updates = SettingsWalk.readyUpdates(github, folder.newFolder())).use(::listWidth)
        }
        assertEquals(without, with)
    }

    @Test(timeout = 120_000)
    fun theSearchShortcutFloatsTheSearchBoxBesideTheTitle() {
        A11yScene(folder.root, reduceMotion = true).use { s ->
            s.onUi { s.app.openSettings() }
            s.render(4)
            s.onUi { s.app.perform(Shortcut.Search) }
            s.render(4)
            assertTrue(s.app.omnibox.open)
            assertEquals("the search box has the keyboard", "Search", s.focused()?.name())
            s.onUi { s.app.omnibox.open = false }
            s.render(2)
            s.onUi { s.app.leaveSettings() }
            s.render(4)
            assertEquals("leaving Settings lets go of the title's mark", IntRect.Zero, s.app.omnibox.trigger)
        }
    }

    @Test(timeout = 120_000)
    fun theFindShortcutInSettingsGoesToTheSettingsSearch() {
        A11yScene(folder.root, reduceMotion = true).use { s ->
            s.onUi { s.app.openSettings() }
            s.render(4)
            s.onUi { s.app.perform(Shortcut.Filter) }
            s.render(4)
            assertEquals("Search settings", s.focused()?.name())
            assertTrue(s.app.settingsUi.searchHasKeyboard)
            assertFalse("the search box stays shut", s.app.omnibox.open)
        }
    }

    @Test(timeout = 120_000)
    fun theKeyboardGoesFromTheSidebarToTheListAndBack() {
        A11yScene(folder.root, reduceMotion = true).use { s ->
            assertTrue(s.tabTo("Settings"))
            s.press(Key.Enter)
            s.render(3)
            assertTrue(s.app.inSettings)
            assertEquals("the page shown has the keyboard", "Playback", s.focused()?.name())
            s.onUi { s.app.perform(Shortcut.CloseLayer) }
            s.render(4)
            assertFalse(s.app.inSettings)
            assertEquals("back on the sidebar's Settings row", "Settings", s.focused()?.name())
        }
    }

    @Test(timeout = 120_000)
    fun theSidebarKeepsItsPlaceInItsListAcrossSettings() {
        A11yScene(folder.root, reduceMotion = true, height = 420).use { s ->
            fun homeRow() = s.nodes(merged = true).any { it.role() == Role.Tab && it.name() == "Home" }
            assertTrue(homeRow())
            val list = s.nodes().first { it.config.getOrNull(SemanticsActions.ScrollToIndex) != null && it.boundsInWindow.left < FrameSize.Sidebar.value }
            s.onUi { list.config[SemanticsActions.ScrollToIndex].action?.invoke(12) }
            s.render(4)
            assertFalse("scrolled past Home", homeRow())
            s.onUi { s.app.openSettings() }
            s.render(4)
            s.onUi { s.app.leaveSettings() }
            s.render(4)
            assertFalse("still past Home", homeRow())
        }
    }

    @Test(timeout = 120_000)
    fun theKeyboardInTheSidePanelGoesToTheListsPage() {
        A11yScene(folder.root, reduceMotion = true).use { s ->
            s.onUi {
                s.app.play(s.app.library!!.index!!.songs, 0)
                s.app.showSidePanel(SidePanel.Queue)
            }
            s.render(4)
            val edge = s.width - FrameSize.Panel.value
            val inPanel = s.nodes(merged = true).first { it.config.getOrNull(SemanticsActions.RequestFocus) != null && it.boundsInWindow.left > edge }
            s.onUi { inPanel.config[SemanticsActions.RequestFocus].action?.invoke() }
            s.render(2)
            assertTrue(s.app.panelHasKeyboard)
            s.onUi { s.app.perform(Shortcut.Settings) }
            s.render(4)
            assertTrue(s.app.inSettings)
            assertEquals("Playback", s.focused()?.name())
        }
    }

    @Test(timeout = 120_000)
    fun aScreenReaderHearsTheListAsTabsUnderItsHeading() {
        A11yScene(folder.root, reduceMotion = true).use { s ->
            s.onUi { s.app.openSettings(SettingsKey.Sound) }
            s.render(4)
            val nodes = s.nodes(merged = true)
            val headings = s.nodes().filter { it.config.getOrNull(SemanticsProperties.Heading) != null }.map { it.name() }
            assertTrue(headings.toString(), headings.containsAll(listOf("Settings", "Listening", "Your music", "This computer")))
            // The one way back is the title bar's.
            assertEquals(1, nodes.count { it.name() == "Back" && it.role() == Role.Button })
            for (name in pageNames) {
                val item = nodes.firstOrNull { it.role() == Role.Tab && it.name().startsWith(name) }
                assertTrue("$name is a tab", item != null)
                assertEquals("$name selected", name == "Sound", item!!.config.getOrNull(SemanticsProperties.Selected))
            }
        }
    }

    @Test(timeout = 120_000)
    fun theListSlidesInWithMotionAndSwapsAtOnceWithout() {
        fun A11yScene.playbackLeft(): Float = nodes(merged = true).first { it.role() == Role.Tab && it.name() == "Playback" }.boundsInWindow.left
        A11yScene(folder.root, reduceMotion = false).use { s ->
            s.onUi { s.app.openSettings() }
            val start = System.nanoTime()
            s.frameAt(start).close()
            s.frameAt(start + 16_000_000).close()
            s.frameAt(start + 120_000_000).close()
            val middle = s.playbackLeft()
            assertFalse("the leaving sidebar is passed by", s.tabs().contains("Albums"))
            s.frameAt(start + 1_000_000_000).close()
            val end = s.playbackLeft()
            assertTrue("slides in from $middle to $end", middle > end + 1f)
        }
        A11yScene(folder.root, reduceMotion = true).use { s ->
            s.onUi { s.app.openSettings() }
            s.render(1)
            val first = s.playbackLeft()
            s.settle(500)
            assertEquals("Calm motion swaps at once", s.playbackLeft(), first, 0.5f)
            assertFalse(s.tabs().contains("Albums"))
        }
    }
}
