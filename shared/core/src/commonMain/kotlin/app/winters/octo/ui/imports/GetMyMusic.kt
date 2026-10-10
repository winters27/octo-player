package app.winters.octo.ui.imports

import app.winters.octo.subsonic.AddToLibrary
import app.winters.octo.subsonic.FamilyMe
import app.winters.octo.subsonic.FamilyRole
import app.winters.octo.subsonic.ImportServiceLink
import app.winters.octo.subsonic.SPOTIFY_SERVICE
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

// "Get my music" as the phone and the desktop both show it: a tile for each
// service, TuneMyMusic's export page opened in the browser for the one
// tapped, then a wait for the file it saves, which comes back to Octo by a
// picker, a drop, a share or a paste.

const val IMPORT = "Import"
const val GET_MY_MUSIC = "Get my music"
const val USE_DOWNLOADED_FILE = "Use the file I just downloaded"
const val USE_A_FILE = "Use a file"
const val PASTE_A_LIST = "Paste a list"
const val CONNECT_SPOTIFY_KEEPS_UPDATING = "Connect Spotify (keeps updating)"
const val PASTED_LIST_NAME = "Pasted list"

// What the grid says above its tiles.
const val GET_MY_MUSIC_LINE = "Pick where your music is now. Octo opens TuneMyMusic, which saves your lists to a file for Octo to read."

// The paste sheet's words.
const val PASTE_LINE = "One song a line, as Artist - Title."

// The file types a list can come in, by name ending.
val IMPORT_FILE_ENDINGS = listOf("txt", "csv", "zip", "json")

// Whether a file's name says it is a list Octo reads.
fun isImportFileName(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in IMPORT_FILE_ENDINGS

// Where a "Get my music" visit stands.
sealed interface ImportStep {
    // The grid of services.
    data object Choose : ImportStep

    // Spotify on a server that can sign in to it: keep it updating through
    // a sign-in, or use a file like the other services.
    data class SpotifyWays(val service: ImportServiceLink) : ImportStep

    // The export page is open in the browser; the file comes back here.
    // `service` is null when a file is used without a service picked.
    data class Waiting(val service: ImportServiceLink?) : ImportStep

    // A file or text on its way to the server.
    data class Sending(val service: ImportServiceLink?, val name: String) : ImportStep

    // The server read it. `message` is its own words; `approval` says who
    // approves downloads first, when someone does.
    data class Sent(val message: String, val approval: String?) : ImportStep
}

// The tile a step lights up, if any.
val ImportStep.service: ImportServiceLink?
    get() = when (this) {
        is ImportStep.SpotifyWays -> service
        is ImportStep.Waiting -> service
        is ImportStep.Sending -> service
        else -> null
    }

// The only export pages Octo opens: https on tunemymusic.com or
// www.tunemymusic.com, with no sign-in and the usual port. Answers the
// address as it will be opened, or null for anything else.
fun tuneMyMusicUrl(url: String): String? {
    val parsed = url.trim().toHttpUrlOrNull() ?: return null
    if (parsed.scheme != "https") return null
    if (parsed.host != "tunemymusic.com" && parsed.host != "www.tunemymusic.com") return null
    if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return null
    if (parsed.port != 443) return null
    return parsed.toString()
}

// What to do on TuneMyMusic for this service.
fun waitingLine(service: ImportServiceLink): String =
    "Tap the ${service.tile.ifBlank { service.name }} tile, sign in, choose your playlists or Liked songs, then Export to file as CSV."

// What to do with a file already saved, with no service picked.
const val WAITING_ANY_LINE = "Pick a list file saved from TuneMyMusic or another app: CSV, TXT, JSON or ZIP."

// The waiting step's heading.
fun waitingTitle(service: ImportServiceLink?): String =
    service?.let { "Waiting for your ${it.name} file" } ?: "Waiting for your file"

// Why an export page was not opened.
const val NOT_TUNEMYMUSIC = "That export link does not go to TuneMyMusic, so Octo will not open it."

const val FILE_TOO_BIG = "That file is over 64 MB, more than Octo takes."
const val FILE_EMPTY = "That file is empty."
const val NOT_A_LIST_FILE = "Octo reads lists from CSV, TXT, JSON or ZIP files."

// Whether a Spotify tile also offers the sign-in that keeps lists updating.
fun ImportServiceLink.offersSpotifySignIn(spotifyConnect: Boolean): Boolean = spotifyConnect && id == SPOTIFY_SERVICE

// Whether this family member's lists wait for approval before Octo fetches
// their missing songs: a member who asks for copies, whose asks are not
// approved on their own.
fun FamilyMe.listsNeedApproval(): Boolean =
    managed && role != FamilyRole.Owner && abilities.addToLibrary == AddToLibrary.Request && !abilities.autoApprove

// What a member reads once their lists are in and wait for approval.
fun approvalLine(owner: String?): String =
    "Your lists are in. ${owner?.trim()?.takeIf(String::isNotEmpty) ?: "The owner"} approves downloads before Octo fetches the missing songs."
