package app.winters.octo.catalog

import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger

// Song keys an older reading stored are worked out again at launch, with
// no merge at all: a library of one server's songs and no phone songs (no
// audio permission) gets no merge, and still gets its keys.
@RunWith(RobolectricTestRunner::class)
class KeysReadyAtStartTest {
    private val server = "server:home.test"
    private val db = openTestCatalog()

    @After
    fun close() {
        db.close()
    }

    // A hang guard, not a speed bound: the 1,200-song refill and the merge
    // after it can take well over 10 s in a loaded full run.
    private fun run(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) = runBlocking { withTimeout(60_000) { block() } }

    private fun stale() = db.longOf("SELECT COUNT(*) FROM source_track WHERE keysVersion != ?", SONG_KEYS_VERSION)

    private suspend fun serverOnlyWithOldKeys() {
        db.put(library(server, 1_200))
        db.exec("UPDATE source_track SET titleKey = 'stale', matchKey = 'stale', keysVersion = 0")
    }

    @Test
    fun theStartRefillsOldKeysWithNoMerge() = run {
        serverOnlyWithOldKeys()
        val hooks = TestHooks()
        val merge = db.testMerge(server, hooks)
        assertFalse(merge.keysReady.value)
        merge.start()
        merge.keysReady.first { it }
        assertEquals(0L, stale())
        assertTrue(db.stringOf("SELECT matchKey FROM source_track LIMIT 1") != "stale")
        assertEquals(0, hooks.merges.get())
        assertEquals(0, hooks.skips.get())
    }

    @Test
    fun keysAlreadyCurrentAreReadyAtStart() = run {
        db.put(library(server, 3))
        val merge = db.testMerge(server)
        merge.start()
        merge.keysReady.first { it }
    }

    // A merge asked for while the start's refill runs waits for that refill
    // instead of starting a second.
    @Test
    fun oneRefillAtATime() = run {
        serverOnlyWithOldKeys()
        val walks = AtomicInteger()
        val paused = CompletableDeferred<Unit>()
        val go = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        val inner = db.workDao()
        val work = object : WorkDao by inner {
            override suspend fun keyRows(old: Int, after: String, limit: Int): List<KeyRow> {
                if (after == "") {
                    if (walks.incrementAndGet() == 1) {
                        paused.complete(Unit)
                        go.await()
                    } else {
                        second.complete(Unit)
                    }
                }
                return inner.keyRows(old, after, limit)
            }
        }
        val merge = db.testMerge(server, work = work, inUse = {
            second.complete(Unit)
            server
        })
        merge.start()
        paused.await()
        val merging = async(Dispatchers.Default) { merge.rebuild() }
        // The merge neither walks the keys again nor goes on past the refill.
        assertNull(withTimeoutOrNull(500) { second.await() })
        go.complete(Unit)
        merging.await()
        assertEquals(1, walks.get())
        assertEquals(0L, stale())
        assertTrue(merge.keysReady.value)
    }
}
