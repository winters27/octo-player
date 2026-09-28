package app.winters.octo.playback

// Why a song would not play, in the words both apps show.
enum class PlayFailure(val words: String) {
    Missing("That song isn't on the server any more."),
    Refused("The server wouldn't send that song."),
    Unreachable("Couldn't reach the server to play that song."),
    Unsupported("Octo can't play that kind of file."),
    Damaged("That song's file is damaged."),
    Device("The sound device stopped working. Pick another output."),
    Other("That song couldn't play."),
}

// The one line shown when a song is passed over, or when it was the last
// that could have played.
fun skippedLine(title: String, why: String) = "Skipped $title. $why"

fun stoppedLine(title: String, why: String) = "Couldn't play $title. $why"
