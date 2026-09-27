package app.winters.octo.lyrics

import app.winters.octo.subsonic.LYRICS_AUTO
import app.winters.octo.subsonic.LYRICS_NONE
import app.winters.octo.subsonic.LyricsCandidates
import app.winters.octo.subsonic.OCTO_LYRICS
import app.winters.octo.subsonic.ServerLyricsCandidate

// Who decides a song's lyrics, when the server keeps a choice for every app
// (it lists the octoLyrics extension) and this phone keeps its own
// (LyricsChoices):
//
// - A pick or a hide made on this phone wins on this phone, over whatever
//   the server chose, automatic or pinned. It is how this phone keeps the
//   song file's or a .lrc file's lyrics, which the server cannot see.
// - Choosing one of the server's copies, or "Automatic", sends it to the
//   server and clears this phone's own pick and hide for the song, so the
//   two can never disagree.
// - Hiding sends "none" to the server and hides the song here too. Showing
//   again sends "auto" and shows the song here, keeping a pick this phone
//   made before the hide.
// - The server's answer is final: plain lyrics from it are used as they
//   are, and the online library is asked directly only when the server
//   cannot be reached, since the server asks it itself and may have been
//   told to show none.
// - After any choice sent to the server, the song's saved lyrics are
//   dropped and fetched again at once.
// - A server without the extension changes nothing: every choice stays on
//   this phone, as before.

// Whether the server keeps lyrics choices for every app, going by the
// extensions it listed.
fun offersLyricsChoices(extensions: Set<String>): Boolean = "$OCTO_LYRICS:1" in extensions

// How the server is asked for a song's lyrics.
enum class ServerLyricsCall {
    // By artist and title, the older call, for a server without songLyrics.
    ByName,

    // By song id (songLyrics version 1): timed lines.
    BySongId,

    // By song id with enhanced=true (songLyrics version 2): word timings too.
    WithWords,
}

fun serverLyricsCall(extensions: Set<String>): ServerLyricsCall {
    val versions = extensions.filter { it.startsWith("songLyrics:") }.mapNotNull { it.substringAfter(':').toIntOrNull() }
    return when {
        versions.isEmpty() -> ServerLyricsCall.ByName
        versions.max() >= 2 -> ServerLyricsCall.WithWords
        else -> ServerLyricsCall.BySongId
    }
}

// The sources a song's lyrics are looked for in, in order. When the song is
// on a server that keeps lyrics choices (`serverDecides`), its answer is
// final and the online library is only asked when the server could not be
// reached (see the rules above). A source that is null is not there for
// this song.
fun lyricsSteps(
    server: suspend () -> Lyrics?,
    serverDecides: Boolean,
    songFile: (suspend () -> Lyrics?)?,
    lyricsFile: (suspend () -> Lyrics?)?,
    online: (suspend () -> Lyrics?)?,
): List<LyricsStep> {
    var serverAnswered = false
    return buildList {
        add(LyricsStep(LyricsSource.Server, final = serverDecides) { server().also { serverAnswered = true } })
        songFile?.let { add(LyricsStep(LyricsSource.SongFile, fetch = it)) }
        lyricsFile?.let { add(LyricsStep(LyricsSource.LyricsFile, fetch = it)) }
        online?.let { fetch ->
            add(LyricsStep(LyricsSource.Online) { if (serverDecides && serverAnswered) null else fetch() })
        }
    }
}

// A source's name as the listener knows it, from the server's name for it.
fun lyricsSourceName(key: String): String = when (key.trim().lowercase()) {
    "kugou" -> "KuGou"
    "lrclib" -> "LRCLIB"
    "netease" -> "NetEase"
    "lyricsovh" -> "Lyrics.ovh"
    "", "pinned" -> "your server"
    else -> key.trim().replaceFirstChar(Char::uppercaseChar)
}

// Where a song's lyrics came from, in plain words, for the lyrics menu:
// "From KuGou, word by word", "From LRCLIB", "From your server".
fun Lyrics.sourceLine(): String {
    val pinned = serverPick?.takeIf { source == LyricsSource.Server }?.substringBefore(':')
    val from = if (pinned != null) "From ${lyricsSourceName(pinned)}" else source.label
    return if (kindOf(this) == LyricsKind.Words) "$from, word by word" else from
}

// A candidate's first line in the chooser: where it is from, in plain
// words, or "Automatic" for letting the server find them.
fun candidateLabel(candidate: LyricsCandidate): String {
    val pick = candidate.pick
    return when {
        pick is LyricsPick.OnServer && pick.choice == LYRICS_AUTO -> "Automatic"
        pick is LyricsPick.OnServer -> "From ${lyricsSourceName(candidate.source.ifBlank { pick.choice.substringBefore(':', "") })}"
        else -> when (candidate.origin) {
            CandidateOrigin.Server, CandidateOrigin.ServerCopy -> LyricsSource.Server.label
            CandidateOrigin.SongFile -> LyricsSource.SongFile.label
            CandidateOrigin.LyricsFile -> LyricsSource.LyricsFile.label
            CandidateOrigin.OnlineMatch, CandidateOrigin.OnlineSearch -> LyricsSource.Online.label
        }
    }
}

// How the server says a copy is timed.
fun serverKindOf(kind: String): LyricsKind = when (kind.trim().lowercase()) {
    "word" -> LyricsKind.Words
    "line" -> LyricsKind.Lines
    "instrumental" -> LyricsKind.Instrumental
    else -> LyricsKind.Plain
}

// A copy the server holds, as an entry to choose from.
fun ServerLyricsCandidate.asCandidate() = LyricsCandidate(
    pick = LyricsPick.OnServer(id),
    origin = CandidateOrigin.ServerCopy,
    lyrics = null,
    title = title,
    artist = artist,
    album = album.orEmpty(),
    durationMs = (duration ?: 0).coerceAtLeast(0) * 1_000L,
    source = source.ifBlank { id.substringBefore(':', "") },
    serverKind = serverKindOf(kind),
    serverPreview = preview,
)

// The chooser's "Automatic" entry, which lets the server find the lyrics
// itself. While that is what the server does, it carries the lyrics the
// server answers with; while a copy is pinned or the lyrics are hidden,
// the server's answer is not its own find, so it carries none.
fun automaticCandidate(serverChoice: String, answer: Lyrics?) = LyricsCandidate(
    LyricsPick.OnServer(LYRICS_AUTO),
    CandidateOrigin.Server,
    answer?.takeIf { serverChoice == LYRICS_AUTO && !it.isEmpty },
)

// Everything to choose from when the server keeps the choice: its
// automatic answer, the copies its sources hold, then the lyrics only this
// phone has (the song file's, a .lrc file's). The online library is not
// asked directly, since the server asks it itself. `copies` is null when
// the server could not be reached.
fun serverCandidates(copies: LyricsCandidates?, answer: Lyrics?, local: List<LyricsCandidate>): List<LyricsCandidate> = buildList {
    if (copies != null || answer != null) add(automaticCandidate(copies?.choice ?: LYRICS_AUTO, answer))
    copies?.candidate.orEmpty().filter { it.id.isNotBlank() }.forEach { add(it.asCandidate()) }
    addAll(local)
}

// Which entry in the chooser is showing now. When the server keeps the
// choice (`serverChoice` is not null), lyrics that came from the server are
// its choice: the copy it pinned, or its automatic answer. Anything else is
// told by where the lyrics came from.
fun showingPick(serverChoice: String?, showing: Lyrics?): LyricsPick? = when {
    showing == null -> null
    serverChoice != null && showing.source == LyricsSource.Server ->
        if (serverChoice == LYRICS_NONE) null else LyricsPick.OnServer(serverChoice)
    else -> pickOf(showing)
}

// Whether a song's saved answer no longer matches what the server has
// chosen for it (changed from another app or device), so it is fetched
// again. Only an answer the usual order found counts: lyrics this phone
// picked stand, whatever the server chose.
fun servedStale(cached: CachedLyrics?, serverChoice: String): Boolean {
    if (cached == null || cached.pick != null) return false
    val lyrics = cached.lyrics
    val fromServer = lyrics?.source == LyricsSource.Server
    return when (serverChoice) {
        LYRICS_AUTO -> fromServer && lyrics?.serverPick != null
        LYRICS_NONE -> fromServer
        else -> !fromServer || lyrics?.serverPick != serverChoice
    }
}

// What a search typed in the chooser asks the server for, as a title and
// an artist: "artist - title" gives both; anything else is a title, by the
// song's own artist.
fun splitLyricsSearch(query: String): Pair<String, String?> {
    val words = query.trim()
    val parts = words.split(" - ", limit = 2).map(String::trim)
    return if (parts.size == 2 && parts.all(String::isNotEmpty)) parts[1] to parts[0] else words to null
}

// Sends a choice to the server, then brings this phone in line with it
// (see the rules above) and forgets the song's saved lyrics, so they are
// fetched again. When the server cannot take it, this throws and nothing on
// the phone changes.
suspend fun sendServerChoice(
    songId: String,
    choice: String,
    choices: LyricsChoices,
    send: suspend (String) -> Unit,
    forget: suspend () -> Unit,
    showAgain: Boolean = false,
) {
    send(choice)
    forget()
    when {
        choice == LYRICS_NONE -> choices.hide(songId)
        showAgain -> choices.show(songId)
        else -> choices.clear(songId)
    }
}
