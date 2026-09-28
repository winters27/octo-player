package app.winters.octo.desktop.discord

import app.winters.octo.desktop.system.NowPlaying
import app.winters.octo.desktop.system.isOpenedFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

// Showing what plays in the listener's Discord status. Off until they turn
// it on; songs opened from a file on this computer stay out of it unless
// they say so too.
@Serializable
data class DiscordPrefs(
    val on: Boolean = false,
    val openedFiles: Boolean = false,
)

// What Octo puts in the Discord status: the song, its artist and album,
// and when it started and will end (Discord draws the time bar from those,
// so nothing needs sending while it plays on).
data class DiscordActivity(
    val song: String,
    val artist: String?,
    val album: String?,
    val startMs: Long,
    val endMs: Long?,
) {
    // Whether this shows the same as `other`: the same words, and times
    // no further apart than a moment of drift. A seek moves them further.
    fun looksLike(other: DiscordActivity?): Boolean =
        other != null &&
            song == other.song &&
            artist == other.artist &&
            album == other.album &&
            kotlin.math.abs(startMs - other.startMs) <= TIME_SLACK_MS &&
            (endMs == null) == (other.endMs == null) &&
            kotlin.math.abs((endMs ?: 0) - (other.endMs ?: 0)) <= TIME_SLACK_MS
}

// How far the shown times may drift from the player's before they are sent again.
const val TIME_SLACK_MS = 2_000L

// The picture shown beside the song: Octo's icon, uploaded to the Discord
// application under this name. Never a cover from the listener's server,
// since its address would carry the sign-in.
const val DISCORD_ICON = "octo"

// Discord takes words of 2 to 128 characters.
private const val TEXT_MIN = 2
private const val TEXT_MAX = 128

// What to show for the song playing, or null to show nothing: nothing
// while paused or with nothing in, and nothing for a file opened from this
// computer unless the listener allowed it. The times follow the playing
// speed, so the bar ends when the song does.
fun discordActivityFor(now: NowPlaying?, positionMs: Long, clockMs: Long, prefs: DiscordPrefs): DiscordActivity? {
    if (!prefs.on || now == null || !now.playing) return null
    if (isOpenedFile(now.songId) && !prefs.openedFiles) return null
    val speed = now.speed.takeIf { it.isFinite() && it > 0f } ?: 1f
    val length = now.durationMs.takeIf { it > 0 }
    val played = positionMs.coerceIn(0, length ?: Long.MAX_VALUE)
    val start = clockMs - (played / speed).toLong()
    return DiscordActivity(
        song = discordText(now.title) ?: "Unknown song",
        artist = discordText(now.artist),
        album = discordText(now.album),
        startMs = start,
        endMs = length?.let { start + (it / speed).toLong() },
    )
}

// Words made fit for Discord: trimmed, cut to its limit without splitting
// a character, and a single character padded with a blank it keeps.
// Nothing for blank words.
fun discordText(text: String?): String? {
    val trimmed = text?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    if (trimmed.length > TEXT_MAX) {
        var end = TEXT_MAX - 1
        if (Character.isHighSurrogate(trimmed[end - 1])) end--
        return trimmed.substring(0, end).trimEnd() + "…"
    }
    return if (trimmed.length < TEXT_MIN) trimmed + "⠀" else trimmed
}

// The activity as Discord reads it: "Listening to" the song's name, the
// artist under it, the album on the picture.
fun DiscordActivity.toJson(): JsonObject = buildJsonObject {
    put("type", LISTENING)
    put("status_display_type", SHOW_DETAILS)
    put("details", song)
    artist?.let { put("state", it) }
    putJsonObject("timestamps") {
        put("start", startMs)
        endMs?.let { put("end", it) }
    }
    putJsonObject("assets") {
        put("large_image", DISCORD_ICON)
        album?.let { put("large_text", it) }
    }
}

// The command that sets the status, or clears it with no activity.
fun setActivityCommand(activity: DiscordActivity?, pid: Long, nonce: String): JsonObject = buildJsonObject {
    put("cmd", "SET_ACTIVITY")
    putJsonObject("args") {
        put("pid", pid)
        put("activity", activity?.toJson() ?: JsonNull)
    }
    put("nonce", nonce)
}

// Discord's numbers for "Listening to", and for naming the details (the
// song) after it in the member list rather than the app.
private const val LISTENING = 2
private const val SHOW_DETAILS = 2

// How long Octo waits before trying Discord again: soon at first, then
// less often, never longer than a minute, so a Discord opened later is
// found within a minute. A working connection starts it over.
class Backoff(private val steps: List<Long> = RETRY_STEPS_MS) {
    private var tries = 0

    fun next(): Long = steps[minOf(tries, steps.lastIndex)].also { tries++ }

    fun reset() {
        tries = 0
    }
}

val RETRY_STEPS_MS = listOf(2_000L, 5_000L, 10_000L, 20_000L, 40_000L, 60_000L)
