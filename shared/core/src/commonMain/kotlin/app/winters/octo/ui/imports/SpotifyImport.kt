package app.winters.octo.ui.imports

import app.winters.octo.subsonic.ImportActions
import app.winters.octo.subsonic.ImportAnswer
import app.winters.octo.subsonic.ImportListSummary
import app.winters.octo.subsonic.ImportOverview
import app.winters.octo.subsonic.ImportSource
import app.winters.octo.subsonic.ImportTrackState
import app.winters.octo.subsonic.LoopbackCallback
import app.winters.octo.subsonic.SpotifyStatus
import app.winters.octo.subsonic.SubsonicClient
import app.winters.octo.subsonic.TrickleState
import app.winters.octo.subsonic.TrickleStatus
import app.winters.octo.ui.upgrade.songsText
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// Spotify import as the phone and the desktop both show it: the words for each
// state, what a list's line says, and the Spotify sign-in, which runs the same
// way on both. The server does the reading, matching and fetching; the apps
// draw what it says and pass on what was tapped.

// How often to ask how things are going while a list is being read or songs
// are on their way, and otherwise while the view is open.
const val IMPORTS_BUSY_POLL_MS = 3_000L
const val IMPORTS_IDLE_POLL_MS = 20_000L

// Whether anything is moving on the server, so the view asks again soon.
fun ImportOverview.moving(): Boolean = reading.busy || trickle.queued > 0 || trickle.downloading > 0

const val SPOTIFY_IMPORT = "Spotify import"
const val KEEP_AS_PLAYLIST = "Keep as playlist"
const val GET_MISSING_SONGS = "Get missing songs"

// One song's state, in the words the dashboard uses too.
fun ImportTrackState.label(): String = when (this) {
    ImportTrackState.Have -> "In your library"
    ImportTrackState.Missing -> "Missing"
    ImportTrackState.Queued -> "Queued"
    ImportTrackState.Downloading -> "Fetching"
    ImportTrackState.Done -> "Fetched"
    ImportTrackState.NotFound -> "Not found"
    ImportTrackState.Skipped -> "Skipped"
    ImportTrackState.Unknown -> "Unknown"
}

// Where a list came from, and who made it when someone else did.
fun ImportListSummary.originLine(): String = listOfNotNull(
    when (origin) {
        ImportSource.SpotifyLiked, ImportSource.SpotifyPlaylist -> "Spotify"
        ImportSource.Link -> "Link"
        ImportSource.File -> "File"
        ImportSource.Unknown -> null
    },
    by?.let { "by $it" },
    if (gone) "no longer on your Spotify" else null,
).joinToString(" · ")

// "6 of 10 · 2 missing · 1 coming · 1 not found".
fun ImportListSummary.countsLine(): String = listOfNotNull(
    "$have of $total",
    if (missing > 0) "$missing missing" else null,
    if (coming > 0) "$coming coming" else null,
    if (notFound > 0) "$notFound not found" else null,
).joinToString(" · ")

// The account's line: who is connected and until when, or what to do first.
fun SpotifyStatus.line(zone: ZoneId = ZoneId.systemDefault()): String = when {
    !configured -> "Add your Spotify app's Client ID on the Octo dashboard first, on its Spotify import page."
    redirectProblem != null -> "The redirect address on the server will not work: $redirectProblem"
    problem != null -> problem.orEmpty()
    connected -> endsUtc?.let { "Spotify ends this sign-in on ${day(it, zone)}; connect again then." } ?: "Connected."
    else -> "Connect opens Spotify in your browser to let Octo read your library."
}

fun SpotifyStatus.title(): String = when {
    connected -> "Connected as ${account ?: "your account"}"
    problem != null -> "Sign in again"
    else -> "Not connected"
}

// What the trickle is doing, as a heading.
fun TrickleStatus.title(): String = when (stage) {
    TrickleState.Idle, TrickleState.Unknown -> "Nothing to fetch"
    TrickleState.Running -> if (downloading > 0) "Fetching" else "Waiting for the next turn"
    TrickleState.Paused -> "Paused"
    TrickleState.Off -> "Off"
    TrickleState.Yielding -> "Waiting for other downloads"
    TrickleState.WaitingForSoulseek -> "Waiting for Soulseek"
}

// The trickle's line under it: the pace, when the next song starts, and the counts.
fun TrickleStatus.line(zone: ZoneId = ZoneId.systemDefault()): String {
    val parts = ArrayList<String>()
    if (stage == TrickleState.Off) parts += "Songs an hour is 0 on the server, so nothing starts."
    else parts += "Up to ${songsText(perHour)} an hour."
    if (stage == TrickleState.Running && downloading == 0) nextUtc?.let { parts += "Next song at ${time(it, zone)}." }
    if (stage == TrickleState.Yielding) parts += "Someone's own downloads come first."
    if (stage == TrickleState.WaitingForSoulseek) parts += "It goes on once Soulseek is back."
    val tally = listOf(queued to "waiting", downloading to "fetching", done to "fetched", notFound to "not found", skipped to "skipped")
        .filter { it.first > 0 }.joinToString(", ") { "${it.first} ${it.second}" }
    if (tally.isNotEmpty()) parts += "$tally."
    return parts.joinToString(" ")
}

private fun day(utc: String, zone: ZoneId): String = runCatching {
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(Instant.parse(utc).atZone(zone))
}.getOrDefault(utc)

private fun time(utc: String, zone: ZoneId): String = runCatching {
    DateTimeFormatter.ofPattern("HH:mm").format(Instant.parse(utc).atZone(zone))
}.getOrDefault(utc)

// ---- Signing in ------------------------------------------------------------------------------

// The Spotify sign-in from an app. Spotify sends the browser back to a page on
// this device (a loopback address with a free port), which catches its answer,
// and the server, which holds the PKCE secret, finishes it. A server whose
// redirect is its own https address finishes it by itself, and the app only
// opens the page. `open` shows the address in the browser.
suspend fun signInToSpotify(client: SubsonicClient, open: (String) -> Unit, timeoutMs: Long = 10 * 60_000L): ImportAnswer {
    val spotify = client.imports().spotify
    if (!spotify.configured || spotify.redirectProblem != null) return ImportAnswer(false, spotify.line())
    if (spotify.octoFinishes) {
        val start = client.importAction(ImportActions.CONNECT)
        start.url?.let(open)
        return if (start.ok) ImportAnswer(true, "Allow Octo in your browser. This page notices on its own when that is done.") else start
    }
    val catcher = LoopbackCallback.open(spotify.redirectUri)
        ?: return ImportAnswer(
            false,
            "The apps sign in with the redirect http://127.0.0.1/callback, with no port. Set that on the Spotify app and on the Octo dashboard, or connect from the dashboard.",
        )
    return catcher.use {
        val start = client.importAction(ImportActions.CONNECT, mapOf("redirect" to catcher.redirectUri))
        val url = start.url
        if (!start.ok || url == null) return@use start
        open(url)
        val back = catcher.await(timeoutMs) ?: return@use ImportAnswer(false, "Spotify did not answer. Connect again when you are ready.")
        when {
            back.error == "access_denied" -> ImportAnswer(false, "Spotify says access was not allowed.")
            back.error != null -> ImportAnswer(false, "Spotify sent back an error: ${back.error}")
            else -> client.importAction(ImportActions.FINISH, mapOf("code" to back.code.orEmpty(), "state" to back.state.orEmpty()))
        }
    }
}
