package app.winters.octo.ui.family

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.winters.octo.subsonic.Album
import app.winters.octo.subsonic.FamilyDevice
import app.winters.octo.subsonic.FamilyDeviceAdded
import app.winters.octo.subsonic.FamilyDeviceKind
import app.winters.octo.subsonic.FamilyInfo
import app.winters.octo.subsonic.FamilyMe
import app.winters.octo.subsonic.FamilyRequest
import app.winters.octo.subsonic.FamilyRequestState
import app.winters.octo.subsonic.RequestQuality
import app.winters.octo.subsonic.Song
import app.winters.octo.subsonic.SubsonicClient
import app.winters.octo.subsonic.SubsonicException
import app.winters.octo.subsonic.addFamilyDevice
import app.winters.octo.subsonic.cancelFamilyRequest
import app.winters.octo.subsonic.decideFamilyRequest
import app.winters.octo.subsonic.family
import app.winters.octo.subsonic.familyDevices
import app.winters.octo.subsonic.familyRequests
import app.winters.octo.subsonic.removeFromMyLibrary
import app.winters.octo.subsonic.requestCopy
import app.winters.octo.subsonic.signOutFamilyDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// The outside songs and albums the account saved: hearted songs found
// online, kept as links that play from the source.
data class SavedOutside(val songs: List<Song> = emptyList(), val albums: List<Album> = emptyList()) {
    val isEmpty: Boolean get() = songs.isEmpty() && albums.isEmpty()
}

// The parts of the Family view, in the order they show.
enum class FamilySection(val title: String, val managersOnly: Boolean = false) {
    Plan(MY_PLAN),
    Saved(SAVED),
    Requests(REQUESTS),
    Devices(DEVICES),
    Members(MEMBERS, managersOnly = true),
    Inbox(REQUESTS_WAITING, managersOnly = true),
}

// The Family view's state for the server in use, on the phone and the
// desktop alike. It is there only while the server lists octoFamily
// (`supported`); everything else waits on the server, which decides.
@Stable
class FamilyModel(
    private val client: () -> SubsonicClient?,
    private val supported: () -> Boolean,
    private val scope: CoroutineScope,
    // A request the sheet sent and showed the answer of, so the phone's
    // notices do not tell it again.
    private val answered: (FamilyRequest) -> Unit = {},
    private val pollMs: Long = FAMILY_POLL_MS,
) {
    var info by mutableStateOf<FamilyInfo?>(null)
        private set

    // Why the server could not be read.
    var problem by mutableStateOf<String?>(null)
        private set

    // The account's own requests, newest first as the server lists them.
    var requests by mutableStateOf<List<FamilyRequest>>(emptyList())
        private set

    // Every member's waiting requests, for a manager who approves.
    var inbox by mutableStateOf<List<FamilyRequest>>(emptyList())
        private set

    var devices by mutableStateOf<List<FamilyDevice>>(emptyList())
        private set

    var saved by mutableStateOf(SavedOutside())
        private set

    // A device just added: its pair code or app password, shown once.
    var added by mutableStateOf<FamilyDeviceAdded?>(null)
        private set

    // What the server said about the last thing asked, in its own words.
    var said by mutableStateOf<String?>(null)
        private set

    var working by mutableStateOf(false)
        private set

    private var watching: Job? = null

    val available: Boolean get() = supported()

    val me: FamilyMe? get() = info?.me

    val manages: Boolean get() = info?.manager != null

    val approves: Boolean get() = info?.me?.abilities?.approveRequests == true

    // The sections this account sees.
    fun sections(): List<FamilySection> = FamilySection.entries.filter { section ->
        when (section) {
            FamilySection.Members -> manages
            FamilySection.Inbox -> approves
            else -> true
        }
    }

    // While the view shows: read now, then again now and then.
    fun watch() {
        if (watching?.isActive == true) return
        watching = scope.launch {
            while (isActive) {
                refresh()
                delay(pollMs)
            }
        }
    }

    fun stop() {
        watching?.cancel()
        watching = null
    }

    // Another server or account: nothing of this one's stays.
    fun forget() {
        stop()
        info = null
        problem = null
        requests = emptyList()
        inbox = emptyList()
        devices = emptyList()
        saved = SavedOutside()
        added = null
        said = null
    }

    // Reads the plan only, for the parts of the app that follow it (song
    // actions, offline copies). Null without a family.
    suspend fun plan(): FamilyMe? {
        if (!supported()) return null
        info?.let { return it.me }
        val client = client() ?: return null
        return runCatching { client.family() }.getOrNull()?.also { info = it }?.me
    }

    suspend fun refresh() {
        val client = client() ?: return
        if (!supported()) {
            forget()
            return
        }
        try {
            val read = client.family()
            info = read
            problem = null
            coroutineScope {
                val mine = async { client.familyRequests() }
                val mineDevices = async { client.familyDevices() }
                val waiting = async { if (read.me.abilities.approveRequests) client.familyRequests(all = true, state = FamilyRequestState.Pending) else emptyList() }
                val starred = async { runCatching { client.starred() }.getOrNull() }
                requests = mine.await()
                devices = mineDevices.await()
                inbox = waiting.await()
                starred.await()?.let { found -> saved = SavedOutside(found.song.filter { it.isExternal }, found.album.filter { it.isExternal }) }
            }
        } catch (e: SubsonicException.Unreachable) {
            problem = "Octo can't reach the server right now."
        } catch (e: SubsonicException) {
            problem = e.message ?: "The server did not answer."
        }
    }

    // Asks for a copy and answers what became of it, or null when it could
    // not be asked (the reason is in `said`).
    suspend fun request(id: String, quality: RequestQuality): FamilyRequest? {
        val client = client() ?: return null
        working = true
        return try {
            client.requestCopy(id, quality).also { made ->
                said = requestAnswerLine(made)
                answered(made)
                requests = listOf(made) + requests.filterNot { it.id == made.id }
            }
        } catch (e: SubsonicException) {
            said = e.message ?: "The request did not go through."
            null
        } finally {
            working = false
            scope.launch { refresh() }
        }
    }

    fun requestCopy(id: String, quality: RequestQuality) {
        scope.launch { request(id, quality) }
    }

    fun cancel(id: String) = act { cancelFamilyRequest(id).let { "Request cancelled" } }

    fun approve(id: String, note: String? = null) = act { decideFamilyRequest(id, approve = true, note = note).let { "Approved ${requestTitle(it)}" } }

    fun decline(id: String, note: String? = null) = act { decideFamilyRequest(id, approve = false, note = note).let { "Declined ${requestTitle(it)}" } }

    fun signOut(device: FamilyDevice) = act {
        signOutFamilyDevice(device.id)
        "${device.name.ifBlank { "The device" }} is signed out"
    }

    // A new device: an Octo app gets a pair code, any other app a password.
    fun addDevice(name: String, kind: FamilyDeviceKind) = act {
        added = addFamilyDevice(name.ifBlank { if (kind == FamilyDeviceKind.OctoApp) "Octo app" else "Music app" }, kind)
        null
    }

    // The code or password has been seen; it is never shown again.
    fun dismissAdded() {
        added = null
    }

    // Takes a song out of this member's own library.
    fun removeFromMyLibrary(id: String, title: String) = act {
        removeFromMyLibrary(id)
        "Removed $title from your library"
    }

    // Takes an outside song or album off the saved list.
    fun unsave(id: String, album: Boolean) = act {
        if (album) unstarAlbums(listOf(id)) else unstar(listOf(id))
        "Removed from Saved"
    }

    fun clearSaid() {
        said = null
    }

    private fun act(call: suspend SubsonicClient.() -> String?) {
        val client = client() ?: return
        scope.launch {
            working = true
            try {
                client.call()?.let { said = it }
            } catch (e: SubsonicException.Unreachable) {
                said = "Octo can't reach the server right now."
            } catch (e: SubsonicException) {
                said = e.message ?: "The server said no."
            } finally {
                working = false
            }
            refresh()
        }
    }
}

// How often the open view reads the server again.
const val FAMILY_POLL_MS = 30_000L

// What the sheet says once a request is sent.
fun requestAnswerLine(request: FamilyRequest): String = when (request.state) {
    FamilyRequestState.Done -> outcomeLine(request.outcome)
    FamilyRequestState.Approved -> "Approved. Getting it now"
    FamilyRequestState.Pending -> "Requested. You'll hear when it's decided"
    else -> requestStateLine(request)
}
