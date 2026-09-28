package app.winters.octo.ui.search

// Recent searches, kept the same way by the phone and the desktop.

// How many past searches are kept.
const val RECENT_SEARCHES = 10

private val spaces = Regex("\\s+")

// A search as it is kept: trimmed, with runs of spaces made one.
fun tidySearch(query: String): String = query.trim().replace(spaces, " ")

// The recent searches with one more, newest first. Searching again for one
// already there (whatever its case) moves it to the top rather than adding
// it twice, and only the newest ten are kept.
fun withRecent(recent: List<String>, query: String): List<String> {
    val tidy = tidySearch(query)
    if (tidy.isEmpty()) return recent
    return (listOf(tidy) + recent.filterNot { it.equals(tidy, ignoreCase = true) }).take(RECENT_SEARCHES)
}

fun withoutRecent(recent: List<String>, query: String): List<String> =
    recent.filterNot { it.equals(tidySearch(query), ignoreCase = true) }

// Kept as one search per line, newest first.
fun encodeRecent(recent: List<String>): String = recent.joinToString("\n")

fun decodeRecent(text: String?): List<String> =
    text.orEmpty().split("\n").filter { it.isNotBlank() }.take(RECENT_SEARCHES)
