package app.winters.octo.subsonic

import kotlinx.serialization.Serializable

// The OpenSubsonic extension an Octo server lists while it looks lyrics up
// itself: every copy its sources hold for a song, and one choice between
// them that holds for the whole server, so every app and every user sees it.
const val OCTO_LYRICS = "octoLyrics"

// The server's choice for a song when nothing is pinned: it finds the
// lyrics itself.
const val LYRICS_AUTO = "auto"

// The server's choice for a song whose lyrics are hidden.
const val LYRICS_NONE = "none"

// Every copy of a song's lyrics the server's sources hold, and what the
// song is set to now: "auto", "none", or the id of the copy pinned.
@Serializable
data class LyricsCandidates(
    @Serializable(with = LooseString::class) val id: String = "",
    val choice: String = LYRICS_AUTO,
    val candidate: List<ServerLyricsCandidate> = emptyList(),
)

// One copy the server found. The id names its source before the colon
// ("kugou:123"), and is what a pin sends back.
@Serializable
data class ServerLyricsCandidate(
    val id: String = "",
    // Where it was found, as the server names it: "kugou", "lrclib" and so on.
    val source: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String? = null,
    // In seconds, when the source says.
    val duration: Int? = null,
    // "word", "line", "plain" or "instrumental".
    val kind: String = "",
    // Whether the server is sure it is this song, going by title, artist and length.
    val sameSong: Boolean = false,
    // Whether it is the copy pinned now.
    val chosen: Boolean = false,
    // Its first lines with words.
    val preview: List<String> = emptyList(),
)

// What setLyricsChoice answers: the song and what it is set to now.
@Serializable
internal data class LyricsChoiceAnswer(
    @Serializable(with = LooseString::class) val id: String = "",
    val choice: String = LYRICS_AUTO,
)

// What asking for a song's lyrics copies sends. A title or artist typed by
// hand searches for those instead of the song's own tags.
fun lyricsCandidatesParams(id: String, title: String?, artist: String?): Map<String, String> = buildMap {
    put("id", id)
    title?.trim()?.takeIf(String::isNotEmpty)?.let { put("title", it) }
    artist?.trim()?.takeIf(String::isNotEmpty)?.let { put("artist", it) }
}
