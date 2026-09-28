package app.winters.octo.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import app.winters.octo.desktop.listening.listeningFolder
import app.winters.octo.desktop.livelists.LiveListStore
import app.winters.octo.desktop.nav.Page
import app.winters.octo.desktop.nav.SidebarItem
import app.winters.octo.desktop.nav.sidebarItemOf
import app.winters.octo.desktop.pages.LiveListPage
import app.winters.octo.desktop.pages.NewLiveListPage
import app.winters.octo.desktop.player.SilentPlayer
import app.winters.octo.desktop.search.commandsFor
import app.winters.octo.desktop.secrets.SessionOnlySecrets
import app.winters.octo.desktop.server.Accounts
import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.desktop.ui.Sidebar
import app.winters.octo.livelists.LiveList
import app.winters.octo.livelists.LiveListsJson
import app.winters.octo.lyrics.OnlineLyrics
import app.winters.octo.playback.QueueSource
import app.winters.octo.query.FilterPresets
import app.winters.octo.query.LibraryQuery
import app.winters.octo.query.QuerySort
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

// Live lists in the window, against a pretend server: made, opened, played,
// copied to the server, deleted, in the sidebar, and kept per account.
@OptIn(ExperimentalComposeUiApi::class)
class LiveListActionsTest {
    @get:Rule val temp = TemporaryFolder()

    private val server = FakeServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val player = SilentPlayer()

    @After
    fun stop() {
        scope.cancel()
        server.close()
    }

    private fun song(id: String, title: String, starred: Boolean = false, suffix: String = "mp3", cover: String = "c$id") =
        """{"id":"$id","title":"$title","artist":"Artist $id","album":"Album $id","albumId":"al$id","coverArt":"$cover","duration":200,"suffix":"$suffix"""" +
            (if (starred) ""","starred":"2026-09-01T00:00:00Z"""" else "") + "}"

    // A library of four songs: two favourites in FLAC, one favourite MP3.
    private fun app(listeningRoot: File? = null): AppState {
        server.answer("getPlaylists", """"playlists":{"playlist":[{"id":"p1","name":"Road trip","owner":"winters","songCount":3}]}""")
        server.answer("getAlbumList2", """"albumList2":{}""")
        server.answer("getArtists", """"artists":{}""")
        server.answer(
            "search3",
            """"searchResult3":{"song":[${song("s1", "One", starred = true, suffix = "flac")},${song("s2", "Two")},${song("s3", "Three", starred = true, suffix = "flac")},${song("s4", "Four", starred = true)}]}""",
        )
        val settings = SettingsStore(File(temp.root, "settings.json"), 0)
        val http = OkHttpClient()
        val app = AppState(
            settings,
            Accounts(settings, SessionOnlySecrets(), http),
            http,
            scope,
            DesktopOs.Windows,
            player,
            OnlineLyrics(http, server.address.toHttpUrl()),
            restored = server.connection(),
            listeningRoot = listeningRoot,
        )
        waitFor { app.library?.index != null && app.playlists.isNotEmpty() }
        return app
    }

    private fun waitFor(what: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!what() && System.currentTimeMillis() < until) Thread.sleep(20)
        assertTrue("waited too long", what())
    }

    private fun callsTo(endpoint: String) = server.calls.filter { it.url.pathSegments.last() == endpoint }

    private val losslessFavourites = LibraryQuery(listOf(FilterPresets.Favourites, FilterPresets.Lossless), sort = QuerySort("Title"))

    @Test
    fun aListPicksFromTheLibraryAsTheWindowShowsIt() {
        val app = app()
        val list = app.liveLists.save(LiveList.new("Lossless favourites", losslessFavourites, 0))
        assertEquals(listOf("s1", "s3"), app.liveListSongs(list)?.map { it.id })
        // A heart taken off a moment ago counts before the server catches up.
        app.setStarred(listOf(app.library!!.index!!.songs.first { it.id == "s3" }), false)
        assertEquals(listOf("s1"), app.liveListSongs(list)?.map { it.id })
    }

    @Test
    fun makingOneShowsItInPlaceOfTheEditor() {
        val app = app()
        app.navigator.go(Page.Songs)
        app.navigator.go(Page.NewLiveList())
        val made = app.createLiveList("Mine", losslessFavourites)
        assertEquals(Page.LiveList(made.id), app.navigator.current.page)
        assertEquals(SidebarItem.LiveListItem(made.id), app.navigator.sidebarItem)
        // Back skips the editor it replaced.
        app.navigator.back()
        assertEquals(Page.Songs, app.navigator.current.page)
    }

    @Test
    fun deletingOneLeavesItsPage() {
        val app = app()
        val list = app.liveLists.save(LiveList.new("Gone soon", losslessFavourites, 0))
        app.navigator.go(Page.Songs)
        app.navigator.go(Page.LiveList(list.id))
        app.deleteLiveList(list)
        assertEquals(Page.Songs, app.navigator.current.page)
        assertNull(app.liveLists.byId(list.id))
    }

    @Test
    fun aCopyOnTheServerIsAPlaylistOfTheSongsItHoldsNow() {
        server.answer("createPlaylist", """"playlist":{"id":"p9","name":"Lossless favourites"}""")
        val app = app()
        val list = app.liveLists.save(LiveList.new("Lossless favourites", losslessFavourites, 0))
        app.saveLiveListToServer(list)
        waitFor { callsTo("createPlaylist").isNotEmpty() }
        val call = callsTo("createPlaylist").single()
        assertEquals("Lossless favourites", call.url.queryParameter("name"))
        assertEquals(listOf("s1", "s3"), call.url.queryParameterValues("songId"))
        waitFor { app.notice != null }
        assertEquals("Saved a copy of Lossless favourites to the server, 2 songs", app.notice)
        // The live list stays as it is.
        assertNotNull(app.liveLists.byId(list.id))
    }

    @Test
    fun anEmptyListSaysSoRatherThanMakingAnEmptyPlaylist() {
        val app = app()
        val list = app.liveLists.save(LiveList.new("Nothing", LibraryQuery(listOf(FilterPresets.NeverPlayed, FilterPresets.playedAtLeast(3))), 0))
        app.saveLiveListToServer(list)
        waitFor { app.notice != null }
        assertEquals("\"Nothing\" has no songs right now, so there is nothing to save.", app.notice)
        assertTrue(callsTo("createPlaylist").isEmpty())
    }

    @Test
    fun playingFromItsPageNamesTheQueueAfterIt() {
        val app = app()
        val list = app.liveLists.save(LiveList.new("Lossless favourites", losslessFavourites, 0))
        app.navigator.go(Page.LiveList(list.id))
        app.play(app.liveListSongs(list)!!)
        assertEquals(QueueSource.Played("Lossless favourites"), player.state.value.queue.first().source)
    }

    @Test
    fun theCommandsOfferANewOneAndEachList() {
        val app = app()
        val list = app.liveLists.save(LiveList.new("Lossless favourites", losslessFavourites, 0))
        val commands = commandsFor(app)
        assertNotNull(commands.firstOrNull { it.title == "New live list" })
        commands.first { it.title == "Lossless favourites" }.run()
        assertEquals(Page.LiveList(list.id), app.navigator.current.page)
        commands.first { it.title == "New live list" }.run()
        assertTrue(app.navigator.current.page is Page.NewLiveList)
        assertEquals(null, sidebarItemOf(Page.NewLiveList()))
    }

    @Test
    fun eachAccountsListsAreKeptInItsOwnFolder() {
        val app = app(listeningRoot = temp.root)
        app.liveLists.save(LiveList.new("Kept", losslessFavourites, 0, "k"))
        app.beforeQuit()
        val file = File(listeningFolder(temp.root, "winters", server.address), LiveListStore.FILE_NAME)
        waitFor { file.exists() && LiveListsJson.decode(file.readText()).isNotEmpty() }
        assertEquals(listOf("Kept"), LiveListsJson.decode(file.readText()).map { it.name })
        // Signed out, the lists go with the account.
        app.signOut()
        assertEquals(emptyList<LiveList>(), app.liveLists.lists.value)
    }

    // The node showing exactly this text.
    private fun find(node: SemanticsNode, text: String): SemanticsNode? {
        if (node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } == text) return node
        return node.children.firstNotNullOfOrNull { find(it, text) }
    }

    private fun ImageComposeScene.node(text: String): SemanticsNode? = semanticsOwners.firstNotNullOfOrNull { find(it.unmergedRootSemanticsNode, text) }

    private fun need(node: SemanticsNode?): SemanticsNode = node ?: throw AssertionError("not on screen")

    private fun ImageComposeScene.click(node: SemanticsNode) {
        val spot = node.boundsInRoot.center
        sendPointerEvent(PointerEventType.Enter, spot)
        sendPointerEvent(PointerEventType.Press, spot, buttons = PointerButtons(isPrimaryPressed = true))
        render()
        sendPointerEvent(PointerEventType.Release, spot)
        render()
    }

    @Test
    fun theSidebarListsLiveListsAbovePlaylistsAndOpensThem() {
        val app = app()
        val list = app.liveLists.save(LiveList.new("Lossless favourites", losslessFavourites, 0))
        val scene = ImageComposeScene(300, 900, Density(1f)) { Sidebar(app, HazeState()) }
        try {
            repeat(3) { scene.render() }
            val live = need(scene.node("Lossless favourites"))
            val playlist = need(scene.node("Road trip"))
            assertTrue(live.boundsInRoot.top < playlist.boundsInRoot.top)
            scene.click(live)
            assertEquals(Page.LiveList(list.id), app.navigator.current.page)
            // A list deleted elsewhere leaves the sidebar.
            app.liveLists.remove(list.id)
            repeat(2) { scene.render() }
            assertNull(scene.node("Lossless favourites"))
        } finally {
            scene.close()
        }
    }

    @Test
    fun aListsPageSaysWhatItPicksAndItsEditorPreviewsAChange() {
        val app = app()
        val list = app.liveLists.save(LiveList.new("Lossless favourites", losslessFavourites, 0))
        app.navigator.go(Page.LiveList(list.id))
        val visit = app.navigator.current
        val scene = ImageComposeScene(1200, 900, Density(1f)) { LiveListPage(app, visit, list.id) }
        try {
            repeat(3) { scene.render() }
            assertNotNull(scene.node("Favourites, lossless, by title, 2 songs"))
            assertNotNull(scene.node("Three"))
            assertNull(scene.node("Four"))
            // Its rules show as words, not as pills to change, until Edit rules.
            assertNull(scene.node("Lossless"))
        } finally {
            scene.close()
        }
    }

    @Test
    fun theEditorPreviewsAsRulesComeOffAndMakesTheListOnSave() {
        val app = app()
        app.navigator.go(Page.NewLiveList(losslessFavourites))
        val visit = app.navigator.current
        val scene = ImageComposeScene(1200, 900, Density(1f)) { NewLiveListPage(app, visit, visit.page as Page.NewLiveList) }
        try {
            repeat(3) { scene.render() }
            assertNull(scene.node("Four"))
            // A click on the Lossless pill takes the rule off; the preview follows.
            scene.click(need(scene.node("Lossless")))
            waitFor {
                scene.render()
                scene.node("Four") != null
            }
            assertNotNull(scene.node("3 songs match right now"))
            scene.click(need(scene.node("Make live list")))
            val made = app.liveLists.lists.value.single()
            assertEquals("Favourites", made.name)
            assertEquals(listOf(FilterPresets.Favourites), made.query.rules)
            assertEquals(Page.LiveList(made.id), app.navigator.current.page)
        } finally {
            scene.close()
        }
    }

    @Test
    fun startersShowWhileThereAreNoneAndOneIsMadeOnlyWhenPicked() {
        val app = app()
        app.navigator.go(Page.NewLiveList())
        val visit = app.navigator.current
        val scene = ImageComposeScene(1200, 1400, Density(1f)) { NewLiveListPage(app, visit, visit.page as Page.NewLiveList) }
        try {
            repeat(3) { scene.render() }
            assertNotNull(scene.node("Or start with one of these"))
            assertTrue(app.liveLists.lists.value.isEmpty())
            scene.click(need(scene.node("Lossless favourites")))
            assertEquals(listOf("Lossless favourites"), app.liveLists.lists.value.map { it.name })
        } finally {
            scene.close()
        }
    }
}
