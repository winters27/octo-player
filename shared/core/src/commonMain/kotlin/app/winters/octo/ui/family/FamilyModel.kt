package app.winters.octo.ui.family

import app.winters.octo.subsonic.setFamilyDeviceQuality
import app.winters.octo.subsonic.setFamilyQuality
import app.winters.octo.subsonic.StreamQuality
import app.winters.octo.subsonic.DeviceQualityMode
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
import app.winters.octo.subsonic.FamilyMember
import app.winters.octo.subsonic.FamilyPreset
import app.winters.octo.subsonic.FamilyWeb
import app.winters.octo.subsonic.familyJoinUrl
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
    // The time now in milliseconds, and how often an open Add a device
    // popup looks at its code's expiry.
    private val clock: () -> Long = System::currentTimeMillis,
    private val tickMs: Long = 250,
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

    // The Add a device popup while it is open: the pair code or app
    // password, shown once, and the member it is for when a manager adds
    // one for someone else.
    var sheet by mutableStateOf<DeviceSheet?>(null)
        private set
    private var sheets = 0
    private var renewing: Job? = null

    // Whether the window or app is on screen; a code is only renewed while
    // someone could be looking at it.
    var onScreen by mutableStateOf(true)
        private set

    // What the server said about the last thing asked, in its own words.
    var said by mutableStateOf<String?>(null)
        private set

    var working by mutableStateOf(false)
        private set

    // A new member's invite, shown as a QR code with a way to copy it,
    // until closed.
    var invite by mutableStateOf<InviteSheet?>(null)
        private set

    private var watching: Job? = null

    val available: Boolean get() = supported()

    val me: FamilyMe? get() = info?.me

    val manages: Boolean get() = info?.manager != null

    val approves: Boolean get() = info?.me?.abilities?.approveRequests == true

    // This device, as the server lists it, once read.
    val thisDevice: FamilyDevice? get() = devices.firstOrNull { it.current }

    // Who picks this device's audio quality; null until read.
    val deviceMode: DeviceQualityMode? get() = thisDevice?.quality

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
        dismissAdded()
        said = null
        invite = null
    }

    // Reads the account's abilities only, for the parts of the app that follow it (song
    // actions, offline copies). Null without a family.
    suspend fun plan(): FamilyMe? {
        if (!supported()) return null
        info?.let { return it.me }
        val client = client() ?: return null
        val read = runCatching { client.family() }.getOrNull() ?: return null
        info = read
        // This device's quality mode decides how the app asks for songs.
        runCatching { client.familyDevices() }.getOrNull()?.let { devices = it }
        return read.me
    }

    // The account's own quality at home or away, kept on the server for
    // every app the account uses.
    fun setQuality(home: StreamQuality? = null, away: StreamQuality? = null) = act {
        val now = setFamilyQuality(home, away)
        info = info?.let { it.copy(me = it.me.copy(quality = now)) }
        null
    }

    // Whether this device follows the account's choice or its own app's.
    fun setDeviceMode(mode: DeviceQualityMode) {
        val device = thisDevice ?: return
        devices = devices.map { if (it.id == device.id) it.copy(quality = mode) else it }
        act {
            setFamilyDeviceQuality(device.id, mode)
            null
        }
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
    // The popup opens at once and fills in when the server answers.
    fun addDevice(name: String, kind: FamilyDeviceKind) =
        openSheet(DeviceSheet(deviceName = name.trim(), view = viewFor(kind), awayAllowed = me?.abilities?.away != false))

    // A manager's new device for a member: that member's pair code or app
    // password, shown as for one's own.
    fun addMemberDevice(member: FamilyMember, kind: FamilyDeviceKind = FamilyDeviceKind.OctoApp, name: String = "") = openSheet(
        DeviceSheet(
            forName = member.displayName.ifBlank { member.username },
            forUsername = member.username,
            deviceName = name.trim(),
            view = viewFor(kind),
            awayAllowed = member.username !in keptHome,
        ),
    )

    // After an hour open the popup stops renewing its code and asks;
    // this starts it again with a fresh code.
    fun newCode() {
        val open = sheet ?: return
        sheet = open.copy(openedAt = clock(), stale = false, refreshFailed = false)
        fetch(DeviceSheetView.Code)
        renew(open.id)
    }

    // The window or app shown or hidden: renewing pauses while hidden, and
    // a code that expired meanwhile is replaced on return.
    fun sheetOnScreen(visible: Boolean) {
        onScreen = visible
    }

    // The app password view, for Symfonium or another app. Its password is
    // made the first time it is asked for.
    fun showOtherApps() = switchTo(DeviceSheetView.OtherApps)

    fun backToCode() = switchTo(DeviceSheetView.Code)

    // Asks again for what failed.
    fun retrySheet() {
        val open = sheet ?: return
        fetch(open.view)
    }

    // The code or password has been seen; it is never shown again.
    fun dismissAdded() {
        renewing?.cancel()
        renewing = null
        sheet = null
    }

    private fun viewFor(kind: FamilyDeviceKind) = if (kind == FamilyDeviceKind.OctoApp) DeviceSheetView.Code else DeviceSheetView.OtherApps

    private fun openSheet(open: DeviceSheet) {
        sheets += 1
        sheet = open.copy(id = sheets, openedAt = clock())
        fetch(open.view)
        renew(sheets)
    }

    // While the popup is open and on screen, a pair code is replaced a few
    // seconds before it expires, ending the old one, so the QR code on
    // screen always works. A failed try is made again every 10 seconds.
    // After an hour open it stops and asks instead, so codes never pile up.
    private fun renew(id: Int) {
        renewing?.cancel()
        renewing = scope.launch {
            while (isActive) {
                val open = sheet?.takeIf { it.id == id } ?: return@launch
                val code = open.code
                val due = when {
                    !onScreen || open.loading || open.stale || code == null -> null
                    open.refreshFailed -> open.retryAt
                    else -> expiresAtMs(code.expires)?.minus(RENEW_LEAD_MS)
                }
                if (due != null && clock() >= due) {
                    if (clock() - open.openedAt >= RENEW_FOR_MS) {
                        sheet = open.copy(stale = true, refreshFailed = false)
                        return@launch
                    }
                    replaceCode(open, code!!)
                }
                delay(tickMs)
            }
        }
    }

    private suspend fun replaceCode(open: DeviceSheet, old: FamilyDeviceAdded) {
        val client = client() ?: return
        sheet = open.copy(loading = true)
        val got = runCatching {
            client.addFamilyDevice(deviceNameOr(open.deviceName, FamilyDeviceKind.OctoApp), FamilyDeviceKind.OctoApp, username = open.forUsername, replaces = old.deviceId)
        }
        val now = sheet?.takeIf { it.id == open.id } ?: return
        sheet = got.fold(
            { now.copy(code = it, loading = false, refreshFailed = false, renewed = now.renewed + 1) },
            { now.copy(loading = false, refreshFailed = true, retryAt = clock() + RENEW_RETRY_MS) },
        )
    }

    private fun switchTo(view: DeviceSheetView) {
        val open = sheet ?: return
        sheet = open.copy(view = view, error = null)
        if (open.loading) return
        if ((view == DeviceSheetView.Code && open.code == null) || (view == DeviceSheetView.OtherApps && open.password == null)) fetch(view)
    }

    // Asks the server for a pair code or an app password for the popup
    // that is open, and fills it in; an answer for a popup since closed is
    // dropped.
    private fun fetch(view: DeviceSheetView) {
        val client = client() ?: return
        val asked = sheet ?: return
        val kind = if (view == DeviceSheetView.Code) FamilyDeviceKind.OctoApp else FamilyDeviceKind.SubsonicApp
        // A code asked for again ends the one it replaces.
        val replaces = if (view == DeviceSheetView.Code) asked.code?.deviceId else null
        sheet = asked.copy(loading = true, error = null)
        scope.launch {
            val got = runCatching { client.addFamilyDevice(deviceNameOr(asked.deviceName, kind), kind, username = asked.forUsername, replaces = replaces) }
            val now = sheet?.takeIf { it.id == asked.id } ?: return@launch
            sheet = got.fold(
                { if (view == DeviceSheetView.Code) now.copy(code = it, loading = false) else now.copy(password = it, loading = false) },
                { now.copy(loading = false, error = if (now.view != view) null else if (view == DeviceSheetView.Code) CODE_FAILED else PASSWORD_FAILED) },
            )
            if (got.isSuccess) refresh()
        }
    }

    private fun deviceNameOr(name: String, kind: FamilyDeviceKind) = name.ifBlank { if (kind == FamilyDeviceKind.OctoApp) "Octo app" else "Music app" }

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

    // A manager adds a member, who may listen away from home or not; their
    // invite shows as a QR code to scan or a link to send.
    fun addMember(username: String, displayName: String, preset: FamilyPreset, away: Boolean = true) = manage { web ->
        val added = web.addMember(username, displayName, preset, away)
        val name = added.member.displayName.ifBlank { displayName.ifBlank { username } }
        val member = added.member.username.ifBlank { username.trim() }
        keptHome = if (away) keptHome - member else keptHome + member
        invite = InviteSheet(
            name,
            member,
            added.inviteLink,
            links = added.links,
            anywhereAvailable = added.anywhereAvailable,
            homeOnly = added.homeOnly,
            awayAllowed = away,
        )
        "Added $name"
    }

    // Members added here who may not listen away from home: their popups
    // offer only the home link.
    private var keptHome = emptySet<String>()

    // A new link for the invite that is open, in place of the last one.
    fun sendNewLink() {
        val client = client() ?: return
        val asked = invite ?: return
        invite = asked.copy(loading = true, error = null)
        scope.launch {
            val got = runCatching { client.familyWeb().newInvite(asked.username) }
            val now = invite?.takeIf { it.username == asked.username } ?: return@launch
            invite = got.fold(
                { now.copy(url = it.inviteLink, links = it.links, anywhereAvailable = it.anywhereAvailable, homeOnly = it.homeOnly, loading = false) },
                { now.copy(loading = false, error = LINK_FAILED) },
            )
        }
    }

    fun closeInvite() {
        invite = null
    }

    // A change on the family page, signed as every call this app makes.
    private fun manage(change: suspend (FamilyWeb) -> String?) {
        val client = client() ?: return
        scope.launch {
            working = true
            try {
                change(client.familyWeb())?.let { said = it }
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

// The https link for a device just added with a pair code, which any
// camera opens, with the home address when the server names one; null for
// an app password, which has no link. The server's own link wins.
fun addedDeviceLink(added: FamilyDeviceAdded, server: String): String? {
    val code = added.pairCode ?: return null
    added.link?.takeIf(String::isNotBlank)?.let { return it }
    return familyJoinUrl(added.server?.takeIf(String::isNotBlank) ?: server, added.username, code, added.home)
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
