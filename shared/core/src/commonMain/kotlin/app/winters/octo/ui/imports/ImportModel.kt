package app.winters.octo.ui.imports

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.winters.octo.subsonic.ImportActions
import app.winters.octo.subsonic.ImportListDetail
import app.winters.octo.subsonic.ImportOverview
import app.winters.octo.subsonic.SubsonicClient
import app.winters.octo.subsonic.SubsonicException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// The Spotify import view's state, for the server in use, on the phone and
// the desktop alike: the server's overview, the list opened, the last thing
// the server said, and the sign-in while it waits for the browser. The server
// does the work; this asks it, every few seconds while the view is open and
// something moves.
@Stable
class ImportModel(
    private val client: () -> SubsonicClient?,
    private val scope: CoroutineScope,
    private val openUrl: (String) -> Unit = {},
    private val busyPollMs: Long = IMPORTS_BUSY_POLL_MS,
    private val idlePollMs: Long = IMPORTS_IDLE_POLL_MS,
) {
    var overview by mutableStateOf<ImportOverview?>(null)
        private set

    // Why the server could not be read, or that it has no Spotify import.
    var problem by mutableStateOf<String?>(null)
        private set

    var openId by mutableStateOf<String?>(null)
        private set
    var detail by mutableStateOf<ImportListDetail?>(null)
        private set

    // What the server said about the last thing asked, in its own words.
    var said by mutableStateOf<String?>(null)
        private set
    var working by mutableStateOf(false)
        private set
    var signingIn by mutableStateOf(false)
        private set

    private var watching: Job? = null
    private var signIn: Job? = null

    // While the page shows: read now, then again soon while anything moves.
    fun watch() {
        if (watching?.isActive == true) return
        watching = scope.launch {
            while (isActive) {
                refresh()
                delay(if (overview?.moving() == true || signingIn) busyPollMs else idlePollMs)
            }
        }
    }

    fun stop() {
        watching?.cancel()
        watching = null
    }

    // Another server: nothing of this one's stays.
    fun forget() {
        stop()
        signIn?.cancel()
        overview = null
        problem = null
        openId = null
        detail = null
        said = null
    }

    suspend fun refresh() {
        val client = client() ?: return
        try {
            overview = client.imports()
            problem = null
        } catch (e: SubsonicException.Unreachable) {
            problem = "Octo can't reach the server right now."
        } catch (e: SubsonicException) {
            // An Octo from before Spotify import passes the call on to Navidrome, which does not know it.
            problem = if (overview == null) "This server has no Spotify import yet. Update Octo on the server to use it." else e.message
        }
        val id = openId ?: return
        runCatching { client.importList(id) }.onSuccess { if (openId == id) detail = it }
    }

    // Opens Spotify in the browser and waits for its answer on this device.
    fun connect(open: (String) -> Unit = openUrl) {
        val client = client() ?: return
        if (signingIn) return
        signIn = scope.launch {
            signingIn = true
            said = "Allow Octo in your browser, then come back here."
            try {
                said = signInToSpotify(client, open).message
            } catch (e: SubsonicException) {
                said = e.message
            } finally {
                signingIn = false
                refresh()
            }
        }
    }

    fun cancelSignIn() {
        signIn?.cancel()
        signingIn = false
        said = null
    }

    fun disconnect() = act(ImportActions.DISCONNECT)
    fun readAgain() = act(ImportActions.READ)
    fun keepPlaylist(id: String, on: Boolean) = act(ImportActions.PLAYLIST, mapOf("id" to id, "on" to on.toString()))
    fun getMissing(id: String, on: Boolean) = act(ImportActions.FETCH, mapOf("id" to id, "on" to on.toString()))
    fun getSongs(id: String, keys: List<String>) = act(ImportActions.SONGS, mapOf("id" to id), keys)
    fun readList(id: String) = act(ImportActions.REFRESH, mapOf("id" to id))
    fun pause() = act(ImportActions.PAUSE)
    fun resume() = act(ImportActions.RESUME)
    fun retry(keys: List<String> = emptyList()) = act(ImportActions.RETRY, keys = keys)
    fun skip(key: String) = act(ImportActions.SKIP, keys = listOf(key))
    fun clearFinished() = act(ImportActions.CLEAR)

    fun remove(id: String) {
        if (openId == id) close()
        act(ImportActions.REMOVE, mapOf("id" to id))
    }

    // A public playlist or album by its link; the new list opens.
    fun addLink(url: String) = act(ImportActions.ADD_LINK, mapOf("url" to url.trim())) { answer -> answer.listId?.let(::open) }

    fun open(id: String) {
        if (openId != id) detail = null
        openId = id
        scope.launch { refresh() }
    }

    fun close() {
        openId = null
        detail = null
    }

    private fun act(
        action: String,
        params: Map<String, String> = emptyMap(),
        keys: List<String> = emptyList(),
        then: (app.winters.octo.subsonic.ImportAnswer) -> Unit = {},
    ) {
        val client = client() ?: return
        scope.launch {
            working = true
            try {
                val answer = client.importAction(action, params, keys)
                said = answer.message
                if (answer.ok) then(answer)
            } catch (e: SubsonicException) {
                said = e.message ?: "Octo could not reach the server."
            } finally {
                working = false
            }
            refresh()
        }
    }
}
