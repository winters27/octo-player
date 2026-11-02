package app.winters.octo.ui.imports

import app.winters.octo.subsonic.ImportListSummary
import app.winters.octo.subsonic.ImportOverview
import app.winters.octo.subsonic.ImportSource
import app.winters.octo.subsonic.ImportTrack
import app.winters.octo.subsonic.ImportTrackState
import app.winters.octo.subsonic.TrickleState
import app.winters.octo.subsonic.TrickleStatus
import app.winters.octo.ui.upgrade.songsText
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// Imports as the phone and the desktop both show them: the words for each
// state and what a list's line says. The server does the reading, matching
// and fetching; the apps draw what it says and pass on what was tapped.

// How often to ask how things are going while songs are on their way, and
// otherwise while the view is open.
const val IMPORTS_BUSY_POLL_MS = 3_000L
const val IMPORTS_IDLE_POLL_MS = 20_000L

// Whether anything is moving on the server, so the view asks again soon.
fun ImportOverview.moving(): Boolean = trickle.queued > 0 || trickle.downloading > 0

const val KEEP_AS_PLAYLIST = "Keep as playlist"
const val GET_MISSING_SONGS = "Get missing songs"

// One song's state, in the words the dashboard uses too.
fun ImportTrackState.label(): String = when (this) {
    ImportTrackState.Have -> "In your library"
    ImportTrackState.Missing -> "Missing"
    ImportTrackState.Queued -> "Waiting"
    ImportTrackState.Downloading -> "Fetching"
    ImportTrackState.Done -> "Fetched"
    ImportTrackState.NotFound -> "Not found"
    ImportTrackState.Skipped -> "Skipped"
    ImportTrackState.Unknown -> "Unknown"
}

// Where a list came from, who made it when someone else did, and that a
// list from a Spotify account sign-in no longer updates.
fun ImportListSummary.originLine(): String = listOfNotNull(
    when (origin) {
        ImportSource.SpotifyLiked, ImportSource.SpotifyPlaylist -> "Spotify"
        ImportSource.Link -> "Link"
        ImportSource.File -> "File"
        ImportSource.Unknown -> null
    },
    by?.let { "by $it" },
    if (origin.fromSignIn) "not updating" else null,
).joinToString(" · ")

// "6 of 10 · 2 missing · 1 coming · 1 not found".
// Each count is held to its words with no-break spaces, so a line never breaks inside one.
fun ImportListSummary.countsLine(): String = listOfNotNull(
    "$have\u00A0of\u00A0$total",
    if (missing > 0) "$missing\u00A0missing" else null,
    if (coming > 0) "$coming\u00A0coming" else null,
    if (notFound > 0) "$notFound\u00A0not\u00A0found" else null,
).joinToString(" · ")

// Removing a list is asked first: the question, and what goes and what
// stays. The server keeps the songs it fetched and the playlist it made.
const val REMOVE_LIST = "Remove this list?"

fun ImportListSummary.removeLine(): String = buildString {
    append("Octo forgets $name")
    if (getMissing) append(" and stops getting its missing songs")
    append(". Songs already in your library stay")
    if (playlistId != null) append(", and so does its playlist")
    append(".")
}

// What the trickle is doing, as a heading. A state this app does not know yet
// reads as the server's own words, or as work while songs wait, never as
// nothing to fetch.
fun TrickleStatus.title(): String = when (stage) {
    TrickleState.Idle -> "Nothing to fetch"
    TrickleState.Unknown -> label?.takeIf { it.isNotBlank() }
        ?: if (queued > 0 || downloading > 0) "Working on it" else "Nothing to fetch"
    TrickleState.Running -> if (downloading > 0) "Fetching" else "Waiting for the next turn"
    TrickleState.Paused -> "Paused"
    TrickleState.Off -> "Off"
    TrickleState.Yielding -> "Waiting for other downloads"
    TrickleState.WaitingForSoulseek -> "Waiting for Soulseek"
    TrickleState.WaitingForMusicDatabase -> "Waiting for MusicBrainz"
    TrickleState.WaitingForMornyeCheck -> "Waiting for Mornye's check"
    TrickleState.WaitingForMornye -> "Waiting for Mornye"
}

// The trickle's line under it: why it waits (when it does), the pace, when the next song starts, and the counts, each count held to its word and the counts kept together as one group.
fun TrickleStatus.line(zone: ZoneId = ZoneId.systemDefault()): String {
    val parts = ArrayList<String>()
    if (stage == TrickleState.Yielding) parts += "Someone's own downloads come first."
    if (stage == TrickleState.WaitingForSoulseek) parts += "It goes on once Soulseek is back."
    if (stage == TrickleState.WaitingForMornyeCheck) parts += "It goes on when Mornye may try its check again, or once it is passed on the dashboard."
    if (stage == TrickleState.WaitingForMornye) parts += "It goes on once Mornye can download."
    if (stage == TrickleState.WaitingForMusicDatabase) parts += "It goes on once MusicBrainz is back."
    if (stage == TrickleState.Off) parts += "Songs an hour is 0 on the server, so nothing starts."
    else parts += "Up to ${songsText(perHour).replace(' ', '\u00A0')} an hour."
    if (stage == TrickleState.Running && downloading == 0) nextUtc?.let { parts += "Next song at ${time(it, zone)}." }
    val tally = listOf(queued to "waiting", downloading to "fetching", done to "fetched", notFound to "not found", skipped to "skipped")
        .filter { it.first > 0 }.joinToString(",${Char(0xA0)}") { "${it.first}\u00A0${it.second.replace(' ', '\u00A0')}" }
    if (tally.isNotEmpty()) parts += "$tally."
    return parts.joinToString(" ")
}

// The next songs as shown: while every one waits for MusicBrainz the heading says so once, so
// their rows leave out the same words.
fun TrickleStatus.shownNext(): List<ImportTrack> =
    if (stage == TrickleState.WaitingForMusicDatabase) next.map { it.copy(detail = null) } else next

private fun time(utc: String, zone: ZoneId): String = runCatching {
    DateTimeFormatter.ofPattern("HH:mm").format(Instant.parse(utc).atZone(zone))
}.getOrDefault(utc)
