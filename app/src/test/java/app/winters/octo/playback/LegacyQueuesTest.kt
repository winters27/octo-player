package app.winters.octo.playback

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import app.winters.octo.catalog.QueueAccountEntity
import app.winters.octo.catalog.QueueEntryEntity
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.file.Files

// The queue as the app kept it before an upgrade: the migrated queue's
// sources become a list per run of one source, and the file goes; another
// account's kept queue is written as a generation the first time that
// account comes back, in its order and shuffled order, where it was left,
// and its kept text goes.
@RunWith(RobolectricTestRunner::class)
class LegacyQueuesTest {
    private val db = openTestCatalog()
    private val store = QueueStore(db)
    private val dir = Files.createTempDirectory("legacy").toFile()
    private val dataScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Kept in memory: a file store's second write cannot replace its file
    // on the build machine's file system.
    private val parked = object : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = transform(state.value).also { state.value = it }
    }
    private val sources = java.io.File(dir, "queue-sources")
    private val legacy = LegacyQueues(store, sources, parked)

    @After
    fun close() {
        store.scope.cancel()
        dataScope.cancel()
        db.close()
        dir.deleteRecursively()
    }

    @Test
    fun theMigratedQueuesSourcesBecomeLists() = runBlocking {
        db.songs(7)
        val dao = db.queueDao()
        dao.putList(StoredList(1, null, false).row(1, 0))
        dao.putEntries((0 until 7).map { n -> QueueEntryEntity(1, entryOf(1, n.toLong()), "s$n", 1, playKey(n.toLong()), SK_MID + n * KEY_STEP, 1_000) })
        dao.putAccount(QueueAccountEntity("", 1, entryOf(1, 0), 0, 0, false, null, null, null, 0))
        val you = QueueSource.You.encoded()
        sources.writeText(listOf("p:Album", "p:Album", you, you, you, "", QueueSource.Autoplay.encoded()).joinToString("\n"))
        legacy.importSources()
        assertFalse(sources.exists())
        val rows = store.inTurn { store.page(1, QueueOrder.Play, null, true, 100, Floors.NONE) }
        val lists = rows.map { it.listId }
        assertEquals(lists[0], lists[1])
        assertEquals(lists[2], lists[4])
        assertTrue(lists[0] != lists[2])
        assertEquals(1L, lists[5])
        val words = rows.map { row -> store.inTurn { dao.list(row.listId) } }
        assertEquals("p:Album", words[0]?.source)
        assertEquals(you, words[3]?.source)
        assertTrue(words[6]!!.autoplay)
    }

    @Test
    fun anotherAccountsKeptQueueComesBackAsAGeneration() = runBlocking {
        db.songs(10)
        val key = stringPreferencesKey("queue@away")
        val text = """{"trackIds":[${(0 until 10).joinToString(",") { "\"s$it\"" }}],"shuffleOrder":[9,8,7,6,5,4,3,2,1,0],"index":3,""" +
            """"positionMs":4200,"repeatMode":2,"shuffle":true,"sources":[]}"""
        parked.edit { it[key] = text }
        legacy.bringParked("away")
        assertNull(parked.data.first()[key])
        val account = store.inTurn { store.account("away") }!!
        assertEquals(4200, account.positionMs)
        assertEquals(2, account.repeatMode)
        assertTrue(account.shuffle)
        val played = store.inTurn { store.page(account.queueId, QueueOrder.Play, null, true, 100, Floors.NONE) }.map { it.trackId }
        assertEquals((0 until 10).map { "s$it" }, played)
        val shuffled = store.inTurn { store.page(account.queueId, QueueOrder.Shuffle, null, true, 100, Floors.NONE) }.map { it.trackId }
        assertEquals((9 downTo 0).map { "s$it" }, shuffled)
        val current = store.inTurn { db.queueDao().entry(account.queueId, account.currentEntry!!) }!!
        assertEquals("s3", current.trackId)
        // An account that has a queue already keeps it.
        parked.edit { it[key] = text }
        legacy.bringParked("away")
        assertEquals(account.queueId, store.inTurn { store.account("away") }!!.queueId)
    }
}
