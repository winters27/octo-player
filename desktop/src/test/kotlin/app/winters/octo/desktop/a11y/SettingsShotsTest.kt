package app.winters.octo.desktop.a11y

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import app.winters.octo.desktop.FakeServer
import app.winters.octo.desktop.nav.Page
import app.winters.octo.desktop.nav.SettingsKey
import app.winters.octo.desktop.nav.Shortcut
import app.winters.octo.desktop.nav.settingsPages
import app.winters.octo.desktop.pages.LocalRowFlashMs
import app.winters.octo.desktop.pages.RowFlash
import app.winters.octo.desktop.pages.RowId
import app.winters.octo.desktop.pages.RowTitle
import app.winters.octo.desktop.pages.askToRemove
import app.winters.octo.desktop.pages.askToRemoveAll
import app.winters.octo.desktop.pages.showSection
import app.winters.octo.desktop.player.DesktopPlayer
import app.winters.octo.desktop.player.SilentPlayer
import app.winters.octo.desktop.server.SignInOutcome
import app.winters.octo.desktop.server.SignInRequest
import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.desktop.settings.name
import app.winters.octo.desktop.update.DesktopUpdates
import app.winters.octo.desktop.update.FakeReleases
import app.winters.octo.ui.family.FAMILY
import app.winters.octo.ui.imports.IMPORT
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Surface
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

// Pictures of every Settings page and its states, for looking at the
// layout: OCTO_SHOTS=1 ./gradlew :desktop:test --tests '*SettingsShotsTest*'
// writes OCTO_SHOTS_DIR (else build/shots/settings-v3) as
// settings-<page>-<size>[-<state>].png. Each picture is checked as it is
// taken: the page's title is shown, no setting's name, card title or page
// name is cut, and a row asked for is lit.
class SettingsShotsTest {
    @get:Rule val folder = TemporaryFolder()

    private val out by lazy { File(System.getenv("OCTO_SHOTS_DIR") ?: "build/shots/settings-v3").apply { mkdirs() } }

    private class Size(val width: Int, val height: Int, val text: Float = 1f) {
        val label: String get() = "${width}x$height" + if (text != 1f) "-text${(text * 100).roundToInt()}" else ""
    }

    private val wide = Size(1440, 900)
    private val sizes = listOf(wide, Size(1980, 1080), Size(960, 640), Size(960, 640, 1.3f))

    // A cover in a color its id picks, so the account card's tiles differ.
    private fun cover(id: String): ByteArray {
        val colors = listOf(0xFF3AA6A0, 0xFFE0703A, 0xFF5B5FC7, 0xFFC7A23A, 0xFF8A1C3C, 0xFF2E7D4F)
        val surface = Surface.makeRasterN32Premul(300, 300)
        surface.canvas.clear(colors[Math.floorMod(id.hashCode(), colors.size)].toInt())
        surface.canvas.drawCircle(150f, 150f, 80f, Paint().apply { color = 0x40FFFFFF })
        return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
    }

    private val albums = """"albumList2":{"album":[${listOf("a1" to "OK Computer", "a3" to "Dummy", "a5" to "Mezzanine", "a7" to "Kid A").joinToString(",") { (id, name) ->
        """{"id":"$id","name":"$name","artist":"Artist","coverArt":"art-$id","songCount":3}"""
    }}]}"""

    private fun scene(
        size: Size,
        os: DesktopOs = DesktopOs.Windows,
        player: DesktopPlayer = SettingsWalk.ShapingPlayer(),
        extensions: List<String> = listOf(),
        updates: ((SettingsStore) -> DesktopUpdates)? = null,
        serve: (FakeServer) -> Unit = {},
    ) = A11yScene(
        folder.newFolder(),
        reduceMotion = true,
        width = size.width,
        height = size.height,
        textScale = size.text,
        player = player,
        os = os,
        withSystem = true,
        saved = true,
        extensions = extensions,
        updates = updates,
        wrap = { content -> CompositionLocalProvider(LocalRowFlashMs provides 60_000L) { content() } },
        serve = { server ->
            server.answer("getAlbumList2", albums)
            server.fileBy("getCoverArt") { cover(it.url.queryParameter("id").orEmpty()) }
            serve(server)
        },
    ).also { s ->
        s.onUi {
            s.app.play(s.app.library!!.index!!.songs, 0)
            s.app.player.togglePlay()
        }
        s.render(4)
    }

    private fun SemanticsNode.under(): List<SemanticsNode> = children.flatMap { listOf(it) + it.under() }

    private fun SemanticsNode.words(): String? = config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }

    // Words cut short: a line ending in an ellipsis, more lines than fit, or
    // a word broken across two lines. The layout a screen reader is given is
    // made at the widest the words could take, so its width says nothing
    // about a cut.
    private fun TextLayoutResult.cut(): Boolean {
        val words = layoutInput.text.text
        val broken = (0 until lineCount - 1).any { line ->
            val end = getLineEnd(line)
            end in 1 until words.length && !words[end - 1].isWhitespace() && words[end - 1] != '-' && !words[end].isWhitespace()
        }
        return broken || didOverflowHeight || (0 until lineCount).any { isLineEllipsized(it) }
    }

    // The words that must never be cut, and were: each setting's name, each
    // heading, and each name in Settings' list.
    private fun A11yScene.cutWords(): List<String> {
        val cut = mutableListOf<String>()
        onUi {
            val all = nodes()
            val watched = buildList {
                for (node in all) {
                    val title = node.config.getOrNull(RowTitle)
                    if (title != null) node.under().firstOrNull { it.words() == title.title }?.let(::add)
                    if (node.config.getOrNull(SemanticsProperties.Heading) != null) add(node)
                    if (node.config.getOrNull(SemanticsProperties.Role) == Role.Tab) addAll(node.under().filter { it.words() != null })
                }
            }
            for (node in watched.distinct()) {
                val laid = mutableListOf<TextLayoutResult>()
                node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(laid)
                if (laid.firstOrNull()?.cut() == true) cut += node.words().orEmpty()
            }
        }
        return cut
    }

    // What the pictures showed wrong, said once every picture is made.
    private val problems = mutableListOf<String>()

    @After
    fun noProblems() = assertTrue("the pictures show:\n" + problems.joinToString("\n"), problems.isEmpty())

    // Saves the window as `name`, then checks it.
    private fun A11yScene.shot(name: String, title: String? = null, lit: String? = null, ms: Long = 700) {
        val image = settle(ms)
        File(out, "$name.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        image.close()
        if (title != null && nodes().none { it.config.getOrNull(SemanticsProperties.Heading) != null && it.name() == title }) problems += "$name: its title \"$title\" is not shown"
        val cut = cutWords()
        if (cut.isNotEmpty()) problems += "$name: cut: $cut"
        if (lit != null && nodes().none { it.config.getOrNull(RowFlash) != null && it.config.getOrNull(RowId) == lit }) problems += "$name: $lit is not lit"
    }

    // The page's own scroll, when it has room to scroll.
    private fun A11yScene.pageScrolls(): Boolean = nodes().any { node ->
        node.boundsInRoot.left > 200f && (node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.maxValue?.invoke() ?: 0f) > 0f
    }

    private fun A11yScene.wheel(ticks: Int) {
        val at = Offset(width * 0.65f, height * 0.5f)
        onUi {
            repeat(ticks) { scene.sendPointerEvent(PointerEventType.Scroll, at, scrollDelta = Offset(0f, 1f)) }
            // The pointer goes to the title bar, so no row shows its hover.
            scene.sendPointerEvent(PointerEventType.Move, Offset(width * 0.5f, 8f))
        }
        render(4)
    }

    private fun A11yScene.open(page: String, row: String? = null) {
        onUi { app.openSettings(page, row) }
        render(4)
    }

    // Words typed into Settings' search, the keyboard there.
    private fun A11yScene.search(words: String) {
        onUi { app.perform(Shortcut.Filter) }
        render(2)
        onUi { app.settingsUi.query = words }
        render(4)
    }

    private fun A11yScene.results(): List<SemanticsNode> = nodes(merged = true).filter {
        it.config.getOrNull(SemanticsProperties.Role) == Role.Button && it.config.getOrNull(SemanticsProperties.Selected) != null && it.boundsInRoot.right < width / 2f
    }

    private fun A11yScene.account(): String = nodes(merged = true).first { it.clickable && it.name().startsWith("winters, ") }.name()

    private fun A11yScene.until(what: String, check: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!check()) {
            check(System.currentTimeMillis() < end) { "waited too long for $what" }
            render(2)
        }
    }

    private fun withDiscord(block: () -> Unit) {
        System.setProperty("octo.discordAppId", "1234567890123456789")
        try {
            block()
        } finally {
            System.clearProperty("octo.discordAppId")
        }
    }

    // The narrowest window at the largest text, without pictures: the
    // quality pickers leave their rows' names and lines whole, and About
    // with its version and an update's dot is not broken. Every word in a
    // setting's row is watched here, its line under the name too.
    @Test(timeout = 120_000)
    fun theNarrowestWindowCutsNoWords() {
        FakeReleases().use { github ->
            scene(sizes.last(), updates = SettingsWalk.readyUpdates(github, folder.newFolder())).use { s ->
                for (page in listOf(SettingsKey.Quality, SettingsKey.Offline)) {
                    s.open(page)
                    s.render(4)
                    val cut = s.cutWords().toMutableList()
                    s.onUi {
                        for (row in s.nodes().filter { it.config.getOrNull(RowTitle) != null }) {
                            for (node in row.under().filter { it.words() != null }) {
                                val laid = mutableListOf<TextLayoutResult>()
                                node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(laid)
                                if (laid.firstOrNull()?.cut() == true) cut += node.words().orEmpty()
                            }
                        }
                    }
                    assertTrue("$page cuts: ${cut.distinct()}", cut.isEmpty())
                }
            }
        }
    }

    // Every page at every size, its foot too where it scrolls, with an
    // update ready, a Discord build and a server that answers.
    @Test(timeout = 600_000)
    fun drawEveryPageAtEverySize() {
        assumeTrue(System.getenv("OCTO_SHOTS") == "1")
        withDiscord {
            FakeReleases().use { github ->
                for (size in sizes) {
                    scene(size, updates = SettingsWalk.readyUpdates(github, folder.newFolder())).use { s ->
                        s.open(SettingsKey.Playback)
                        s.settle(1_500).close()
                        assertTrue("the account answered", s.account().endsWith("Connected"))
                        for (page in settingsPages(windows = true, discord = true)) {
                            s.open(page.key)
                            s.shot("settings-${page.key}-${size.label}", page.name)
                            if (size !== sizes[1] && s.pageScrolls()) {
                                s.wheel(80)
                                s.shot("settings-${page.key}-${size.label}-end", page.name)
                            }
                        }
                    }
                }
            }
        }
    }

    // Sound without the sound engine, and Playback without its blend and
    // speed cards.
    @Test(timeout = 300_000)
    fun drawThePagesWithoutTheEngine() {
        assumeTrue(System.getenv("OCTO_SHOTS") == "1")
        scene(wide, player = SilentPlayer()).use { s ->
            s.open(SettingsKey.Sound)
            s.shot("settings-sound-${wide.label}-no-engine", "Sound")
            assertTrue(s.nodes().any { it.config.getOrNull(RowTitle)?.title == "Nothing to shape" })
            s.open(SettingsKey.Playback)
            s.shot("settings-playback-${wide.label}-no-engine", "Playback")
            assertTrue(s.nodes().none { it.config.getOrNull(RowId) == "crossfade" })
        }
    }

    // Servers with a newer Octo for the server in use, its mark in the list.
    @Test(timeout = 300_000)
    fun drawServersWithAServerUpdate() {
        assumeTrue(System.getenv("OCTO_SHOTS") == "1")
        val behind = """{"enabled":true,"repo":"winters27/octo","running":"2026.10.11","latest":{"tag":"2026.10.13"},"newer":[{"tag":"2026.10.13"}],"updateAvailable":true,"standing":"behind","helper":{"installed":true,"mode":"image","outdated":false},"pending":false,"run":null}"""
        scene(wide, extensions = listOf("octoAcquisitions:1"), serve = { server ->
            server.answerBy("ping") { """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"octo","serverVersion":"2026.10.11","openSubsonic":true}}""" }
            server.answerBy("update") { behind }
        }).use { s ->
            s.open(SettingsKey.Servers)
            s.until("the server update mark") { s.nodes(merged = true).any { it.config.getOrNull(SemanticsProperties.Role) == Role.Tab && it.name().startsWith("Servers, ") } }
            s.shot("settings-servers-${wide.label}-server-update", "Servers")
        }
    }

    // The account card before its first check answers, when the server
    // cannot be reached, and while a switch to another server is under way.
    @Test(timeout = 300_000)
    fun drawTheAccountCardsStates() {
        assumeTrue(System.getenv("OCTO_SHOTS") == "1")
        // Pings after signing in wait on `held`, or fail once `down` is set.
        val held = AtomicReference<CountDownLatch?>(null)
        val down = AtomicReference(false)
        fun pings(server: FakeServer) = server.answerBy("ping") {
            held.get()?.await(10, TimeUnit.SECONDS)
            if (down.get()) server.failed(0, "Connection refused") else server.ok()
        }
        scene(wide, serve = ::pings).use { s ->
            val gate = CountDownLatch(1)
            held.set(gate)
            s.open(SettingsKey.Playback)
            s.render(6)
            assertEquals("no mark before the check answers", "winters, ${s.app.connection!!.server.name}", s.account())
            s.shot("settings-playback-${wide.label}-account-unchecked", "Playback")
            held.set(null)
            gate.countDown()
        }
        down.set(false)
        scene(wide, serve = ::pings).use { s ->
            down.set(true)
            s.open(SettingsKey.Playback)
            s.until("Can't reach") { s.account().endsWith("Can't reach") }
            s.shot("settings-playback-${wide.label}-account-cant-reach", "Playback")
        }
        down.set(false)
        FakeServer().use { other ->
            other.answer("getOpenSubsonicExtensions", """"openSubsonicExtensions":[]""")
            val otherHeld = CountDownLatch(1)
            val holding = AtomicReference(false)
            other.answerBy("ping") {
                if (holding.get()) otherHeld.await(10, TimeUnit.SECONDS)
                other.ok()
            }
            scene(wide, serve = ::pings).use { s ->
                val id = (runBlocking { s.app.accounts.add(SignInRequest(other.address, "brandon", "pw")) } as SignInOutcome.Saved).server.id
                s.open(SettingsKey.Servers)
                s.until("the first covers") { s.nodes().any { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("account-cover:art-") == true } }
                s.shot("settings-servers-${wide.label}-account-connected", "Servers")
                holding.set(true)
                s.onUi { s.app.switchTo(id, Page.Settings) }
                s.until("the switch") { s.app.switching != null }
                s.render(4)
                assertTrue(s.nodes(merged = true).any { it.clickable && it.name().endsWith("Switching") })
                s.shot("settings-servers-${wide.label}-account-switching", "Servers")
                otherHeld.countDown()
            }
        }
    }

    // Settings' search: several results with the first marked, one, none,
    // a result opened (its row lit and holding the keyboard), Discord's
    // mark in a result, and the window page as macOS names it.
    @Test(timeout = 300_000)
    fun drawTheSearchStates() {
        assumeTrue(System.getenv("OCTO_SHOTS") == "1")
        withDiscord {
            for (size in listOf(wide, sizes[3])) {
                scene(size).use { s ->
                    s.open(SettingsKey.Playback)
                    s.search("motion")
                    assertTrue("several results", s.results().size >= 2)
                    assertEquals("the first is marked", true, s.results().first().config.getOrNull(SemanticsProperties.Selected))
                    s.shot("settings-search-${size.label}-several", "Playback")
                    if (size !== wide) return@use
                    s.search("calm motion")
                    assertEquals(1, s.results().size)
                    s.shot("settings-search-${size.label}-one", "Playback")
                    s.search("zebra crossing")
                    assertTrue(s.nodes().any { it.name() == "No settings match" })
                    s.shot("settings-search-${size.label}-none", "Playback")
                    s.search("friends")
                    assertTrue("a Discord result", s.results().any { it.name().startsWith("Friends' list shows") })
                    s.shot("settings-search-${size.label}-discord", "Playback")
                    s.search("brightness cap")
                    s.press(Key.Enter)
                    s.until("the page") { s.app.settingsUi.page == SettingsKey.Look }
                    s.shot("settings-look-${size.label}-search-opened", "Appearance", lit = "full-player.brightness-cap")
                    assertTrue("the keyboard is on the lit row", s.focused()?.boundsInRoot?.left?.let { it > s.width / 4f } == true)
                }
            }
        }
        scene(wide, os = DesktopOs.Mac).use { s ->
            s.open(SettingsKey.Playback)
            s.search("window")
            assertTrue("macOS names the page Window", s.results().any { it.name() == "Window, Window" || it.name().startsWith("Window,") })
            s.shot("settings-search-${wide.label}-mac-window", "Playback")
        }
    }

    // The two questions asked before letting go, and each picture choice.
    @Test(timeout = 300_000)
    fun drawTheSheetsAndPictureChoices() {
        assumeTrue(System.getenv("OCTO_SHOTS") == "1")
        scene(wide).use { s ->
            s.onUi { s.app.offline.keepLiked(true) }
            s.open(SettingsKey.Offline, "remove-all")
            s.render(4)
            s.onUi { askToRemoveAll(s.app) }
            s.shot("settings-offline-${wide.label}-remove-all-sheet", "Streaming and downloads")
            assertTrue(s.nodes().any { it.name() == "Remove every kept song?" })
            s.onUi { s.app.popups.close() }
            s.open(SettingsKey.Servers)
            s.onUi { askToRemove(s.app, s.app.settings.current.servers.first()) }
            s.shot("settings-servers-${wide.label}-remove-server-sheet", "Servers")
            s.onUi { s.app.popups.close() }
            for ((row, state) in listOf("window-background.ambience" to "ambience", "covers.playlist-covers" to "playlist-covers", "full-player.animated-art" to "animated-art")) {
                s.open(SettingsKey.Look, row)
                s.shot("settings-look-${wide.label}-$state", "Appearance", lit = row)
            }
        }
    }

    // A sidebar folded to icons at the narrowest window, then Settings
    // taking its place with every name whole.
    @Test(timeout = 300_000)
    fun drawSettingsFromAFoldedSidebar() {
        assumeTrue(System.getenv("OCTO_SHOTS") == "1")
        val narrow = sizes[2]
        scene(narrow).use { s ->
            s.onUi {
                s.app.updateFrame { it.copy(sidebarRail = true) }
                s.app.navigator.go(Page.Home)
            }
            s.render(4)
            s.shot("settings-sidebar-${narrow.label}-folded")
            s.open(SettingsKey.Playback)
            s.shot("settings-playback-${narrow.label}-from-folded", "Playback")
            assertTrue("the sidebar stays folded", s.app.settings.current.frame.sidebarRail)
        }
    }

    // Family and Import, whose own lists pick their section as Settings' list
    // picks its page.
    @Test(timeout = 300_000)
    fun drawFamilyAndImport() {
        assumeTrue(System.getenv("OCTO_SHOTS") == "1")
        val owner = """"family":{"me":{"username":"winters","displayName":"Jordan","role":"Owner","managed":false,
            "abilities":{"addToLibrary":"Direct","requestQuality":"Best","autoApprove":true,"weeklyRequestLimit":0,"streamCap":0,"awayCap":0,
            "away":true,"devicesAtOnce":0,"downloadFiles":true,"offlineCopies":true,"share":true,"importPlaylists":true,"cleanOnly":false,
            "familyPlaylistsEdit":true,"manageFamily":true,"approveRequests":true,"storageLimitGb":0,"instantFromFamily":true},
            "requestsThisWeek":0,"storageUsedBytes":0,"place":"Home","deviceId":null},
            "manager":{"members":[
              {"username":"alex","displayName":"Alex","role":"Listener","suspended":false,"devices":2,"playingNow":true,"pendingRequests":2,"storageUsedBytes":5400000000,"storageLimitGb":10},
              {"username":"sam","displayName":"Sam","role":"Kid","suspended":false,"devices":1,"playingNow":false,"pendingRequests":0,"storageUsedBytes":800000000,"storageLimitGb":5}],
            "pendingRequests":2,"liveStreams":1}}"""
        scene(wide, extensions = listOf("octoFamily:1", "octoImports:2"), serve = { server ->
            server.answer("getFamily", owner, type = "octo")
            server.answer("getImportServices", """"importServices":{"services":[{"id":"spotify","name":"Spotify","exportUrl":"https://www.tunemymusic.com/transfer/spotify-to-file","tile":"Spotify"}]}""", type = "octo")
            server.answer("getImports", """"imports":{"lists":[{"id":"file-gym","name":"Gym","source":"file","total":31,"have":31}]}""", type = "octo")
        }).use { s ->
            runBlocking { s.app.family.plan() }
            s.onUi {
                showSection(FAMILY, "plan")
                s.app.navigator.go(Page.Family)
            }
            s.render(6)
            s.shot("settings-family-${wide.label}", FAMILY, ms = 1_500)
            s.onUi {
                showSection(IMPORT, "lists")
                s.app.navigator.go(Page.Imports)
            }
            s.render(6)
            s.shot("settings-import-${wide.label}", IMPORT, ms = 1_500)
        }
    }
}
