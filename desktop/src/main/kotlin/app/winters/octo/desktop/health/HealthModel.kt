package app.winters.octo.desktop.health

import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.winters.octo.desktop.server.userMessage
import app.winters.octo.health.FixOutcome
import app.winters.octo.health.FixStep
import app.winters.octo.health.HealthCheck
import app.winters.octo.health.HealthReport
import app.winters.octo.health.SubsonicHealth
import app.winters.octo.health.checkLibrary
import app.winters.octo.health.runFix
import app.winters.octo.health.settledFor
import app.winters.octo.subsonic.LibraryActions
import app.winters.octo.subsonic.LibraryActionResult
import app.winters.octo.subsonic.LibraryTrash
import app.winters.octo.subsonic.OCTO_LIBRARY_ACTIONS
import app.winters.octo.subsonic.Song
import app.winters.octo.subsonic.SongLookup
import app.winters.octo.subsonic.SubsonicClient
import app.winters.octo.subsonic.SubsonicException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// The Library health page's state: the report on the library as it was
// last read, worked out away from the window's thread; the check being
// looked at; what the server lets this user do to its files; the fix
// running now; and the songs fixed here, left out of their check until the
// server's list catches up. Kept for the whole sign-in, so leaving the page
// and coming back neither checks again nor shows a fixed song.
@Stable
class HealthModel(
    private val client: () -> SubsonicClient?,
    private val scope: CoroutineScope,
    private val work: CoroutineDispatcher = Dispatchers.Default,
) {
    private var full by mutableStateOf<HealthReport<Song>?>(null)
    private var checkedSongs: List<Song>? = null
    private var job: Job? = null

    // Ids of songs removed here.
    private var removed by mutableStateOf<Set<String>>(emptySet())

    // Songs fixed here, by the check they were fixed for.
    private var settled by mutableStateOf<Map<HealthCheck, Set<String>>>(emptyMap())

    // The check picked on the page; null shows the first one with findings.
    var picked by mutableStateOf<HealthCheck?>(null)

    // Null until the server has said, and on a server without the
    // extension.
    var actions by mutableStateOf<LibraryActions?>(null)
        private set

    // The fix running now, or null.
    var running by mutableStateOf<FixProgress?>(null)
        private set
    private var stopAsked = false

    // Songs whose tags, album or cover were changed here, which can be put
    // back one at a time from their menu.
    var changed by mutableStateOf<Set<String>>(emptySet())
        private set

    // The server's trash, once asked for.
    var trash by mutableStateOf<LibraryTrash?>(null)
        private set

    // The report with what was fixed here left out, or null while checking.
    private val left = derivedStateOf {
        val report = full?.without(removed, SubsonicHealth) ?: return@derivedStateOf null
        settled.entries.fold(report) { now, (check, ids) -> now.settledFor(check, ids, SubsonicHealth) }
    }
    val report: HealthReport<Song>? get() = left.value

    // The check the page shows.
    val shown: HealthCheck?
        get() {
            val now = report ?: return null
            return picked?.takeIf { now.count(it) > 0 } ?: now.findings.firstOrNull()
        }

    // Whether songs can be taken off the server's disk from here.
    val canDelete: Boolean get() = actions?.canRemove == true

    // Checks these songs, unless they are the ones checked already.
    fun check(songs: List<Song>) {
        if (songs === checkedSongs) return
        checkedSongs = songs
        job?.cancel()
        job = scope.launch {
            val found = withContext(work) { checkLibrary(songs, SubsonicHealth) }
            full = found
            // What the server list now shows is what the server holds.
            removed = removed.filterTo(HashSet()) { id -> songs.any { it.id == id } }
            settled = emptyMap()
        }
    }

    // Asks the server what this user may do to its files.
    fun askServer() {
        val client = client() ?: return
        scope.launch {
            actions = try {
                if (client.supports(OCTO_LIBRARY_ACTIONS)) client.libraryActions() else null
            } catch (e: SubsonicException) {
                actions
            }
        }
    }

    // Runs fix steps one after another, `label` saying what for while it
    // goes. Songs it fixed leave `settle` (and removed songs every check)
    // until the server's list catches up. `done` hears how it went.
    fun run(label: String, steps: List<FixStep>, settle: HealthCheck? = null, done: (FixOutcome) -> Unit = {}) {
        val client = client() ?: return
        if (running != null || steps.isEmpty()) return
        stopAsked = false
        running = FixProgress(label, 0, steps.size)
        scope.launch {
            val outcome = runFix(
                steps,
                send = { step -> withContext(Dispatchers.IO) { send(client, step) } },
                progress = { at, total -> running = FixProgress(label, at, total) },
                stop = { stopAsked },
                failure = { e -> (e as? SubsonicException)?.userMessage() ?: e.message ?: "something went wrong" },
            )
            removed = removed + outcome.removed
            val restored = outcome.done.filterIsInstance<FixStep.Restore>().mapTo(HashSet()) { it.id }
            if (restored.isNotEmpty()) {
                removed = removed - restored
                trash = trash?.let { it.copy(songs = it.songs.filterNot { song -> song.id in restored }) }
            }
            val edited = outcome.done.filter { it is FixStep.Retag || it is FixStep.JoinAlbum || it is FixStep.AddCover }.mapTo(HashSet()) { it.id }
            val undone = outcome.done.filterIsInstance<FixStep.Undo>().mapTo(HashSet()) { it.id }
            changed = changed + edited - undone
            if (settle != null && edited.isNotEmpty()) settled = settled + (settle to (settled[settle].orEmpty() + edited))
            running = null
            done(outcome)
        }
    }

    // Ends the fix running now after the song it is on.
    fun stop() {
        stopAsked = true
    }

    // The tags a download of the song would get, or null when the server
    // could not say; `failed` hears why.
    suspend fun lookUp(song: Song, failed: (String) -> Unit = {}): SongLookup? {
        val client = client() ?: return null
        return try {
            val lookup = withContext(Dispatchers.IO) { client.lookUpTags(song.id) }
            if (lookup.found) return lookup
            failed(lookup.detail?.takeIf(String::isNotBlank) ?: "Nothing was found for ${song.title}.")
            null
        } catch (e: SubsonicException) {
            failed("Could not look up ${song.title}. ${e.userMessage()}")
            null
        }
    }

    // Reads the server's trash.
    fun readTrash(done: (String?) -> Unit = {}) {
        val client = client() ?: return
        scope.launch {
            try {
                trash = withContext(Dispatchers.IO) { client.libraryTrash() }
                done(null)
            } catch (e: SubsonicException) {
                done("Could not read the server's trash. ${e.userMessage()}")
            }
        }
    }

    // A different account: everything is forgotten.
    fun forget() {
        job?.cancel()
        full = null
        checkedSongs = null
        removed = emptySet()
        settled = emptyMap()
        picked = null
        actions = null
        running = null
        changed = emptySet()
        trash = null
    }

    private suspend fun send(client: SubsonicClient, step: FixStep): LibraryActionResult =
        if (step.with.isEmpty()) client.libraryAction(step.id, step.action) else client.libraryAction(step.id, step.action, step.with)
}

// How far a fix has got: `done` of `total` songs.
data class FixProgress(val label: String, val done: Int, val total: Int) {
    val words: String get() = "$label: $done of $total"
}
