package app.winters.octo.livelists

import app.winters.octo.query.LibraryQuery
import app.winters.octo.query.QueryMatch
import app.winters.octo.query.QueryRule
import app.winters.octo.query.QuerySort
import app.winters.octo.query.label
import app.winters.octo.sort.SongSort
import java.time.ZoneId

// What a live list picks, in one quiet line under its name: "Added in the
// last month, lossless, 42 songs". Rules met together are joined by commas,
// either-or rules by "or"; the order and a limit follow, then how many
// songs it holds now (left out with no count, as in a list of lists).
fun liveListSummary(query: LibraryQuery, count: Int?, zone: ZoneId = ZoneId.systemDefault()): String {
    val parts = ArrayList<String>()
    val rules = query.rules.filter(QueryRule::complete).map { it.label(zone) }
    if (rules.isNotEmpty()) parts += rules.mapIndexed { i, r -> if (i == 0) r else lowerFirst(r) }.joinToString(if (query.match == QueryMatch.Any) " or " else ", ")
    val words = query.text.trim()
    if (words.isNotEmpty()) parts += "with “$words”"
    if (parts.isEmpty()) parts += "Every song"
    query.sort?.let(::sortWords)?.let { parts += it }
    val limit = query.limit
    when {
        count == null -> if (limit != null) parts += "the top $limit"
        limit != null && count >= limit -> parts += "the top ${songsWord(count)}"
        else -> parts += songsWord(count)
    }
    return parts.mapIndexed { i, part -> if (i == 0) part.replaceFirstChar(Char::uppercaseChar) else part }.joinToString(", ")
}

// A name for a list made from rules, until the listener gives it one: the
// rule, the first two rules, or the words.
fun liveListName(query: LibraryQuery, zone: ZoneId = ZoneId.systemDefault()): String {
    val rules = query.rules.filter(QueryRule::complete).map { it.label(zone) }
    val words = query.text.trim()
    return when {
        rules.size == 1 && words.isEmpty() -> rules[0]
        rules.isNotEmpty() -> rules.take(2).mapIndexed { i, r -> if (i == 0) r else lowerFirst(r) }.joinToString(if (query.match == QueryMatch.Any) " or " else ", ")
        words.isNotEmpty() -> words.replaceFirstChar(Char::uppercaseChar)
        else -> "New live list"
    }
}

// Which way a live list runs, in words: "newest first", "most played first".
fun sortWords(sort: QuerySort): String? {
    val by = SongSort.entries.firstOrNull { it.id == sort.by } ?: return null
    val down = sort.descending
    return when (by) {
        SongSort.Title -> if (down) "by title, Z to A" else "by title"
        SongSort.Artist -> if (down) "by artist, Z to A" else "by artist"
        SongSort.Album -> if (down) "by album, Z to A" else "by album"
        SongSort.RecentlyAdded -> if (down) "newest first" else "oldest first"
        SongSort.Year -> if (down) "latest year first" else "earliest year first"
        SongSort.Length -> if (down) "longest first" else "shortest first"
        SongSort.MostPlayed -> if (down) "most played first" else "least played first"
        SongSort.RecentlyPlayed -> if (down) "last played first" else "longest unplayed first"
        SongSort.Rating -> if (down) "highest rated first" else "lowest rated first"
        SongSort.Liked, SongSort.DateLiked -> if (down) "newest favorites first" else "oldest favorites first"
        SongSort.FolderOrder -> "in folder order"
    }
}

// Over the editor's preview: "42 songs match right now".
fun matchWords(count: Int): String = if (count == 1) "1 song matches right now" else "%,d songs match right now".format(count)

private fun songsWord(count: Int) = if (count == 1) "1 song" else "%,d songs".format(count)

// "Genre is Rock" reads "genre is Rock" inside a line; "BPM" keeps its capitals.
private fun lowerFirst(text: String): String =
    if (text.length > 1 && text[1].isUpperCase()) text else text.replaceFirstChar(Char::lowercaseChar)

// A live list as the editor holds it until saved: the name typed and the
// rules picked.
data class LiveListDraft(val name: String, val query: LibraryQuery) {
    // The name it is saved under: the one typed, else one from its rules.
    val savedName: String get() = name.trim().ifEmpty { liveListName(query) }

    // Whether saving would change `list`.
    fun changes(list: LiveList): Boolean = savedName != list.name || query != list.query

    companion object {
        fun of(list: LiveList) = LiveListDraft(list.name, list.query)
    }
}
