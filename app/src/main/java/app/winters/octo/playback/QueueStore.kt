package app.winters.octo.playback

import android.database.sqlite.SQLiteFullException
import android.util.Log
import androidx.room.withTransaction
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.QUEUE_AFTER_QK_SQL
import app.winters.octo.catalog.QUEUE_AFTER_SK_SQL
import app.winters.octo.catalog.QUEUE_BEFORE_QK_SQL
import app.winters.octo.catalog.QUEUE_BEFORE_SK_SQL
import app.winters.octo.catalog.QUEUE_COUNT_AFTER_QK_SQL
import app.winters.octo.catalog.QUEUE_COUNT_AFTER_SK_SQL
import app.winters.octo.catalog.QUEUE_COUNT_SQL
import app.winters.octo.catalog.QUEUE_DELETE_RANGE_SQL
import app.winters.octo.catalog.QUEUE_FIRST_KEYED_SQL
import app.winters.octo.catalog.QUEUE_FOLLOW_OR_GONE_SQL
import app.winters.octo.catalog.QUEUE_FOLLOW_SQL
import app.winters.octo.catalog.QUEUE_HEAL_PAGE_SQL
import app.winters.octo.catalog.QUEUE_HELD_AMONG_CHUNK
import app.winters.octo.catalog.queueHeldAmongSql
import app.winters.octo.catalog.QUEUE_LENGTH_AFTER_QK_SQL
import app.winters.octo.catalog.QUEUE_LENGTH_AFTER_SK_SQL
import app.winters.octo.catalog.QUEUE_LIST_FIRST_QK_SQL
import app.winters.octo.catalog.QUEUE_LIST_FIRST_SK_SQL
import app.winters.octo.catalog.QUEUE_LIST_HOLDS_SQL
import app.winters.octo.catalog.QUEUE_MAX_QK_SQL
import app.winters.octo.catalog.QUEUE_MAX_SK_SQL
import app.winters.octo.catalog.QUEUE_NEXT_GENERATION_SQL
import app.winters.octo.catalog.QUEUE_NEXT_LISTED_SQL
import app.winters.octo.catalog.QUEUE_NEXT_QK_SQL
import app.winters.octo.catalog.QUEUE_NEXT_SK_SQL
import app.winters.octo.catalog.QUEUE_PAGE_END_SQL
import app.winters.octo.catalog.QUEUE_PREV_QK_SQL
import app.winters.octo.catalog.QUEUE_PREV_SK_SQL
import app.winters.octo.catalog.QueueAccountEntity
import app.winters.octo.catalog.QueueEntryEntity
import app.winters.octo.catalog.QueueListEntity
import app.winters.octo.catalog.QueuePendingEntity
import app.winters.octo.catalog.WriteLabels
import app.winters.octo.catalog.isFind
import app.winters.octo.catalog.queueQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

// How many ids one claim takes from the saved counters at a time.
private const val ID_RUN = 32

// How the queue's writes are told apart when they are counted: the
// worker's jobs, a builder's own writes beside its pages, and a play's
// start.
internal const val QUEUE_JOB_WRITE = "queue worker job"
internal const val QUEUE_BUILD_WRITE = "queue build"
internal const val QUEUE_START_WRITE = "queue start"

// The most rows one transaction of a delete or a heal takes.
const val QUEUE_PAGE = 500

// Play order or shuffle order.
enum class QueueOrder { Play, Shuffle }

// Where Remove played left the start of the queue, in each order.
data class Floors(val q: Long = Long.MIN_VALUE, val s: Long = Long.MIN_VALUE) {
    companion object {
        val NONE = Floors()

        fun of(account: QueueAccountEntity?): Floors = Floors(account?.floorQ ?: Long.MIN_VALUE, account?.floorS ?: Long.MIN_VALUE)
    }
}

// A row's key in an order.
fun QueueEntryEntity.key(order: QueueOrder): Long = if (order == QueueOrder.Play) qk else sk

// Two rows of a list that plays each song once holding one song after a
// rename: the row that goes (the later in the order the generation plays)
// and the one that stays.
data class Doubled(val queueId: Long, val drop: Long, val keep: Long)

// The listed lengths of songs, library songs and found ones, 900 a read.
suspend fun songLengths(store: QueueStore, ids: List<String>): Map<String, Long> {
    if (ids.isEmpty()) return emptyMap()
    val (finds, songs) = ids.distinct().partition(::isFind)
    return songs.chunked(900).flatMap { store.dao.lengths(it) }.associate { it.id to it.durationMs } +
        finds.chunked(900).flatMap { store.dao.findLengths(it) }.associate { it.id to it.durationMs }
}

// The queue as Room keeps it, for the whole process: one worker that runs
// the queue's jobs one at a time in the order they were posted, the ids of
// new generations and lists, and the builders of lists still being
// written.
//
// A job never waits for another job, a build or the main thread; it waits
// only for its own reads and writes. A job that throws is logged and the
// worker goes on. A job that would run long (a delete, a heal) does one
// page and posts the next at the back, so jobs posted meanwhile go first.
@Singleton
class QueueStore @Inject constructor(val db: CatalogDatabase) {
    val dao = db.queueDao()
    private val work = db.workDao()

    // Builders, and work that outlives the service, run here.
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Lists of the queue in use still being written, for Up next.
    val building = kotlinx.coroutines.flow.MutableStateFlow<List<BuildingRow>>(emptyList())

    // The generation the player plays, 0 for none.
    val inUse = kotlinx.coroutines.flow.MutableStateFlow(0L)

    // Pairs a rename left in the generation in use, for the window to take
    // one of each out of the player and Room.
    val doubled = kotlinx.coroutines.flow.MutableSharedFlow<List<Doubled>>(extraBufferCapacity = 64)

    // The queue in use as a playlist's rows, a page at a time in the order
    // that plays: what played, the current song and what is to come,
    // leaving out Autoplay's songs and stations. Each page is one short job.
    suspend fun savePages(emit: suspend (List<app.winters.octo.catalog.NewItem>) -> Unit) {
        val queueId = inUse.value
        if (queueId == 0L) return
        // Lists still being written join first.
        building.first { it.isEmpty() }
        val (order, floors) = inTurn {
            val account = dao.accounts().firstOrNull { it.queueId == queueId }
            (if (account?.shuffle == true) QueueOrder.Shuffle else QueueOrder.Play) to Floors.of(account)
        }
        val autoplay = inTurn { dao.listsOf(queueId).filter { it.autoplay }.mapTo(HashSet()) { it.listId } }
        var from: QueueEntryEntity? = null
        while (true) {
            val page = inTurn { page(queueId, order, from, forward = true, QUEUE_PAGE, floors) }
            if (page.isEmpty()) return
            val kept = page.filter { it.listId !in autoplay && !isRadio(it.trackId) }
            val keys = inTurn { kept.map { it.trackId }.distinct().chunked(900).flatMap { db.dao().tracksByIdsUnordered(it) }.associate { it.id to it.relinkKey } }
            if (kept.isNotEmpty()) emit(kept.map { app.winters.octo.catalog.NewItem(it.trackId, keys[it.trackId].orEmpty()) })
            if (page.size < QUEUE_PAGE) return
            from = page.last()
        }
    }
    private val jobs = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch(WriteLabels.of(QUEUE_JOB_WRITE)) {
            for (job in jobs) {
                try {
                    job()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SQLiteFullException) {
                    Log.w("Octo", "queue store: the disk is full")
                } catch (e: Exception) {
                    Log.w("Octo", "queue store: ${e.javaClass.simpleName}", e)
                }
            }
        }
    }

    // Runs `job` after every job posted before it.
    fun post(job: suspend () -> Unit) {
        jobs.trySend(job)
    }

    // Runs `block` in its turn and answers what it found. Never called by a
    // job: a job that waits for a later one never ends.
    suspend fun <T> inTurn(block: suspend () -> T): T {
        val answer = CompletableDeferred<T>()
        post {
            try {
                answer.complete(block())
            } catch (e: Throwable) {
                answer.completeExceptionally(e)
            }
        }
        return answer.await()
    }

    // ---- Ids ----

    private val seeding = Mutex()
    @Volatile private var seeded = false
    private val idLock = Any()
    private var nextList = 0L
    private var listsTo = 0L
    private var nextQueue = 0L
    private var queuesTo = 0L

    // Reads the saved counters once a process, so ids are given from
    // memory after. A read: it never waits for a write.
    suspend fun seed() {
        if (seeded) return
        seeding.withLock {
            if (seeded) return
            val meta = dao.meta()
            synchronized(idLock) {
                nextList = maxOf(meta?.nextListId ?: 1L, 1L)
                listsTo = nextList
                nextQueue = maxOf(meta?.nextQueueId ?: 1L, 1L)
                queuesTo = nextQueue
                firstQueue = nextQueue
            }
            seeded = true
        }
    }

    // The first generation id this process gives out: every one below it
    // was made by an earlier process.
    @Volatile private var firstQueue = Long.MAX_VALUE

    // ---- The start's sweep ----

    @Volatile private var swept = false

    // Once a process, at its first restore: generations an earlier process
    // left, killed while it deleted them or made and never taken up, go a
    // page at a time. Never one an account keeps, one in `kept` (Clear's,
    // while its Undo shows), or one this process made: a play still starting
    // has rows before any account holds it.
    fun sweepOnce(kept: Set<Long>) {
        if (swept || !seeded) return
        swept = true
        post {
            val below = firstQueue
            val found = HashSet<Long>()
            for (sql in listOf(QUEUE_NEXT_GENERATION_SQL, QUEUE_NEXT_LISTED_SQL)) {
                var after = Long.MIN_VALUE
                while (true) {
                    val next = dao.number(queueQuery(sql, after)) ?: break
                    if (next >= below) break
                    found += next
                    after = next
                }
            }
            val held = dao.accounts().mapTo(HashSet()) { it.queueId }
            for (generation in found.sorted()) {
                if (generation in held || generation in kept) continue
                Log.i("Octo", "queue: sweeping generation $generation an earlier run left")
                deleteGeneration(generation)
            }
        }
    }

    val isSeeded: Boolean get() = seeded

    suspend fun claimList(): Long {
        seed()
        return claimListNow()
    }

    suspend fun claimQueue(): Long {
        seed()
        return claimQueueNow()
    }

    // A generation id from memory, once the counters are read.
    fun claimQueueNow(): Long {
        check(seeded) { "the queue's ids are not read yet" }
        return synchronized(idLock) {
            if (nextQueue >= queuesTo) reserve(queues = ID_RUN)
            nextQueue++
        }
    }

    // A list id from memory, once the counters are read. Each run taken is
    // saved by a job posted before any job that uses its ids, so a death
    // never lets the next process give one out twice.
    fun claimListNow(): Long {
        check(seeded) { "the queue's ids are not read yet" }
        return synchronized(idLock) {
            if (nextList >= listsTo) reserve(lists = ID_RUN)
            nextList++
        }
    }

    // Ids for a job already on the worker: a run it takes is saved before
    // the job goes on, since a posted save would only run after it.
    suspend fun claimListInTurn(): Long = claimInTurn(lists = true)

    suspend fun claimQueueInTurn(): Long = claimInTurn(lists = false)

    private suspend fun claimInTurn(lists: Boolean): Long {
        seed()
        val (id, save) = synchronized(idLock) {
            val took = if (lists) nextList >= listsTo else nextQueue >= queuesTo
            if (took) if (lists) listsTo += ID_RUN else queuesTo += ID_RUN
            val id = if (lists) nextList++ else nextQueue++
            id to (if (took) queuesTo to listsTo else null)
        }
        save?.let { (q, l) -> dao.reserve(q, l) }
        return id
    }

    private fun reserve(lists: Int = 0, queues: Int = 0) {
        listsTo += lists
        queuesTo += queues
        val (q, l) = queuesTo to listsTo
        post { dao.reserve(q, l) }
    }

    // ---- Builders ----

    private class Running(val queueId: Long, val job: Job)

    private val builderLock = Any()
    private val builders = HashMap<Long, Running>()
    private val replaced = HashSet<Long>()

    fun builderOf(listId: Long): Job? = synchronized(builderLock) { builders[listId]?.job }

    fun buildersOf(queueId: Long): List<Long> = synchronized(builderLock) { builders.filterValues { it.queueId == queueId }.keys.toList() }

    // Starts `listId`'s builder unless one runs; answers the one that runs.
    fun startBuilder(listId: Long, queueId: Long, start: () -> Job): Job = synchronized(builderLock) {
        builders[listId]?.job ?: start().also { builders[listId] = Running(queueId, it) }
    }

    fun cancelBuilders(queueId: Long) {
        val running = synchronized(builderLock) { builders.values.filter { it.queueId == queueId }.map { it.job } }
        running.forEach { it.cancel() }
    }

    fun cancelBuilder(listId: Long) {
        synchronized(builderLock) { builders[listId]?.job }?.cancel()
    }

    // A builder that stops: answers whether it was its generation's last
    // and the generation is no longer wanted, so it deletes it.
    fun builderStopped(listId: Long, job: Job): Boolean = synchronized(builderLock) {
        val running = builders[listId]
        if (running?.job === job) builders.remove(listId)
        val queueId = running?.queueId ?: return@synchronized false
        queueId in replaced && builders.values.none { it.queueId == queueId }
    }

    // A generation is no longer wanted: answers whether no builder of it
    // runs, so the caller deletes it now; else its last builder does.
    fun retire(queueId: Long): Boolean = synchronized(builderLock) {
        replaced += queueId
        builders.values.none { it.queueId == queueId }
    }

    // A generation in use again (an Undo of Clear).
    fun unretire(queueId: Long) {
        synchronized(builderLock) { replaced -= queueId }
    }

    // ---- Reads, as autocommit statements: they never wait for a write ----

    suspend fun followed(trackId: String): String {
        if (isFind(trackId) || isRadio(trackId) || isOpenedFile(trackId)) return trackId
        return dao.ids(queueQuery(QUEUE_FOLLOW_SQL, trackId)).firstOrNull() ?: trackId
    }

    // A frozen copy's song as the library has it now, or null when it is
    // gone for good.
    suspend fun followedOrGone(trackId: String): String? {
        if (isFind(trackId) || isRadio(trackId) || isOpenedFile(trackId)) return trackId
        return dao.ids(queueQuery(QUEUE_FOLLOW_OR_GONE_SQL, trackId)).firstOrNull()
    }

    suspend fun account(account: String): QueueAccountEntity? = dao.account(account)

    // Up to `limit` rows after (or before) `from`'s place in an order,
    // nearest first; from the start (or end) for null.
    suspend fun page(queueId: Long, order: QueueOrder, from: QueueEntryEntity?, forward: Boolean, limit: Int, floors: Floors): List<QueueEntryEntity> {
        val sql = when (order) {
            QueueOrder.Play -> if (forward) QUEUE_AFTER_QK_SQL else QUEUE_BEFORE_QK_SQL
            QueueOrder.Shuffle -> if (forward) QUEUE_AFTER_SK_SQL else QUEUE_BEFORE_SK_SQL
        }
        val key = from?.key(order) ?: if (forward) Long.MIN_VALUE else Long.MAX_VALUE
        val entry = from?.entry ?: if (forward) Long.MIN_VALUE else Long.MAX_VALUE
        return dao.rows(queueQuery(sql, queueId, key, entry, floors.q, floors.s, limit.toLong()))
    }

    suspend fun count(queueId: Long, floors: Floors = Floors.NONE): Int =
        dao.number(queueQuery(QUEUE_COUNT_SQL, queueId, floors.q, floors.s))?.toInt() ?: 0

    // How many rows, and how long they play, after `from` in an order.
    suspend fun tail(queueId: Long, order: QueueOrder, from: QueueEntryEntity, floors: Floors): Pair<Int, Long> {
        val (countSql, lengthSql) = if (order == QueueOrder.Play) {
            QUEUE_COUNT_AFTER_QK_SQL to QUEUE_LENGTH_AFTER_QK_SQL
        } else {
            QUEUE_COUNT_AFTER_SK_SQL to QUEUE_LENGTH_AFTER_SK_SQL
        }
        val args = arrayOf<Any?>(queueId, from.key(order), from.entry, floors.q, floors.s)
        return (dao.number(queueQuery(countSql, *args))?.toInt() ?: 0) to (dao.number(queueQuery(lengthSql, *args)) ?: 0L)
    }

    suspend fun heldAmong(queueId: Long, trackIds: List<String>): Set<String> = trackIds.distinct().chunked(QUEUE_HELD_AMONG_CHUNK).flatMapTo(HashSet()) { chunk ->
        val args = ArrayList<Any?>(chunk)
        args += queueId
        dao.ids(queueQuery(queueHeldAmongSql(chunk.size), *args.toTypedArray()))
    }

    // The key next after (or before) `key` in an order, if any.
    private suspend fun neighborKey(queueId: Long, order: QueueOrder, key: Long, after: Boolean): Long? {
        val sql = when (order) {
            QueueOrder.Play -> if (after) QUEUE_NEXT_QK_SQL else QUEUE_PREV_QK_SQL
            QueueOrder.Shuffle -> if (after) QUEUE_NEXT_SK_SQL else QUEUE_PREV_SK_SQL
        }
        return dao.number(queueQuery(sql, queueId, key))
    }

    private suspend fun maxKey(queueId: Long, order: QueueOrder): Long? =
        dao.number(queueQuery(if (order == QueueOrder.Play) QUEUE_MAX_QK_SQL else QUEUE_MAX_SK_SQL, queueId))

    // ---- Writes, each a job's or a builder's own ----

    // A list with its rows, every song followed to the library song it is
    // now, in one transaction.
    suspend fun writeList(list: QueueListEntity, rows: List<QueueEntryEntity>) {
        val followed = rows.map { it.copy(trackId = followed(it.trackId)) }
        db.withTransaction {
            val baseline = work.swapCount()
            dao.putList(list.copy(swapCount = list.swapCount ?: baseline))
            followed.chunked(QUEUE_PAGE).forEach { dao.putEntries(it) }
        }
    }

    suspend fun swapCount(): Long = work.swapCount()

    // The account plays generation `queueId` from here on, healed as far as
    // its oldest list's data.
    suspend fun adopt(account: String, queueId: Long, current: Long?, positionMs: Long, repeatMode: Int, shuffle: Boolean) {
        db.withTransaction {
            val healedAt = dao.listsOf(queueId).mapNotNull { it.swapCount }.minOrNull() ?: work.swapCount()
            dao.putAccount(QueueAccountEntity(account, queueId, current, positionMs, repeatMode, shuffle, null, null, healedAt, System.currentTimeMillis()))
        }
    }

    // Deletes a generation a page at a time, each page a job of its own
    // posted at the back, then its lists and their waiting edits; then it
    // leaves the retired set. One an account keeps stays, and stays retired.
    fun deleteGeneration(queueId: Long, after: Long = Long.MIN_VALUE) {
        post {
            if (dao.accountsOn(queueId) > 0) return@post
            val end = deletePage(queueId, after, Long.MAX_VALUE)
            if (end != null) {
                deleteGeneration(queueId, end)
            } else {
                db.withTransaction {
                    dao.listsOf(queueId).forEach { dao.deletePending(it.listId) }
                    dao.deleteListsOf(queueId)
                }
                synchronized(builderLock) { if (builders.values.none { it.queueId == queueId }) replaced -= queueId }
            }
        }
    }

    // The generations retired and not yet deleted.
    internal fun retired(): Set<Long> = synchronized(builderLock) { HashSet(replaced) }

    // Deletes a list's rows a page at a time, then its row and waiting edits.
    fun deleteList(queueId: Long, listId: Long, after: Long = firstEntryOf(listId) - 1) {
        post {
            val end = deletePage(queueId, after, lastEntryOf(listId))
            if (end != null) {
                deleteList(queueId, listId, end)
            } else {
                db.withTransaction {
                    dao.deletePending(listId)
                    dao.deleteList(listId)
                }
            }
        }
    }

    // Deletes the rows numbered past `after` up to `last`, at most a page;
    // answers the last number it took when more may be left.
    suspend fun deletePage(queueId: Long, after: Long, last: Long): Long? {
        val end = dao.number(queueQuery(QUEUE_PAGE_END_SQL, queueId, after, last, (QUEUE_PAGE - 1).toLong()))
        db.withTransaction {
            db.openHelper.writableDatabase.execSQL(QUEUE_DELETE_RANGE_SQL, arrayOf<Any?>(queueId, after, end ?: last))
        }
        return end
    }

    // Keys for a run of `count` placed right after `after` (an entry in
    // that order; null for the very start) and before whatever follows it,
    // in one order; spaced out first when the gap is too small.
    suspend fun runKeys(queueId: Long, order: QueueOrder, after: QueueEntryEntity?, before: QueueEntryEntity?, count: Int): LongArray {
        val lo = after?.key(order) ?: before?.key(order)?.let { neighborKey(queueId, order, it, after = false) }
        val hi = when {
            after != null -> neighborKey(queueId, order, after.key(order), after = true)
            before != null -> before.key(order)
            else -> null
        }
        val lower = if (after == null && before == null) maxKey(queueId, order) else lo
        val upper = if (after == null && before == null) null else hi
        spacedKeys(lower, upper, count)?.let { return it }
        respace(queueId, order, lower ?: Long.MIN_VALUE, upper ?: Long.MAX_VALUE, count)
        val again = after?.let { dao.entry(queueId, it.entry) }
        return runKeys(queueId, order, again, before?.let { dao.entry(queueId, it.entry) }, count)
    }

    // Spreads the rows around a gap too small for `count` more over a
    // wider span, so the gap holds them. Only nested big inserts get here.
    private suspend fun respace(queueId: Long, order: QueueOrder, lo: Long, hi: Long, count: Int) {
        var take = QUEUE_PAGE
        while (true) {
            val behind = page(queueId, order, keyRow(order, lo, Long.MAX_VALUE), forward = false, take, Floors.NONE).reversed()
            val ahead = page(queueId, order, keyRow(order, hi, Long.MIN_VALUE), forward = true, take, Floors.NONE)
            val from = behind.firstOrNull()?.key(order)?.let { neighborKey(queueId, order, it, after = false) }
            val to = ahead.lastOrNull()?.key(order)?.let { neighborKey(queueId, order, it, after = true) }
            val slots = behind.size + count + ahead.size
            val keys = spacedKeys(from, to, slots)
            if (keys != null) {
                db.withTransaction {
                    behind.forEachIndexed { i, row -> setKey(row, order, keys[i]) }
                    ahead.forEachIndexed { i, row -> setKey(row, order, keys[behind.size + count + i]) }
                }
                return
            }
            if (behind.size < take && ahead.size < take) throw IllegalStateException("no room for $count more")
            take *= 2
        }
    }

    private fun keyRow(order: QueueOrder, key: Long, entry: Long) =
        QueueEntryEntity(0, entry, "", 0, if (order == QueueOrder.Play) key else 0, if (order == QueueOrder.Shuffle) key else 0, 0)

    private suspend fun setKey(row: QueueEntryEntity, order: QueueOrder, key: Long) {
        if (order == QueueOrder.Play) dao.setQk(row.queueId, row.entry, key) else dao.setKeys(row.queueId, row.entry, row.qk, key)
    }

    // The first key of a list's rows past `key` in an order, if any.
    suspend fun listFirstAfter(queueId: Long, listId: Long, order: QueueOrder, key: Long): Long? = dao.number(
        queueQuery(if (order == QueueOrder.Play) QUEUE_LIST_FIRST_QK_SQL else QUEUE_LIST_FIRST_SK_SQL, queueId, firstEntryOf(listId), lastEntryOf(listId), key),
    )

    // A page of a generation's rows by number, for the heal pass.
    suspend fun healPage(queueId: Long, after: Long): List<QueueEntryEntity> =
        dao.rows(queueQuery(QUEUE_HEAL_PAGE_SQL, queueId, after, QUEUE_PAGE.toLong()))

    // A big add's base and step in one order, from where its spot lands
    // now: room for PHASE1_BASE rows between the spot and the next row.
    suspend fun addKeys(queueId: Long, order: QueueOrder, spot: Spot): Pair<Long, Long> {
        val (after, before) = QueueApply(this).resolve(queueId, order, spot)
        val lo = when {
            after != null -> after.key(order)
            before != null -> page(queueId, order, before, forward = false, 1, Floors.NONE).firstOrNull()?.key(order)
            else -> page(queueId, order, null, forward = false, 1, Floors.NONE).firstOrNull()?.key(order)
        }
        val hi = when {
            after != null -> page(queueId, order, after, forward = true, 1, Floors.NONE).firstOrNull()?.key(order)
            before != null -> before.key(order)
            else -> null
        }
        addSpacing(lo, hi)?.let { return it }
        // Too little room: the gap is spaced out to fit the add's rows.
        respace(queueId, order, lo ?: Long.MIN_VALUE, hi ?: Long.MAX_VALUE, PHASE1_BASE.toInt())
        return addKeys(queueId, order, spot)
    }

    // Follows rows of a generation (or of one list, `listId`) whose song a
    // merge took away to the library song it is now, a page at a time by
    // number, each page a job of its own posted at the back. A list still
    // being written is passed over: its join heals it. The last page notes
    // the swap count the rows are healed to. A rename that leaves a list
    // that plays each song once holding a song twice keeps the one first in
    // the order the generation plays.
    fun healRange(generation: Long, after: Long, last: Long, count: Long, skipped: List<Long>, listId: Long?) {
        post {
            val page = healPage(generation, after).filter { it.entry <= last }
            var skip = skipped
            var jumpTo: Long? = null
            val pairs = db.withTransaction {
                val renames = ArrayList<Pair<QueueEntryEntity, String>>()
                for (row in page) {
                    val list = listOfEntry(row.entry)
                    if (listId == null && dao.list(list)?.let { it.queueId == generation && it.fillState != QueueBuilds.JOINED } == true) {
                        if (list !in skip) skip = skip + list
                        jumpTo = lastEntryOf(list)
                        break
                    }
                    val now = followed(row.trackId)
                    if (now != row.trackId) renames += row to now
                }
                renamed(generation, renames)
            }
            settleDoubled(generation, pairs)
            val next = jumpTo ?: page.lastOrNull()?.entry
            if (next != null && (jumpTo != null || page.size == QUEUE_PAGE) && next < last) {
                healRange(generation, next, last, count, skip, listId)
            } else if (listId != null) {
                dao.setBaseline(listId, count)
            } else {
                dao.setHealedAt(generation, count)
                dao.healedLists(generation, count, skip.ifEmpty { listOf(-1L) })
            }
        }
    }

    // ---- Shuffle on and off ----

    // Turning shuffle on: the current row takes the first shuffled key,
    // below every other row the floors show and every key a list still being
    // written can take, never below the shuffled floor; the key it had stays
    // in skBefore. Every other row keeps its key, so the rest plays in the
    // play's own order, and a row that held the first key takes its own
    // back. A job of the worker's.
    suspend fun keyFirst(queueId: Long, current: Long, floors: Floors) {
        db.withTransaction {
            restoreFirstKeys(queueId, floors, keep = current)
            val row = dao.entry(queueId, current) ?: return@withTransaction
            val shown = page(queueId, QueueOrder.Shuffle, null, forward = true, 2, floors).firstOrNull { it.entry != current }?.sk
            val lowest = listOfNotNull(shown, lowestUnwritten(queueId)).minOrNull() ?: return@withTransaction
            if (row.sk < lowest) return@withTransaction
            val floor = floors.s
            val first = firstKeyBelow(lowest, floor)
            if (first == lowest) {
                // The floor's own row is the lowest: it moves just past the
                // floor, so the current row alone takes the floor's key.
                val next = neighborKey(queueId, QueueOrder.Shuffle, lowest, after = true)
                val moved = if (next == null) lowest + KEY_STEP else lowest + maxOf(1L, gapStep(lowest, next, 1))
                for (at in page(queueId, QueueOrder.Shuffle, keyRow(QueueOrder.Shuffle, lowest, Long.MIN_VALUE), forward = true, QUEUE_PAGE, floors)) {
                    if (at.sk != lowest) break
                    if (at.entry != current) dao.setShuffled(queueId, at.entry, moved, at.skBefore)
                }
            }
            dao.setShuffled(queueId, current, first, row.skBefore ?: row.sk)
        }
    }

    // Turning shuffle off: every row that held the first key takes its own
    // back. A job of the worker's.
    suspend fun unkeyFirst(queueId: Long, floors: Floors, keep: Long?) {
        db.withTransaction { restoreFirstKeys(queueId, floors, keep) }
    }

    // Rows holding the first shuffled key take back the keys they had,
    // but `keep` and those Remove played's floors hide, which stay hidden.
    private suspend fun restoreFirstKeys(queueId: Long, floors: Floors, keep: Long?) {
        for (row in dao.rows(queueQuery(QUEUE_FIRST_KEYED_SQL, queueId, Long.MIN_VALUE))) {
            if (row.entry == keep || row.qk < floors.q || row.sk < floors.s) continue
            dao.setShuffled(queueId, row.entry, row.skBefore ?: continue, null)
        }
    }

    // The lowest shuffled key a list of the generation still being written
    // can write: a play's hashed keys, or an add's run from its base.
    private suspend fun lowestUnwritten(queueId: Long): Long? = dao.listsOf(queueId).filter { it.fillState != QueueBuilds.JOINED }.mapNotNull { list ->
        when {
            list.qStep == null -> SK_REST
            list.sBase != null && list.sStep != null -> list.sBase + list.sStep
            else -> null
        }
    }.minOrNull()

    // ---- Renames ----

    // Inside a transaction: rows take the songs they are now. A row of a
    // list that plays each song once whose list holds that song already
    // leaves a pair; answers the pairs, the row later in the order the
    // generation plays named to go.
    private suspend fun renamed(generation: Long, renames: List<Pair<QueueEntryEntity, String>>): List<Doubled> {
        if (renames.isEmpty()) return emptyList()
        val shuffled = dao.accounts().firstOrNull { it.queueId == generation }?.shuffle == true
        val order = if (shuffled) QueueOrder.Shuffle else QueueOrder.Play
        val once = HashMap<Long, Boolean>()
        val pairs = ArrayList<Doubled>()
        for ((row, now) in renames) {
            dao.setTrack(generation, row.entry, now)
            val single = once.getOrPut(row.listId) { dao.list(row.listId)?.songsOnce == true }
            if (!single) continue
            val other = dao.rows(queueQuery(QUEUE_LIST_HOLDS_SQL, now, generation, row.listId)).firstOrNull { it.entry != row.entry } ?: continue
            val rowFirst = row.key(order) < other.key(order) || (row.key(order) == other.key(order) && row.entry < other.entry)
            pairs += if (rowFirst) Doubled(generation, drop = other.entry, keep = row.entry) else Doubled(generation, drop = row.entry, keep = other.entry)
        }
        return pairs
    }

    // After the rename's transaction: the generation in use's pairs go to
    // the window, which takes one of each out of the player and Room but
    // never the song playing; another generation's go at once, the saved
    // song kept.
    private suspend fun settleDoubled(generation: Long, pairs: List<Doubled>) {
        if (pairs.isEmpty()) return
        if (generation == inUse.value) {
            doubled.emit(pairs)
            return
        }
        val current = dao.accounts().firstOrNull { it.queueId == generation }?.currentEntry
        val drops = pairs.map { if (it.drop == current) it.keep else it.drop }.distinct()
        drops.chunked(900).forEach { db.withTransaction { dao.deleteEntries(generation, it) } }
    }

    // Rows a read found under songs a merge renamed, before the heal got to
    // them: they take the songs they are now. A job of the worker's.
    suspend fun rename(generation: Long, renames: List<Pair<QueueEntryEntity, String>>) {
        val pairs = db.withTransaction { renamed(generation, renames) }
        settleDoubled(generation, pairs)
    }

    // A waiting edit of a generation while one of its lists builds.
    suspend fun putPending(listId: Long, body: String) {
        db.withTransaction {
            dao.putPending(QueuePendingEntity(listId, dao.lastPending(listId) + 1, "edit", body))
        }
    }

    // Another account's queue becomes the one in use; no row moves. With a
    // queue saved for `to`, it comes back. With none, `keepWhenNone` keeps
    // the queue in use as `to`'s; otherwise `to` starts with none. Answers
    // whether the queue in use changed.
    suspend fun handOver(from: String, to: String, keepWhenNone: Boolean): Boolean = inTurn {
        if (dao.account(to) != null) return@inTurn true
        if (!keepWhenNone) return@inTurn true
        if (dao.account(from) != null) dao.renameAccount(from, to)
        false
    }

    // Forgets the queue kept for a server taken off the list: its row, and
    // its generation once nothing builds it.
    suspend fun forget(account: String) {
        val row = inTurn { dao.account(account)?.also { dao.deleteAccount(account) } } ?: return
        cancelBuilders(row.queueId)
        if (retire(row.queueId)) deleteGeneration(row.queueId)
    }
}
