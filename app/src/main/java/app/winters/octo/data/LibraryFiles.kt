package app.winters.octo.data

import app.winters.octo.catalog.SourceDao
import app.winters.octo.catalog.SourceTrackEntity
import app.winters.octo.health.FIX_OTHER_SERVER
import app.winters.octo.health.FixOutcome
import app.winters.octo.health.FixStep
import app.winters.octo.health.deletedLine
import app.winters.octo.health.runFix
import app.winters.octo.server.ServerSync
import app.winters.octo.subsonic.LibraryActions
import app.winters.octo.subsonic.LibraryTrash
import app.winters.octo.subsonic.OCTO_LIBRARY_ACTIONS
import app.winters.octo.subsonic.SongLookup
import app.winters.octo.subsonic.SubsonicException
import app.winters.octo.ui.common.Feedback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filter
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
fun LibraryActions.canRun(step: FixStep): Boolean = when (step) {
    is FixStep.Remove -> canRemove
    is FixStep.Restore -> canRestore
    is FixStep.Retag, is FixStep.Undo -> canEdit
    is FixStep.JoinAlbum -> canJoinAlbums
    is FixStep.AddCover -> canAddCover
}

// Whether every step can be done now, as for an Undo.
fun LibraryActions?.canRunAll(steps: List<FixStep>): Boolean = this != null && steps.isNotEmpty() && steps.all { canRun(it) }

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

    private val _lastUndo = MutableStateFlow<List<FixStep>>(emptyList())

    // What puts the last run back, until another run replaces it.
    val lastUndo: StateFlow<List<FixStep>> = _lastUndo

    @Volatile
    private var stopping = false

    fun start() {
        if (running) return
        running = true
        scope.launch {
            sessions.state.distinctUntilChangedBy { (it as? SessionState.SignedIn)?.session }.collect { state ->
                _actions.value = null
                _source.value = null
                _lastUndo.value = emptyList()
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
        return withContext(Dispatchers.IO) { serverSongIds(sources.copiesOf(trackIds.distinct()), _source.value) }
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
    // the caller tells how it went. A run that waited its turn while
    // another server was signed in to is not sent. `stop()` ends it between
    // two steps. What puts it back is kept as the last change, unless this
    // run is itself an undo or another server is signed in to by the end.
    suspend fun run(steps: List<FixStep>, keepUndo: Boolean = true, on: String? = server()): FixOutcome = one.withLock {
        if (steps.isEmpty()) return@withLock FixOutcome()
        stopping = false
        val session = session()
        if (on == null || session == null) return@withLock FixOutcome(failed = steps.map { it to "Not signed in" })
        if (session.key != on) return@withLock FixOutcome(failed = steps.map { it to FIX_OTHER_SERVER })
        val client = session.client
        _progress.value = FixRun(0, steps.size)
        val outcome = try {
            runFix(
                steps,
                send = { step -> client.libraryAction(step.id, step.action, step.with) },
                progress = { done, total -> _progress.value = FixRun(done, total) },
                stop = { stopping },
                failure = { it.userMessage() },
            )
        } finally {
            _progress.value = null
        }
        val still = server() == on
        if (still && keepUndo && outcome.undo.isNotEmpty()) _lastUndo.value = outcome.undo
        if (still && outcome.done.isNotEmpty()) reload()
        outcome
    }

    fun stop() {
        stopping = true
    }

    // Runs the steps away from the page that asked, so leaving it does not
    // cut a run short, on the server `on` they were planned on. `shown`
    // hears how it went and answers whether it told the listener; when it
    // did not (its page has gone), a line says it. Once another server is
    // signed in to, how it went is not told at all. An `undo` run puts back
    // an earlier one's `FixOutcome.undo`.
    fun launch(steps: List<FixStep>, undo: Boolean = false, on: String? = server(), shown: (FixOutcome) -> Boolean = { false }) {
        scope.launch {
            val outcome = if (undo) undo(steps, on) else run(steps, on = on)
            if (on != null && server() != on) return@launch
            val heard = withContext(Dispatchers.Main) { shown(outcome) }
            if (!heard) say(outcome.summary(), outcome, on)
        }
    }

    // Says how a run on the server `on` went, with an Undo when the server
    // can put it back. The Undo goes to that same server, and only while it
    // is still the one signed in to.
    fun say(line: String, outcome: FixOutcome, on: String? = server()) {
        val back = outcome.undo
        if (on != null && _actions.value.canRunAll(back)) {
            feedback.undoable(line) {
                scope.launch {
                    if (server() != on) {
                        feedback.show(FIX_OTHER_SERVER)
                        return@launch
                    }
                    val put = undo(back, on)
                    if (server() == on) feedback.show(put.summary())
                }
            }
        } else {
            feedback.show(line)
        }
    }

    // Puts back what a run on the server `on` did, from its outcome's
    // `undo`. Once it is put back, it is no longer the last change to undo.
    suspend fun undo(steps: List<FixStep>, on: String? = server()): FixOutcome {
        _lastUndo.compareAndSet(steps, emptyList())
        return run(steps, keepUndo = false, on = on)
    }

    // Puts back the last run, once.
    suspend fun undoLast(): FixOutcome = undo(_lastUndo.value)

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
            val copies = sources.copiesOf(trackIds.distinct())
            val steps = deleteSteps(trackIds, copies, _source.value?.takeIf { server() == on })
            if (steps.isEmpty()) return@launch
            val outcome = run(steps, on = on)
            if (server() != on) return@launch
            val removed = outcome.removed
            val songs = trackIds.distinct().count { id -> copies.any { it.mergedId == id && it.nativeId in removed } }
            val line = if (outcome.failed.isEmpty() && !outcome.rehearsed && !outcome.stopped) deletedLine(songs, title) else outcome.summary()
            say(line, outcome, on)
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
