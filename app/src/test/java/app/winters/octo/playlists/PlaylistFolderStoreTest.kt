package app.winters.octo.playlists

import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.PlaylistEntity
import app.winters.octo.catalog.PlaylistPlaceEntity
import app.winters.octo.catalog.PlaylistSets
import app.winters.octo.data.Session
import app.winters.octo.data.SessionState
import app.winters.octo.subsonic.Credentials
import app.winters.octo.subsonic.PLAYLIST_FOLDERS_EXTENSION
import app.winters.octo.subsonic.PlaylistFolder
import app.winters.octo.subsonic.PlaylistFolders
import app.winters.octo.subsonic.PlaylistPlace
import app.winters.octo.subsonic.SubsonicClient
import app.winters.octo.subsonic.SubsonicException
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

// Folder changes made offline go in order once the server answers, only as
// the account that made them; a refused sign-in waits for the next sign-in;
// a missing folder drops its change with one word; a pull replays the
// changes still waiting on top and drops placements whose folder went; a
// deleted folder's contents move up; an unlinked playlist's placement stays
// on the phone.
@RunWith(RobolectricTestRunner::class)
class PlaylistFolderStoreTest {
    private val db: CatalogDatabase = openTestCatalog()
    private val state = MutableStateFlow<SessionState>(SessionState.SignedOut)
    private val words = ArrayList<String>()
    private val fake = FakeFolders()

    // A scope that never runs what is launched on it: the tests send by calling sync().
    private val idle = CoroutineScope(Job().apply { cancel() })
    private val store = PlaylistFolderStore(db, state, { words += it }, { fake }, idle, sendDelayMs = 0, now = { 1_000L })
    private val dao = db.playlistFolderDao()

    @After
    fun close() = db.close()

    private fun session(id: String) = Session(
        SubsonicClient("http://localhost/".toHttpUrl(), Credentials("u", "p"), OkHttpClient()),
        serverType = "octo",
        serverVersion = null,
        isOcto = true,
        adminReachable = false,
        extensions = setOf("$PLAYLIST_FOLDERS_EXTENSION:1"),
        id = id,
    )

    private fun signIn(id: String) {
        state.value = SessionState.SignedIn(session(id))
    }

    @Test
    fun changesMadeOfflineGoInOrderOnceTheServerAnswers() = runBlocking {
        signIn("acct")
        val a = store.create("Road trips", null)!!
        val b = store.create("Summer", a)!!
        store.place(listOf("pl1", "local:row1"), b)
        fake.fail = { SubsonicException.Unreachable(IOException("down")) }
        store.sync()
        assertTrue(fake.sent.isEmpty())
        assertEquals(3, dao.ops("acct").size)
        fake.fail = { null }
        store.sync()
        assertEquals(listOf("save $a Road trips -", "save $b Summer $a", "place pl1 $b"), fake.sent)
        assertTrue(dao.ops("acct").isEmpty())
        assertEquals(b, dao.place("acct", "pl1")?.folderId)
        assertEquals("the pull kept the phone-only placement", b, dao.place("acct", "local:row1")?.folderId)
    }

    @Test
    fun changesGoOnlyAsTheAccountThatMadeThem() = runBlocking {
        signIn("acct-a")
        store.create("A", null)
        signIn("acct-b")
        store.sync()
        assertTrue(fake.sent.isEmpty())
        assertEquals(1, dao.ops("acct-a").size)
        signIn("acct-a")
        store.sync()
        assertEquals(1, fake.sent.size)
    }

    @Test
    fun aRefusedSignInWaitsForTheNextSignIn() = runBlocking {
        signIn("acct")
        store.create("A", null)
        fake.fail = { SubsonicException.WrongCredentials("no") }
        store.sync()
        fake.fail = { null }
        store.sync()
        assertTrue("still waiting for this sign-in", fake.sent.isEmpty())
        assertEquals(1, dao.ops("acct").size)
        signIn("acct")
        store.sync()
        assertEquals(1, fake.sent.size)
    }

    @Test
    fun aMissingFolderDropsItsChangesWithOneWord() = runBlocking {
        signIn("acct")
        val a = store.create("A", null)!!
        store.rename(a, "B")
        fake.fail = { what -> if (what.startsWith("save")) SubsonicException.NotFound("There is no such folder.") else null }
        store.sync()
        assertTrue(dao.ops("acct").isEmpty())
        assertEquals(listOf(PlaylistFolderWords.FOLDER_CHANGE_FAILED), words)
        assertNull("the pull took the server's folders", dao.folder("acct", a))
    }

    @Test
    fun aPullReplaysTheWaitingChangesOnTopAndDropsHomelessPlacements() = runBlocking {
        signIn("acct")
        val s = PlaylistFolder("pfSSSSSSSSSSSSSSSSSSSS", "Server's")
        val gone = PlaylistFolder("pfGGGGGGGGGGGGGGGGGGGG", "Gone")
        store.applyPull("acct", PlaylistFolders("t1", listOf(s, gone), listOf(PlaylistPlace("pl9", gone.id))))
        val mine = store.create("Mine", s.id)!!
        store.place(listOf("pl9"), mine)
        store.place(listOf("local:r"), gone.id)
        // Meanwhile the folder Gone was deleted on another device.
        store.applyPull("acct", PlaylistFolders("t2", listOf(s), emptyList()))
        assertEquals(s.id, dao.folder("acct", mine)?.parent)
        assertEquals(mine, dao.place("acct", "pl9")?.folderId)
        assertNull("a placement whose folder went is dropped", dao.place("acct", "local:r"))
        assertNull(dao.folder("acct", gone.id))
    }

    @Test
    fun aDeletedFoldersContentsMoveUpAndAFolderNeverGoesInsideItself() = runBlocking {
        val a = store.create("A", null)!!
        val b = store.create("B", a)!!
        store.place(listOf("local:r"), b)
        assertEquals(FolderMoveRefusal.IntoItself, store.move(a, b))
        store.delete(b)
        assertNull(dao.folder("", b))
        assertEquals(a, dao.place("", "local:r")?.folderId)
        assertTrue("no server: nothing waits to be sent", dao.ops("").isEmpty())
    }

    @Test
    fun anUnlinkedPlaylistsPlacementStaysOnThePhone() = runBlocking {
        db.userDao().insertPlaylist(PlaylistEntity("p", "Road", 1, 1, serverId = "srv", sourceId = "server:h", syncedAt = 1, syncedName = "Road", serverStamp = "a|0"))
        dao.putPlace(PlaylistPlaceEntity("acct", "srv", "pfAAAAAAAAAAAAAAAAAAAA"))
        PlaylistSets(db).unlinkPlaylist("p")
        assertNull(dao.place("acct", "srv"))
        val local = dao.place("acct", PlaylistFolderTree.localKey("p"))!!
        assertEquals("pfAAAAAAAAAAAAAAAAAAAA", local.folderId)
        assertTrue(local.localOnly)
    }
}

// A server's folders, for the queue's tests: each call is noted as it
// lands, and `fail` can refuse one.
private class FakeFolders : FolderCalls {
    val folders = LinkedHashMap<String, PlaylistFolder>()
    val places = LinkedHashMap<String, String>()
    val sent = ArrayList<String>()
    var fail: (String) -> SubsonicException? = { null }

    private fun state() = PlaylistFolders("now", folders.values.toList(), places.map { PlaylistPlace(it.key, it.value) })

    private fun call(what: String, apply: () -> Unit): PlaylistFolders {
        fail(what)?.let { throw it }
        sent += what
        apply()
        return state()
    }

    override suspend fun folders(): PlaylistFolders = state()

    override suspend fun save(id: String, name: String, parent: String?): PlaylistFolders =
        call("save $id $name ${parent ?: "-"}") { folders[id] = PlaylistFolder(id, name, parent) }

    override suspend fun delete(id: String): PlaylistFolders = call("delete $id") {
        val parent = folders.remove(id)?.parent
        folders.replaceAll { _, f -> if (f.parent == id) f.copy(parent = parent) else f }
        if (parent == null) places.entries.removeIf { it.value == id } else places.replaceAll { _, f -> if (f == id) parent else f }
    }

    override suspend fun place(playlistIds: List<String>, folderId: String?): PlaylistFolders =
        call("place ${playlistIds.joinToString(",")} ${folderId ?: "-"}") {
            playlistIds.forEach { if (folderId == null) places.remove(it) else places[it] = folderId }
        }
}
