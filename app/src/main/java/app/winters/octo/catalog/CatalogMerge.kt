package app.winters.octo.catalog

import android.database.sqlite.SQLiteFullException
import android.util.Log
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import app.winters.octo.data.SessionRepository
import app.winters.octo.data.SessionState
import app.winters.octo.data.userMessage
import app.winters.octo.device.DEVICE
import app.winters.octo.server.isServerSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

// How big the library is after a merge.
data class MergeCounts(val tracks: Int, val albums: Int, val artists: Int)

// A copy of a source's library that was not swapped in, and why, in words;
// `noRoom` when the phone ran short of room for it.
class StageAborted(val reason: String, val noRoom: Boolean = false) : Exception(reason)

// A save that stopped after its first group: the groups done stay, and the
// rest waits to be applied. `reason` is the stop's words.
class ApplyPending(val reason: String) : Exception(reason)

// How far a save's apply is: groups done of those planned, and how much of
// the save is in (`part`, 0 to 1), which a resumed save goes on from.
data class ApplyProgress(val done: Long, val of: Long, val part: Float = if (of <= 0) 1f else done.toFloat() / of)

// How far a merge is with a copy it claimed: `placed` of the copy's `of`
// songs, then `saving` once every song is placed (albums, artists and the
// save are left), with how much of the save is in (`saved`, 0 to 1) once
// its groups are planned.
data class StageProgress(val sourceId: String, val generation: Long, val placed: Int, val of: Int, val saving: Boolean, val saved: Float? = null)

// A server copy whose part-applied save is resumed, as the save's record
// keeps it: its source and generation, whose copy it is, whether that
// account's first, the server's scan the copy read, and how much of the
// save was in when it stopped (0 to 1).
data class ResumedCopy(val sourceId: String, val generation: Long, val account: String, val firstCopy: Boolean, val scan: String?, val saved: Float = 0f)

// What the app shows of a resumed save of a server's copy, and does once it
// is in. The merge calls it; the server side (ServerSync) gives it.
interface SaveResumes {
    // A resume of `copy`'s save begins: where it says how far it is.
    fun resuming(copy: ResumedCopy): ResumeReport
}

interface ResumeReport {
    // How much of the save is in, 0 to 1, after each group.
    fun saved(part: Float) {}

    // The save is in (`failure` null), or stopped again with `failure`.
    fun ended(failure: Throwable?) {}
}

// What a copy tells the merge before the merge is asked for it: the server's
// scan it read, kept with the save's record so a resume finishes the copy's
// record with it.
data class CopyNote(val scan: String?)

// A save's record while it is part applied, as merge_state.applyClaim
// keeps it: the claim (its sources in rank order with whether each is a
// staged copy, the copies it claimed with their generations and counts, and
// the server), or a skipped merge's save (`claim` false); whether its groups
// are in and its last steps begun (`lastSteps`); and how much of it is in.
@Serializable
internal data class SaveRecord(
    val sources: List<RecordSource> = emptyList(),
    val claimed: List<RecordStage> = emptyList(),
    val server: String = "",
    val claim: Boolean = true,
    val lastSteps: Boolean = false,
    val part: Float = 0f,
) {
    fun text(): String = RECORD_JSON.encodeToString(serializer(), this)

    companion object {
        fun of(text: String): SaveRecord = RECORD_JSON.decodeFromString(serializer(), text)
    }
}

@Serializable
internal data class RecordSource(val id: String, val rank: Int, val staged: Boolean)

@Serializable
internal data class RecordStage(val source: String, val generation: Long, val tracks: Int)

private val RECORD_JSON = Json { ignoreUnknownKeys = true }

// A group's swap count raised with the save's record as it stands after
// the group, in one write of the one row.
internal const val RECORD_RAISE_SQL =
    "UPDATE merge_state SET swapCount = swapCount + 1, applyClaim = ?1, applyAccount = ?2, applyFirstCopy = ?3, applyScan = ?4, " +
        "applyStopped = NULL WHERE id = 0"

// The space a save takes beyond the file's pages in use at its merge's
// build's end, per song of its claimed copies and of the merge's output, by
// its kind (SaveKind): the most its find's rows and its groups' writes add
// before its work rows leave. Measured by
// HeapProof1bTest.shortSaveAtProofSize with long titles, album and artist
// names at 1,000 songs (first copy 3,299, switch 2,546, retag 393, an
// everyday copy retitling every song 502), and with short ones at 1,000
// and at 200,000 songs with 50,000 likes (first copy 1,782 and 1,883,
// switch 1,720 and 2,043, retag 387 and 413): the long names' figure, grown
// as short names grow from 1,000 songs to 200,000, and a tenth more.
// Libraries of short names are asked up to twice what they take.
internal const val FIRST_COPY_BYTES_PER_SONG = 3_800L
internal const val SWITCH_BYTES_PER_SONG = 3_300L
internal const val RECOPY_BYTES_PER_SONG = 615L

// The most one save group's transaction writes into the write-ahead log,
// for logRoom, per entry of the save's bound (SaveCosts), or per song of a
// save with fewer songs than its bound holds entries. Measured as above,
// the log's most less its checkpoint span, over two groups: 9,150 an entry
// at 200,000 songs (a switch, with writes and reads beside it, whose readers
// keep the log from starting again for more than one group), and a tenth
// more. A small library's groups write less than this a song, so it is
// asked up to about 40 MB more than it takes.
internal const val SAVE_LOG_BYTES_PER_ENTRY = 10_000L

// What a save does to the library, as its free space check tells it from
// the claim before the find: the first songs into an empty library
// (FirstCopy); a copy of a source the library holds no songs from, or of a
// server other than the one it was last merged for, beside songs it holds,
// whose songs may all change their ids (Switch); or a source's copy again,
// which changes at most every song in place (Recopy). An everyday copy and
// a retag of every song are both a Recopy here, as only the find tells
// them apart, so each asks what the retag takes.
internal enum class SaveKind(val bytesPerSong: Long) {
    FirstCopy(FIRST_COPY_BYTES_PER_SONG),
    Switch(SWITCH_BYTES_PER_SONG),
    Recopy(RECOPY_BYTES_PER_SONG),
}

// The most rows one transaction of a work table's clear deletes, and how a
// swapped copy's stage clear's writes are told apart when they are counted.
// The stage's clear and the merge's work clear take fewer, by what each row
// costs (clearRowCost), so each transaction stays within SAVE_GROUP_ENTRIES.
internal const val STAGE_CLEAR_ROWS = 2_000
internal const val STAGE_CLEAR = "stage clear"

// The reads one row's delete of a clear's page makes besides its writes:
// the row by its key, the page's look-up of it through an index, and one
// for the page's own check (a stage's source, a note's server copy).
internal const val CLEAR_READS = 3L

// The stage's tables, cleared one after another.
internal val STAGE_TABLES = listOf("stage_track", "stage_album", "stage_artist")

// What deleting one row of `table` in a clear costs (SaveCosts): its row
// and its index entries written, the tracker's probe, and its reads.
internal fun clearRowCost(costs: SaveCosts, table: String): Long = costs.whole(table) + CLEAR_READS

// How many rows of `table` one transaction of a clear takes with `room`
// of its cost left.
internal fun clearPageRows(costs: SaveCosts, table: String, room: Long = SAVE_GROUP_ENTRIES): Int = (room / clearRowCost(costs, table)).toInt()

// The clears of the merge's own work rows and of a copy's pages of the
// server's library, each run after the work that left them, by name (their
// writes' label too).
internal const val MERGE_WORK_CLEAR = "merge work clear"
internal const val COPY_PAGES_CLEAR = "copy pages clear"

// The save's own work rows its find writes; the renames in force
// (save_rename) are not among them.
internal val SAVE_WORK_TABLES = listOf("save_found", "save_ref", "save_node", "save_set")

// Points a test can stop a merge at. Each does nothing in the app.
internal open class MergeHooks {
    // After the claim, holding the source-write gate.
    open suspend fun claimed() {}

    // After the first page of songs is placed, outside any transaction.
    open suspend fun firstOutputWritten() {}

    // After a claimed merge's build has written all its output, before the
    // free space is checked, holding the source-write gate and no
    // transaction.
    open suspend fun built() {}

    // The free space a claimed merge's save asks for once its build is
    // done, and the kind of save it is taken for.
    open fun spaceAsked(kind: SaveKind, bytes: Long) {}

    // Before a claim's copies are checked, right before its save's first
    // group, holding the source-write gate and no transaction.
    open suspend fun checking() {}

    // Inside the first group of a claimed merge's save, before it writes,
    // the swap stamp odd.
    open suspend fun inSwap() {}

    // Inside each group of a save (each page of a run is one), before it
    // writes: its number in the save, from 1, and its group in the plan.
    open suspend fun inGroup(turn: Long, group: Long) {}

    // Inside a group, once it has written: each of its steps (a
    // statement's way of writing and its table) and the rows it wrote.
    open fun groupStep(turn: Long, step: String, rows: Long) {}

    // After each group committed and let the merge lock go, holding the
    // source-write gate and no transaction.
    open suspend fun betweenGroups(turn: Long) {}

    // After a save's last transaction: how many groups it took, the rows it
    // wrote and how long it took, from its find to its end.
    open fun applied(turns: Long, rows: Long, millis: Long) {}

    // After each page of a swapped copy's stage rows went, outside any
    // transaction and holding no lock.
    open suspend fun stageClearPage(sourceId: String) {}

    // After a copy swapped in has left the stage, page by page: the rows
    // that went and how long it took.
    open fun stageCleared(sourceId: String, rows: Long, millis: Long) {}

    // After each page of a clear run later (MERGE_WORK_CLEAR,
    // COPY_PAGES_CLEAR) went, outside any transaction and holding no lock,
    // and once it is done, with the rows that went.
    open suspend fun clearPage(what: String) {}

    open fun cleared(what: String, rows: Long) {}

    open fun merged() {}

    open fun skipped() {}

    // Every write of how far the merge is with a claimed copy.
    open fun progress(progress: StageProgress?) {}

    // A group of a save waits for holds of the merge lock to leave: new
    // Walk holds queue from now, and new Read and Page holds too once no
    // Walk is inside.
    open fun groupWaits() {}

    // A resume of a part-applied save begins, holding the source-write gate
    // and no transaction; `auto` for the one a stop starts. And it ended:
    // its save in (`failure` null), or stopped again.
    open suspend fun resuming(auto: Boolean) {}

    open fun resumed(failure: Throwable?) {}
}

// Rebuilds the library the screens read from what the sources have, after
// any source changes. One merge at a time, the phone's files first so its
// songs keep their ids.
//
// Every kept server's music is kept (see ServerSync), but only the server
// in use joins the library, so two servers' copies of a song never become
// one row. The other servers' copies are no library song's meanwhile: they
// lose their merged id, so nothing that looks up a song's copies (playing,
// stars, plays, downloads) finds them.
//
// Asking for a merge waits for one that starts after the ask; asks that
// come while one runs share the next. A merge whose inputs (which sources,
// each one's stamp, any copy waiting to be swapped in, the rules) are the
// ones last merged is skipped. A server copy built beside the live rows
// (stage_*) is claimed by the next merge, which reads it in place of the
// live rows and saves both together.
//
// A save is a find (SaveFind: reads, and its own work rows) and an apply
// (SaveApply) in groups, each one short transaction within `bound`, so no
// write of a save holds the database's write lock for long and every other
// writer waits one group at most. A skipped merge's save finds and applies
// only what follows song ids.
//
// Locks, always taken in this order: a writer's own lock (scan, take-in,
// drop), then the source-write gate, then the merge lock, then a database
// transaction. The merge holds the gate from its claim to the end of its
// save, and the merge lock alone only for each group. The gate is never
// taken inside a transaction or a betweenRebuilds block. A swapped copy's
// stage rows go afterwards, under none of them.
@Singleton
class CatalogMerge internal constructor(
    private val db: CatalogDatabase,
    private val freeSpace: FreeSpace,
    private val serverInUse: suspend () -> String?,
    private val work: WorkDao = db.workDao(),
    // What one write transaction of a save may cost (SaveCosts); never below
    // the floor (saveBound).
    bound: Long = SAVE_GROUP_ENTRIES,
) {
    @Inject constructor(db: CatalogDatabase, sessions: SessionRepository, freeSpace: FreeSpace) : this(
        db,
        freeSpace,
        { (sessions.state.first { it !is SessionState.Loading } as? SessionState.SignedIn)?.session?.sourceId },
    )

    internal val bound: Long = saveBound(bound)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + WriteLabels.of("merge"))

    // The merge lock: held alone by each group of a save, shared by
    // betweenRebuilds.
    private val swapGate = SwapGate().also { gate -> gate.aloneWaits = { hooks.groupWaits() } }

    // The source-write gate: held by a merge from its claim to its save's
    // end, and by every write to the sources' rows.
    private val gate = Mutex()

    internal var hooks = MergeHooks()
    private val merger = CatalogMerger(db, work) { hooks }

    private val pending = ArrayList<CompletableDeferred<MergeCounts>>()
    private var running = false

    // How each copy waiting to be swapped in ended, by source and generation.
    private val stages = HashMap<Pair<String, Long>, CompletableDeferred<Unit>>()

    // The clear of each source's copy swapped in or dropped last, while it
    // runs.
    private val clears = HashMap<String, Deferred<Unit>>()

    // The clears run after the work that left their rows (clearLater), by
    // name, while they run.
    private val later = HashMap<String, Deferred<Unit>>()

    // How far the merge holding a claim is with the server's copy it
    // claimed; null whenever no claim holds one. A claim holds the copy of
    // the server in use at most, and may hold the phone's copy beside it,
    // which says nothing here.
    private val _stageProgress = MutableStateFlow<StageProgress?>(null)
    val stageProgress: StateFlow<StageProgress?> = _stageProgress

    // Odd inside each group's transaction, from its first act to its
    // commit, even otherwise (a group that only waits for its turn leaves it
    // even); it only grows, from 0 in each process. A read that saw the same
    // even value before and after saw no song id change.
    private val _swapStamp = MutableStateFlow(0L)
    val swapStamp: StateFlow<Long> = _swapStamp

    // Odd from a save's first group to its last transaction, even after
    // it, and after a stop; it only grows. What re-rolls once a save is in
    // waits for it to turn even.
    private val _applyStamp = MutableStateFlow(0L)
    val applyStamp: StateFlow<Long> = _applyStamp

    // True from inside a save's first group to its last transaction's
    // commit, a stopped save's included: while it is, part of the save is
    // in and part is not, and a walk that decides from songs' rows together
    // (the offline rules, a playlist's push) waits for it to end.
    private val _applyPending = MutableStateFlow(false)
    val applyPending: StateFlow<Boolean> = _applyPending

    // A save stopped after its first group, with the stop's words, until
    // the next save's first group.
    private val _applyStopped = MutableStateFlow<String?>(null)
    val applyStopped: StateFlow<String?> = _applyStopped

    // How far the save running now is; null while none runs.
    private val _applyProgress = MutableStateFlow<ApplyProgress?>(null)
    val applyProgress: StateFlow<ApplyProgress?> = _applyProgress

    // After each group of a save committed: the songs it renamed by follow,
    // old id to new; null once the save has ended.
    private val _published = MutableSharedFlow<Map<String, String>?>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val published: SharedFlow<Map<String, String>?> = _published

    // True once no song keys stored by an older way of reading them are
    // left. Rows are always written with today's keys, so it stays true.
    private val _keysReady = MutableStateFlow(false)
    val keysReady: StateFlow<Boolean> = _keysReady

    // The refill of old song keys running now, if one is.
    private var refill: Deferred<Unit>? = null
    private val refillLock = Any()

    // The last generation this process gave a copy, phone or server, so
    // none is given twice even after a source and its rows are removed and
    // added again.
    private val issued = AtomicLong(0)

    // Where a resumed save of a server's copy says how far it is, and what
    // finishes the copy once it is in (ServerSync gives it).
    @Volatile var resumes: SaveResumes? = null

    // What each copy told the merge before it was asked for, by source and
    // generation, until the copy is forgotten.
    private val notes = HashMap<Pair<String, Long>, CopyNote>()

    // The resume of a part-applied save running now, if one is: one at a
    // time, in the merge's own scope, so no caller's cancel stops it.
    private var resume: Deferred<Unit>? = null
    private val resumeLock = Any()

    // Asks for a merge and waits for it. Answers how big the library is.
    suspend fun rebuild(): MergeCounts {
        val done = CompletableDeferred<MergeCounts>()
        val start = synchronized(pending) {
            pending += done
            if (running) false else true.also { running = true }
        }
        if (start) scope.launch { runMerges() }
        return done.await()
    }

    // The server the library was last merged for ("" for none), as the
    // merge itself wrote it with its result; null before any merge did.
    suspend fun mergedFor(): String? = work.mergedServer()

    // Runs `block` between groups of a save, never inside one, so it sees
    // song ids and everything that follows them in step. Blocks share the
    // lock, so each keeps its own lock for the rows it writes; `kind` says
    // how long it holds the lock (see HoldKind). Inside a hold it runs at
    // once. Nothing inside may wait for anything that could need a merge.
    suspend fun <T> betweenRebuilds(kind: HoldKind, block: suspend () -> T): T = swapGate.shared(kind, block)

    // Whether this coroutine holds the merge lock, shared or alone.
    internal suspend fun holdsMergeLock(): Boolean = swapGate.held()

    // How many wait for the merge lock now, for a test to look at.
    internal suspend fun lockWaiters(): Int = swapGate.waiters()

    // Whether no save is part way now: none is applying, and none stopped
    // with part of it in.
    val whole: Boolean get() = _applyStamp.value % 2 == 0L && !_applyPending.value

    // Waits until no save is part way, a save the app's end left part
    // applied included.
    suspend fun awaitWhole() {
        recordRead.await()
        combine(_applyStamp, _applyPending) { stamp, pending -> stamp % 2 == 0L && !pending }.first { it }
    }

    // A stamp even while no save is part way and odd while one is, which
    // moves as each save begins and ends.
    internal fun wholeStamp(): Long {
        val stamp = _applyStamp.value
        return if (_applyPending.value || stamp % 2 != 0L) 2 * stamp + 1 else 2 * stamp
    }

    // A new copy's generation, past `lastInDb` (the highest any copy has in
    // the database) and past every one this process gave before.
    fun issueGeneration(lastInDb: Long?): Long = issued.updateAndGet { maxOf(lastInDb ?: 0L, it) + 1 }

    // At launch: a save the app's end left part applied (its record stands)
    // is part way from now (applyPending), with a stop's words if it had
    // stopped, until the launch's repair resumes it; and song keys stored by
    // an older way of reading them are worked out again now, whether or not
    // a merge ever runs.
    fun start() {
        scope.launch {
            try {
                recordRead.await()
                refillKeys()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("Octo", "song keys refill failed: ${e.javaClass.simpleName}")
            }
        }
    }

    // The save's record as the database has it when this process first
    // asks: part way while it stands, with its stop's words. A save that
    // began in this process first says for itself.
    private val recordRead: Deferred<Unit> by lazy {
        scope.async {
            val state = try {
                work.mergeState()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("Octo", "merge: its record was not read: ${e.javaClass.simpleName}")
                null
            }
            synchronized(recordLock) {
                if (!turnedHere && state?.applyClaim != null) {
                    _applyPending.value = true
                    state.applyStopped?.let { _applyStopped.value = it }
                }
            }
        }
    }
    private val recordLock = Any()

    // Whether a save's first group has begun in this process.
    private var turnedHere = false

    // Runs a write to the sources' rows while no merge holds a claim, so a
    // merge never reads half of one. A save part applied is resumed first,
    // and while it still stands (its resume stopped) the write never runs:
    // this throws ApplyPending instead. Inside a hold of the merge lock it
    // throws at once, the resume started, since the resume's groups would
    // wait for that hold.
    suspend fun <T> sourceWrite(block: suspend () -> T): T {
        if (holdsMergeLock()) {
            if (work.applying()) {
                resumeJob(auto = false)
                throw ApplyPending(pendingWords())
            }
        } else {
            awaitResume()
        }
        return gate.withLock {
            if (work.applying()) throw ApplyPending(pendingWords())
            block()
        }
    }

    // Tells the merge what the copy of `sourceId` built as `generation`
    // read, for the save's record.
    fun noteCopy(sourceId: String, generation: Long, note: CopyNote) {
        synchronized(notes) { notes[sourceId to generation] = note }
    }

    // Whether the source-write gate is held now, for a test to look at.
    internal val gateHeld: Boolean get() = gate.isLocked

    // Waits until the copy of `sourceId` built as `generation` is swapped
    // in, or throws StageAborted when it was not.
    suspend fun awaitStage(sourceId: String, generation: Long) {
        try {
            stageOutcome(sourceId, generation).await()
        } finally {
            forgetStage(sourceId, generation)
        }
    }

    // The copy is done with its outcome, however it ended.
    fun forgetStage(sourceId: String, generation: Long) {
        synchronized(stages) { stages.remove(sourceId to generation) }
        synchronized(notes) { notes.remove(sourceId to generation) }
    }

    // A new copy of the source starts: outcomes of its earlier ones that no
    // copy waits for any more (one cancelled while its swap ran) go.
    fun forgetStagesBefore(sourceId: String, generation: Long) {
        synchronized(stages) { stages.keys.removeAll { it.first == sourceId && it.second < generation } }
    }

    // At launch: a save the app's end left part applied is resumed, and a
    // source row no merge has placed (the app stopped between a write and
    // its merge) gets its merge now.
    suspend fun repair() {
        val server = serverInUse()
        val broken = work.applying() || liveSources().filter { inLibrary(it, server) }.any { work.hasUnmerged(it) }
        if (!broken) return
        work.setSkipKey(null)
        try {
            rebuild()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Octo", "library repair failed: ${e.javaClass.simpleName}")
        }
    }

    private suspend fun runMerges() {
        while (true) {
            val batch = synchronized(pending) {
                if (pending.isEmpty()) {
                    running = false
                    return
                }
                pending.toList().also { pending.clear() }
            }
            try {
                val counts = mergeOnce()
                batch.forEach { it.complete(counts) }
            } catch (e: CancellationException) {
                batch.forEach { it.cancel(e) }
                synchronized(pending) { running = false }
                throw e
            } catch (e: Exception) {
                batch.forEach { it.completeExceptionally(e) }
            }
        }
    }

    private suspend fun mergeOnce(): MergeCounts {
        // Copies claimed again in this run after another copy failed its
        // check: each is put back once at most.
        val putBack = HashSet<Pair<String, Long>>()
        while (true) {
            // A save part applied is resumed first, outside every
            // transaction and before decide(); the last merge's work rows go
            // before this one takes the gate, and old song keys are read
            // again after the resume.
            awaitResume()
            awaitWorkClear()
            refillKeys()
            val server = serverInUse()
            var again = false
            var found = false
            val counts = try {
                gate.withLock {
                    // The resume stopped: nothing else is merged while its
                    // record stands.
                    if (work.applying()) throw ApplyPending(pendingWords())
                    mergeClaim(server, putBack, { again = true }, { found = true }) ?: if (again) {
                        null
                    } else {
                        // A skipped merge's save clears only what it found.
                        skipped().also { found = work.hasMergeWork() }
                    }
                }
            } finally {
                // Once the gate is let go: the work rows go, and each copy
                // swapped in or dropped leaves the stage, in the background.
                withContext(NonCancellable) {
                    if (found) clearLater(MERGE_WORK_CLEAR) { clearWork() }
                    clearMarked()
                }
            }
            if (again) continue
            return counts!!
        }
    }

    // Under the gate: claims what waits to be merged, merges and saves it,
    // or aborts; null when nothing was claimed or the claim aborted
    // (`again`, which asks for another round). `claimed` is told when a claim
    // ran.
    private suspend fun mergeClaim(server: String?, putBack: MutableSet<Pair<String, Long>>, again: () -> Unit, claimed: () -> Unit): MergeCounts? {
        val step = decide(server)
        failStages(step.dropped, ELSEWHERE)
        failStages(step.leftover, CHANGED)
        val claim = step.claim ?: return null
        claimed()
        hooks.claimed()
        // Each abort says first that nothing is being saved, before the
        // copies it drops are marked to go. Once a group of the save is in,
        // nothing is put back: the save stops, its groups stay, and each
        // claimed copy hears why.
        var started = false
        // The save's kind and bound, and the library's songs, read before the
        // merge writes, so a full disk is answered with no read after it.
        var sizing: SaveSizing? = null
        return try {
            if (claim.claimed.isNotEmpty()) sizing = SaveSizing(kindOf(claim), boundOf(claim), work.trackTotal())
            val counts = merger.merge(claim.sources, progressOf(claim))
            hooks.built()
            sizing?.let { checkSpace(claim, counts, it.kind, it.bound) }
            save(claim) { started = true }
            claim.claimed.forEach { stageOutcome(it.sourceId, it.generation).complete(Unit) }
            hooks.merged()
            counts
        } catch (e: StageAbort) {
            progressEnds()
            abort(claim, e.reason, e.failed, putBack, e.noRoom)
            again()
            null
        } catch (e: SQLiteFullException) {
            progressEnds()
            if (started) {
                failPending(claim.claimed, e.userMessage())
                throw e
            }
            if (claim.claimed.isEmpty()) {
                abort(claim, e.userMessage())
                throw e
            }
            val needed = sizing?.let { neededFor(claim, it.songs, it.kind, it.bound) }
            abort(claim, NotEnoughSpace(needed).userMessage(), noRoom = true)
            again()
            null
        } catch (e: CancellationException) {
            progressEnds()
            if (started) {
                failPending(claim.claimed, e.userMessage())
                throw e
            }
            withContext(NonCancellable) { abort(claim, e.userMessage()) }
            throw e
        } catch (e: Exception) {
            progressEnds()
            if (started) {
                failPending(claim.claimed, e.userMessage())
                throw e
            }
            abort(claim, e.userMessage())
            throw e
        } finally {
            progressEnds()
        }
    }

    // Runs `clear` in the background, after the work that left its rows, as
    // the one clear of `what` at a time: the clear running now, or a new one.
    internal fun clearLater(what: String, clear: suspend () -> Unit): Deferred<Unit> = synchronized(later) {
        later[what]?.takeIf { it.isActive } ?: scope.async { clear() }.also { later[what] = it }
    }

    // Waits for the clear of `what` running now, if one is.
    internal suspend fun awaitClear(what: String) {
        synchronized(later) { later[what]?.takeIf { it.isActive } }?.await()
    }

    // Waits until the last merge's work rows are gone: the clear running,
    // or, with rows left (a clear that failed), one now.
    private suspend fun awaitWorkClear() {
        awaitClear(MERGE_WORK_CLEAR)
        if (work.hasMergeWork()) clearLater(MERGE_WORK_CLEAR) { clearWork() }.await()
    }

    // Waits for the resume of a part-applied save, started now if none
    // runs, without the gate: a caller's cancel leaves the resume running,
    // and a resume that stops leaves its record, which the caller reads
    // again under the gate.
    private suspend fun awaitResume() {
        if (!work.applying()) return
        try {
            resumeJob(auto = false).await()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
        } catch (e: Exception) {
            Log.w("Octo", "merge: a resume stopped: ${e.javaClass.simpleName}")
        }
    }

    // The resume running now, or a new one: one Deferred in the merge's
    // scope, however many ask.
    private fun resumeJob(auto: Boolean): Deferred<Unit> = synchronized(resumeLock) {
        resume?.takeIf { it.isActive } ?: scope.async { resumePending(auto) }.also { resume = it }
    }

    // For Try again: resumes a part-applied save (and waits for it);
    // answers false when none stands.
    suspend fun resumeSave(): Boolean {
        if (!work.applying()) return false
        awaitResume()
        return true
    }

    // Whether a save is part applied now, as its record says.
    suspend fun savePending(): Boolean = work.applying()

    // Resumes the save whose record stands: under the gate, outside every
    // transaction, once a clear of the merge's work rows running now is
    // done. Its find runs again against what the groups done left (save_*
    // cleared first, the renames in force kept), and its apply writes what
    // is left; a record whose groups were all in goes straight to its last
    // steps. A resumed server copy reports through `resumes` and is
    // finished there.
    private suspend fun resumePending(auto: Boolean) {
        awaitClear(MERGE_WORK_CLEAR)
        gate.withLock {
            val state = work.mergeState() ?: return@withLock
            val text = state.applyClaim ?: return@withLock
            val record = SaveRecord.of(text)
            hooks.resuming(auto)
            val claim = if (record.claim) {
                Claim(record.sources.map { MergeSource(it.id, it.rank, it.staged) }, record.claimed.map { ClaimedStage(it.source, it.generation, it.tracks) }, record.server)
            } else {
                null
            }
            val stage = claim?.claimed?.firstOrNull { it.sourceId == claim.server }
            val account = state.applyAccount
            val report = if (stage != null && account != null) {
                resumes?.resuming(ResumedCopy(stage.sourceId, stage.generation, account, state.applyFirstCopy, state.applyScan, record.part))
            } else {
                null
            }
            try {
                save(claim, Resume(record, state, auto), report) {}
            } catch (e: Throwable) {
                report?.ended(e)
                hooks.resumed(e)
                throw e
            }
            claim?.claimed?.forEach { c -> synchronized(stages) { stages[c.sourceId to c.generation] }?.complete(Unit) }
            report?.ended(null)
            hooks.resumed(null)
        }
        withContext(NonCancellable) {
            clearLater(MERGE_WORK_CLEAR) { clearWork() }
            clearMarked()
        }
    }

    // A part-applied save being resumed: its record, the record's row, and
    // whether a stop started it.
    private class Resume(val record: SaveRecord, val state: MergeStateEntity, val auto: Boolean)

    // What a claim's free space ask is worked out from: its save's kind and
    // bound, and the songs the library holds before its merge.
    private class SaveSizing(val kind: SaveKind, val bound: Long, val songs: Int)

    // The words of the save part applied: its stop's, or what a resume
    // still to come says.
    private fun pendingWords(): String = _applyStopped.value ?: STILL_SAVING

    // The merge's work rows, its output's and its save's (but never the
    // renames in force), and the take-in log and rating notes no copy keeps,
    // go a page a transaction, the tables one after another, each
    // transaction costing at most SAVE_GROUP_ENTRIES by what each table's
    // rows cost (clearRowCost) and the record's read, so no write holds the
    // lock for longer than a save's group and a small merge's clear is one
    // transaction. While a save is part applied (its record stands) nothing
    // goes: its resume needs them, and clears its find's rows itself. A
    // delete that fails leaves rows, and the next merge clears what is left
    // first.
    private suspend fun clearWork() {
        try {
            val pages: List<Pair<String, suspend (Int) -> Int>> = listOf<Pair<String, suspend (Int) -> Int>>(
                "merge_candidate" to { work.deleteCandidatePage(it) },
                "merged_id_next" to { work.deleteMergedIdNextPage(it) },
                "album_next" to { work.deleteAlbumNextPage(it) },
                "artist_next" to { work.deleteArtistNextPage(it) },
            ) + SAVE_WORK_TABLES.map { table -> Pair<String, suspend (Int) -> Int>(table) { limit -> savePage(table, limit) } } + listOf<Pair<String, suspend (Int) -> Int>>(
                "take_in_log" to { work.deleteStaleTakeInPage(it) },
                "rating_note" to { work.deleteStaleNotePage(it) },
            )
            val costs = SaveCosts.of(db.openHelper.writableDatabase, pages.map { it.first })
            val rowCosts = pages.map { clearRowCost(costs, it.first) }
            val rows = withContext(WriteLabels.of(MERGE_WORK_CLEAR)) {
                var rows = 0L
                var at = 0
                while (at < pages.size) {
                    val gone = db.withTransaction {
                        if (work.applying()) {
                            at = pages.size
                            return@withTransaction 0
                        }
                        // What is left of the transaction's cost once the
                        // record's read is counted.
                        var room = SAVE_GROUP_ENTRIES - 1
                        var gone = 0
                        while (at < pages.size) {
                            val ask = (room / rowCosts[at]).toInt()
                            if (ask == 0) break
                            val n = pages[at].second(ask)
                            gone += n
                            room -= n * rowCosts[at]
                            // A table that gave less than asked is empty now;
                            // one that gave all of it may hold more.
                            if (n < ask) at++ else break
                        }
                        gone
                    }
                    rows += gone
                    hooks.clearPage(MERGE_WORK_CLEAR)
                }
                rows
            }
            hooks.cleared(MERGE_WORK_CLEAR, rows)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Octo", "merge: its work rows stay for now: ${e.javaClass.simpleName}")
        }
    }

    // Up to `limit` of a save work table's rows deleted; answers how many.
    private fun savePage(table: String, limit: Int): Int =
        db.openHelper.writableDatabase.compileStatement("DELETE FROM `$table` WHERE rowid IN (SELECT rowid FROM `$table` LIMIT ?1)").use {
            it.bindLong(1, limit.toLong())
            it.executeUpdateDelete()
        }

    // Says how far the merge is with the server's copy `claim` holds, if it
    // holds one.
    private fun progressOf(claim: Claim): MergeProgress {
        val stage = claim.claimed.firstOrNull { it.sourceId == claim.server } ?: return MergeProgress.None
        var placed = 0
        progressIs(StageProgress(stage.sourceId, stage.generation, 0, stage.tracks, saving = false))
        return object : MergeProgress {
            override fun page(source: MergeSource, rows: Int) {
                if (source.sourceId != stage.sourceId || !source.staged) return
                placed += rows
                progressIs(StageProgress(stage.sourceId, stage.generation, placed, stage.tracks, saving = false))
            }

            override fun placed() = progressIs(StageProgress(stage.sourceId, stage.generation, placed, stage.tracks, saving = true))
        }
    }

    private fun progressIs(progress: StageProgress?) {
        _stageProgress.value = progress
        hooks.progress(progress)
    }

    private fun progressEnds() {
        if (_stageProgress.value != null) progressIs(null)
    }

    // Nothing changed since the last merge: the library stays, and rows
    // written meanwhile with an id a merge took away still follow it, in
    // groups as any save's; each group that moves one raises the swap count.
    private suspend fun skipped(): MergeCounts {
        save(null) {}
        hooks.skipped()
        return MergeCounts(work.trackTotal(), work.albumTotal(), work.artistTotal())
    }

    // A save, under the gate: its find (reads, and its own work rows, outside
    // every transaction and the merge lock); a claim's copies checked; then
    // its apply, a group at a time. `started` is told once its first group
    // is in, after which a failure stops the save with its groups kept.
    //
    // Each group writes the save's record with its swap count (SaveRecords),
    // so a stop or the app's end leaves what a resume needs; the final
    // transaction removes it. A resume (`resume`) keeps the renames in
    // force, skips the copies' check (the claim is in already), and goes on
    // from how much of the save its record says is in.
    private suspend fun save(claim: Claim?, resume: Resume? = null, report: ResumeReport? = null, started: () -> Unit) {
        val began = System.nanoTime()
        val copies = (claim?.sources?.size ?: 2).toLong().coerceAtLeast(1)
        val bound = saveBoundFor(copies)
        val base = resume?.record?.part ?: 0f
        val turns = Turns(claim, resume != null, started)
        val records = SaveRecords(claim, resume?.state, base).also { it.prepare() }
        val apply = SaveApply(db, bound, turns, { hooks }, copies, record = { done, of -> records.args(partOf(base, done, of)) }, lastStepsBegun = records::lastSteps)
        val applyClaim = claim?.let { c -> ApplyClaim(c.sources.map { it.sourceId }, c.claimed.map { it.sourceId to it.generation }, c.server) }
        try {
            if (resume != null && resume.record.lastSteps && applyClaim != null) {
                // Every group was in: only the last steps are left.
                resumedWithNoGroups()
                apply.lastStepsOnly(applyClaim)
            } else {
                val plan = SaveFind(db, bound).find(claim?.let { c -> SaveClaim(c.sources.map { it.sourceId }, c.claimed.associate { it.sourceId to it.generation }) })
                if (claim != null && resume == null) {
                    hooks.checking()
                    checkCopies(claim)
                }
                apply.run(plan, applyClaim, resumed = resume != null) { done, of -> progress(partOf(base, done, of), done, of, report) }
            }
        } catch (e: Throwable) {
            if (turns.began || resume != null) stopped(e.userMessage(), automatic = resume == null)
            throw e
        } finally {
            _applyProgress.value = null
        }
        // The save is in: its last transaction has committed.
        if (turns.began || resume != null) {
            _applyPending.value = false
            _applyStopped.value = null
            if (_applyStamp.value % 2 != 0L) _applyStamp.value += 1
        }
        _published.tryEmit(null)
        val millis = (System.nanoTime() - began) / 1_000_000
        Log.i("Octo", "merge: save wrote ${apply.rows} rows in ${apply.turnsDone} groups in $millis ms")
        hooks.applied(apply.turnsDone, apply.rows, millis)
    }

    // For a test: the save of a claim a test already claimed and built
    // (the sources in the library in rank order, each claimed copy's
    // generation by source), under the gate as a merge's is; and a skipped
    // merge's save.
    internal suspend fun saveBuilt(sources: List<String>, staged: Map<String, Long>, server: String) = gate.withLock {
        val claimed = staged.map { (source, generation) -> ClaimedStage(source, generation, 0) }
        save(Claim(sources.mapIndexed { rank, id -> MergeSource(id, rank, id in staged) }, claimed, server)) {}
    }

    internal suspend fun saveSkipped() = gate.withLock { save(null) {} }

    // What one write transaction of a save of a claim with `copies` copies a
    // song may cost: the merge's bound, raised to the largest unit of that
    // save's own work, whose recounts read every copy (SaveCosts.largestUnit).
    internal fun saveBoundFor(copies: Long): Long =
        maxOf(bound, SaveCosts.of(db.openHelper.writableDatabase, SAVE_COST_TABLES).largestUnit(copies))

    // How much of a save is in after `done` of its `of` groups, going on
    // from `base` for a resume, so it never moves back.
    private fun partOf(base: Float, done: Long, of: Long): Float = if (of <= 0) 1f else base + (1f - base) * done / of

    // After each group: how far the save is, for the copy being saved and a
    // resume's report.
    private fun progress(part: Float, done: Long, of: Long, report: ResumeReport?) {
        _applyProgress.value = ApplyProgress(done, of, part)
        _stageProgress.value?.takeIf { it.saving }?.let { progressIs(it.copy(saved = part)) }
        report?.saved(part)
    }

    // A resume whose groups were all in: it is applying from now, as a
    // first group would make it.
    private fun resumedWithNoGroups() {
        synchronized(recordLock) { turnedHere = true }
        _applyStopped.value = null
        if (_applyStamp.value % 2 == 0L) _applyStamp.value += 1
        _applyPending.value = true
    }

    // The save's record, as each group writes it: the claim (or a skipped
    // merge's save), how much is in, and for a server's copy whose copy it
    // is, whether that account's first, and the scan the copy read. A
    // resume's comes from the record it resumes.
    private inner class SaveRecords(private val claim: Claim?, prior: MergeStateEntity?, base: Float) {
        private val record = SaveRecord(
            sources = claim?.sources?.map { RecordSource(it.sourceId, it.rank, it.staged) }.orEmpty(),
            claimed = claim?.claimed?.map { RecordStage(it.sourceId, it.generation, it.tracks) }.orEmpty(),
            server = claim?.server.orEmpty(),
            claim = claim != null,
            part = base,
        )
        private var copy: Triple<String?, Boolean, String?>? = prior?.let { Triple(it.applyAccount, it.applyFirstCopy, it.applyScan) }

        // Before the first group, under the gate and outside every
        // transaction: the server copy's account from its stage (fixed since
        // the claim), its first copy when the address's rows are not that
        // account's yet, and the scan its copy noted.
        suspend fun prepare() {
            if (copy != null) return
            val stage = claim?.claimed?.firstOrNull { it.sourceId == claim.server }
            val meta = stage?.let { s -> work.metas().firstOrNull { it.sourceId == s.sourceId } }
            val note = stage?.let { s -> synchronized(notes) { notes[s.sourceId to s.generation] } }
            copy = Triple(meta?.stageOwner, meta != null && meta.stageOwner != null && meta.copyOf != meta.stageOwner, note?.scan)
        }

        // RECORD_RAISE_SQL's arguments, for a group's transaction.
        fun args(part: Float): Array<Any?> {
            val (account, first, scan) = copy ?: Triple(null, false, null)
            return arrayOf(record.copy(part = part).text(), account, if (first) 1L else 0L, scan)
        }

        // Inside the transaction of the copies' bookkeeping: the groups are
        // in and the last steps begun, with the skip key the save ends with.
        suspend fun lastSteps() {
            val sources = claim?.sources?.map { it.sourceId }.orEmpty()
            val key = skipKey(sources, work.metas().associate { it.sourceId to it.stamp }, emptyMap())
            db.openHelper.writableDatabase.execSQL(
                "UPDATE merge_state SET applyClaim = ?1, applySkipKey = ?2 WHERE id = 0",
                arrayOf<Any?>(record.copy(lastSteps = true, part = 1f).text(), key),
            )
        }
    }

    // A save stopped after its first group: its groups stay, and it is
    // still part way, its record kept with the stop's words. The stop of a
    // save starts one resume of it at once; the stop of a resume starts
    // none, so only work (a merge ask, a write to the sources, Try again,
    // the next launch) starts another.
    private suspend fun stopped(words: String, automatic: Boolean) {
        _applyStopped.value = words
        if (_applyStamp.value % 2 != 0L) _applyStamp.value += 1
        withContext(NonCancellable) {
            try {
                work.setApplyStopped(words)
            } catch (e: Exception) {
                Log.w("Octo", "merge: a stop's words were kept in memory only: ${e.javaClass.simpleName}")
            }
        }
        if (automatic) resumeJob(auto = true)
    }

    // Each claimed copy of a stopped save hears that its save waits.
    private fun failPending(stages: List<ClaimedStage>, words: String) {
        stages.forEach { stageOutcome(it.sourceId, it.generation).completeExceptionally(ApplyPending(words)) }
    }

    // Before the first group: every claimed copy still holds the rows it
    // was claimed with. Reads under the gate, outside every transaction; a
    // check that fails names each copy that failed it.
    private suspend fun checkCopies(claim: Claim) {
        val metas = work.metas().associateBy { it.sourceId }
        val failed = claim.claimed.filterNot { stage ->
            val meta = metas[stage.sourceId]
            meta != null && meta.stage == STAGE_CLAIMED && meta.generation == stage.generation &&
                work.stageTrackCount(stage.sourceId) == meta.stagedTracks &&
                work.stageAlbumCount(stage.sourceId) == meta.stagedAlbums &&
                work.stageArtistCount(stage.sourceId) == meta.stagedArtists
        }
        if (failed.isNotEmpty()) throw StageAbort(CHANGED, failed.mapTo(HashSet()) { it.sourceId })
    }

    // Each group's turn at the merge lock, alone: it waits outside every
    // transaction for every hold to leave, then runs its transaction with
    // the stamp odd from its first act to its commit; after it, the stamp
    // turns even and its renames are published before the lock is let go.
    // The save's first group also turns the save's signals on inside its
    // transaction, and (for a claim) checks again that each claimed copy is
    // still claimed as it was; a resume's first group (`resumed`) does the
    // same, its claim's hook aside.
    private inner class Turns(private val claim: Claim?, private val resumed: Boolean, private val started: () -> Unit) : ApplyTurns {
        // Whether the save's first group is in.
        var began = false
            private set

        override suspend fun group(first: Boolean, block: suspend (SupportSQLiteDatabase) -> Map<String, String>) {
            swapGate.exclusive {
                var odd = false
                var renames: Map<String, String> = emptyMap()
                val pendingBefore = _applyPending.value
                try {
                    withContext(WriteLabels.of(SAVE_APPLY)) {
                        db.withTransaction {
                            _swapStamp.value += 1
                            odd = true
                            if (first) {
                                synchronized(recordLock) { turnedHere = true }
                                _applyStopped.value = null
                                if (_applyStamp.value % 2 == 0L) _applyStamp.value += 1
                                _applyPending.value = true
                                if (claim != null) {
                                    stillClaimed(claim)
                                    if (!resumed) hooks.inSwap()
                                }
                            }
                            renames = block(db.openHelper.writableDatabase)
                        }
                    }
                } catch (e: Throwable) {
                    // A first group that did not commit leaves the save as
                    // it was before it.
                    if (first) {
                        _applyPending.value = pendingBefore
                        if (_applyStamp.value % 2 != 0L) _applyStamp.value += 1
                    }
                    throw e
                } finally {
                    if (odd) _swapStamp.value += 1
                }
                if (first) {
                    began = true
                    started()
                }
                if (renames.isNotEmpty()) _published.tryEmit(renames)
            }
        }
    }

    // Inside the first group: each claimed copy is still the one claimed.
    private suspend fun stillClaimed(claim: Claim) {
        val metas = work.metas().associateBy { it.sourceId }
        val failed = claim.claimed.filterNot { stage ->
            val meta = metas[stage.sourceId]
            meta != null && meta.stage == STAGE_CLAIMED && meta.generation == stage.generation
        }
        if (failed.isNotEmpty()) throw StageAbort(CHANGED, failed.mapTo(HashSet()) { it.sourceId })
    }

    // A source's copy that waits to be swapped in, as claimed.
    private class ClaimedStage(val sourceId: String, val generation: Long, val tracks: Int)

    private class Claim(val sources: List<MergeSource>, val claimed: List<ClaimedStage>, val server: String)

    // What a merge does: nothing (claim null) or merge what it claimed.
    // Copies of sources out of the library are dropped either way, and so
    // are copies a failed abort left claimed.
    private class Step(val claim: Claim?, val dropped: List<ClaimedStage>, val leftover: List<ClaimedStage>)

    // A claim that cannot be swapped in. `failed` names the copies that
    // failed their check; null when the claim as a whole failed, as it does
    // when there is no room (`noRoom`).
    private class StageAbort(val reason: String, val failed: Set<String>? = null, val noRoom: Boolean = false) : Exception(reason)

    // Under the gate: skips when nothing changed, otherwise claims every
    // copy waiting for a source in the library, adding in what was taken in
    // while it was built, and starts noting rating taps.
    private suspend fun decide(server: String?): Step = db.withTransaction {
        // A claim left by a merge whose abort could not write (a full disk)
        // is over: under the gate no merge holds one.
        val leftover = work.metas().filter { it.stage == STAGE_CLAIMED }.map { ClaimedStage(it.sourceId, it.generation, 0) }
        leftover.forEach { dropStageRows(it) }
        if (leftover.isNotEmpty() || work.mergeState()?.claimActive == true) {
            work.setClaimActive(false)
            endRatingNotes()
        }
        val metas = work.metas().associateBy { it.sourceId }
        val ready = metas.values.filter { it.stage == STAGE_READY }
        val (inUse, elsewhere) = ready.partition { inLibrary(it.sourceId, server) }
        val dropped = elsewhere.map { ClaimedStage(it.sourceId, it.generation, 0) }
        dropped.forEach { dropStageRows(it) }
        val ids = (liveSources() + inUse.map { it.sourceId })
            .filter { inLibrary(it, server) }
            .distinct()
            .sortedBy { if (it == DEVICE) "" else it }
        val key = skipKey(ids, metas.mapValues { it.value.stamp }, inUse.associate { it.sourceId to it.generation })
        if (work.skipKey() == key) {
            // The same inputs make the same library, now this server's.
            work.setMergedServer(server.orEmpty())
            return@withTransaction Step(null, dropped, leftover)
        }
        val claimed = inUse.map { meta ->
            work.setStage(meta.sourceId, STAGE_CLAIMED)
            ClaimedStage(meta.sourceId, meta.generation, meta.stagedTracks + carryTakeIns(meta))
        }
        work.setClaimActive(true)
        val staged = claimed.mapTo(HashSet()) { it.sourceId }
        Step(Claim(ids.mapIndexed { rank, id -> MergeSource(id, rank, id in staged) }, claimed, server.orEmpty()), dropped, leftover)
    }

    // Songs, albums and artists taken in while a copy was built join it,
    // after its last rows. A row taken in has a place of its own in the
    // source, which its stage row need not share, so each counts as moved.
    // Answers how many songs joined.
    private suspend fun carryTakeIns(meta: SourceMetaEntity): Int {
        val source = meta.sourceId
        val trackIds = work.loggedIds(meta.generation, "track")
        var trackSeq = work.lastStageTrackSeq(source) ?: -1
        val tracks = trackIds.chunked(MERGE_PAGE).sumOf { ids ->
            val rows = work.liveTracks(ids).map { StageTrackEntity(it.copy(seq = ++trackSeq, mergedId = "")) }
            work.insertStageTracks(rows).count { it != -1L }
        }
        val albumIds = work.loggedIds(meta.generation, "album")
        var albumSeq = work.lastStageAlbumSeq(source) ?: -1
        val albums = albumIds.chunked(MERGE_PAGE).sumOf { ids ->
            val rows = work.liveAlbums(ids).map { StageAlbumEntity(it.copy(seq = ++albumSeq)) }
            work.insertStageAlbums(rows).count { it != -1L }
        }
        val artistIds = work.loggedIds(meta.generation, "artist")
        var artistSeq = work.lastStageArtistSeq(source) ?: -1
        val artists = artistIds.chunked(MERGE_PAGE).sumOf { ids ->
            val rows = work.liveArtists(ids).map { StageArtistEntity(it.copy(seq = ++artistSeq)) }
            work.insertStageArtists(rows).count { it != -1L }
        }
        if (tracks + albums + artists > 0) work.addStaged(source, tracks, albums, artists)
        if (trackIds.size + albumIds.size + artistIds.size > 0) work.addMoved(source, trackIds.size, albumIds.size, artistIds.size)
        return tracks
    }

    // The free space a claim's save takes, from the merge's output of
    // `songs` songs: its kind's bytes for every song of the claimed copies
    // and of the output, and the log's room beside them (logRoom), whose
    // largest transaction is one group. A save rewrites rows in place,
    // which the log holds though the file does not grow, so the log's room
    // is not held to the growth.
    private fun neededFor(claim: Claim, songs: Int, kind: SaveKind, bound: Long): Long {
        val counted = claim.claimed.sumOf { it.tracks.toLong() } + songs
        val work = counted * kind.bytesPerSong
        val group = minOf(bound, counted) * SAVE_LOG_BYTES_PER_ENTRY
        return work + logRoom(Long.MAX_VALUE, group)
    }

    // The bound of the claim's save (saveBoundFor), by its copies.
    private fun boundOf(claim: Claim): Long = saveBoundFor(claim.sources.size.toLong().coerceAtLeast(1))

    // What the claim's save does to the library (SaveKind), read before
    // its find: whether the library holds any song, whether it was last
    // merged for another server, and whether it holds songs from each
    // claimed copy's source. No recorded server (a library from before the
    // record was kept) says nothing, and the sources' songs decide.
    private fun kindOf(claim: Claim): SaveKind {
        if (db.readLong("SELECT EXISTS (SELECT 1 FROM track)") == 0L) return SaveKind.FirstCopy
        val merged = db.read("SELECT server FROM merge_state WHERE id = 0") { if (it.isNull(0)) null else it.getString(0) }.firstOrNull()
        if (merged != null && merged != claim.server) return SaveKind.Switch
        val newSource = claim.claimed.any { db.readLong("SELECT EXISTS (SELECT 1 FROM source_track WHERE sourceId = ?)", it.sourceId) == 0L }
        return if (newSource) SaveKind.Switch else SaveKind.Recopy
    }

    private fun checkSpace(claim: Claim, counts: MergeCounts, kind: SaveKind, bound: Long) {
        if (claim.claimed.isEmpty()) return
        val needed = neededFor(claim, counts.tracks, kind, bound)
        hooks.spaceAsked(kind, needed)
        if (freeSpace.bytes() < needed) throw StageAbort(NotEnoughSpace(needed).userMessage(), noRoom = true)
    }

    // Each copy swapped in or dropped (the source is marked clearing)
    // leaves the stage after the merge has let the gate go, in the
    // background, so nothing that waits for the gate or for the merge waits
    // for it. A read that fails leaves the marks for the next copies.
    private suspend fun clearMarked() {
        try {
            work.metas().filter { it.stageClearing }.forEach { clearOf(it.sourceId) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Octo", "merge: the stage's clears wait for the next copies: ${e.javaClass.simpleName}")
        }
    }

    // A source's copy that left the stage marked clearing, cleared now in
    // the background (a copy that ended unclaimed).
    internal fun clearStageLater(sourceId: String) {
        clearOf(sourceId)
    }

    // The clear of a source's swapped copy: the one running, or a new one.
    private fun clearOf(sourceId: String): Deferred<Unit> = synchronized(clears) {
        clears[sourceId]?.takeIf { it.isActive } ?: scope.async { clearStage(sourceId) }.also { clears[sourceId] = it }
    }

    // Waits until no copy swapped in of `sourceId` is left in the stage, so
    // a new copy's stage is never deleted with an old one's: the clear
    // running now, or one the app stopped in the middle of (the source is
    // still marked clearing), which runs now.
    suspend fun awaitStageClear(sourceId: String) {
        synchronized(clears) { clears[sourceId]?.takeIf { it.isActive } }?.await()
        if (work.stageClearing(sourceId) == true) clearOf(sourceId).await()
    }

    // A source's stage rows go a page a transaction, each costing at most
    // SAVE_GROUP_ENTRIES by what its table's rows cost (clearRowCost), so no
    // write holds the lock for longer than a save's group, and the source
    // stops being marked clearing once they are gone. A delete that fails
    // leaves the mark, and the source's next copy clears what is left first.
    private suspend fun clearStage(sourceId: String) {
        val started = System.nanoTime()
        try {
            val rows = withContext(WriteLabels.of(STAGE_CLEAR)) {
                val costs = SaveCosts.of(db.openHelper.writableDatabase, STAGE_TABLES)
                var rows = 0L
                val pages = listOf(work::deleteStageTrackPage, work::deleteStageAlbumPage, work::deleteStageArtistPage)
                for ((table, page) in STAGE_TABLES.zip(pages)) {
                    val each = clearPageRows(costs, table)
                    while (true) {
                        val gone = page(sourceId, each)
                        rows += gone
                        hooks.stageClearPage(sourceId)
                        if (gone < each) break
                    }
                }
                rows
            }
            work.stageCleared(sourceId)
            hooks.stageCleared(sourceId, rows, (System.nanoTime() - started) / 1_000_000)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Octo", "merge: a swapped copy's stage stays for now: ${e.javaClass.simpleName}")
        }
    }

    // A claimed copy is not swapped in: it goes, and its copy is told why.
    // When the claim failed only for the copies in `failed`, only those go;
    // every other copy waits to be claimed again, as it was (its count,
    // generation and copy still waiting), unless `putBack` shows it was put
    // back once already in this run. When the abort's own writes fail (a
    // full disk), every copy is told, and the next merge clears the claim it
    // left (decide).
    private suspend fun abort(
        claim: Claim,
        reason: String,
        failed: Set<String>? = null,
        putBack: MutableSet<Pair<String, Long>> = HashSet(),
        noRoom: Boolean = false,
    ) {
        val (dropped, kept) = claim.claimed.partition { stage ->
            failed == null || stage.sourceId in failed || (stage.sourceId to stage.generation) in putBack
        }
        var back = emptyList<ClaimedStage>()
        try {
            withContext(WriteLabels.of("abort")) {
                db.withTransaction {
                    dropped.forEach { dropStageRows(it) }
                    kept.forEach { work.setStage(it.sourceId, STAGE_READY) }
                    work.setClaimActive(false)
                    endRatingNotes()
                }
            }
            back = kept
            kept.forEach { putBack += it.sourceId to it.generation }
        } finally {
            failStages(claim.claimed - back.toSet(), reason, noRoom)
        }
    }

    // Rating notes last while any server's copy is being built or waits to
    // be swapped in: one still to come would bring the ratings it read. The
    // phone's copy brings no server ratings, so it keeps no note alive.
    private suspend fun endRatingNotes() {
        if (work.metas().none { it.sourceId != DEVICE && it.stage != STAGE_NONE }) work.clearRatingNotes()
    }

    // A dropped copy's rows leave the stage once the merge has let the gate
    // go, a page at a time (clearMarked): the source is marked clearing, so
    // its next copy waits for them to go before it stages anything.
    private suspend fun dropStageRows(stage: ClaimedStage) {
        work.markStageClearing(stage.sourceId)
        work.clearTakeInLog(stage.generation)
        work.stageDropped(stage.sourceId, stage.generation)
    }

    private fun failStages(stages: List<ClaimedStage>, reason: String, noRoom: Boolean = false) {
        stages.forEach { stageOutcome(it.sourceId, it.generation).completeExceptionally(StageAborted(reason, noRoom)) }
    }

    private fun stageOutcome(sourceId: String, generation: Long): CompletableDeferred<Unit> =
        synchronized(stages) { stages.getOrPut(sourceId to generation) { CompletableDeferred() } }

    // Every source with rows, without reading the rows.
    private suspend fun liveSources(): Set<String> {
        val ids = LinkedHashSet<String>()
        var after = ""
        while (true) {
            val next = work.nextTrackSource(after) ?: break
            ids += next
            after = next
        }
        after = ""
        while (true) {
            val next = work.nextAlbumSource(after) ?: break
            ids += next
            after = next
        }
        return ids
    }

    // Song keys stored by an older way of reading them are worked out again,
    // one refill at a time: asking while one runs waits for that one.
    private suspend fun refillKeys() {
        val running = synchronized(refillLock) {
            refill?.takeIf { it.isActive } ?: scope.async { refillAll() }.also { refill = it }
        }
        running.await()
    }

    // A page at a time, each under the source-write gate, so no save's find
    // or apply meets a key it did not read. A row changed meanwhile already
    // has new keys, and each write takes only a row still at the old version.
    private suspend fun refillAll() {
        while (true) {
            val old = work.staleKeysVersion(SONG_KEYS_VERSION)
            if (old == null) {
                _keysReady.value = true
                return
            }
            var after = ""
            while (true) {
                val rows = work.keyRows(old, after, MERGE_PAGE)
                if (rows.isEmpty()) break
                gate.withLock {
                    db.withTransaction {
                        for (row in rows) {
                            val keys = SongIdentity.songKeys(row.artist, row.title)
                            work.setKeys(row.id, keys.title, keys.matchKey, SONG_KEYS_VERSION, old)
                        }
                    }
                }
                after = rows.last().id
            }
        }
    }

    private companion object {
        const val ELSEWHERE = "Another server is in use now."
        const val CHANGED = "Something went wrong saving the library. Try syncing again."
        const val STILL_SAVING = "Octo is still saving your library."
    }
}

// Whether a source's music joins the library: the phone's own always, a
// server's only while it is the one in use.
fun inLibrary(sourceId: String, serverInUse: String?): Boolean = !isServerSource(sourceId) || sourceId == serverInUse

// What a merge read, as text: the rules and keys versions, each source with
// its stamp, in rank order, and each copy waiting to be swapped in.
internal fun skipKey(sources: List<String>, stamps: Map<String, Long>, stages: Map<String, Long>): String = buildString {
    append("rules ").append(MERGE_RULES_VERSION).append(" keys ").append(SONG_KEYS_VERSION)
    for (source in sources) append('|').append(source).append('=').append(stamps[source] ?: 0)
    for ((source, generation) in stages.toSortedMap()) append("|stage ").append(source).append('=').append(generation)
}

internal fun columnsOf(sql: SupportSQLiteDatabase, table: String): List<String> =
    sql.query("PRAGMA table_info(`$table`)").use { c ->
        val name = c.getColumnIndexOrThrow("name")
        buildList { while (c.moveToNext()) add(c.getString(name)) }
    }

// The apply's statements, one found row each (SaveApply), built from the
// tables' own columns so a column added later is carried too. Each names
// its row by key (?1) and writes only while the row still differs, column
// by column byte for byte whatever the column's collation, so a change of
// case alone (a genre's) is written. A song's rating and a server copy's
// follow the rating rule: the value the find saw (?2) is replaced, and a
// rating written since it stands.
internal class SwapSql(
    source: List<String>,
    album: List<String>,
    artist: List<String>,
    track: List<String>,
    libraryAlbum: List<String>,
    libraryArtist: List<String>,
) {
    private val sourceColumns = mapOf("source_track" to source - "mergedId", "source_album" to album, "source_artist" to artist)
    private val stageOf = mapOf("source_track" to "stage_track", "source_album" to "stage_album", "source_artist" to "stage_artist")

    // A copy's row into its source, with no song yet (its merged id comes
    // with its song's group), once `has` says it is not there. Neither
    // insert reads the table it writes, so neither buffers its row.
    val sourceIn: Map<String, String> = sourceColumns.mapValues { (live, columns) ->
        "INSERT INTO `$live` (${columns.joinToString { "`$it`" }}) SELECT ${columns.joinToString { "g.`$it`" }} FROM `${stageOf.getValue(live)}` g WHERE g.id = ?1"
    }

    // A copy's row as the copy has it, but its place and its song: the
    // columns that still differ (one flag each, `sourceColumnsSet`; a server
    // copy's rating by the rule, ?2 as seen), then those alone written, so
    // a row rewrites only the indexes that hold a column it changes.
    val sourceColumnsSet: Map<String, List<String>> = sourceColumns.mapValues { (_, columns) -> columns - "id" - "seq" }

    private fun sourceValue(live: String, column: String, row: String): String =
        if (live == "source_track" && column == "rating") "CASE WHEN $row.rating IS ?2 THEN g.rating ELSE $row.rating END" else "g.`$column`"

    val sourceDiffers: Map<String, String> = sourceColumnsSet.mapValues { (live, set) ->
        "SELECT ${set.joinToString { "(${sourceValue(live, it, "l")} IS NOT l.`$it` COLLATE BINARY)" }} " +
            "FROM `${stageOf.getValue(live)}` g CROSS JOIN `$live` l ON l.id = g.id WHERE g.id = ?1"
    }

    // Each statement that writes some of a row's columns, by its table and
    // those columns, made once.
    private val written = HashMap<Pair<String, List<String>>, String>()

    fun sourceChanged(live: String, columns: List<String>): String = written.getOrPut(live to columns) {
        "UPDATE `$live` SET ${setList(columns, "`${stageOf.getValue(live)}` g WHERE g.id = `$live`.id") { sourceValue(live, it, "`$live`") }} WHERE id = ?1"
    }

    // A SET list of `columns` from one row of a subquery (`from` its FROM
    // and WHERE): a row value for several columns, one column on its own.
    private fun setList(columns: List<String>, from: String, value: (String) -> String): String =
        if (columns.size == 1) {
            "`${columns[0]}` = (SELECT ${value(columns[0])} FROM $from)"
        } else {
            "(${columns.joinToString { "`$it`" }}) = (SELECT ${columns.joinToString(transform = value)} FROM $from)"
        }

    // A copy's row placed where the copy has it in its source's order: a
    // row whose place is all that moved rewrites only the indexes that
    // hold its place.
    val sourcePlaced: Map<String, String> = sourceColumns.filterValues { "seq" in it }.mapValues { (live, _) ->
        val stage = stageOf.getValue(live)
        "UPDATE `$live` SET `seq` = (SELECT g.`seq` FROM `$stage` g WHERE g.id = `$live`.id) " +
            "WHERE id = ?1 AND EXISTS (SELECT 1 FROM `$stage` g WHERE g.id = `$live`.id AND g.`seq` IS NOT `$live`.`seq`)"
    }

    val sourceGone: Map<String, String> = sourceColumns.keys.associateWith { "DELETE FROM `$it` WHERE id = ?1" }

    private val libraryColumns = mapOf("track" to track, "album" to libraryAlbum, "artist" to libraryArtist)
    private val outputOf = mapOf("track" to "merge_candidate", "album" to "album_next", "artist" to "artist_next")

    // A song's year is its first release's where the merge read one; its
    // rating is the one the find worked out (?2 for a new song), or by the
    // rule (?2 as seen, ?3 the new one).
    private fun value(table: String, column: String, rating: String): String = when {
        table == "track" && column == "year" -> "COALESCE(c.originalYear, c.year)"
        table == "track" && column == "rating" -> rating
        else -> "c.`$column`"
    }

    private fun insert(table: String, rating: String): String {
        val columns = libraryColumns.getValue(table)
        return "INSERT INTO `$table` (${columns.joinToString { "`$it`" }}) SELECT ${columns.joinToString { value(table, it, rating) }} FROM `${outputOf.getValue(table)}` c WHERE c.id = ?1"
    }

    // A new album or artist, and a new song with its rating, once `has`
    // says it is not there.
    val libraryIn: Map<String, String> = listOf("album", "artist").associateWith { insert(it, "") }
    val songIn: String = insert("track", "?2")

    // Whether a row is in its table already, by its key, read before it
    // goes in: 1 when it is, no row when it is not.
    val has: Map<String, String> = (sourceColumns.keys + libraryColumns.keys).associateWith { "SELECT 1 FROM `$it` WHERE id = ?1" }

    // An album, an artist or a song as the merge made it: the columns that
    // still differ (one flag each, `libraryColumnsSet`; a song's rating by
    // the rule, ?2 as seen and ?3 the new one), then those alone written.
    val libraryColumnsSet: Map<String, List<String>> = libraryColumns.mapValues { (_, columns) -> columns - "id" }

    private fun rated(row: String) = "CASE WHEN $row.rating IS ?2 THEN ?3 ELSE $row.rating END"

    val libraryDiffers: Map<String, String> = libraryColumnsSet.mapValues { (table, set) ->
        "SELECT ${set.joinToString { "(${value(table, it, rated("t"))} IS NOT t.`$it` COLLATE BINARY)" }} " +
            "FROM `${outputOf.getValue(table)}` c CROSS JOIN `$table` t ON t.id = c.id WHERE c.id = ?1"
    }

    fun libraryChanged(table: String, columns: List<String>): String = written.getOrPut(table to columns) {
        "UPDATE `$table` SET ${setList(columns, "`${outputOf.getValue(table)}` c WHERE c.id = `$table`.id") { value(table, it, rated("`$table`")) }} WHERE id = ?1"
    }

    // A song whose rating alone changes, while it shows the one seen.
    val songRated = "UPDATE track SET rating = ?3 WHERE id = ?1 AND rating IS ?2 AND rating IS NOT ?3"

    val libraryGone: Map<String, String> = libraryColumns.keys.associateWith { "DELETE FROM `$it` WHERE id = ?1" }

    // A copy's library song, and a copy that is no library song's.
    val mergedId = "UPDATE source_track SET mergedId = ?2 WHERE id = ?1 AND mergedId IS NOT ?2"
    val unmerge = "UPDATE source_track SET mergedId = '' WHERE id = ?1 AND mergedId != ''"

    // A rating tap whose server copy is gone.
    val tapGone = "DELETE FROM rating_tap WHERE sourceRowId = ?1 AND NOT EXISTS (SELECT 1 FROM source_track s WHERE s.id = ?1)"

    // Every statement here, for a plan check: each partial write with all
    // its table's columns.
    val all: List<String>
        get() = has.values + sourceIn.values + sourceDiffers.values + sourceColumnsSet.map { (t, c) -> sourceChanged(t, c) } + sourcePlaced.values + sourceGone.values +
            libraryIn.values + songIn + libraryDiffers.values + libraryColumnsSet.map { (t, c) -> libraryChanged(t, c) } + songRated +
            libraryGone.values + mergedId + unmerge + tapGone
}
