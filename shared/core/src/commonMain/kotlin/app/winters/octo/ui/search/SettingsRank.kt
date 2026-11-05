package app.winters.octo.ui.search

import app.winters.octo.catalog.searchKey

// How well a setting matches what was typed, best first: the title begins
// with it, every word is in the title, the title and other words together
// cover it, or only the line about it does.
private enum class SettingsMatch { TitleStart, Title, Keywords, Summary }

// Lowercase, accents and punctuation gone, so "wifi" finds "Wi-Fi".
private fun settingsSearchText(text: String): String =
    searchKey(text).filter { it.isLetterOrDigit() || it.isWhitespace() }.replace(Spaces, " ").trim()

private val Spaces = Regex("\\s+")

// A typed word is found in some text when a word there starts with it, or
// anywhere inside it once it is three letters or more.
private fun found(word: String, text: String): Boolean {
    if (text.isEmpty()) return false
    if (text.startsWith(word) || text.contains(" $word")) return true
    return word.length >= 3 && text.contains(word)
}

// How a setting's title, other words and summary match the typed words, or
// null when some typed word is found in none of them.
private fun matchOf(typed: String, words: List<String>, title: String, other: String, summary: String): SettingsMatch? = when {
    title.startsWith(typed) -> SettingsMatch.TitleStart
    words.all { found(it, title) } -> SettingsMatch.Title
    words.all { found(it, title) || found(it, other) } -> SettingsMatch.Keywords
    words.all { found(it, title) || found(it, other) || found(it, summary) } -> SettingsMatch.Summary
    else -> null
}

// The settings that match what was typed, best first. `other` is a
// setting's other words (keywords, and where it lives). Every typed word
// must be found somewhere. Ties keep the order the settings are given in.
fun <T> rankSettings(
    query: String,
    entries: List<T>,
    title: (T) -> String,
    other: (T) -> String,
    summary: (T) -> String,
): List<T> {
    val typed = settingsSearchText(query)
    if (typed.isEmpty()) return emptyList()
    val words = typed.split(' ')
    return entries.mapNotNull { entry ->
        val match = matchOf(typed, words, settingsSearchText(title(entry)), settingsSearchText(other(entry)), settingsSearchText(summary(entry)))
        match?.let { entry to it }
    }.sortedBy { it.second }.map { it.first }
}
