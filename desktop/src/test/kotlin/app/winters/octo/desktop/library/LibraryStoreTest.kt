package app.winters.octo.desktop.library

import app.winters.octo.subsonic.Library
import app.winters.octo.subsonic.Song
import app.winters.octo.subsonic.SubsonicException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class LibraryStoreTest {
    // A stand-in for the window's thread: one thread, with a name to look for.
    private val windowThread = Executors.newSingleThreadExecutor { Thread(it, WINDOW) }
    private val window = windowThread.asCoroutineDispatcher()
    private val job = SupervisorJob()
    private val scope = CoroutineScope(window + job)

    @After
    fun stop() {
        scope.cancel()
        window.close()
    }

    private val songs = listOf(
        Song("s1", "Blue Monday", artist = "New Order", albumId = "a1", genre = "Synth-pop", played = "2026-09-01T10:00:00Z"),
        Song("s2", "Atmosphere", artist = "Joy Division", albumId = "a2", genre = "Post-punk"),
    )

    // Waits for every load started so far to finish.
    private fun settle() = runBlocking { withTimeout(10_000) { job.children.forEach { it.join() } } }

    private fun ready(store: LibraryStore): LibraryIndex = runBlocking {
        withTimeout(10_000) { (store.state.first { it is LibraryState.Ready } as LibraryState.Ready).index }
    }

    @Test
    fun theLibraryIsReadAndIndexedAwayFromTheWindowsThread() {
        val threads = ConcurrentHashMap.newKeySet<String>()
        // A song list that notes every thread that reads it.
        val watched = object : AbstractList<Song>() {
            override val size get() = songs.size

            override fun get(index: Int): Song {
                threads += Thread.currentThread().name
                return songs[index]
            }
        }
        val store = LibraryStore({
            threads += Thread.currentThread().name
            Library(watched, emptyList(), emptyList())
        }, scope)
        store.load()
        val index = ready(store)
        assertEquals(listOf("s1"), index.history.map { it.id })
        assertEquals(2, index.genres.size)
        assertTrue(threads.isNotEmpty())
        assertFalse("nothing on the window's thread: $threads", WINDOW in threads)
        assertTrue("all on the shared worker threads: $threads", threads.all { it.startsWith("DefaultDispatcher-worker") })
    }

    @Test
    fun aFailedRefreshKeepsWhatWasReadBefore() {
        var reads = 0
        val store = LibraryStore({
            if (++reads > 1) throw SubsonicException.NotFound("gone")
            Library(songs, emptyList(), emptyList())
        }, scope)
        store.load()
        val first = ready(store)
        settle()
        store.load()
        settle()
        assertEquals(2, reads)
        assertSame(first, store.index)
    }

    @Test
    fun aFailedFirstReadSaysSo() {
        val store = LibraryStore({ throw SubsonicException.NotFound("gone") }, scope)
        store.load()
        settle()
        assertTrue(store.state.value is LibraryState.Failed)
    }

    private companion object {
        const val WINDOW = "window"
    }
}
