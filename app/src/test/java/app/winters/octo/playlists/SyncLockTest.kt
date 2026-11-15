package app.winters.octo.playlists

import android.database.sqlite.SQLiteFullException
import app.winters.octo.subsonic.Credentials
import app.winters.octo.subsonic.SubsonicClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

// The base step runs first in every holder of the sync lock, for the server
// that holder works with (at launch, after a later sign-in, after a switch
// to another server alike), as its own guarded call: a full disk inside it
// leaves the holder's own step to run.
@RunWith(RobolectricTestRunner::class)
class SyncLockTest {
    private fun server(source: String) =
        SyncServer(SubsonicClient("http://localhost/".toHttpUrl(), Credentials("u", "p"), OkHttpClient()), source, "acct", formPost = false, familyRules = false, playlistSongs = true)

    @Test
    fun everyHolderRunsTheBaseStepForItsServerFirst() = runBlocking {
        val seen = ArrayList<String>()
        val lock = SyncLock { seen += "base ${it.sourceId}" }
        lock.withLock(server("server:a")) { seen += "sync a" }
        lock.withLock(server("server:a")) { seen += "send a" }
        lock.withLock(server("server:b")) { seen += "sync b" }
        assertEquals(listOf("base server:a", "sync a", "base server:a", "send a", "base server:b", "sync b"), seen)
    }

    @Test
    fun aFullDiskInTheBaseStepStillRunsTheHoldersStep() = runBlocking {
        val lock = SyncLock { throw SQLiteFullException("full") }
        assertEquals("pushed", lock.withLock(server("server:a")) { "pushed" })
    }

    @Test
    fun aCancelInTheBaseStepGoesThrough() = runBlocking {
        val lock = SyncLock { throw CancellationException("stop") }
        assertTrue(runCatching { lock.withLock(server("server:a")) { "pushed" } }.exceptionOrNull() is CancellationException)
    }

    // Every holder in PlaylistSync takes the lock with its server, so none
    // skips the base step.
    @Test
    fun everyHolderInPlaylistSyncTakesTheLockWithItsServer() {
        val text = File(System.getProperty("octo.mainSources")!!, "app/winters/octo/playlists/PlaylistSync.kt").readText()
        assertEquals(4, Regex("""lock\.withLock\(server\)""").findAll(text).count())
        assertTrue(Regex("""lock\.withLock\s*\{""").findAll(text).none())
    }
}
