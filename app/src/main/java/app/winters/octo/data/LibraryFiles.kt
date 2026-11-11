package app.winters.octo.data

import app.winters.octo.catalog.SourceDao
import app.winters.octo.catalog.SourceTrackEntity
import app.winters.octo.catalog.copiesInChunks
import app.winters.octo.health.FIX_OTHER_SERVER
import app.winters.octo.health.FixStep
import app.winters.octo.health.deletedLine
import app.winters.octo.server.ServerSync
import app.winters.octo.subsonic.LIBRARY_ACTION_COVER
import app.winters.octo.subsonic.LIBRARY_ACTION_JOIN_ALBUM
import app.winters.octo.subsonic.LIBRARY_ACTION_REMOVE
import app.winters.octo.subsonic.LIBRARY_ACTION_RESTORE
import app.winters.octo.subsonic.LIBRARY_ACTION_RETAG
import app.winters.octo.subsonic.LIBRARY_ACTION_UNDO
import app.winters.octo.subsonic.LibraryActions
import app.winters.octo.subsonic.LibraryTrash
import app.winters.octo.subsonic.OCTO_LIBRARY_ACTIONS
import app.winters.octo.subsonic.SongLookup
import app.winters.octo.subsonic.SubsonicException
import app.winters.octo.ui.common.Feedback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

// Whether a server lists Octo's library actions at all. Removing a song is
// there from the first version, so any version counts.
fun listsLibraryActions(extensions: Set<String>): Boolean =
    extensions.any { it == OCTO_LIBRARY_ACTIONS || it.startsWith("$OCTO_LIBRARY_ACTIONS:") }

// Each library song's copy on the server `sourceId`, by library song: the
// copy with the lowest id there when the server has two.
fun serverSongIds(copies: List<SourceTrackEntity>, sourceId: String?): Map<String, String> {
    if (sourceId == null) return emptyMap()
    return copies.filter { it.sourceId == sourceId && it.mergedId.isNotEmpty() }
        .groupBy { it.mergedId }
        .mapValues { (_, mine) -> mine.minOf { it.nativeId } }
}

// The steps that delete library songs from the server's disk: every copy
// each one has there, so none is left behind in the library.
fun deleteSteps(trackIds: List<String>, copies: List<SourceTrackEntity>, sourceId: String?): List<FixStep> {
    if (sourceId == null) return emptyList()
    val onServer = copies.filter { it.sourceId == sourceId }.groupBy { it.mergedId }
    return trackIds.distinct().flatMap { id ->
        onServer[id].orEmpty().sortedBy { it.nativeId }.map { FixStep.Remove(it.nativeId, it.title) }
    }
}

// Steps planned on library songs, sent to the server's songs: each step's
// song, and an album join's `like`, by its id there. A step for a song
// with no copy on that server is left out.
fun List<FixStep>.onServer(ids: Map<String, String>): List<FixStep> = mapNotNull { step ->
    val id = ids[step.id] ?: return@mapNotNull null
    when (step) {
        is FixStep.Remove -> step.copy(id = id)
        is FixStep.Retag -> step.copy(id = id)
        is FixStep.JoinAlbum -> step.copy(id = id, like = ids[step.like] ?: return@mapNotNull null)
        is FixStep.AddCover -> step.copy(id = id)
        is FixStep.Restore -> step.copy(id = id)
        is FixStep.Undo -> step.copy(id = id)
    }
}

// Whether the server can do a step now.
fun LibraryActions.canRun(step: FixStep): Boolean = canRun(step.action)

// Whether the server can take a library action now.
fun LibraryActions.canRun(action: String): Boolean = when (action) {
    LIBRARY_ACTION_REMOVE -> canRemove
    LIBRARY_ACTION_RESTORE -> canRestore
    LIBRARY_ACTION_RETAG, LIBRARY_ACTION_UNDO -> canEdit
    LIBRARY_ACTION_JOIN_ALBUM -> canJoinAlbums
    LIBRARY_ACTION_COVER -> canAddCover
    else -> false
}

// Whether every step can be done now, as for an Undo.
fun LibraryActions?.canRunAll(steps: List<FixStep>): Boolean = this != null && steps.isNotEmpty() && steps.all { canRun(it) }

// Shows `line` with `undo` as its Undo, which puts back the run `owner`
// stands for, and keeps that run's undo for as long as this very line
// shows: once another line replaces it or it is put away, it goes.
internal fun Feedback.offerUndo(line: String, undo: () -> Unit, owner: String, runs: FixRuns, scope: CoroutineScope) {
    undoable(line, undo)
    scope.launch {
        message.first { it?.onAction !== undo }
        runs.release(owner)
    }
}

// A run of steps under way: what it is, and how far.
data class FixRun(val done: Int, val total: Int)

// Library health's fixes and deleting songs from disk on the signed-in
// Octo server: what the server lets this user do, which of the library's
// songs it has, and the steps sent to it one at a time. Whatever changes,
// the library is copied again after, so a removed song leaves it and a
// fixed one shows its new tags. What the server can do is read at each
// sign-in, from any version of its library actions.
@Singleton
class LibraryFiles @Inject constructor(
    private val sessions: SessionRepository,
    private val sources: SourceDao,
    private val sync: ServerSync,
    private val feedback: Feedback,
    private val runs: FixRuns,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running = false

    // One run at a time, so two never step on the same songs.
    private val one = Mutex()

    // Copies run one after another, so none started for a change is lost.
    private val reloading = Mutex()

    private val _actions = MutableStateFlow<LibraryActions?>(null)

    // What the server lets this user do to its files; null for a server
    // that offers none, or none signed in.
    val actions: StateFlow<LibraryActions?> = _actions

    private val _source = MutableStateFlow<String?>(null)

    // The library's source for that server, while it offers anything.
    val source: StateFlow<String?> = _source

    private val _progress = MutableStateFlow<FixRun?>(null)

    // How far the run under way has got; null while none is.
    val progress: StateFlow<FixRun?> = _progress

    // What puts the last run back, until another run replaces it.
    val lastUndo: StateFlow<FixUndo?> = runs.last

    @Volatile
    private var stopping = false

    fun start() {
        if (running) return
        running = true
        scope.launch {
            sessions.state.distinctUntilChangedBy { (it as? SessionState.SignedIn)?.session }.collect { state ->
                _actions.value = null
                _source.value = null
                runs.forget()
                val session = (state as? SessionState.SignedIn)?.session ?: return@collect
                if (!listsLibraryActions(session.extensions)) return@collect
                read(session)
            }
        }
    }

    // Reads again what the server lets this user do, as when a page about
    // it opens: an admin may have changed it since sign-in.
    suspend fun refresh() {
        val session = session() ?: return
        if (listsLibraryActions(session.extensions)) read(session)
    }

    private suspend fun read(session: Session) {
        val actions = try {
            session.client.libraryActions()
        } catch (e: SubsonicException) {
            return
        }
        _actions.value = actions
        _source.value = session.sourceId
    }

    // The server's id for each of `trackIds` it has a copy of.
    suspend fun serverIds(trackIds: List<String>): Map<String, String> {
        if (trackIds.isEmpty()) return emptyMap()
        return withContext(Dispatchers.IO) { serverSongIds(sources.copiesInChunks(trackIds), _source.value) }
    }

    // The library songs among `trackIds` that can be deleted from disk now.
    suspend fun deletable(trackIds: List<String>): List<String> {
        if (_actions.value?.canRemove != true) return emptyList()
        val ids = serverIds(trackIds)
        return trackIds.filter { it in ids }
    }

    // The server steps are planned for: the kept server signed in to now,
    // by its id, so another account on the same address is another server
    // here. Steps carry that server's own song ids, so a run and its Undo
    // go to it and nowhere else.
    fun server(): String? = session()?.key

    // The kept server in use, by the same id, as it changes; null with none.
    val servers: Flow<String?> = sessions.state.filter { it !is SessionState.Loading }
        .map { (it as? SessionState.SignedIn)?.session?.key }
        .distinctUntilChanged()

    // Runs the steps one after another on the server `on` and says nothing;
    // the caller tells how it went. The steps are read a page at a time,
    // and what puts each done one back is kept in the database (FixRuns).
    // A run that waited its turn while another server was signed in to is
    // not sent. `stop()` ends it between two steps. What puts it back is
    // kept as the last change, unless this run is itself an undo (it puts
    // back `putsBack`, which the caller has claimed when `claimed` says so)
    // or another server is signed in to by the end. `each` hears every step
    // done. The source lets go of what it holds once the run is over.
    suspend fun run(
        source: StepSource,
        keepUndo: Boolean = true,
        on: String? = server(),
        putsBack: FixUndo? = null,
        each: (FixStep) -> Unit = {},
        claimed: Boolean = false,
    ): FixDone {
        var sent = false
        try {
            return one.withLock {
                if (source.total == 0) return@withLock FixDone()
                stopping = false
                val session = session()
                if (on == null || session == null) return@withLock FixDone(runs.refused(source, "Not signed in"))
                if (session.key != on) return@withLock FixDone(runs.refused(source, FIX_OTHER_SERVER))
                sent = true
                val client = session.client
                _progress.value = FixRun(0, source.total)
                val done = try {
                    runs.run(
                        source,
                        send = { step -> client.libraryAction(step.id, step.action, step.with) },
                        keepUndo = keepUndo,
                        putsBack = putsBack,
                        still = { server() == on },
                        progress = { done, total -> _progress.value = FixRun(done, total) },
                        stop = { stopping },
                        failure = { it.userMessage() },
                        each = each,
                        claimed = claimed,
                    )
                } finally {
                    _progress.value = null
                }
                if (server() == on && done.tally.done > 0) reload()
                done
            }
        } finally {
            withContext(NonCancellable) {
                if (!sent && claimed && putsBack != null) runs.unclaim(putsBack)
                source.close()
            }
        }
    }

    suspend fun run(steps: List<FixStep>, on: String? = server()): FixDone = run(stepsOf(steps), on = on)

    fun stop() {
        stopping = true
    }

    // Runs the steps away from the page that asked, so leaving it does not
    // cut a run short, on the server `on` they were planned on. `shown`
    // hears how it went and answers whether it told the listener, whose
    // window then offers its Undo until it closes (`letGo`); when it did
    // not (its page has gone), a line says it. Once another server is
    // signed in to, how it went is not told at all.
    fun launch(source: StepSource, on: String? = server(), shown: (FixDone) -> Boolean = { false }) = tell(on, shown) { run(source, on = on) }

    fun launch(steps: List<FixStep>, on: String? = server(), shown: (FixDone) -> Boolean = { false }) = launch(stepsOf(steps), on, shown)

    // The same for putting back an earlier run, from its outcome's `undo`,
    // claimed now, so the window that offered it can close meanwhile.
    fun launchUndo(undo: FixUndo, on: String? = server(), shown: (FixDone) -> Boolean = { false }) {
        val held = runs.claim(undo)
        tell(on, shown) { putBack(undo, on, held) }
    }

    private fun tell(on: String?, shown: (FixDone) -> Boolean, block: suspend () -> FixDone) {
        scope.launch {
            val done = block()
            if (on != null && server() != on) {
                done.undo?.let { runs.release(it.owner) }
                return@launch
            }
            val heard = withContext(Dispatchers.Main) { shown(done) }
            if (!heard) say(done.summary(), done, on)
        }
    }

    // A window that showed an outcome closed: its Undo is no longer
    // offered there.
    fun letGo(owner: String) {
        scope.launch { runs.release(owner) }
    }

    // Steps not sent after all let go of what they hold.
    fun putAside(source: StepSource) {
        scope.launch { source.close() }
    }

    // Says how a run on the server `on` went, with an Undo when the server
    // can put it back. The Undo goes to that same server, and only while it
    // is still the one signed in to; the run's undo is kept while the line
    // shows, and goes once another line replaces it or it is put away.
    fun say(line: String, done: FixDone, on: String? = server()) {
        val back = done.undo
        if (on != null && back != null && _actions.value.canPutBack(back)) {
            val undo: () -> Unit = {
                val held = runs.claim(back)
                scope.launch {
                    if (server() != on) {
                        if (held) runs.unclaim(back)
                        feedback.show(FIX_OTHER_SERVER)
                        return@launch
                    }
                    val put = putBack(back, on, held)
                    put.undo?.let { runs.release(it.owner) }
                    if (server() == on) feedback.show(put.summary())
                }
            }
            feedback.offerUndo(line, undo, back.owner, runs, scope)
        } else {
            feedback.show(line)
            back?.let { scope.launch { runs.release(it.owner) } }
        }
    }

    // Puts back what a run on the server `on` did, from its outcome's
    // `undo`, the last done first. Once it is put back, it is no longer the
    // last change to undo; one no longer kept is not sent, and says so.
    suspend fun undo(undo: FixUndo, on: String? = server()): FixDone = run(runs.steps(undo), keepUndo = false, on = on, putsBack = undo)

    // The same for an undo claimed already (`held`), or not kept (no
    // longer held by anything when it was claimed).
    private suspend fun putBack(undo: FixUndo, on: String?, held: Boolean): FixDone =
        if (held) run(runs.steps(undo), keepUndo = false, on = on, putsBack = undo, claimed = true) else FixDone(gone = true)

    // Puts back the last run, once.
    suspend fun undoLast(): FixDone = runs.last.value?.let { undo(it) } ?: FixDone()

    // The tags a download of a song would get. Throws when the server
    // cannot be asked.
    suspend fun lookUp(serverId: String): SongLookup {
        val client = session()?.client ?: return SongLookup(serverId, "failed", "Not signed in")
        return client.lookUpTags(serverId)
    }

    // The songs in the server's trash. Throws when the server cannot be asked.
    suspend fun trash(): LibraryTrash {
        val client = session()?.client ?: return LibraryTrash()
        return client.libraryTrash()
    }

    // Deletes library songs from the server's disk, once asked, then says
    // so with an Undo that puts them back from the server's trash. The
    // server `on` is the one signed in to when it was asked; once another
    // is, nothing is sent or said.
    fun deleteFromDisk(trackIds: List<String>, title: String?, on: String? = server()) {
        if (on == null || server() != on) return
        scope.launch {
            val copies = sources.copiesInChunks(trackIds)
            val steps = deleteSteps(trackIds, copies, _source.value?.takeIf { server() == on })
            if (steps.isEmpty()) return@launch
            val removed = HashSet<String>()
            val done = run(stepsOf(steps), on = on, each = { if (it is FixStep.Remove) removed += it.id })
            if (server() != on) {
                done.undo?.let { runs.release(it.owner) }
                return@launch
            }
            val songs = trackIds.distinct().count { id -> copies.any { it.mergedId == id && it.nativeId in removed } }
            val tally = done.tally
            val line = if (tally.failed == 0 && !tally.rehearsed && !tally.stopped) deletedLine(songs, title) else done.summary()
            say(line, done, on)
        }
    }

    // A copy already running may have read the server before the change,
    // so then one more runs after it.
    private suspend fun reload() {
        reloading.withLock {
            val busy = sync.syncing.value
            sync.syncNowAndWait()
            if (busy) sync.syncNowAndWait()
        }
    }

    private fun session(): Session? = (sessions.state.value as? SessionState.SignedIn)?.session

    // A kept server's id; a session from before the list had none, so its
    // source stands in.
    private val Session.key: String get() = id.ifEmpty { sourceId }
}
