package app.winters.octo.lyrics

// A line's time stamp, like [01:23.45], [01:23:45] or [01:23].
private val lineStamp = Regex("""^\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

// A word's time stamp inside a line, like <01:23.45>.
private val wordStamp = Regex("""<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>""")

// What some editors put before the text of a UTF-8 file.
private val ByteOrderMark = Char(0xFEFF).toString()

// A tag line, like [ar:Someone] or [offset:+250].
private val tagLine = Regex("""^\[([A-Za-z#]+):(.*)]$""")

// Lyrics from text, which may be LRC (timed) or plain. Tags like [ar:] and
// [ti:] are skipped, [offset:] moves every time (above zero, sooner), an
// empty timed line is a gap where nobody sings, and <mm:ss.xx> stamps time
// each word. A line with several stamps is sung at each of them. Null when
// there is nothing to show.
fun parseLyricsText(text: String, source: LyricsSource): Lyrics? {
    val rows = text.removePrefix(ByteOrderMark).lines()
    var offset = 0L
    val timed = mutableListOf<LyricLine>()
    val plain = mutableListOf<String>()
    // The lines the last timed row made, which a [bg:] row adds backing to.
    var lastMade = emptyList<Int>()

    for (row in rows) {
        var rest = row.trim()
        val stamps = mutableListOf<Long>()
        while (true) {
            val stamp = lineStamp.find(rest) ?: break
            stamps += millisOf(stamp)
            rest = rest.substring(stamp.range.last + 1)
        }
        if (stamps.isNotEmpty()) {
            lastMade = stamps.map { start ->
                val (line, words) = timedText(start, rest)
                timed += LyricLine(startMs = start, text = line, words = words, endMs = words.lastOrNull()?.endMs)
                timed.lastIndex
            }
            continue
        }
        val tag = tagLine.matchEntire(rest)
        if (tag != null) {
            val value = tag.groupValues[2].trim()
            when (tag.groupValues[1].lowercase()) {
                "offset" -> offset = value.removePrefix("+").toLongOrNull() ?: offset
                "bg" -> lastMade.forEach { index ->
                    val (line, words) = timedText(timed[index].startMs, value)
                    timed[index] = timed[index].copy(backingText = line, backing = words)
                }
            }
            continue
        }
        plain += rest
    }

    if (timed.isNotEmpty()) {
        if (timed.none { !it.isGap }) return null
        val lines = timed
            .map { it.shifted(-offset) }
            .sortedBy { it.startMs }
            .dropWhile { it.isGap }
        return Lyrics(synced = true, lines = lines, source = source)
    }

    // Plain text: blank rows split verses, so runs of them fold into one.
    val lines = plain
        .fold(mutableListOf<String>()) { kept, row ->
            if (row.isNotEmpty() || kept.lastOrNull()?.isNotEmpty() == true) kept += row
            kept
        }
        .dropLastWhile(String::isEmpty)
    if (lines.isEmpty()) return null
    return Lyrics(synced = false, lines = lines.map { LyricLine(text = it) }, source = source)
}

// A line's text, and its timed words when it has <mm:ss.xx> stamps. Text
// before the first stamp starts with the line. A stamp with nothing after it
// ends the word before it.
private fun timedText(start: Long, body: String): Pair<String, List<LyricWord>> {
    val stamps = wordStamp.findAll(body).toList()
    if (stamps.isEmpty()) return body.trim() to emptyList()

    val pieces = mutableListOf<Pair<Long, String>>()
    val lead = body.substring(0, stamps.first().range.first)
    if (lead.isNotBlank()) pieces += start to lead
    stamps.forEachIndexed { index, stamp ->
        val end = stamps.getOrNull(index + 1)?.range?.first ?: body.length
        pieces += millisOf(stamp) to body.substring(stamp.range.last + 1, end)
    }

    val text = StringBuilder()
    val words = mutableListOf<LyricWord>()
    pieces.forEachIndexed { index, (at, piece) ->
        if (piece.isEmpty()) return@forEachIndexed
        val from = text.length
        text.append(piece)
        if (piece.isNotBlank()) words += LyricWord(at, pieces.getOrNull(index + 1)?.first, piece, from, text.length)
    }

    // Spaces around the line go, and the words move with the text.
    val cut = text.length - text.trimStart().length
    val trimmed = text.trim().toString()
    return trimmed to words.mapNotNull { word ->
        val from = (word.from - cut).coerceIn(0, trimmed.length)
        val to = (word.to - cut).coerceIn(0, trimmed.length)
        if (to > from) word.copy(from = from, to = to) else null
    }
}

// Milliseconds from a stamp's minutes, seconds and fraction. The fraction is
// read as a decimal, so .5, .50 and .500 are all half a second.
private fun millisOf(stamp: MatchResult): Long {
    val (minutes, seconds, fraction) = stamp.destructured
    val millis = when (fraction.length) {
        0 -> 0
        1 -> fraction.toInt() * 100
        2 -> fraction.toInt() * 10
        else -> fraction.toInt()
    }
    return minutes.toLong() * 60_000 + seconds.toLong() * 1_000 + millis
}

// The same line moved in time, never before the start of the song.
internal fun LyricLine.shifted(by: Long): LyricLine {
    if (by == 0L) return this
    fun move(time: Long) = (time + by).coerceAtLeast(0)
    return copy(
        startMs = move(startMs),
        endMs = endMs?.let(::move),
        words = words.map { it.copy(startMs = move(it.startMs), endMs = it.endMs?.let(::move)) },
        backing = backing.map { it.copy(startMs = move(it.startMs), endMs = it.endMs?.let(::move)) },
    )
}
