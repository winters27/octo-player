package app.winters.octo.lyrics

// Where a set of lyrics to choose from was found, in the order the list
// shows them: the song's own sources, the online library's own match for
// the song, then the other copies its search holds.
enum class CandidateOrigin { Server, SongFile, LyricsFile, OnlineMatch, OnlineSearch }

// The origin lyrics from a source have when nothing else is known.
fun originOf(source: LyricsSource): CandidateOrigin = when (source) {
    LyricsSource.Server -> CandidateOrigin.Server
    LyricsSource.SongFile -> CandidateOrigin.SongFile
    LyricsSource.LyricsFile -> CandidateOrigin.LyricsFile
    LyricsSource.Online -> CandidateOrigin.OnlineMatch
}

// One set of lyrics the listener can pick for a song. An online copy
// carries what the library calls it; the song's own sources leave that
// blank.
data class LyricsCandidate(
    val pick: LyricsPick,
    val origin: CandidateOrigin,
    val lyrics: Lyrics,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0,
) {
    // The first two lines with words, to tell candidates apart at a glance.
    val preview: List<String>
        get() = lyrics.lines.asSequence().map { it.text.trim() }.filter { it.isNotEmpty() }.take(2).toList()
}

fun OnlineCopy.asCandidate(origin: CandidateOrigin) =
    LyricsCandidate(LyricsPick.Online(id), origin, lyrics, title, artist, album, durationMs)

// The list to choose from, and which entry (0 or none, -1) is showing now.
data class CandidateChoices(val items: List<LyricsCandidate>, val showing: Int)

// Builds the list: the lyrics showing now first (the listener's pick, or
// else the ones the usual order found), then the server's, the song
// file's, the .lrc file's, the online library's match, then its search
// results, each kept in the order it came. `current` is the pick in use,
// when known; without it, the showing lyrics are told apart by their
// words. Each pick shows once, and lyrics with the same words and timing
// as one above are left out, so a copy found twice is offered once. The
// showing lyrics are added when no source offered them this time (the
// library could not be reached, say), so they can still be seen.
fun candidateList(found: List<LyricsCandidate>, current: LyricsPick?, showing: Lyrics?): CandidateChoices {
    val showingWords = showing?.let(::wordsOf)
    fun isShowing(candidate: LyricsCandidate) = when {
        current != null -> candidate.pick == current
        showingWords != null -> wordsOf(candidate.lyrics) == showingWords
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
    val items = ordered.filter { !it.lyrics.isEmpty && picks.add(it.pick) && words.add(wordsOf(it.lyrics)) }
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
