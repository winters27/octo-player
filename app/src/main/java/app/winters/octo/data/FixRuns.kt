package app.winters.octo.data

import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.FixUndoEntity
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.catalog.deleteOwned
import app.winters.octo.health.FixStep
import app.winters.octo.health.FixTally
import app.winters.octo.health.StepAnswer
import app.winters.octo.health.sendStep
import app.winters.octo.subsonic.LIBRARY_ACTION_COVER
import app.winters.octo.subsonic.LIBRARY_ACTION_JOIN_ALBUM
import app.winters.octo.subsonic.LIBRARY_ACTION_REMOVE
import app.winters.octo.subsonic.LIBRARY_ACTION_RESTORE
import app.winters.octo.subsonic.LIBRARY_ACTION_RETAG
import app.winters.octo.subsonic.LibraryActionResult
import app.winters.octo.subsonic.LibraryActions
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

// How many steps a run reads ahead, and how many of a run's undo steps one
// read takes.
const val FIX_PAGE = 200

// What a put-back says when what it would put back is no longer kept.
const val FIX_GONE = "That change can no longer be put back."

// One step of a run and its number in the run's order.
data class NumberedStep(val ord: Long, val step: FixStep)

// The steps a run takes, read a page at a time so no run holds them all:
// how many there are, for how far it has got, and the page after the step
// numbered `after` (the first page for null). An empty page ends the run.
// `close` lets go of what the source holds, once its run is over or was
// never sent.
interface StepSource {
    val total: Int

    suspend fun next(after: Long?): List<NumberedStep>

    suspend fun close() {}
}

// A short list's steps, numbered by place: one look-up's, one set's, one
// song's.
fun stepsOf(steps: List<FixStep>, page: Int = FIX_PAGE): StepSource = object : StepSource {
    override val total = steps.size

    override suspend fun next(after: Long?): List<NumberedStep> {
        val from = after?.let { it.toInt() + 1 } ?: 0
        return (from until minOf(steps.size, from + page)).map { NumberedStep(it.toLong(), steps[it]) }
    }
}

// What puts a run back: its steps, kept under the run's own owner in the
// database, how many there are, and which actions they take, for whether
// the server lets this user take them all.
data class FixUndo(val owner: String, val count: Int, val actions: Set<String>)

// How a run went, counted, and what puts it back while that is kept.
// `gone`: a put-back whose change was no longer kept, so nothing was sent.
data class FixDone(val tally: FixTally = FixTally(), val undo: FixUndo? = null, val gone: Boolean = false) {
    fun summary(): String = if (gone) FIX_GONE else tally.summary()
}

// Whether every step that puts a run back can be taken now.
fun LibraryActions?.canPutBack(undo: FixUndo?): Boolean =
    this != null && undo != null && undo.count > 0 && undo.actions.all { canRun(it) }

// Runs of fixes, paged: a run reads its steps a page at a time and writes
// what puts each done step back to the database under its own owner, so
// neither its steps nor its undo are held whole. A finished run's undo is
// kept while something can still offer it: as the last change, until a
// later kept run replaces it, and for its own outcome, while its window is
// open or its line shows (each lets it go through `release`). An undo
// nothing offers and that is not the last change is cleared by its owner,
// as is one put back, one cut short and every one at a change of server.
//
// Runs come one at a time from LibraryFiles, which holds its own lock
// around each; the books here are guarded by a lock of their own that
// guards memory only.
@Singleton
class FixRuns internal constructor(private val db: CatalogDatabase, private val owners: WorkOwners, private val page: Int) {
    @Inject constructor(db: CatalogDatabase, owners: WorkOwners) : this(db, owners, FIX_PAGE)

    private val reader = db.readerDao()
    private val lock = Any()

    private val _last = MutableStateFlow<FixUndo?>(null)

    // What puts the last kept run back, until another replaces it.
    val last: StateFlow<FixUndo?> = _last

    // The owners of the outcomes a window or a line offers now.
    private val offered = HashSet<String>()

    // The owners of undos being put back, held until read through.
    private val putting = HashSet<String>()

    // The steps that put `undo` back, the last done first, a page at a time.
    fun steps(undo: FixUndo): StepSource = object : StepSource {
        override val total = undo.count

        override suspend fun next(after: Long?): List<NumberedStep> =
            reader.undoPage(undo.owner, after ?: Long.MAX_VALUE, page).map { NumberedStep(it.ord, it.step()) }
    }

    // Runs `source`'s steps one after another through `send`, between
    // steps ending when `stop` says so, and keeps what puts the done ones
    // back. `putsBack` is the undo this run puts back, claimed here unless
    // `claimed` says the caller did: from then on it is no longer the last
    // change or offered, and it is cleared once read; one no longer kept
    // sends nothing and says so. At the end `still` says whether the server
    // is still the one signed in to: only then is the run's undo kept, as
    // the last change unless `keepUndo` is false (an undo of an undo), and
    // offered for its outcome.
    suspend fun run(
        source: StepSource,
        send: suspend (FixStep) -> LibraryActionResult,
        keepUndo: Boolean = true,
        putsBack: FixUndo? = null,
        still: () -> Boolean = { true },
        progress: (done: Int, total: Int) -> Unit = { _, _ -> },
        stop: () -> Boolean = { false },
        failure: (Throwable) -> String = { it.message ?: "something went wrong" },
        each: (FixStep) -> Unit = {},
        claimed: Boolean = false,
    ): FixDone {
        if (putsBack != null && !claimed && !claim(putsBack)) return FixDone(gone = true)
        val owner = owners.fix()
        var tally = FixTally()
        var written = 0
        var sent = 0
        val actions = HashSet<String>()
        try {
            progress(0, source.total)
            var after: Long? = null
            pages@ while (true) {
                val steps = source.next(after)
                if (steps.isEmpty()) break
                val back = ArrayList<FixUndoEntity>()
                try {
                    for ((ord, step) in steps) {
                        if (stop()) {
                            tally = tally.copy(stopped = true)
                            break@pages
                        }
                        val answer = sendStep(step, send, failure)
                        tally = tally.plus(step, answer)
                        if (answer is StepAnswer.Done) {
                            each(step)
                            step.undo?.let { undo ->
                                back += undo.row(owner, (written + back.size).toLong())
                                actions += undo.action
                            }
                        }
                        progress(++sent, source.total)
                        after = ord
                    }
                } finally {
                    if (back.isNotEmpty()) withContext(NonCancellable) { reader.putUndo(back) }
                    written += back.size
                }
            }
        } catch (e: Throwable) {
            withContext(NonCancellable) {
                drop(owner)
                putsBack?.let { taken(it) }
            }
            throw e
        }
        putsBack?.let { taken(it) }
        val stays = still()
        return FixDone(tally, settle(FixUndo(owner, written, actions).takeIf { written > 0 }, keep = stays && keepUndo, offered = stays))
    }

    // How a run that is not sent goes: every step refused with `words`.
    suspend fun refused(source: StepSource, words: String): FixTally {
        val first = source.next(null).firstOrNull()?.step ?: return FixTally()
        return FixTally(failed = source.total, firstFailed = first to words, first = first)
    }

    // Every kept undo goes: another server is signed in to.
    suspend fun forget() {
        val gone = synchronized(lock) {
            (listOfNotNull(_last.value?.owner) + offered).distinct().also {
                _last.value = null
                offered.clear()
            }
        }
        gone.forEach { drop(it) }
    }

    // Claims an undo for putting it back: no longer the last change or
    // offered, and held while its steps are read. False when it is no
    // longer kept (nothing offered it and it was not the last change), or
    // another put-back has it.
    fun claim(undo: FixUndo): Boolean {
        synchronized(lock) {
            val kept = _last.value?.owner == undo.owner || undo.owner in offered
            if (!kept || undo.owner in putting) return false
            if (_last.value?.owner == undo.owner) _last.value = null
            offered -= undo.owner
            putting += undo.owner
        }
        owners.pin(undo.owner)
        return true
    }

    // A claimed undo that is not put back after all (its run was not sent):
    // it goes, as one read through does.
    suspend fun unclaim(undo: FixUndo) = taken(undo)

    // A window or a line that offered an outcome's undo no longer does: it
    // goes, unless it is the last change or being put back.
    suspend fun release(owner: String) {
        val gone = synchronized(lock) { offered.remove(owner) && _last.value?.owner != owner }
        if (gone) drop(owner)
    }

    // A run's undo, kept as the last change and offered for its outcome as
    // asked; what neither keeps any more is cleared. Answers the undo its
    // outcome offers, if any.
    private suspend fun settle(made: FixUndo?, keep: Boolean, offered: Boolean): FixUndo? {
        val gone = synchronized(lock) {
            val before = listOfNotNull(_last.value?.owner, made?.owner)
            if (made != null && keep) _last.value = made
            if (made != null && offered) this.offered += made.owner
            before.distinct().filter { it != _last.value?.owner && it !in this.offered && it !in putting }
        }
        gone.forEach { drop(it) }
        return made?.takeIf { offered }
    }

    // An undo read through: it goes.
    private suspend fun taken(undo: FixUndo) {
        synchronized(lock) { putting -= undo.owner }
        owners.unpin(undo.owner)
        drop(undo.owner)
    }

    private suspend fun drop(owner: String) {
        owners.clear(owner) { db.deleteOwned("fix_undo", owner) }
    }
}

// A step as one row of what puts a run back.
private fun FixStep.row(owner: String, ord: Long) = FixUndoEntity(owner, ord, action, id, title, payload())

// What a step carries beside its id and title.
private fun FixStep.payload(): String = when (this) {
    is FixStep.Remove -> if (copy) "copy" else ""
    is FixStep.Restore -> if (replaced) "replaced" else ""
    is FixStep.JoinAlbum -> like
    is FixStep.Retag -> JSONObject(tags).toString()
    is FixStep.AddCover, is FixStep.Undo -> ""
}

private fun FixUndoEntity.step(): FixStep = when (kind) {
    LIBRARY_ACTION_REMOVE -> FixStep.Remove(id, title, copy = payload == "copy")
    LIBRARY_ACTION_RESTORE -> FixStep.Restore(id, title, replaced = payload == "replaced")
    LIBRARY_ACTION_JOIN_ALBUM -> FixStep.JoinAlbum(id, title, payload)
    LIBRARY_ACTION_RETAG -> JSONObject(payload).let { tags -> FixStep.Retag(id, title, tags.keys().asSequence().associateWith { tags.getString(it) }) }
    LIBRARY_ACTION_COVER -> FixStep.AddCover(id, title)
    else -> FixStep.Undo(id, title)
}
