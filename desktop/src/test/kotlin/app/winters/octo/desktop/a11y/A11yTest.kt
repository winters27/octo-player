package app.winters.octo.desktop.a11y

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import app.winters.octo.desktop.SidePanel
import app.winters.octo.desktop.nav.Page
import app.winters.octo.desktop.nav.Shortcut
import app.winters.octo.design.FrameSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.math.hypot

// What a screen reader and the keyboard get from the real window: every
// control named, with a role and a target big enough to hit; Tab walking
// the frame in order; the song table read row by row; menus that the
// keyboard can open, walk and leave.
class A11yTest {
    @get:Rule val folder = TemporaryFolder()

    private val pages = listOf(
        Page.Home, Page.Songs, Page.Albums, Page.Artists, Page.Genres, Page.Folders, Page.Favourites,
        Page.History, Page.RecentlyAdded, Page.LibraryHealth, Page.Settings, Page.Sound, Page.Album("a1"),
        Page.Playlist("p1"), Page.Search,
    )

    // A song playing and the queue open, so the player and the panel are full.
    private fun A11yScene.playing() {
        onUi {
            app.play(app.library!!.index!!.songs, 0)
            app.toggleSidePanel(SidePanel.Queue)
        }
        render(4)
    }

    // What a screen reader skips.
    private fun SemanticsNode.hidden() = config.getOrNull(SemanticsProperties.HideFromAccessibility) != null ||
        config.getOrNull(SemanticsProperties.InvisibleToUser) != null
    private fun SemanticsNode.field() = config.getOrNull(SemanticsProperties.EditableText) != null

    @Test(timeout = 180_000)
    fun everyControlOnEveryPageHasANameAndARole() {
        A11yScene(folder.root).use { s ->
            s.playing()
            val problems = mutableListOf<String>()
            for (page in pages + listOf(null)) {
                s.onUi { if (page != null) s.app.navigator.go(page) else s.app.fullPlayer = true }
                s.render(6)
                for (node in s.nodes(merged = true).filterNot { it.hidden() }) {
                    val focusable = node.config.getOrNull(SemanticsProperties.Focused) != null
                    if ((node.clickable || focusable) && node.name().isBlank()) problems += "$page: nameless ${node.config}"
                    // A row reads as a song and a text field as a field; every other click says what it is.
                    val row = node.name().contains(", ") && node.config.getOrNull(SemanticsProperties.Selected) != null
                    if (node.clickable && !node.field() && !row && node.config.getOrNull(SemanticsProperties.Role) == null) problems += "$page: no role on '${node.name()}'"
                }
            }
            assertTrue(problems.joinToString("\n"), problems.isEmpty())
        }
    }

    // WCAG's least target, 24 by 24, or, for a smaller one, room enough
    // round it that a 24 wide circle on it touches no other target.
    @Test(timeout = 180_000)
    fun everyClickIsATargetOf24OrHasRoomAroundIt() {
        A11yScene(folder.root).use { s ->
            s.playing()
            val problems = mutableListOf<String>()
            for (page in pages + listOf(null)) {
                s.onUi { if (page != null) s.app.navigator.go(page) else s.app.fullPlayer = true }
                s.render(6)
                // Targets on screen, each with the part of the frame it is in:
                // the player floats over the page, so only neighbours in one part count.
                val targets = s.nodes(merged = true)
                    .filter { it.clickable && !it.hidden() && !it.field() && it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f }
                    .map { Triple(it.name(), it.boundsInRoot, s.partOf(it)) }
                for ((name, box, part) in targets) {
                    if (box.width >= 24f && box.height >= 24f) continue
                    val clash = targets.firstOrNull { (_, rect, where) ->
                        // One lying over the other is the page passing under the player, not a neighbour.
                        where == part && rect != box && !rect.overlaps(box) &&
                            (if (rect.width < 24f || rect.height < 24f) hypot(rect.center.x - box.center.x, rect.center.y - box.center.y) < 24f else rect.distanceTo(box.center) < 12f)
                    }
                    if (clash != null) problems += "$page: '$name' ${box.width}x${box.height} is crowded by '${clash.first}'"
                }
            }
            assertTrue(problems.joinToString("\n"), problems.isEmpty())
        }
    }

    private fun Rect.distanceTo(point: Offset): Float {
        val dx = maxOf(left - point.x, 0f, point.x - right)
        val dy = maxOf(top - point.y, 0f, point.y - bottom)
        return hypot(dx, dy)
    }

    // Which part of the frame a node is in, by where it is.
    private fun A11yScene.partOf(node: SemanticsNode): String {
        val c = node.boundsInRoot.center
        val panel = app.sidePanel != null
        return when {
            c.y < FrameSize.TitleBar.value -> "title"
            c.x < FrameSize.Sidebar.value -> "sidebar"
            panel && c.x > width - FrameSize.Panel.value - 1 -> "panel"
            c.y > height - FrameSize.Player.value - FrameSize.PlayerGap.value -> "player"
            else -> "page"
        }
    }

    // The parts Tab passes through, in order, each once per visit.
    private fun A11yScene.walk(presses: Int, back: Boolean = false): List<String> {
        val seen = mutableListOf<String>()
        repeat(presses) {
            press(Key.Tab, shift = back)
            val part = focused()?.let { partOf(it) } ?: "nothing"
            if (seen.lastOrNull() != part) seen += part
        }
        return seen
    }

    @Test(timeout = 180_000)
    fun tabWalksTitleBarSidebarPagePanelThenPlayer() {
        A11yScene(folder.root).use { s ->
            s.playing()
            s.onUi { s.app.navigator.go(Page.Songs) }
            s.render(4)
            s.onUi { s.app.navigator.go(Page.Albums) }
            s.render(4)
            val parts = s.walk(140)
            // From wherever it starts, the walk goes round in this order.
            val cycle = listOf("title", "sidebar", "page", "panel", "player")
            val start = parts.indexOf("title")
            assertTrue("reached the title bar: $parts", start >= 0)
            val round = parts.drop(start).take(cycle.size + 1)
            assertEquals("Tab's order", cycle + "title", round)
            // Shift+Tab goes round the other way.
            val backward = s.walk(140, back = true)
            val from = backward.indexOf("player")
            assertEquals("Shift+Tab's order", listOf("player", "panel", "page", "sidebar", "title"), backward.drop(from).take(5))
        }
    }

    @Test(timeout = 180_000)
    fun withTheFullPlayerOpenTheKeyboardStaysOffTheHiddenFrame() {
        A11yScene(folder.root).use { s ->
            s.playing()
            s.onUi { s.app.fullPlayer = true }
            s.render(6)
            val names = mutableSetOf<String>()
            repeat(40) {
                s.press(Key.Tab)
                s.focused()?.let { names += it.name() }
            }
            assertTrue("the full player's own controls: $names", "Shuffle" in names && ("Pause" in names || "Play" in names))
            assertFalse("nothing of the sidebar under it: $names", "Songs" in names || "Settings" in names)
        }
    }

    @Test(timeout = 180_000)
    fun songRowsReadAsTitleArtistAlbumAndLength() {
        A11yScene(folder.root).use { s ->
            s.playing()
            s.onUi { s.app.navigator.go(Page.Songs) }
            s.render(6)
            val rows = s.nodes().map { it.name() }
            assertTrue(rows.toString(), "Karma Police, Radiohead, OK Computer, 4:21" in rows)
            // The playing one says so.
            assertTrue(rows.toString(), rows.any { it.startsWith("Airbag, Radiohead, OK Computer, 4:44, playing") })
            val table = s.nodes().first { it.name().startsWith("Song list") }
            assertEquals("Song list, 5 songs", table.name())
        }
    }

    @Test(timeout = 180_000)
    fun theMenuKeyOpensTheSongMenuWhichTheArrowsWalkAndEscapeLeaves() {
        A11yScene(folder.root).use { s ->
            s.playing()
            s.onUi { s.app.navigator.go(Page.Songs) }
            s.render(6)
            // Tab into the table, then down to a row.
            var guard = 0
            while (s.focused()?.name()?.startsWith("Song list") != true && guard++ < 60) s.press(Key.Tab)
            assertTrue("the table took the keyboard", s.focused()?.name()?.startsWith("Song list") == true)
            // The row the keyboard is on is the one a screen reader hears as focused.
            fun keyRow() = s.nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.Focused) == true && it.config.getOrNull(SemanticsProperties.Selected) != null }?.name()
            s.press(Key.DirectionDown)
            val one = keyRow()
            s.press(Key.DirectionDown)
            val two = keyRow()
            assertTrue("Down moves the keyboard's row: $one then $two", one != null && two != null && one != two)
            s.press(Key.Menu)
            assertTrue("the song menu opened", s.app.popups.open)
            val first = s.focused()?.name()
            assertEquals("opened from the keyboard, its first row has it", "Play", first)
            s.press(Key.DirectionDown)
            val second = s.focused()?.name()
            assertTrue("Down moved on: $first then $second", second != null && second != first)
            // Tab stays inside the menu.
            repeat(30) { s.press(Key.Tab) }
            assertTrue("still in the menu", s.app.popups.open && s.focused() != null)
            s.onUi { s.app.perform(Shortcut.CloseLayer) }
            s.render(4)
            assertFalse(s.app.popups.open)
            val table = s.nodes(merged = true).first { it.name().startsWith("Song list") }
            assertEquals("the table has the keyboard again", true, table.config.getOrNull(SemanticsProperties.Focused))
        }
    }

    @Test(timeout = 180_000)
    fun escapeInTheSidePanelClosesIt() {
        A11yScene(folder.root).use { s ->
            s.playing()
            var guard = 0
            while (s.focused()?.let { s.partOf(it) } != "panel" && guard++ < 80) s.press(Key.Tab)
            assertTrue("reached the panel", s.app.panelHasKeyboard)
            s.onUi { s.app.perform(Shortcut.CloseLayer) }
            s.render(4)
            assertEquals(null, s.app.sidePanel)
        }
    }

    @Test(timeout = 180_000)
    fun theTogglesSayWhetherTheyAreOnAndTheSlidersWhereTheyAre() {
        A11yScene(folder.root).use { s ->
            s.playing()
            s.onUi { s.app.player.setShuffle(true) }
            s.render(4)
            val nodes = s.nodes()
            val shuffle = nodes.first { it.name() == "Shuffle" }
            assertEquals(ToggleableState.On, shuffle.config.getOrNull(SemanticsProperties.ToggleableState))
            val queue = nodes.first { it.name() == "Queue" && it.config.getOrNull(SemanticsProperties.ToggleableState) != null }
            assertEquals(ToggleableState.On, queue.config.getOrNull(SemanticsProperties.ToggleableState))
            val position = nodes.first { it.name() == "Song position" }
            assertNotNull(position.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo))
            assertTrue(position.config.getOrNull(SemanticsProperties.StateDescription).orEmpty().endsWith("of 4:44"))
            assertTrue(nodes.any { it.name() == "Pause" })
        }
    }

    @Test(timeout = 180_000)
    fun pageTitlesAndSectionsAreHeadings() {
        A11yScene(folder.root).use { s ->
            s.onUi { s.app.navigator.go(Page.Settings) }
            s.render(6)
            val headings = s.nodes().filter { it.config.getOrNull(SemanticsProperties.Heading) != null }.map { it.name() }
            assertTrue(headings.toString(), "Settings" in headings)
            assertTrue(headings.toString(), headings.size >= 3)
        }
    }
}
