package app.winters.octo.playback

import android.util.Log
import app.winters.octo.catalog.OnlineDao
import app.winters.octo.catalog.OnlineSongEntity
import app.winters.octo.catalog.WriteLabels
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

// The label a find's keep writes under.
const val FIND_KEEP_WRITE = "find keep"

// Finds read from the server's answer whose online_song row is not written
// yet. A play's start reads its finds with no write and holds them here
// under its list id; their row comes with the queue's first rows once the
// player has them (keep). A read of what the server found (search, an
// artist's top songs) holds its finds under no play, with their keep
// posted behind its answer.
//
// Until a find's keep commits, every reader of finds takes it from here,
// and every write to its row by id waits for that keep, so no write lands
// on a row that is not there yet. A keep merges the length the player
// learned meanwhile. A keep that failed (the phone ran out of room) leaves
// its finds here while their play holds them: the next queue write, or a
// writer of one of them, keeps them again, and a new queue in place of
// their play's lets them go.
@Singleton
class FindMap internal constructor(
    private val online: OnlineDao,
    private val real: RealLengths,
    private val scope: CoroutineScope,
) {
    @Inject constructor(online: OnlineDao, real: RealLengths) : this(online, real, CoroutineScope(SupervisorJob() + Dispatchers.IO))

    // One find held: as its row will be written, the plays holding it,
    // whether a read holds it too, whether its last keep failed, and the
    // first keep's end, which every writer of its row waits for.
    private class Held(var find: OnlineSongEntity) {
        val plays = HashSet<Long>()
        var read = false
        var failed = false
        val settled = CompletableDeferred<Unit>()
    }

    private val lock = Any()
    private val held = HashMap<String, Held>()

    // One keep at a time, so a retry never races the keep it repeats.
    private val writer = Mutex()

    // A find held now, by its id, or null.
    operator fun get(id: String): OnlineSongEntity? = synchronized(lock) { held[id]?.find }

    // Whether a find is held now, waiting for its keep or after one failed.
    fun holds(id: String): Boolean = synchronized(lock) { id in held }

    // Holds a play's finds (`play` its list id) until their keep commits.
    fun hold(play: Long, finds: Collection<OnlineSongEntity>) {
        if (finds.isEmpty()) return
        synchronized(lock) {
            for (find in finds) {
                val h = held.getOrPut(find.id) { Held(find) }
                h.find = find
                h.plays += play
            }
        }
    }

    // A play that did not start lets its finds go; one another play or a
    // read still holds stays.
    fun release(play: Long) {
        synchronized(lock) {
            val it = held.values.iterator()
            while (it.hasNext()) {
                val h = it.next()
                if (!h.plays.remove(play) || h.plays.isNotEmpty() || h.read) continue
                h.settled.complete(Unit)
                it.remove()
            }
        }
    }

    // A new queue in place of the one before: failed finds only another
    // play held go, as no queue item names them now.
    fun replaced(play: Long) {
        synchronized(lock) {
            val it = held.values.iterator()
            while (it.hasNext()) {
                val h = it.next()
                if (h.failed && !h.read && play !in h.plays) it.remove()
            }
        }
    }

    // Writes the rows of the finds `play` holds that no keep has settled:
    // a job of the queue's worker, after the player has them. Answers
    // whether it kept them; a find that failed stays held.
    suspend fun keep(play: Long): Boolean {
        val ids = synchronized(lock) { held.filterValues { play in it.plays && !it.settled.isCompleted }.keys.toList() }
        return keepNow(ids)
    }

    // Holds a read's finds and keeps them behind its answer, off its
    // caller's path.
    fun post(finds: Collection<OnlineSongEntity>) {
        if (finds.isEmpty()) return
        synchronized(lock) {
            for (find in finds) {
                val h = held.getOrPut(find.id) { Held(find) }
                h.find = find
                h.read = true
            }
        }
        val ids = finds.map { it.id }
        scope.launch { keepNow(ids) }
    }

    // Keeps again every find whose keep failed: the next queue write.
    suspend fun retry() {
        val ids = synchronized(lock) { held.filterValues { it.failed }.keys.toList() }
        if (ids.isNotEmpty()) keepNow(ids)
    }

    // A write to a find's row by id waits here for the find's keep; one
    // whose keep failed is kept again first, so the write finds its row.
    suspend fun awaitKept(id: String) {
        val h = synchronized(lock) {
            held[id]?.also { waiters[id] = (waiters[id] ?: 0) + 1 }
        } ?: return
        try {
            h.settled.await()
        } finally {
            synchronized(lock) { waiters[id]?.let { if (it > 1) waiters[id] = it - 1 else waiters.remove(id) } }
        }
        if (synchronized(lock) { held[id]?.failed == true }) keepNow(listOf(id))
    }

    // How many writes wait for a find's keep now.
    internal fun waiting(id: String): Int = synchronized(lock) { waiters[id] ?: 0 }

    private val waiters = HashMap<String, Int>()

    // One keep: the rows as the server says them now, with what the app
    // knew (OnlineDao.keep), and the length the player learned meanwhile,
    // which wins over any listing as it is the sound's own.
    private suspend fun keepNow(ids: List<String>): Boolean = writer.withLock {
        val rows = synchronized(lock) { ids.distinct().mapNotNull { held[it]?.find } }
        if (rows.isEmpty()) return@withLock true
        val learned = real.corrected.value
        val ok = try {
            withContext(WriteLabels.of(FIND_KEEP_WRITE)) {
                online.keep(rows.map { row -> learned[row.id]?.takeIf { it > 0 }?.let { row.copy(durationMs = it) } ?: row })
            }
            true
        } catch (e: Exception) {
            Log.w("Octo", "finds: could not keep ${rows.size}: ${e.javaClass.simpleName}")
            false
        }
        synchronized(lock) {
            for (row in rows) {
                val h = held[row.id] ?: continue
                h.failed = !ok
                if (ok) held.remove(row.id)
                h.settled.complete(Unit)
            }
        }
        ok
    }
}
