package app.winters.octo.radio

import app.winters.octo.catalog.searchKey

private const val SHORTEST_MS = 45_000L
private const val LONGEST_MS = 45 * 60_000L
private const val SHORT_PIECE_MS = 4 * 60_000L

// Whole words, with the edges spelled out: a plain word boundary means
// different things on different Java versions and on Android, and the
// server must agree with both apps.
private val Talk = Regex("""(?<![\p{L}\p{N}])(interview|podcast)(?![\p{L}\p{N}])""", RegexOption.IGNORE_CASE)
private val Filler = Regex("""(?<![\p{L}\p{N}])(intro|introduction|skit|interlude)(?![\p{L}\p{N}])""", RegexOption.IGNORE_CASE)
private val TalkGenres = setOf("podcast", "podcasts", "audiobook", "audiobooks")

// Songs a radio leaves out: shorter than 45 seconds or longer than 45
// minutes, an interview or a podcast, or an intro, skit or interlude of
// four minutes or less. A length of 0 is unknown and not held against it.
fun isRadioFiller(title: String, durationMs: Long, genres: List<String> = emptyList()): Boolean {
    if (durationMs in 1 until SHORTEST_MS || durationMs > LONGEST_MS) return true
    if (Talk.containsMatchIn(title)) return true
    if (genres.any { searchKey(it) in TalkGenres }) return true
    return durationMs in 1..SHORT_PIECE_MS && Filler.containsMatchIn(title)
}
