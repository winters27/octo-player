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
import app.winters.octo.desktop.FakeServer
import app.winters.octo.desktop.nav.SettingsKey
import app.winters.octo.desktop.nav.Shortcut
import app.winters.octo.desktop.pages.LocalRowFlashMs
import app.winters.octo.desktop.pages.appVersion
import app.winters.octo.desktop.pages.askToRemove
import app.winters.octo.desktop.pages.askToRemoveAll
import app.winters.octo.desktop.pages.itemIconTag
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.desktop.settings.name
import app.winters.octo.desktop.update.DesktopUpdates
import app.winters.octo.desktop.update.FakeReleases
import app.winters.octo.desktop.pages.RowFlash
import app.winters.octo.desktop.pages.RowId
import app.winters.octo.desktop.pages.RowTitle
import app.winters.octo.desktop.pages.STATIONS_ON_SERVER
import app.winters.octo.desktop.pages.SettingsHere
import app.winters.octo.desktop.nav.settingsPages
import app.winters.octo.desktop.pages.settingsPageIcon
import app.winters.octo.desktop.pages.searchDesktopSettings
import app.winters.octo.desktop.pages.settingsIconSize
import app.winters.octo.desktop.settings.DesktopOs
import androidx.compose.runtime.CompositionLocalProvider
import app.winters.octo.desktop.settings.SHARED_SOUND
import app.winters.octo.sound.Crossfeed
import app.winters.octo.sound.CrossfeedPreset
import app.winters.octo.sound.SoundSettings
import org.jetbrains.skia.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        saved: Boolean = false,
        serve: (FakeServer) -> Unit = {},
        updates: ((SettingsStore) -> DesktopUpdates)? = null,
    ) = A11yScene(
        folder.newFolder(),
        reduceMotion = true,
        width = width,
        height = height,
        textScale = textScale,
        player = SettingsWalk.ShapingPlayer(),
        withSystem = true,
        saved = saved,
        serve = serve,
        updates = updates,
        // Crossfeed on with its own values, so its card shows every row.
        look = { it.copy(sound = it.sound.copy(profiles = mapOf(SHARED_SOUND to SoundSettings(crossfeed = Crossfeed(on = true, preset = CrossfeedPreset.Custom))))) },
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
                // The list's view ends at the line over About Octo.
                val end = about.top - 7f
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
            s.open(SettingsKey.Sound)
            val plain = titleLine("loudness")
            val switched = titleLine("crossfeed")
            val titleSwitch = s.nodes().first { it.config.getOrNull(SemanticsProperties.ToggleableState) != null && it.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull() == "Crossfeed" }
            val row = s.nodes().first { it.config.getOrNull(RowTitle)?.title == "Each output keeps its own sound" }
            val inCard = row.under().first { it.config.getOrNull(SemanticsProperties.HideFromAccessibility) != null }
            assertEquals("the title's switch ends with the rows' switches", inCard.whole.right, titleSwitch.whole.right, 0.5f)
            s.open(SettingsKey.Home)
            val action = titleLine("shelves")
            assertEquals("a switch leaves the line as it is", plain, switched, 0.5f)
            assertEquals("an action leaves the line as it is", plain, action, 0.5f)
        }
    }

    // Crossfeed switched off keeps its card and every row, dimmed: nothing
    // in them is a control a screen reader or the keyboard reaches, their
    // names are still read, and the page keeps its height.
    @Test(timeout = 120_000)
    fun aCardSwitchedOffKeepsItsRowsInPlaceAndOutOfReach() {
        scene().use { s ->
            s.open(SettingsKey.Sound)
            // As a screen reader is given them.
            fun rows() = s.nodes(merged = true).filter { it.config.getOrNull(RowId)?.startsWith("crossfeed.") == true }
            fun tall() = s.page().config[SemanticsProperties.VerticalScrollAxisRange].maxValue() + s.page().boundsInRoot.height
            val before = rows().map { it.config[RowId] }
            val height = tall()
            assertTrue("crossfeed has rows: $before", before.size >= 3)
            val titleSwitch = s.nodes().first { it.config.getOrNull(SemanticsProperties.ToggleableState) != null && it.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull() == "Crossfeed" }
            s.onUi { titleSwitch.config[SemanticsActions.OnClick].action?.invoke() }
            s.render(6)
            assertFalse("crossfeed is off", s.app.sound!!.current.value.crossfeed.on)
            assertEquals("every row stays", before, rows().map { it.config[RowId] })
            assertEquals("the page keeps its height", height, tall(), 0.5f)
            val reachable = rows().flatMap { listOf(it) + it.under() }.filter { node ->
                val c = node.config
                c.getOrNull(SemanticsActions.OnClick) != null || c.getOrNull(SemanticsProperties.ToggleableState) != null || c.getOrNull(SemanticsActions.SetProgress) != null
            }
            assertTrue("controls in the rows: ${reachable.map { "${it.name()} ${it.config}" }}", reachable.isEmpty())
            assertTrue("their names are read", rows().any { row -> row.under().any { it.words() == "Preset" } })
            // The keyboard walks past them to the next card.
            val area = rows().map { it.boundsInRoot }.reduce { a, b -> Rect(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom)) }
            var reachedNext = false
            repeat(120) {
                s.press(Key.Tab)
                val at = s.focused() ?: return@repeat
                assertTrue("the keyboard landed in an off row: ${at.name()}", !area.contains(at.boundsInRoot.center) || at.boundsInRoot.width == 0f)
                if (at.name() == "Import") reachedNext = true
            }
            assertTrue("the keyboard reaches the card after", reachedNext)
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

    // No two cards on one page share an icon, and no card wears the icon of
    // another page's item in Settings' list unless the card is about that
    // page's subject, as every page, computer and build draws them.
    @Test(timeout = 300_000)
    fun everyCardKeepsAnIconOfItsOwn() {
        val drawn = SettingsWalk.windows.flatMap { it.cards }.distinctBy { it.page to (it.id ?: it.card.title) }
        assertTrue("cards with icons were drawn: ${drawn.size}", drawn.size >= 25)
        val problems = mutableListOf<String>()
        drawn.groupBy { it.page }.forEach { (page, cards) ->
            cards.groupBy { it.card.icon }.values.filter { it.size > 1 }.forEach { same -> problems += "$page: ${same.map { it.card.title }} share an icon" }
        }
        for (card in drawn) {
            for (item in settingsPages(windows = true, discord = true).map { it.key }) {
                if (item == card.page || SameSubject[card.page to card.card.title] == item) continue
                if (settingsPageIcon(item) === card.card.icon) problems += "${card.page}: \"${card.card.title}\" wears the $item item's icon"
            }
        }
        assertTrue("cards sharing icons:\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    // Cards about another page's subject, which may wear its item's icon.
    private val SameSubject = mapOf((SettingsKey.Radio to STATIONS_ON_SERVER) to SettingsKey.Servers)

    // Settings' list, when it does not fit: its view ends at the line over
    // About Octo, and a faint thumb says there is more while the pointer
    // is nowhere near.
    @Test(timeout = 120_000)
    fun aListThatDoesNotFitEndsAtItsLineAndShowsItsThumbAtRest() {
        scene(width = 960, height = 640, textScale = 1.3f).use { s ->
            s.open(SettingsKey.Playback)
            val image = Bitmap.makeFromImage(s.settle(2_000))
            val list = s.list()
            val about = s.nodes(merged = true).first { it.config.getOrNull(SemanticsProperties.Role) == Role.Tab && it.name().startsWith("About Octo") }.boundsInRoot
            assertTrue("the view ends ${about.top - list.bottom} above About Octo", about.top - list.bottom <= 7.5f)
            val y = list.top.toInt() + 12
            val thumb = image.getColor(list.right.toInt() - 5, y)
            val beside = image.getColor(list.right.toInt() - 12, y)
            fun sum(c: Int) = (c shr 16 and 0xFF) + (c shr 8 and 0xFF) + (c and 0xFF)
            assertTrue("a thumb at rest: ${Integer.toHexString(thumb)} beside ${Integer.toHexString(beside)}", sum(thumb) - sum(beside) >= 30)
        }
    }

    // Settings' list's own scrolling view.
    private fun A11yScene.list(): Rect = nodes().first { node ->
        node.boundsInRoot.right < 400f && node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null
    }.boundsInRoot

    private fun A11yScene.listScroll(): Float = nodes().first { node ->
        node.boundsInRoot.right < 400f && node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null
    }.config[SemanticsProperties.VerticalScrollAxisRange].value()

    // Both sheets give Cancel the keyboard first; opened with the mouse,
    // the keyboard is still Cancel's but no ring shows.
    @Test(timeout = 120_000)
    fun theSheetsGiveCancelTheKeyboardFirst() {
        scene(saved = true).use { s ->
            s.open(SettingsKey.Servers)
            s.onUi { askToRemove(s.app, s.app.connection!!.server) }
            s.render(6)
            assertEquals("Remove server, from the keyboard", "Cancel", s.focused()?.name())
            s.onUi { s.app.popups.close() }
            s.render(4)
            val more = s.nodes(merged = true).first { it.name().startsWith("More for ") && it.clickable }
            s.press(more.boundsInRoot.center)
            val remove = s.nodes(merged = true).last { it.name() == "Remove" && it.clickable }
            s.press(remove.boundsInRoot.center)
            assertEquals("Remove server, from the mouse", "Cancel", s.focused()?.name())
            assertTrue("no ring after a mouse open", !s.keyboard.keyboard)
            s.onUi { s.app.popups.close() }
            s.render(4)
            s.onUi {
                s.keyboard.keyboard = true
                s.app.offline.keepLiked(true)
                s.app.openSettings(SettingsKey.Offline)
            }
            s.render(6)
            s.onUi { askToRemoveAll(s.app) }
            s.render(6)
            assertEquals("Remove all, from the keyboard", "Cancel", s.focused()?.name())
        }
    }

    // A search hit lands as a deep link does: its card's title in view.
    @Test(timeout = 120_000)
    fun aSearchHitComesInUnderItsCardsTitle() {
        scene().use { s ->
            s.open(SettingsKey.Playback)
            s.onUi { s.app.perform(Shortcut.Filter) }
            s.render(2)
            s.onUi { s.app.settingsUi.query = "brightness cap" }
            s.render(4)
            s.press(Key.Enter)
            // As long as the keyboard's arrival and anything it moves take.
            s.settle(1_200).close()
            assertEquals(SettingsKey.Look, s.app.settingsUi.page)
            val view = s.page().boundsInRoot
            val title = s.byId("full-player").under().first { it.config.getOrNull(SemanticsProperties.Heading) != null }.whole
            assertTrue("the card's title ${title.top} is in view from ${view.top}", title.top >= view.top - 0.5f)
        }
    }

    // The account card's server name stays put as its check answers, and
    // when the server cannot be reached.
    @Test(timeout = 120_000)
    fun theAccountCardsServerNameStaysPut() {
        val held = java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CountDownLatch?>(null)
        val down = java.util.concurrent.atomic.AtomicBoolean(false)
        val gate = java.util.concurrent.CountDownLatch(1)
        scene(saved = true, serve = { server ->
            server.answerBy("ping") {
                held.get()?.await(10, java.util.concurrent.TimeUnit.SECONDS)
                if (down.get()) server.failed(0, "Connection refused") else server.ok()
            }
        }).use { s ->
            fun nameAt(): Float = s.nodes().first { it.words() == s.app.connection!!.server.name && it.boundsInRoot.right < 300f && it.boundsInRoot.top < 200f }.boundsInRoot.left
            held.set(gate)
            s.open(SettingsKey.Playback)
            val unchecked = nameAt()
            held.set(null)
            gate.countDown()
            s.waitFor { s.app.serverFacts.checks[s.app.connection!!.server.id] != null }
            s.render(4)
            val connected = nameAt()
            down.set(true)
            kotlinx.coroutines.runBlocking { s.app.serverFacts.refresh() }
            s.render(4)
            val cantReach = nameAt()
            assertEquals("connected against unchecked", unchecked, connected, 0.5f)
            assertEquals("can't reach against unchecked", unchecked, cantReach, 0.5f)
        }
    }

    // A list item's icon sits on its name's first line, however many lines
    // the name takes, and About Octo's on its name above its version.
    @Test(timeout = 120_000)
    fun aListItemsIconSitsOnItsNamesFirstLine() {
        scene(width = 960, height = 640, textScale = 1.3f).use { s ->
            s.open(SettingsKey.Offline)
            s.settle(600).close()
            for ((page, name) in listOf(SettingsKey.Offline to "Streaming and downloads", SettingsKey.About to "About Octo")) {
                val icon = s.nodes().first { it.config.getOrNull(SemanticsProperties.TestTag) == itemIconTag(page) }.whole
                val words = s.nodes().first { it.words() == name && it.boundsInRoot.right < 400f }
                val laid = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                s.onUi { words.config[SemanticsActions.GetTextLayoutResult].action?.invoke(laid) }
                val first = laid.first()
                val middle = words.whole.top + (first.getLineTop(0) + first.getLineBottom(0)) / 2
                assertEquals("$name's icon on its first line (lines ${first.lineCount})", middle, icon.center.y, 1f)
            }
        }
    }

    // Picking About Octo, in the list's foot, leaves the list where it is,
    // and so does a page shown that is under a nudge from where it should
    // sit.
    @Test(timeout = 120_000)
    fun theListStaysForAboutAndForANudge() {
        scene(width = 960, height = 640, textScale = 1.3f).use { s ->
            s.open(SettingsKey.Playback)
            s.settle(600).close()
            val before = s.listScroll()
            val about = s.nodes(merged = true).first { it.config.getOrNull(SemanticsProperties.Role) == Role.Tab && it.name().startsWith("About Octo") }
            s.press(about.boundsInRoot.center)
            s.settle(800).close()
            assertEquals(SettingsKey.About, s.app.settingsUi.page)
            assertEquals("About leaves the list", before, s.listScroll(), 0.5f)
            // Keyboard shown at the list's end, Window and startup picked (in
            // view with room), the list moved up 6 px, then Keyboard picked
            // again: the 6 px it would need are left.
            s.open(SettingsKey.Keys)
            s.settle(800).close()
            val keys = s.listScroll()
            s.onUi { s.app.settingsUi.page = SettingsKey.System }
            s.settle(800).close()
            val system = s.listScroll()
            val list = s.nodes().first { it.boundsInRoot.right < 400f && it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }
            s.onUi { list.config[SemanticsActions.ScrollBy].action?.invoke(0f, -6f) }
            s.settle(400).close()
            val nudged = s.listScroll()
            s.onUi { s.app.settingsUi.page = SettingsKey.Keys }
            s.settle(800).close()
            assertEquals("a 6 px move is left (Keyboard $keys, Window $system, moved $nudged)", nudged, s.listScroll(), 0.5f)
        }
    }

    // About Octo's version stays where it is whether or not an update's dot
    // is beside it.
    @Test(timeout = 120_000)
    fun aboutsVersionStaysPutWithAndWithoutItsDot() {
        fun versionAt(s: A11yScene): Float = s.nodes().first { it.words() == appVersion() && it.boundsInRoot.right < 400f }.boundsInRoot.left
        val without = scene().use { s -> s.open(SettingsKey.Playback); versionAt(s) }
        val with = FakeReleases().use { github ->
            scene(updates = SettingsWalk.readyUpdates(github, folder.newFolder())).use { s -> s.open(SettingsKey.Playback); versionAt(s) }
        }
        assertEquals("the version with the dot and without", without, with, 0.5f)
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
