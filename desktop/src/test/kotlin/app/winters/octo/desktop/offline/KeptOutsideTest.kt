package app.winters.octo.desktop.offline

import app.winters.octo.desktop.FakeServer
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.desktop.songJson
import app.winters.octo.subsonic.Song
import app.winters.octo.ui.family.FamilyModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

// A playlist kept on this computer keeps its library songs and never asks
// for its songs found online.
class KeptOutsideTest {
    @get:Rule val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val server = FakeServer()

    @After
    fun stop() {
        scope.cancel()
        server.close()
    }

    @Test
    fun aKeptPlaylistNeverFetchesItsSongsFoundOnline() {
        server.file("stream", ByteArray(500) { 3 })
        server.answer(
            "getPlaylist",
            """"playlist":{"id":"p1","name":"Mix","entry":[${songJson("tr-1", "One")},{"id":"ext-deezer-song-9","title":"Found","artist":"Artist","isExternal":true}]}""",
        )
        val settings = SettingsStore(File(folder.root, "settings.json"))
        val connection = server.connection()
        val family = FamilyModel({ connection.client }, { false }, scope)
        val offline = DesktopOffline(settings, OkHttpClient(), scope, File(folder.root, "kept"), { connection }, { emptyList() }, family, {})
        offline.start()
        offline.keepPlaylist("p1", true)
        val end = System.currentTimeMillis() + 10_000
        while (!(offline.status.kept == 1 && !offline.status.busy) && System.currentTimeMillis() < end) Thread.sleep(20)
        assertEquals(1, offline.status.kept)
        assertEquals(0, offline.status.waiting)
        assertTrue(server.calls.none { it.url.pathSegments.last() == "stream" && it.url.queryParameter("id") == "ext-deezer-song-9" })
        assertEquals(listOf("tr-1"), keptEntries(listOf(Song("tr-1", "One"), Song("x", "Found", isExternal = true))).map { it.id })
    }
}
