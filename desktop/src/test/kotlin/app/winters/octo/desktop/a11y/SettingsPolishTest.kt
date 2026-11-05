package app.winters.octo.desktop.a11y

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import app.winters.octo.design.IconSize
import app.winters.octo.design.POPUP_SHADE_TAG
import app.winters.octo.design.POPUP_TAG
import app.winters.octo.desktop.nav.SettingsKey
import app.winters.octo.desktop.pages.LocalRowFlashMs
import app.winters.octo.desktop.pages.RowFlash
import app.winters.octo.desktop.pages.RowId
import app.winters.octo.desktop.pages.RowTitle
import app.winters.octo.desktop.pages.SettingsHere
import app.winters.octo.desktop.pages.searchDesktopSettings
import app.winters.octo.desktop.pages.settingsIconSize
import app.winters.octo.desktop.settings.DesktopOs
import androidx.compose.runtime.CompositionLocalProvider
import org.jetbrains.skia.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.math.abs

// Settings drawn as decided after its pictures were looked over: the list
// keeps the page shown in view, a card's title line is one height and its
// switch ends with the rows', a card switched off keeps its rows dimmed and
// out of reach, a question sits in the page it is about, the icons keep up
// with the text, a row asked for comes in under its card's title, a picture
// choice's keyboard ring is its own, Remove all is plain until its button,
// a page found by search says its group, and the server in use says its
// kind once.
class SettingsPolishTest {
    @get:Rule val folder = TemporaryFolder()

    private fun scene(
        width: Int = 1440,
        height: Int = 900,
        textScale: Float = 1f,
        crossfade: Int = 6,
        saved: Boolean = false,
    ) = A11yScene(
        folder.newFolder(),
        reduceMotion = true,
        width = width,
        height = height,
        textScale = textScale,
        player = SettingsWalk.ShapingPlayer(),
        withSystem = true,
        saved = saved,
        look = { it.copy(playback = it.playback.copy(crossfadeSeconds = crossfade)) },
        wrap = { content -> CompositionLocalProvider(LocalRowFlashMs provides 60_000L) { content() } },
    )

    private fun A11yScene.open(page: String, row: String? = null) {
        onUi { app.openSettings(page, row) }
        render(6)
    }

    private fun SemanticsNode.under(): List<SemanticsNode> = children.flatMap { listOf(it) + it.under() }

    private fun SemanticsNode.words(): String? = config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }

    // Where a node is, whole, even where its list has scrolled it out.
    private val SemanticsNode.whole: Rect get() = Rect(positionInRoot, androidx.compose.ui.geometry.Size(size.width.toFloat(), size.height.toFloat()))

    private fun A11yScene.byId(id: String): SemanticsNode = nodes().first { it.config.getOrNull(RowId) == id }

    // The page's own scrolling column, right of Settings' list.
    private fun A11yScene.page(): SemanticsNode = nodes().first { node ->
        node.boundsInRoot.left > 200f && node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null
    }

    private fun A11yScene.press(spot: Offset) {
        onUi {
            scene.sendPointerEvent(PointerEventType.Enter, spot)
            scene.sendPointerEvent(PointerEventType.Move, spot)
            scene.sendPointerEvent(PointerEventType.Press, spot, buttons = PointerButtons(isPrimaryPressed = true))
            scene.sendPointerEvent(PointerEventType.Release, spot)
        }
        render(6)
    }

    // The list keeps the page shown in view with an item's room under it,
    // clear of its faded foot and of About Octo under the line.
    @Test(timeout = 120_000)
    fun theListKeepsThePageShownInViewWithRoomUnderIt() {
        scene(width = 960, height = 640, textScale = 1.3f).use { s ->
            for (page in listOf(SettingsKey.Keys, SettingsKey.Discord, SettingsKey.Home)) {
                if (page == SettingsKey.Keys) s.open(page) else s.onUi { s.app.settingsUi.page = page }
                // The list glides there.
                s.settle(800).close()
                val tabs = s.nodes(merged = true).filter { it.config.getOrNull(SemanticsProperties.Role) == Role.Tab && it.boundsInRoot.right < 400f }
                val shown = tabs.firstOrNull { it.config.getOrNull(SemanticsProperties.Selected) == true } ?: continue
                val about = tabs.first { it.name().startsWith("About Octo") }.whole
                val item = shown.whole
                // The list's view ends above the line over About Octo.
                val end = about.top - 13f
                val list = s.nodes().filter { it.boundsInRoot.right < 400f }.firstNotNullOfOrNull { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) }
                assertTrue("$page: ${item.bottom} + ${item.height} under ${end + 1}, list at ${list?.value?.invoke()} of ${list?.maxValue?.invoke()}", item.bottom + item.height <= end + 1f)
                assertTrue("$page is in view: ${item.top}", item.top >= 0f)
            }
        }
    }

    // A card's title line is one height whether it ends in a switch, an
    // action or nothing, and a switch there ends where the rows' switches
    // end.
    @Test(timeout = 120_000)
    fun aCardTitleLineIsOneHeightAndItsSwitchEndsWithTheRows() {
        scene().use { s ->
            fun titleLine(card: String): Float {
                val top = s.byId(card).whole.top
                val glaze = s.nodes().filter { it.config.getOrNull(RowId)?.startsWith("$card.") == true }.minOf { it.whole.top }
                return glaze - top
            }
            s.open(SettingsKey.Playback)
            val plain = titleLine("when-the-queue-ends")
            val switched = titleLine("crossfade")
            val titleSwitch = s.nodes().first { it.config.getOrNull(SemanticsProperties.ToggleableState) != null && it.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull() == "Crossfade" }
            val row = s.nodes().first { it.config.getOrNull(RowTitle)?.title == "Smart transitions" }
            val inCard = row.under().first { it.config.getOrNull(SemanticsProperties.HideFromAccessibility) != null }
            assertEquals("the title's switch ends with the rows' switches", inCard.whole.right, titleSwitch.whole.right, 0.5f)
            s.open(SettingsKey.Home)
            val action = titleLine("shelves")
            assertEquals("a switch leaves the line as it is", plain, switched, 0.5f)
            assertEquals("an action leaves the line as it is", plain, action, 0.5f)
        }
    }

    // Crossfade switched off keeps its card and every row, dimmed: nothing
    // in them is a control a screen reader or the keyboard reaches, their
    // names are still read, and the page keeps its height.
    @Test(timeout = 120_000)
    fun aCardSwitchedOffKeepsItsRowsInPlaceAndOutOfReach() {
        scene().use { s ->
            s.open(SettingsKey.Playback)
            // As a screen reader is given them.
            fun rows() = s.nodes(merged = true).filter { it.config.getOrNull(RowId)?.startsWith("crossfade.") == true }
            fun tall() = s.page().config[SemanticsProperties.VerticalScrollAxisRange].maxValue() + s.page().boundsInRoot.height
            val before = rows().map { it.config[RowId] }
            val height = tall()
            assertTrue("crossfade has rows", before.size >= 4)
            val titleSwitch = s.nodes().first { it.config.getOrNull(SemanticsProperties.ToggleableState) != null && it.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull() == "Crossfade" }
            s.onUi { titleSwitch.config[SemanticsActions.OnClick].action?.invoke() }
            s.render(6)
            assertEquals("crossfade is off", 0, s.app.settings.current.playback.crossfadeSeconds)
            assertEquals("every row stays", before, rows().map { it.config[RowId] })
            assertEquals("the page keeps its height", height, tall(), 0.5f)
            val reachable = rows().flatMap { listOf(it) + it.under() }.filter { node ->
                val c = node.config
                c.getOrNull(SemanticsActions.OnClick) != null || c.getOrNull(SemanticsProperties.ToggleableState) != null || c.getOrNull(SemanticsActions.SetProgress) != null
            }
            assertTrue("controls in the rows: ${reachable.map { "${it.name()} ${it.config}" }}", reachable.isEmpty())
            assertTrue("their names are read", rows().any { row -> row.under().any { it.words() == "Smart transitions" } })
            // The keyboard walks past them to the next card.
            val area = rows().map { it.boundsInRoot }.reduce { a, b -> Rect(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom)) }
            var reachedSpeed = false
            repeat(90) {
                s.press(Key.Tab)
                val at = s.focused() ?: return@repeat
                assertTrue("the keyboard landed in an off row: ${at.name()}", !area.contains(at.boundsInRoot.center) || at.boundsInRoot.width == 0f)
                if (at.name() == "Speed") reachedSpeed = true
            }
            assertTrue("the keyboard reaches the card after", reachedSpeed)
        }
    }

    // Removing a server, asked from its row's menu by real clicks: the page
    // is shaded, the question sits in the middle of the page and clear of
    // the cards' edges, and its line holds its words and switch in from its
    // edge.
    @Test(timeout = 120_000)
    fun aQuestionSitsShadedInThePageItIsAbout() {
        scene(saved = true).use { s ->
            s.open(SettingsKey.Servers)
            val more = s.nodes(merged = true).first { it.name().startsWith("More for ") && it.clickable }
            s.press(more.boundsInRoot.center)
            val remove = s.nodes(merged = true).last { it.name() == "Remove" && it.clickable }
            s.press(remove.boundsInRoot.center)
            assertTrue("the sheet asks", s.nodes().any { it.name().startsWith("Remove ") && it.name().endsWith("?") })
            val column = s.app.settingsUi.column!!
            val shade = s.nodes().first { it.config.getOrNull(SemanticsProperties.TestTag) == POPUP_SHADE_TAG }.boundsInRoot
            assertEquals(column.left.toFloat(), shade.left, 0.5f)
            assertEquals(column.top.toFloat(), shade.top, 0.5f)
            assertEquals(column.right.toFloat(), shade.right, 0.5f)
            assertEquals(column.bottom.toFloat(), shade.bottom, 0.5f)
            val sheet = s.nodes().first { it.config.getOrNull(SemanticsProperties.TestTag) == POPUP_TAG }.boundsInRoot
            assertEquals("centered in the page", (column.left + column.right) / 2f, sheet.center.x, 1f)
            for (card in s.nodes().filter { node -> node.config.getOrNull(RowId)?.contains('.') == false && node.boundsInRoot.left > 300f }) {
                val edges = listOf(card.boundsInRoot.left, card.boundsInRoot.right)
                assertTrue("the sheet's edge ${sheet.left} by a card's $edges", edges.all { abs(it - sheet.left) >= 4f })
            }
            val line = s.nodes(merged = true).first { it.config.getOrNull(SemanticsProperties.ToggleableState) != null && it.name().startsWith("Also delete") }.boundsInRoot
            val words = s.nodes().first { it.words() == "Also delete what's kept here for it" }.boundsInRoot
            val switch = s.nodes().first { it.config.getOrNull(SemanticsProperties.HideFromAccessibility) != null && line.contains(it.boundsInRoot.center) }.boundsInRoot
            assertEquals("words 12 in", line.left + 12f, words.left, 0.5f)
            assertTrue("words 10 down: ${words.top - line.top}", words.top - line.top >= 10f - 0.5f)
            assertEquals("switch 12 in", line.right - 12f, switch.right, 0.5f)
        }
        // Remove all, asked from its button by a real click, sits the same way.
        scene(height = 1_400).use { s ->
            s.onUi { s.app.offline.keepLiked(true) }
            s.open(SettingsKey.Offline)
            val button = s.nodes(merged = true).last { it.name() == "Remove" && it.clickable }
            s.press(button.boundsInRoot.center)
            assertTrue("the sheet asks", s.nodes().any { it.name() == "Remove every kept song?" })
            val column = s.app.settingsUi.column!!
            val sheet = s.nodes().first { it.config.getOrNull(SemanticsProperties.TestTag) == POPUP_TAG }.boundsInRoot
            assertEquals("centered in the page", (column.left + column.right) / 2f, sheet.center.x, 1f)
            assertTrue("the page is shaded", s.nodes().any { it.config.getOrNull(SemanticsProperties.TestTag) == POPUP_SHADE_TAG })
        }
    }

    // List items, card titles and search results take the next whole icon
    // size once the text is 115% or more.
    @Test
    fun iconsStepUpWithTheText() {
        assertEquals(IconSize.Toolbar, settingsIconSize(1f))
        assertEquals(IconSize.Toolbar, settingsIconSize(1.1f))
        assertEquals(IconSize.Transport, settingsIconSize(1.15f))
        assertEquals(IconSize.Transport, settingsIconSize(1.3f))
    }

    // A row asked for far down its card, under the window's foot, comes
    // into view under its card's title.
    @Test(timeout = 120_000)
    fun aRowAskedForComesInUnderItsCardsTitle() {
        scene(height = 760).use { s ->
            s.open(SettingsKey.Sound, "eq.automatic-preamp")
            s.render(6)
            assertTrue("the row is lit", s.nodes().any { it.config.getOrNull(RowFlash) != null && it.config.getOrNull(RowId) == "eq.automatic-preamp" })
            val view = s.page().boundsInRoot
            val title = s.byId("eq").under().first { it.config.getOrNull(SemanticsProperties.Heading) != null }.whole
            val row = s.byId("eq.automatic-preamp").whole
            assertTrue("the card's title ${title.top} is in view from ${view.top}", title.top >= view.top - 0.5f)
            assertTrue("the row ${row.bottom} is in view to ${view.bottom}", row.bottom <= view.bottom + 0.5f)
        }
    }

    // A picture choice with the keyboard is ringed in white just outside it,
    // never in the accent the chosen one wears.
    @Test(timeout = 120_000)
    fun aPictureChoiceWithTheKeyboardIsRingedInWhiteOutside() {
        scene().use { s ->
            s.open(SettingsKey.Look)
            val tile = s.nodes(merged = true).first {
                it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("picture:") == true && it.config.getOrNull(SemanticsProperties.Selected) == false
            }
            assertTrue("the keyboard reaches ${tile.name()}", s.tabTo(tile.name(), 200))
            val bitmap = Bitmap.makeFromImage(s.settle(300))
            val box = s.nodes(merged = true).first { it.config.getOrNull(SemanticsProperties.TestTag) == tile.config[SemanticsProperties.TestTag] }.boundsInRoot
            val c = bitmap.getColor(box.left.toInt() - 1, box.center.y.toInt())
            val least = minOf(c shr 16 and 0xFF, c shr 8 and 0xFF, c and 0xFF)
            val around = (-4..3).joinToString(" ") { Integer.toHexString(bitmap.getColor(box.left.toInt() + it, box.center.y.toInt())) }
            assertTrue("white just outside: ${Integer.toHexString(c)} at $box, from ${box.left - 4}: $around, focused ${s.focused()?.name()}", least >= 200)
        }
    }

    // A whole page found by search says the group it is listed under, not
    // its own name again.
    @Test
    fun aPageFoundBySearchSaysItsGroup() {
        val hit = searchDesktopSettings("lyrics", SettingsHere(os = DesktopOs.Windows, engine = true)).first { it.entry.row == null && it.entry.page == SettingsKey.Lyrics }
        assertEquals("Listening", hit.place)
        val window = searchDesktopSettings("keyboard", SettingsHere(os = DesktopOs.Windows)).first { it.entry.row == null && it.entry.page == SettingsKey.Keys }
        assertEquals("This computer", window.place)
    }

    // The server in use says what it is once, under the list, not in its
    // row too.
    @Test(timeout = 120_000)
    fun theServerInUseSaysItsKindOnce() {
        scene(saved = true).use { s ->
            s.open(SettingsKey.Servers)
            val kind = s.byId("this-server.kind").config[RowTitle].title
            val said = s.nodes().count { it.words() == kind }
            assertEquals("\"$kind\" is said once", 1, said)
        }
    }
}
