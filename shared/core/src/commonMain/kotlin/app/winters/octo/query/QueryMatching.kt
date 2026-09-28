package app.winters.octo.query

import app.winters.octo.subsonic.Song
import java.text.Normalizer

private const val DAY_MS = 86_400_000L

// The server's songs this query picks, in its order, at most its limit.
// `now` is the time in milliseconds, for rules like "in the last 30 days".
fun LibraryQuery.apply(songs: List<Song>, now: Long): List<Song> = select(songs, now, SubsonicSongs)

// Any kind of song this query picks, read through `fields`. Pure: songs in,
// songs out. Each name is folded (lower case, accents gone) at most once a
// call, and only the fields a rule or the typed words look at are read.
fun <T> LibraryQuery.select(songs: List<T>, now: Long, fields: SongFields<T>): List<T> {
    val test = matcher(now, fields)
    var picked = if (test == null) songs else songs.filter(test)
    sort?.order()?.let { picked = sortSongsBy(picked, it, fields) }
    val most = limit
    if (most != null && most >= 0 && picked.size > most) picked = picked.take(most)
    return picked
}

// The test a song must pass, or null when every song does.
fun <T> LibraryQuery.matcher(now: Long, fields: SongFields<T>): ((T) -> Boolean)? {
    val folds = Folds()
    val tests = rules.filter(QueryRule::complete).map { ruleTest(it, now, fields, folds) }
    val words = text.split(' ', '\t', '\n').filter(String::isNotBlank).map(::foldText).filter(String::isNotEmpty)
    val byRules: ((T) -> Boolean)? = when {
        tests.isEmpty() -> null
        tests.size == 1 -> tests[0]
        match == QueryMatch.All -> { song -> tests.all { it(song) } }
        else -> { song -> tests.any { it(song) } }
    }
    val byWords: ((T) -> Boolean)? = if (words.isEmpty()) null else { song -> hasWords(song, words, fields, folds) }
    return when {
        byRules == null -> byWords
        byWords == null -> byRules
        else -> { song -> byRules(song) && byWords(song) }
    }
}

// Every typed word is in one of the names the filter field searches.
private fun <T> hasWords(song: T, words: List<String>, fields: SongFields<T>, folds: Folds): Boolean {
    val title = foldText(fields.title(song))
    var artist: String? = null
    var album: String? = null
    var albumArtist: String? = null
    var genres: List<String>? = null
    for (word in words) {
        if (title.contains(word)) continue
        if ((artist ?: folds.of(fields.artist(song)).also { artist = it }).contains(word)) continue
        if ((album ?: folds.of(fields.album(song)).also { album = it }).contains(word)) continue
        if ((albumArtist ?: folds.of(fields.albumArtist(song)).also { albumArtist = it }).contains(word)) continue
        if ((genres ?: fields.genres(song).map(folds::of).also { genres = it }).any { it.contains(word) }) continue
        return false
    }
    return true
}

private fun <T> ruleTest(rule: QueryRule, now: Long, fields: SongFields<T>, folds: Folds): (T) -> Boolean = when (rule.field.kind) {
    FieldKind.Text -> textTest(rule, fields, folds)
    FieldKind.Number -> numberTest(rule, fields)
    FieldKind.Date -> dateTest(rule, now, fields)
    FieldKind.Flag -> {
        val wanted = rule.flag == true
        when (rule.field) {
            QueryField.Lossless -> { song -> fields.lossless(song) == wanted }
            else -> { song -> fields.favourite(song) == wanted }
        }
    }
}

// Words. A song with several genres passes a positive test when any genre
// does, and a negative one ("is not", "does not contain") when none fails it.
private fun <T> textTest(rule: QueryRule, fields: SongFields<T>, folds: Folds): (T) -> Boolean {
    val value = foldText(rule.text.orEmpty())
    val values: (T) -> List<String> = when (rule.field) {
        QueryField.Genre -> { song -> fields.genres(song).map(folds::of) }
        QueryField.Title -> { song -> listOf(foldText(fields.title(song))) }
        QueryField.Artist -> { song -> listOf(folds.of(fields.artist(song))) }
        QueryField.Album -> { song -> listOf(folds.of(fields.album(song))) }
        QueryField.AlbumArtist -> { song -> listOf(folds.of(fields.albumArtist(song))) }
        QueryField.Composer -> { song -> listOf(folds.of(fields.composer(song))) }
        else -> { song -> listOf(folds.of(fields.format(song))) }
    }
    return when (rule.op) {
        QueryOp.Contains -> { song -> values(song).any { it.contains(value) } }
        QueryOp.Is -> { song -> values(song).any { it == value } }
        QueryOp.StartsWith -> { song -> values(song).any { it.startsWith(value) } }
        QueryOp.IsNot -> { song -> values(song).none { it == value } }
        else -> { song -> values(song).none { it.contains(value) } }
    }
}

// Numbers. Plays and rating are 0 when there are none; a missing year,
// length, bit rate or BPM passes only "is not".
private fun <T> numberTest(rule: QueryRule, fields: SongFields<T>): (T) -> Boolean {
    val value: (T) -> Long? = when (rule.field) {
        QueryField.Year -> { song -> fields.year(song)?.takeIf { it > 0 }?.toLong() }
        QueryField.Plays -> { song -> fields.plays(song) }
        QueryField.Rating -> { song -> fields.rating(song).toLong() }
        QueryField.Duration -> { song -> fields.seconds(song).takeIf { it > 0 }?.toLong() }
        QueryField.BitRate -> { song -> fields.bitRate(song)?.takeIf { it > 0 }?.toLong() }
        else -> { song -> fields.bpm(song)?.takeIf { it > 0 }?.toLong() }
    }
    val number = rule.number ?: 0
    val low = minOf(number, rule.to ?: number)
    val high = maxOf(number, rule.to ?: number)
    return when (rule.op) {
        QueryOp.Is -> { song -> value(song) == number }
        QueryOp.IsNot -> { song -> value(song) != number }
        QueryOp.AtLeast -> { song -> value(song)?.let { it >= number } == true }
        QueryOp.AtMost -> { song -> value(song)?.let { it <= number } == true }
        else -> { song -> value(song)?.let { it in low..high } == true }
    }
}

// Dates. A song never played counts as not played in any span; "before"
// and "after" split the songs that have a date at `at`.
private fun <T> dateTest(rule: QueryRule, now: Long, fields: SongFields<T>): (T) -> Boolean {
    val value: (T) -> Long? = if (rule.field == QueryField.Added) fields::addedAt else fields::lastPlayedAt
    val since = now - (rule.days ?: 0) * DAY_MS
    val at = rule.at ?: 0
    return when (rule.op) {
        QueryOp.InTheLast -> { song -> value(song)?.let { it >= since } == true }
        QueryOp.NotInTheLast -> { song -> value(song)?.let { it < since } ?: true }
        QueryOp.Before -> { song -> value(song)?.let { it < at } == true }
        QueryOp.After -> { song -> value(song)?.let { it >= at } == true }
        else -> { song -> value(song) == null }
    }
}

// Names folded for matching, each worked out once a call: artists, albums
// and genres repeat across thousands of songs.
private class Folds {
    private val known = HashMap<String, String>()

    fun of(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        return known.getOrPut(text) { foldText(text) }
    }
}

// Lower case with accents gone, so "beyonce" finds "Beyoncé": the same as
// the catalogue's searchKey, done without a regular expression, which took
// most of the time on a big library. Plain ASCII, most names, skips the
// Unicode pass altogether.
fun foldText(text: String): String {
    val ascii = text.all { it.code < 128 }
    if (ascii) return text.trim().lowercase()
    val split = Normalizer.normalize(text.trim(), Normalizer.Form.NFD)
    val out = StringBuilder(split.length)
    var i = 0
    while (i < split.length) {
        val point = split.codePointAt(i)
        if (Character.getType(point) != Character.NON_SPACING_MARK.toInt()) out.appendCodePoint(point)
        i += Character.charCount(point)
    }
    return out.toString().lowercase()
}
