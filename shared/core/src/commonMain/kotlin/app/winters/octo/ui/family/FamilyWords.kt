package app.winters.octo.ui.family

import app.winters.octo.subsonic.AddToLibrary
import app.winters.octo.subsonic.FamilyAbilities
import app.winters.octo.subsonic.FamilyDevice
import app.winters.octo.subsonic.FamilyDeviceKind
import app.winters.octo.subsonic.FamilyMe
import app.winters.octo.subsonic.FamilyMember
import app.winters.octo.subsonic.FamilyPlace
import app.winters.octo.subsonic.FamilyRequest
import app.winters.octo.subsonic.FamilyRequestKind
import app.winters.octo.subsonic.FamilyRequestOutcome
import app.winters.octo.subsonic.FamilyRequestState
import app.winters.octo.subsonic.FamilyRole
import app.winters.octo.subsonic.RequestQuality
import java.util.Locale

// The family's words, the same on the phone and the desktop.
const val FAMILY = "Family"
const val MY_PLAN = "My plan"
const val SAVED = "Saved"
const val REQUESTS = "Requests"
const val DEVICES = "Devices"
const val MEMBERS = "Members"
const val REQUESTS_WAITING = "Requests waiting"
const val REQUEST_A_COPY = "Request a copy"
const val SAVE = "Save"
const val REMOVE_FROM_MY_LIBRARY = "Remove from my library"
const val JOIN_WITH_A_FAMILY_CODE = "Join with a family code"
const val OFFLINE_COPIES_OFF = "Offline copies are off for this account"

fun roleLabel(role: FamilyRole): String = when (role) {
    FamilyRole.Owner -> "Owner"
    FamilyRole.CoAdmin -> "Co-admin"
    FamilyRole.Member -> "Member"
    FamilyRole.Listener -> "Listener"
    FamilyRole.Kid -> "Kid"
    FamilyRole.Unmanaged -> "Not in the family"
}

fun qualityLabel(quality: RequestQuality): String = when (quality) {
    RequestQuality.Best -> "Best"
    RequestQuality.Flac -> "FLAC"
    RequestQuality.Mp3 -> "MP3"
}

// What each quality means, under its name in the request sheet.
fun qualityDetail(quality: RequestQuality): String = when (quality) {
    RequestQuality.Best -> "The best copy Octo can find"
    RequestQuality.Flac -> "Lossless FLAC only"
    RequestQuality.Mp3 -> "A smaller MP3"
}

// One thing an account can or cannot do, in plain words. `on` is false for
// what is held back, so a screen can draw it quieter.
data class AbilityLine(val text: String, val on: Boolean = true)

// Everything the plan lets this account do, in the order a person cares
// about: adding music, listening, devices, then the rest.
fun abilityLines(can: FamilyAbilities): List<AbilityLine> = buildList {
    add(
        when (can.addToLibrary) {
            AddToLibrary.Direct -> AbilityLine("Songs you add go straight into your library")
            AddToLibrary.Request -> AbilityLine("Songs you add are saved. Request a copy to keep one in your library")
            AddToLibrary.SaveOnly -> AbilityLine("Songs you add are saved and play from the internet")
        },
    )
    if (can.addToLibrary == AddToLibrary.Request) {
        add(
            AbilityLine(
                when (can.requestQuality) {
                    RequestQuality.Best -> "Copies in any quality, up to the best"
                    RequestQuality.Flac -> "Copies in FLAC or MP3"
                    RequestQuality.Mp3 -> "Copies in MP3"
                },
            ),
        )
        add(AbilityLine(if (can.autoApprove) "Your requests are approved right away" else "Your requests wait for approval"))
        add(AbilityLine(if (can.weeklyRequestLimit > 0) "${plural(can.weeklyRequestLimit, "request")} a week" else "No limit on requests"))
    }
    if (can.instantFromFamily) add(AbilityLine("Songs the family already has are added right away"))
    add(AbilityLine(if (can.storageLimitGb > 0) "Room for ${can.storageLimitGb} GB in your library" else "No limit on your library's size"))
    add(AbilityLine(if (can.streamCap > 0) "Plays at up to ${can.streamCap} kbps" else "Plays in the original quality"))
    when {
        !can.away -> add(AbilityLine("Listening away from home is off", on = false))
        can.awayCap > 0 -> add(AbilityLine("Away from home, plays at up to ${can.awayCap} kbps"))
        else -> add(AbilityLine("Listens away from home too"))
    }
    add(AbilityLine(if (can.devicesAtOnce > 0) "Plays on ${plural(can.devicesAtOnce, "device")} at once" else "Plays on any number of devices at once"))
    add(AbilityLine(if (can.offlineCopies) "Can keep songs on a device to play offline" else "Offline copies are off", can.offlineCopies))
    add(AbilityLine(if (can.downloadFiles) "Can download song files" else "Cannot download song files", can.downloadFiles))
    add(AbilityLine(if (can.share) "Can share links to songs" else "Cannot share links to songs", can.share))
    add(AbilityLine(if (can.importPlaylists) "Can import playlists" else "Cannot import playlists", can.importPlaylists))
    add(AbilityLine(if (can.familyPlaylistsEdit) "Can add to family playlists" else "Can listen to family playlists", can.familyPlaylistsEdit))
    if (can.cleanOnly) add(AbilityLine("Clean songs only"))
    if (can.approveRequests) add(AbilityLine("Approves the family's requests"))
    if (can.manageFamily) add(AbilityLine("Manages the family"))
}

// "5.4 GB": a size in decimal gigabytes, one place after the point below 100.
fun gigabytes(bytes: Long): String {
    val gb = bytes / 1_000_000_000.0
    return when {
        bytes <= 0 -> "0 GB"
        gb < 0.1 -> "${String.format(Locale.US, "%.0f", bytes / 1_000_000.0)} MB"
        gb < 100 -> "${trimZero(String.format(Locale.US, "%.1f", gb))} GB"
        else -> "${String.format(Locale.US, "%.0f", gb)} GB"
    }
}

private fun trimZero(text: String) = text.removeSuffix(".0")

// "5.4 of 10 GB used", or with no limit "5.4 GB used".
fun storageLine(usedBytes: Long, limitGb: Int): String =
    if (limitGb > 0) "${usedOf(usedBytes, limitGb)} used" else "${gigabytes(usedBytes)} used"

// "5.4 of 10 GB": the part before "used", also for the full library line.
fun usedOf(usedBytes: Long, limitGb: Int): String {
    val used = gigabytes(usedBytes)
    return if (used.endsWith(" GB")) "${used.removeSuffix(" GB")} of $limitGb GB" else "$used of $limitGb GB"
}

// What the request sheet says when the library is full: requests still
// go, and an approver can still let them in.
fun storageFullLine(usedBytes: Long, limitGb: Int): String = "Your library is full (${usedOf(usedBytes, limitGb)})"

// How full the library is, 0 to 1, or null with no limit.
fun storageShare(usedBytes: Long, limitGb: Int): Float? =
    if (limitGb <= 0) null else (usedBytes / (limitGb * 1_000_000_000.0)).coerceIn(0.0, 1.0).toFloat()

fun storageFull(usedBytes: Long, limitGb: Int): Boolean = limitGb > 0 && usedBytes >= limitGb * 1_000_000_000L

// "3 of 10 requests used this week", or with no limit "3 requests this week".
fun weeklyLine(used: Int, limit: Int): String =
    if (limit > 0) "$used of ${plural(limit, "request")} used this week" else "${plural(used, "request")} this week"

// What became of a request, in a few words.
fun requestStateLine(request: FamilyRequest): String = when (request.state) {
    FamilyRequestState.Pending -> "Waiting for approval"
    FamilyRequestState.Approved -> "Approved, getting it now"
    FamilyRequestState.Declined -> "Declined"
    FamilyRequestState.Cancelled -> "Cancelled"
    FamilyRequestState.Failed -> request.failure?.takeIf(String::isNotBlank)?.let { "Failed: $it" } ?: "Failed"
    FamilyRequestState.Done -> outcomeLine(request.outcome)
}

fun outcomeLine(outcome: FamilyRequestOutcome?): String = when (outcome) {
    FamilyRequestOutcome.AlreadyShared -> "Already in the family library"
    FamilyRequestOutcome.AddedFromFamily -> "Added from the family library"
    FamilyRequestOutcome.Downloaded -> "Downloaded"
    null -> "In your library"
}

// What a request asks for: "Song", "Album" and so on, with its quality.
fun requestKindLine(request: FamilyRequest): String {
    val kind = when (request.kind) {
        FamilyRequestKind.Song -> "Song"
        FamilyRequestKind.Album -> "Album"
        FamilyRequestKind.Upgrade -> "Better copy"
        FamilyRequestKind.Import -> "Imported list"
        FamilyRequestKind.PlaylistAdd -> "Family playlist"
    }
    return "$kind · ${qualityLabel(request.quality)}"
}

// The request's title and artist as one line.
fun requestTitle(request: FamilyRequest): String =
    listOf(request.title, request.artist).filter(String::isNotBlank).joinToString(" · ").ifEmpty { request.target }

fun deviceKindLabel(kind: FamilyDeviceKind): String = when (kind) {
    FamilyDeviceKind.OctoApp -> "Octo app"
    FamilyDeviceKind.SubsonicApp -> "Other music app"
    FamilyDeviceKind.NavidromeWeb -> "Web player"
    FamilyDeviceKind.Detected -> "Seen by the server"
}

// A device's second line: the app, home or away, and what it plays.
fun deviceLine(device: FamilyDevice): String = buildList {
    add(device.app.ifBlank { deviceKindLabel(device.kind) })
    add(if (device.place == FamilyPlace.Away) "Away" else "Home")
    device.playing?.let { playing -> add("Playing ${listOf(playing.title, playing.artist).filter(String::isNotBlank).joinToString(" by ")}") }
}.joinToString(" · ")

// A member's line for a manager: role, devices, playing, waiting requests.
fun memberLine(member: FamilyMember): String = buildList {
    add(roleLabel(member.role))
    if (member.suspended) add("Paused")
    add(plural(member.devices, "device"))
    if (member.playingNow) add("Playing now")
    if (member.pendingRequests > 0) add("${plural(member.pendingRequests, "request")} waiting")
}.joinToString(" · ")

// "Alex, Listener": who this plan is for.
fun planTitle(me: FamilyMe): String = "${me.displayName.ifBlank { me.username }}, ${roleLabel(me.role)}"

fun plural(count: Int, word: String): String = if (count == 1) "1 $word" else "$count ${word}s"
