package app.winters.octo.playback

import android.content.Context
import android.util.JsonReader
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.withTransaction
import app.winters.octo.catalog.QueueAccountEntity
import app.winters.octo.catalog.QueueEntryEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.StringReader
import javax.inject.Inject
import javax.inject.Singleton

private val Context.parkedQueueData by preferencesDataStore("parked_queues")

// The queue as the app kept it before the queue lived in Room, brought in
// once: the file of where each song came from (one word a line, beside the
// migrated queue), and the queues of other accounts, kept as one text each.
@Singleton
class LegacyQueues internal constructor(
    private val store: QueueStore,
    private val sourcesFile: File,
    private val parked: DataStore<Preferences>,
) {
    @Inject constructor(store: QueueStore, @ApplicationContext context: Context) :
        this(store, File(context.filesDir, "queue-sources"), context.parkedQueueData)

    private val dao = store.dao

    // The migrated queue's sources: each run of one word becomes a list of
    // its own with that word, set a page at a time; then the file goes.
    suspend fun importSources() {
        if (!sourcesFile.exists()) return
        store.inTurn {
            try {
                val migrated = dao.account("")?.takeIf { it.queueId == MIGRATED_QUEUE }
                if (migrated != null) sourcesFile.bufferedReader().use { lines -> splitRuns(lines.lineSequence()) }
            } catch (e: Exception) {
                Log.w("Octo", "queue: the old sources were not read: ${e.javaClass.simpleName}")
            }
            sourcesFile.delete()
        }
    }

    private suspend fun splitRuns(words: Sequence<String>) {
        val swapCount = store.swapCount()
        var runStart = 0L
        var runWord: String? = null
        var n = 0L
        suspend fun close(end: Long) {
            val word = runWord?.takeIf { it.isNotEmpty() } ?: return
            val listId = store.claimListInTurn()
            val autoplay = queueSourceOf(word) == QueueSource.Autoplay
            dao.putList(StoredList(listId, word, autoplay).row(MIGRATED_QUEUE, swapCount))
            var from = runStart
            while (from < end) {
                val to = minOf(end, from + QUEUE_PAGE) - 1
                store.db.withTransaction { dao.setListOfRange(MIGRATED_QUEUE, entryOf(MIGRATED_LIST, from), entryOf(MIGRATED_LIST, to), listId) }
                from = to + 1
            }
        }
        for (word in words) {
            if (word != runWord) {
                close(n)
                runStart = n
                runWord = word
            }
            n++
        }
        close(n)
    }

    // Another account's queue kept the old way, written as a generation the
    // first time that account comes back; its text is let go then. Read with
    // a streaming reader and written a page at a time.
    suspend fun bringParked(account: String) {
        val key = stringPreferencesKey("queue@$account")
        val text = parked.data.first()[key] ?: return
        store.inTurn {
            try {
                if (dao.account(account) == null) write(account, text)
            } catch (e: Exception) {
                Log.w("Octo", "queue: an old queue was not read: ${e.javaClass.simpleName}")
            }
        }
        parked.edit { it.remove(key) }
    }

    private suspend fun write(account: String, text: String) {
        val stored = readStored(text) ?: return
        if (stored.trackIds.isEmpty()) return
        val queueId = store.claimQueueInTurn()
        val firstList = store.claimListInTurn()
        val swapCount = store.swapCount()
        val count = stored.trackIds.size
        // Each song's place in the shuffled order, when there is one.
        val rank = IntArray(count) { it }
        if (stored.shuffleOrder.size == count) stored.shuffleOrder.forEachIndexed { r, position -> if (position in 0 until count) rank[position] = r }
        // A run of one source is a list of its own.
        val lists = ArrayList<Long>(count)
        var listId = firstList
        var word: String? = stored.sources.getOrNull(0)
        dao.putList(StoredList(listId, word, word?.let { queueSourceOf(it) == QueueSource.Autoplay } == true).row(queueId, swapCount))
        for (i in 0 until count) {
            val now = stored.sources.getOrNull(i)
            if (i > 0 && now != word) {
                word = now
                listId = store.claimListInTurn()
                dao.putList(StoredList(listId, word, word?.let { queueSourceOf(it) == QueueSource.Autoplay } == true).row(queueId, swapCount))
            }
            lists += listId
        }
        for (from in 0 until count step QUEUE_PAGE) {
            val to = minOf(count, from + QUEUE_PAGE)
            val ids = stored.trackIds.subList(from, to).map { store.followed(it) }
            val lengths = songLengths(store, ids)
            val rows = (from until to).map { i ->
                val id = ids[i - from]
                QueueEntryEntity(queueId, entryOf(firstList, i.toLong()), id, lists[i], playKey(i.toLong()), SK_MID + rank[i] * KEY_STEP, lengths[id] ?: 0)
            }
            store.db.withTransaction { dao.putEntries(rows) }
        }
        val index = stored.index.coerceIn(0, count - 1)
        dao.putAccount(
            QueueAccountEntity(
                account, queueId, entryOf(firstList, index.toLong()), stored.positionMs, stored.repeatMode, stored.shuffle,
                null, null, null, System.currentTimeMillis(),
            ),
        )
    }

    private class Stored(
        val trackIds: List<String>,
        val shuffleOrder: List<Int>,
        val index: Int,
        val positionMs: Long,
        val repeatMode: Int,
        val shuffle: Boolean,
        val sources: List<String>,
    )

    // The old queue's text, one field at a time.
    private fun readStored(text: String): Stored? {
        val ids = ArrayList<String>()
        val order = ArrayList<Int>()
        val sources = ArrayList<String>()
        var index = 0
        var position = 0L
        var repeat = 0
        var shuffle = false
        JsonReader(StringReader(text)).use { r ->
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "trackIds" -> {
                        r.beginArray()
                        while (r.hasNext()) ids += r.nextString()
                        r.endArray()
                    }
                    "shuffleOrder" -> {
                        r.beginArray()
                        while (r.hasNext()) order += r.nextInt()
                        r.endArray()
                    }
                    "sources" -> {
                        r.beginArray()
                        while (r.hasNext()) sources += r.nextString()
                        r.endArray()
                    }
                    "index" -> index = r.nextInt()
                    "positionMs" -> position = r.nextLong()
                    "repeatMode" -> repeat = r.nextInt()
                    "shuffle" -> shuffle = r.nextBoolean()
                    else -> r.skipValue()
                }
            }
            r.endObject()
        }
        return Stored(ids, order, index, position, repeat, shuffle, sources.takeIf { it.size == ids.size }.orEmpty())
    }

    companion object {
        // The generation and list the upgrade put the queue in use under.
        const val MIGRATED_QUEUE = 1L
        const val MIGRATED_LIST = 1L
    }
}
