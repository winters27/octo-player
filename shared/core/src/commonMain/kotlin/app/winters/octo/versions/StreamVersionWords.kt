package app.winters.octo.versions

import app.winters.octo.subsonic.MISSED_YOUTUBE
import app.winters.octo.subsonic.MISSED_YOUTUBE_MUSIC
import app.winters.octo.subsonic.PICKED_EARLIER
import app.winters.octo.subsonic.PICKED_PERSON
import app.winters.octo.subsonic.PLAYS_FROM_DOWNLOAD
import app.winters.octo.subsonic.PLAYS_FROM_FILE
import app.winters.octo.subsonic.StreamVersionPlaying
import app.winters.octo.subsonic.StreamVersions
import app.winters.octo.subsonic.VERSION_KIND_MUSIC_VIDEO
import app.winters.octo.subsonic.VERSION_KIND_RELEASE
import app.winters.octo.subsonic.VERSION_KIND_VIDEO

// Every word the apps say about an outside song's versions: the player
// menu's row and the Choose the version sheet. The iPhone app says the same;
// both are checked against fixtures/stream-versions-words.json. A streamed
// outside song is the song itself, so no word calls it anything else.
object StreamVersionWords {
    const val MENU_ROW = "Try another version"
    const val TITLE = "Choose the version"
    const val DONE = "Done"
    const val PLAYING_NOW = "Playing now"
    const val OTHER_VERSIONS = "Other versions"
    const val YOUR_PICK = "Your pick"
    const val PICKED_BY_SOMEONE = "Picked by someone on this server"
    const val PICK_FROM_BEFORE = "A pick from before"
    const val OCTOS_PICK = "Octo's pick"
    const val NEXT_START = "Plays from your next start"
    const val KIND_RELEASE = "Official audio"
    const val KIND_MUSIC_VIDEO = "Music video"
    const val KIND_VIDEO = "Video"
    const val LOOKING = "Looking on YouTube"
    const val COULD_NOT_REACH = "Could not reach YouTube just now."
    const val SERVER_UNREACHABLE = "Could not reach your server just now."
    const val TRY_AGAIN = "Try again"
    const val NOTHING = "No other versions found."
    const val MISSED_YOUTUBE_MUSIC_WORDS = "YouTube Music could not answer this time, so this list may be short."
    const val MISSED_YOUTUBE_WORDS = "YouTube could not answer this time, so this list may be short."
    const val CHECKING = "Checking"
    const val PLAYS_FROM_DOWNLOAD_WORDS =
        "This server adds every song it plays to the library, so a song's version can't be chosen here."
    const val PLAYS_FROM_FILE_WORDS = "This song plays from a file in your library, so its version can't be chosen here."
    const val BUSY = "This song is being added to your library right now. You can choose its version once that finishes."
    const val NOTE = "The version you choose plays for everyone on this server, on every device."
    const val GO_BACK = "Go back to Octo's pick"
    const val MORE_BELOW = "More below"
    const val PICK_UNREACHABLE = "Could not reach your server. Try again."

    // Between the parts of a row's detail line.
    private const val DOT = " · "

    fun pickedBy(name: String): String = "Picked by $name"

    // The sheet's muted second line: the song and its artist.
    fun songLine(title: String, artist: String): String = if (artist.isBlank()) title else "$title by $artist"

    // What a video is, in words; null for a kind the app does not know.
    fun kind(kind: String?): String? = when (kind) {
        VERSION_KIND_RELEASE -> KIND_RELEASE
        VERSION_KIND_MUSIC_VIDEO -> KIND_MUSIC_VIDEO
        VERSION_KIND_VIDEO -> KIND_VIDEO
        else -> null
    }

    // A video's length as m:ss.
    fun length(seconds: Int): String = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

    // A row's line under its title: channel, length and kind, each only
    // when known.
    fun rowDetail(channel: String?, seconds: Int?, kind: String?): String =
        listOfNotNull(
            channel?.trim()?.takeIf(String::isNotEmpty),
            seconds?.takeIf { it >= 0 }?.let(::length),
            kind(kind),
        ).joinToString(DOT)

    // Who picked the video playing, and when the caller last heard another,
    // that this one plays from their next start.
    fun playingNote(playing: StreamVersionPlaying, heardVideoId: String?): String {
        val by = playing.pickedBy?.trim()
        val who = when (playing.picked) {
            PICKED_PERSON -> when {
                playing.mine -> YOUR_PICK
                !by.isNullOrEmpty() -> pickedBy(by)
                else -> PICKED_BY_SOMEONE
            }
            PICKED_EARLIER -> PICK_FROM_BEFORE
            else -> OCTOS_PICK
        }
        return if (heardVideoId != null && heardVideoId != playing.videoId) "$who$DOT$NEXT_START" else who
    }

    // The line above the list when it holds no choices or may be short: the
    // download modes, a file, the caller's own download running, a source
    // that did not answer. Null when the list is whole.
    fun notice(answer: StreamVersions): String? = when {
        answer.playsFrom == PLAYS_FROM_DOWNLOAD -> PLAYS_FROM_DOWNLOAD_WORDS
        answer.playsFrom == PLAYS_FROM_FILE -> PLAYS_FROM_FILE_WORDS
        answer.busy -> BUSY
        MISSED_YOUTUBE_MUSIC in answer.missed -> MISSED_YOUTUBE_MUSIC_WORDS
        MISSED_YOUTUBE in answer.missed -> MISSED_YOUTUBE_WORDS
        else -> null
    }

    // The line a sheet shows for where it stands: looking, no answer, the
    // server's refusal, or the notice above the list.
    fun stateLine(state: StreamVersionsSheetState): String? = when (state) {
        StreamVersionsSheetState.Closed -> null
        is StreamVersionsSheetState.Looking -> LOOKING
        is StreamVersionsSheetState.CouldNotReach -> if (state.server) SERVER_UNREACHABLE else COULD_NOT_REACH
        is StreamVersionsSheetState.Unavailable -> state.message
        is StreamVersionsSheetState.Shown -> state.notice
    }
}
