package app.winters.octo.desktop.health

import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.winters.octo.desktop.server.userMessage
import app.winters.octo.health.DuplicateGroup
import app.winters.octo.health.HealthCheck
import app.winters.octo.health.HealthReport
import app.winters.octo.health.SubsonicHealth
import app.winters.octo.health.checkLibrary
import app.winters.octo.health.qualityText
import app.winters.octo.subsonic.LibraryActionState
import app.winters.octo.subsonic.LibraryActions
import app.winters.octo.subsonic.OCTO_LIBRARY_ACTIONS
import app.winters.octo.subsonic.Song
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
// looked at; what the server lets this user do to its files; and the songs
// taken out of the library here, hidden until the server's list catches up.
// Kept for the whole sign-in, so leaving the page and coming back neither
// checks again nor shows a removed copy.
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

    // The check picked on the page; null shows the first one with findings.
    var picked by mutableStateOf<HealthCheck?>(null)

    // Null until the server has said, and on a server without the
    // extension.
    var actions by mutableStateOf<LibraryActions?>(null)
        private set

    // A song being removed, so its menu row waits.
    var removing by mutableStateOf<String?>(null)
        private set

    // The report with removed songs left out, or null while checking.
    private val left = derivedStateOf { full?.without(removed, SubsonicHealth) }
    val report: HealthReport<Song>? get() = left.value

    // The check the page shows.
    val shown: HealthCheck?
        get() {
            val now = report ?: return null
            return picked?.takeIf { now.count(it) > 0 } ?: now.findings.firstOrNull()
        }

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
        }
    }

    // Asks the server, once per visit, whether songs can be removed.
    fun askServer() {
        val client = client() ?: return
        scope.launch {
            actions = try {
                if (client.supports(OCTO_LIBRARY_ACTIONS)) client.libraryActions() else null
            } catch (e: SubsonicException) {
                null
            }
        }
    }

    // The set of copies a song is one of, when it is listed as a duplicate.
    fun groupOf(song: Song): DuplicateGroup<Song>? = report?.duplicates?.firstOrNull { group -> group.copies.any { it.id == song.id } }

    // Whether the song can be taken out of the library from here: the
    // server allows it for real, and another copy stays behind.
    fun canRemove(song: Song): Boolean =
        actions?.canRemove == true && (groupOf(song)?.copies?.size ?: 0) > 1 && removing == null

    // What the confirmation says about removing `song`.
    fun removeQuestion(song: Song): RemoveQuestion? {
        val group = groupOf(song) ?: return null
        val stays = group.copies.firstOrNull { it.id != song.id } ?: return null
        return RemoveQuestion(
            song = song,
            copy = copyText(song),
            stays = copyText(stays),
            keepDays = actions?.keepDays ?: 0,
        )
    }

    // Takes the song out of the library through the server; `done` gets
    // the line to show.
    fun remove(song: Song, done: (String) -> Unit) {
        val client = client() ?: return
        if (removing != null) return
        removing = song.id
        scope.launch {
            val line = try {
                val result = client.libraryAction(song.id)
                when (result.outcome) {
                    LibraryActionState.Applied -> {
                        removed = removed + song.id
                        "Removed ${song.title} from your library."
                    }
                    LibraryActionState.Rehearsed -> "The server only rehearsed this, so nothing was removed."
                    else -> "Could not remove ${song.title}. ${result.detail.orEmpty()}".trim()
                }
            } catch (e: SubsonicException) {
                "Could not remove ${song.title}. ${e.userMessage()}"
            }
            removing = null
            done(line)
        }
    }

    // A different account: everything is forgotten.
    fun forget() {
        job?.cancel()
        full = null
        checkedSongs = null
        removed = emptySet()
        picked = null
        actions = null
        removing = null
    }
}

// What to ask before removing one copy.
data class RemoveQuestion(val song: Song, val copy: String, val stays: String, val keepDays: Int) {
    val title: String get() = "Remove this copy?"

    val body: String
        get() {
            val kept = if (keepDays > 0) "for $keepDays days" else "until someone clears it"
            return "This takes $copy out of your library. The server keeps the file in its trash $kept, so it can be put back if you change your mind. $stays stays in your library."
        }
}

// One copy in a few words: "Holocene on Bon Iver (MP3, 320 kbps)".
private fun copyText(song: Song): String {
    val album = song.album?.takeIf(String::isNotBlank)?.let { " on $it" }.orEmpty()
    return "${song.title}$album (${qualityText(song, SubsonicHealth)})"
}
