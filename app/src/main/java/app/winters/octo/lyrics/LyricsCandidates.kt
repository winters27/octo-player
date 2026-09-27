package app.winters.octo.lyrics

// Where a set of lyrics to choose from was found, in the order the list
// shows them: the song's own sources (the server's answer, then the copies
// the server's sources hold, when it offers them), the online library's
// own match for the song, then the other copies its search holds.
enum class CandidateOrigin { Server, ServerCopy, SongFile, LyricsFile, OnlineMatch, OnlineSearch }

// The origin lyrics from a source have when nothing else is known.
fun originOf(source: LyricsSource): CandidateOrigin = when (source) {
    LyricsSource.Server -> CandidateOrigin.Server
    LyricsSource.SongFile -> CandidateOrigin.SongFile
    LyricsSource.LyricsFile -> CandidateOrigin.LyricsFile
    LyricsSource.Online -> CandidateOrigin.OnlineMatch
}

// How a set of lyrics is timed, best first.
enum class LyricsKind(val label: String) {
    Words("Word by word"),
    Lines("Timed"),
    Plain("Not timed"),
    Instrumental(""),
}

fun kindOf(lyrics: Lyrics): LyricsKind = when {
    lyrics.instrumental -> LyricsKind.Instrumental
    !lyrics.synced -> LyricsKind.Plain
    lyrics.lines.any { it.words.isNotEmpty() } -> LyricsKind.Words
    else -> LyricsKind.Lines
}

// One set of lyrics the listener can pick for a song. An online copy, or
// one the server holds, carries what its source calls it; the song's own
// sources leave that blank. A copy the server holds has no lyrics here,
// only its first lines and how it is timed: the server fetches the rest
// once it is picked.
data class LyricsCandidate(
    val pick: LyricsPick,
    val origin: CandidateOrigin,
    val lyrics: Lyrics?,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0,
    // Where the server found it ("kugou", "lrclib"), for a server copy.
    val source: String = "",
    // A server copy's timing and first lines, as the server said.
    val serverKind: LyricsKind = LyricsKind.Plain,
    val serverPreview: List<String> = emptyList(),
) {
    val kind: LyricsKind
        get() = lyrics?.let(::kindOf) ?: serverKind

    // The first two lines with words, to tell candidates apart at a glance.
    val preview: List<String>
        get() = lyrics?.let { found -> found.lines.asSequence().map { it.text.trim() }.filter { it.isNotEmpty() }.take(2).toList() }
            ?: serverPreview.map(String::trim).filter(String::isNotEmpty).take(2)
}

fun OnlineCopy.asCandidate(origin: CandidateOrigin) =
    LyricsCandidate(LyricsPick.Online(id), origin, lyrics, title, artist, album, durationMs)

// The list to choose from, and which entry (0 or none, -1) is showing now.
data class CandidateChoices(val items: List<LyricsCandidate>, val showing: Int)

// Builds the list: the lyrics showing now first (the listener's pick, or
// else the ones the usual order found), then the server's, the copies the
// server holds, the song file's, the .lrc file's, the online library's
// match, then its search results, each kept in the order it came.
// `current` is the pick in use, when known; without it, the showing lyrics
// are told apart by their words. Each pick shows once, and lyrics with the
// same words and timing as one above are left out, so a copy found twice is
// offered once (the server's copies are only known by their first lines,
// so they are never merged this way). The showing lyrics are added when no
// source offered them this time (the library could not be reached, say),
// so they can still be seen.
fun candidateList(found: List<LyricsCandidate>, current: LyricsPick?, showing: Lyrics?): CandidateChoices {
    val showingWords = showing?.let(::wordsOf)
    fun isShowing(candidate: LyricsCandidate) = when {
        current != null -> candidate.pick == current
        showingWords != null -> candidate.lyrics?.let(::wordsOf) == showingWords
        else -> false
    }
    val showingPick = current ?: showing?.let(::pickOf)
    val all = if (showing != null && showingPick != null && found.none { it.pick == showingPick }) {
        found + LyricsCandidate(showingPick, originOf(showing.source), showing)
    } else {
        found
    }
    val ordered = all.withIndex()
        .sortedWith(compareBy({ !isShowing(it.value) }, { it.value.origin.ordinal }, { it.index }))
        .map { it.value }
    val picks = HashSet<LyricsPick>()
    val words = HashSet<String>()
    val items = ordered.filter { candidate ->
        val lyrics = candidate.lyrics
        (lyrics == null || !lyrics.isEmpty) && picks.add(candidate.pick) && (lyrics == null || words.add(wordsOf(lyrics)))
    }
    return CandidateChoices(items, if (items.firstOrNull()?.let(::isShowing) == true) 0 else -1)
}

// The words and timing of a set of lyrics, to tell copies apart.
private fun wordsOf(lyrics: Lyrics): String = buildString {
    append(if (lyrics.instrumental) "instrumental" else if (lyrics.synced) "timed" else "plain")
    lyrics.lines.forEach { line ->
        append('\n')
        if (lyrics.synced) append(line.startMs).append(' ')
        append(line.text.trim())
    }
}
