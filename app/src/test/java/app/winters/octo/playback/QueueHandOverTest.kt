package app.winters.octo.playback

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Each server keeps its own queue: a switch puts the one in use away for
// its server and brings back the one the next server left, and a save
// asked for before the switch never lands over the queue handed over.
class QueueHandOverTest {
    private class MemoryPrefs : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        private val lock = Mutex()
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            lock.withLock { transform(state.value).also { state.value = it } }
    }

    // The saved queue, slow to write, as a database can be.
    private class SlowRows : QueueRows {
        @Volatile var saved: QueueSnapshot? = null

        override suspend fun read(): QueueSnapshot? = saved?.takeIf { it.trackIds.isNotEmpty() }

        override suspend fun write(snapshot: QueueSnapshot) {
            delay(20)
            saved = snapshot
        }
    }

    private fun queue(vararg ids: String, index: Int = 0, positionMs: Long = 0) =
        QueueSnapshot(ids.toList(), emptyList(), index, positionMs, 0, false)

    private val rows = SlowRows()
    private val store = QueueStore(rows, ParkedQueues(MemoryPrefs()))

    @Test
    fun aSwitchAndBackBringsEachServersQueueBack() = runBlocking {
        rows.saved = queue("home-1", "home-2", index = 1, positionMs = 42_000)
        assertTrue(store.handOver("home", queue("home-1", "home-2", index = 1, positionMs = 43_000), "work", keepWhenNone = false))
        assertNull("work had none: the queue is empty", store.load())
        store.save(queue("work-1"))
        assertTrue(store.handOver("work", null, "home", keepWhenNone = false))
        assertEquals(queue("home-1", "home-2", index = 1, positionMs = 43_000), store.load())
        assertTrue(store.handOver("home", null, "work", keepWhenNone = false))
        assertEquals("work's queue as saved", queue("work-1"), store.load())
    }

    @Test
    fun aSaveAskedBeforeTheSwitchLandsBeforeIt() = runBlocking {
        store.save(queue("home-1"))
        store.save(queue("home-1", "home-2"))
        store.handOver("home", null, "work", keepWhenNone = false)
        assertNull("the old queue is not written over the new one", store.load())
        store.handOver("work", null, "home", keepWhenNone = false)
        assertEquals(queue("home-1", "home-2"), store.load())
    }

    @Test
    fun signingInFromThePhoneKeepsItsQueueUnlessTheServerLeftOne() = runBlocking {
        rows.saved = queue("phone-1")
        assertFalse(store.handOver("phone", null, "home", keepWhenNone = true))
        assertEquals(queue("phone-1"), store.load())
        // Home left a queue when it was signed out of.
        store.handOver("home", queue("home-1"), "phone", keepWhenNone = false)
        store.save(queue("phone-2"))
        assertTrue(store.handOver("phone", null, "home", keepWhenNone = true))
        assertEquals(queue("home-1"), store.load())
        store.handOver("home", null, "phone", keepWhenNone = false)
        assertEquals("the phone's own queue waited too", queue("phone-2"), store.load())
    }

    @Test
    fun aRemovedServersQueueIsForgotten() = runBlocking {
        store.handOver("home", queue("home-1"), "work", keepWhenNone = false)
        store.forget("home")
        store.handOver("work", null, "home", keepWhenNone = false)
        assertNull(store.load())
    }

    @Test
    fun aQueueIsKeptWholeAsText() {
        val shuffled = QueueSnapshot(listOf("a", "b", "c"), listOf(2, 0, 1), 1, 5_000, 2, true)
        assertEquals(shuffled, decodeQueue(encodeQueue(shuffled)))
        assertNull(decodeQueue("not a queue"))
    }
}
