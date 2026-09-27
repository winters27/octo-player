package app.winters.octo.player

import app.winters.octo.catalog.matchKey

// The album name at the top of the player, or nothing when the song is a
// single: a one-song release, or one named after the song itself, would
// only repeat the title shown below.
fun albumLabel(album: String?, title: String?, albumSongs: Int): String? {
    if (album.isNullOrBlank()) return null
    if (albumSongs <= 1) return null
    if (album.trim().lowercase().endsWith("- single")) return null
    if (title != null && bareName(album) == bareName(title)) return null
    return album
}

private val ReleaseEnding = Regex("""\s*-\s*(single|ep)\s*$""", RegexOption.IGNORE_CASE)

// A name without a "- Single" or "- EP" ending, read as a whole name (case,
// accents, punctuation and bracketed extras like "(feat. someone)" or
// "[Remastered]" aside), for comparing a release with its song.
private fun bareName(name: String): String = matchKey(name.replace(ReleaseEnding, ""))
