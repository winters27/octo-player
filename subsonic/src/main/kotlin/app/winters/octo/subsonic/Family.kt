package app.winters.octo.subsonic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

// The OpenSubsonic extension an Octo server lists while Family is on: one
// owner, members with their own libraries and devices, and the abilities
// the owner gives each of them. Listed only while the owner has it on.
const val OCTO_FAMILY = "octoFamily"

// Who an account is in the family. Owner and CoAdmin manage it; Unmanaged
// is an account the family rules do not touch.
@Serializable
enum class FamilyRole { Owner, CoAdmin, Member, Listener, Kid, Unmanaged }

// What adding an outside song does for this account: adds it straight to
// their library, asks for a copy, or only saves a link that plays from
// the source.
@Serializable
enum class AddToLibrary { Direct, Request, SaveOnly }

// The quality a copy is asked in. Best takes the best copy the server
// finds; the order here is lowest first, so a limit can be compared.
@Serializable
enum class RequestQuality {
    Mp3,
    Flac,
    Best,
    ;

    // Whether asking in this quality is within a limit of `max`.
    fun allowedUnder(max: RequestQuality) = ordinal <= max.ordinal
}

// Where a device listens from: on the home network or away from it.
@Serializable
enum class FamilyPlace { Home, Away }

@Serializable
enum class FamilyRequestKind { Song, Album, Upgrade, Import, PlaylistAdd }

@Serializable
enum class FamilyRequestState { Pending, Approved, Declined, Cancelled, Done, Failed }

// How a request that is Done was met: a new download, a song the shared
// library already had, or a copy another member already had on the
// server, added without a second download.
@Serializable
enum class FamilyRequestOutcome { Downloaded, AlreadyShared, AddedFromFamily }

@Serializable
enum class FamilyDeviceKind { OctoApp, SubsonicApp, NavidromeWeb, Detected }

// The platform a paired Octo app runs on, as the pairing call names it.
enum class FamilyPlatform(val wire: String) {
    Android("Android"),
    Windows("Windows"),
    MacOs("macOS"),
    Linux("Linux"),
    Ios("iOS"),
}

// What the owner lets an account do. A limit of 0 means no limit, and a
// stream cap of 0 means the original file.
@Serializable
data class FamilyAbilities(
    val addToLibrary: AddToLibrary = AddToLibrary.Direct,
    val requestQuality: RequestQuality = RequestQuality.Best,
    val autoApprove: Boolean = false,
    val weeklyRequestLimit: Int = 0,
    // In kbps; 0 is the original.
    val streamCap: Int = 0,
    val awayCap: Int = 0,
    val away: Boolean = true,
    val devicesAtOnce: Int = 0,
    val downloadFiles: Boolean = true,
    val offlineCopies: Boolean = true,
    val share: Boolean = true,
    val importPlaylists: Boolean = true,
    val cleanOnly: Boolean = false,
    val familyPlaylistsEdit: Boolean = true,
    val manageFamily: Boolean = false,
    val approveRequests: Boolean = false,
    val storageLimitGb: Int = 0,
    val instantFromFamily: Boolean = true,
)

// The signed-in account as the family sees it.
@Serializable
data class FamilyMe(
    val username: String = "",
    val displayName: String = "",
    val role: FamilyRole = FamilyRole.Unmanaged,
    val managed: Boolean = false,
    val abilities: FamilyAbilities = FamilyAbilities(),
    val requestsThisWeek: Int = 0,
    val storageUsedBytes: Long = 0,
    val place: FamilyPlace = FamilyPlace.Home,
    // Null for the owner and for unmanaged accounts.
    val deviceId: String? = null,
)

// One member, as a manager sees them.
@Serializable
data class FamilyMember(
    val username: String = "",
    val displayName: String = "",
    val role: FamilyRole = FamilyRole.Member,
    val suspended: Boolean = false,
    val devices: Int = 0,
    val playingNow: Boolean = false,
    val pendingRequests: Int = 0,
    val storageUsedBytes: Long = 0,
    val storageLimitGb: Int = 0,
)

// What a manager sees of the whole family.
@Serializable
data class FamilyManager(
    val members: List<FamilyMember> = emptyList(),
    val pendingRequests: Int = 0,
    val liveStreams: Int = 0,
)

// getFamily's answer. `manager` is there only for accounts that manage
// the family.
@Serializable
data class FamilyInfo(
    val me: FamilyMe = FamilyMe(),
    val manager: FamilyManager? = null,
)

// One request for a copy: a song, an album, a better copy of a library
// song, an import list or a family playlist addition.
@Serializable
data class FamilyRequest(
    val id: String = "",
    val username: String = "",
    val displayName: String = "",
    val kind: FamilyRequestKind = FamilyRequestKind.Song,
    val target: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val coverArt: String? = null,
    val quality: RequestQuality = RequestQuality.Best,
    val state: FamilyRequestState = FamilyRequestState.Pending,
    val created: String = "",
    val decided: String? = null,
    val decidedBy: String? = null,
    val note: String = "",
    val failure: String? = null,
    val librarySongId: String? = null,
    // Set once it is Done.
    val outcome: FamilyRequestOutcome? = null,
)

@Serializable
internal data class FamilyRequests(val request: List<FamilyRequest> = emptyList())

// What a device is playing now.
@Serializable
data class FamilyDevicePlaying(
    val songId: String = "",
    val title: String = "",
    val artist: String = "",
)

// One signed-in device: an Octo app, another Subsonic app, the web player,
// or one the server noticed by itself.
@Serializable
data class FamilyDevice(
    val id: String = "",
    val username: String = "",
    val name: String = "",
    val kind: FamilyDeviceKind = FamilyDeviceKind.Detected,
    val app: String = "",
    val created: String = "",
    val lastSeen: String = "",
    val place: FamilyPlace = FamilyPlace.Home,
    // Null while idle.
    val playing: FamilyDevicePlaying? = null,
    // The device asking.
    val current: Boolean = false,
)

@Serializable
internal data class FamilyDevices(val device: List<FamilyDevice> = emptyList())

// A device added by hand. An Octo app gets a 6 digit pair code that
// expires; any other Subsonic app gets an app password, shown only once.
@Serializable
data class FamilyDeviceAdded(
    val deviceId: String = "",
    val kind: FamilyDeviceKind = FamilyDeviceKind.SubsonicApp,
    val appPassword: String? = null,
    val pairCode: String? = null,
    val expires: String? = null,
    val server: String? = null,
    val username: String = "",
) {
    // Never print the password or the code, even by accident in a log.
    override fun toString() = "FamilyDeviceAdded(deviceId=$deviceId, kind=$kind, username=$username)"
}

// What pairing with a family code answers: the account and the secret
// this device signs in with from now on, as a password.
@Serializable
data class FamilyPair(
    val username: String = "",
    val secret: String = "",
    val deviceId: String = "",
    val server: String? = null,
) {
    override fun toString() = "FamilyPair(username=$username, deviceId=$deviceId, server=$server)"
}

// The signed-in account's place in the family, with the whole family for
// a manager.
suspend fun SubsonicClient.family(): FamilyInfo =
    get("getFamily", key = "family", serializer = FamilyInfo.serializer())

// The signed-in account's requests, or with `all` every member's (managers
// only), optionally only those in one state.
suspend fun SubsonicClient.familyRequests(all: Boolean = false, state: FamilyRequestState? = null): List<FamilyRequest> =
    get(
        "getFamilyRequests",
        buildMap {
            if (all) put("all", "true")
            state?.let { put("state", it.name) }
        },
        "familyRequests",
        FamilyRequests.serializer(),
        FamilyRequests(),
    ).request

// Asks for a copy of an outside song or album, or a better copy of a
// library song. A song the server already has comes back Done at once,
// with how it was met.
suspend fun SubsonicClient.requestCopy(
    id: String,
    quality: RequestQuality = RequestQuality.Best,
    kind: FamilyRequestKind? = null,
): FamilyRequest =
    get(
        "requestCopy",
        buildMap {
            put("id", id)
            put("quality", quality.name)
            kind?.let { put("kind", it.name) }
        },
        "familyRequest",
        FamilyRequest.serializer(),
    )

// Takes back one of the signed-in account's own requests while it waits.
suspend fun SubsonicClient.cancelFamilyRequest(id: String): FamilyRequest =
    get("cancelFamilyRequest", mapOf("id" to id), "familyRequest", FamilyRequest.serializer())

// Approves or declines a member's request, with a note for them.
suspend fun SubsonicClient.decideFamilyRequest(id: String, approve: Boolean, note: String? = null): FamilyRequest =
    get(
        "decideFamilyRequest",
        buildMap {
            put("id", id)
            put("approve", approve.toString())
            note?.trim()?.takeIf(String::isNotEmpty)?.let { put("note", it) }
        },
        "familyRequest",
        FamilyRequest.serializer(),
    )

// The signed-in account's devices, or with `all` every member's (managers
// only).
suspend fun SubsonicClient.familyDevices(all: Boolean = false): List<FamilyDevice> =
    get(
        "getFamilyDevices",
        if (all) mapOf("all" to "true") else emptyMap(),
        "familyDevices",
        FamilyDevices.serializer(),
        FamilyDevices(),
    ).device

suspend fun SubsonicClient.signOutFamilyDevice(id: String) = send("signOutFamilyDevice", listOf("id" to id))

// Adds a device by hand: an Octo app (a pair code) or another Subsonic app
// (an app password).
suspend fun SubsonicClient.addFamilyDevice(name: String, kind: FamilyDeviceKind): FamilyDeviceAdded =
    get(
        "addFamilyDevice",
        mapOf("name" to name.trim(), "kind" to kind.name),
        "familyDeviceAdded",
        FamilyDeviceAdded.serializer(),
    )

// Takes a song out of the signed-in member's own library. The server
// refuses songs of the shared library.
suspend fun SubsonicClient.removeFromMyLibrary(id: String) = send("removeFromMyLibrary", listOf("id" to id))

// Makes a family playlist every member can add to. `approveAdditions`
// (managers only) holds additions for a manager to approve.
suspend fun SubsonicClient.createFamilyPlaylist(name: String, approveAdditions: Boolean = false): PlaylistWithSongs =
    get(
        "createFamilyPlaylist",
        buildMap {
            put("name", name.trim())
            if (approveAdditions) put("approveAdditions", "true")
        },
        "playlist",
        PlaylistWithSongs.serializer(),
    )

// Pairs this device with a family code. The call is open: no sign-in goes
// with it, since the device has none yet. The answer's secret is the
// password this device signs in with from now on.
suspend fun pairWithFamilyCode(
    server: HttpUrl,
    http: OkHttpClient,
    username: String,
    code: String,
    deviceName: String,
    platform: FamilyPlatform,
    clientName: String = "Octo",
): FamilyPair {
    val url = server.newBuilder()
        .addPathSegment("rest")
        .addPathSegment("octoFamilyPair")
        .addQueryParameter("v", API_VERSION)
        .addQueryParameter("c", clientName)
        .addQueryParameter("f", "json")
        .addQueryParameter("username", username.trim())
        .addQueryParameter("code", code.filter(Char::isDigit))
        .addQueryParameter("deviceName", deviceName.trim())
        .addQueryParameter("platform", platform.wire)
        .build()
    val body = try {
        http.newCall(Request.Builder().url(url).build()).awaitBody("octoFamilyPair")
    } catch (e: IOException) {
        throw SubsonicException.Unreachable(e)
    }
    // A client only for reading the answer: it never makes a call.
    val reader = SubsonicClient(server, Credentials(username, ""), http)
    return withContext(Dispatchers.Default) { reader.decode(body, "familyPair", FamilyPair.serializer(), null) }
}

// A pair code is six digits; anything else is not one.
fun isFamilyCode(code: String): Boolean = code.length == 6 && code.all(Char::isDigit)

// What a pairing link (a QR code, or one pasted) carries:
// octo://join?server=<address>&username=<name>&code=<6 digits>.
data class FamilyJoinLink(val server: String, val username: String, val code: String) {
    override fun toString() = "FamilyJoinLink(server=$server, username=$username)"
}

// Reads a pairing link, or null when the text is not one. Spaces around it
// are ignored, so a pasted link works.
fun parseFamilyJoinLink(text: String): FamilyJoinLink? {
    val trimmed = text.trim()
    val prefix = "octo://join?"
    if (!trimmed.startsWith(prefix, ignoreCase = true)) return null
    val params = trimmed.substring(prefix.length).split('&').mapNotNull { part ->
        val at = part.indexOf('=')
        if (at <= 0) null else part.substring(0, at) to decodeQueryValue(part.substring(at + 1))
    }.toMap()
    val server = params["server"]?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val username = params["username"]?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val code = params["code"]?.trim()?.takeIf(::isFamilyCode) ?: return null
    return FamilyJoinLink(server, username, code)
}

private fun decodeQueryValue(value: String): String =
    runCatching { java.net.URLDecoder.decode(value, Charsets.UTF_8) }.getOrDefault(value)

private suspend fun okhttp3.Call.awaitBody(endpoint: String): String {
    val response = await()
    return withContext(Dispatchers.IO) {
        response.use {
            if (!it.isSuccessful) throw SubsonicException.NotSubsonic("HTTP ${it.code} from $endpoint", it.code)
            it.body.string()
        }
    }
}
