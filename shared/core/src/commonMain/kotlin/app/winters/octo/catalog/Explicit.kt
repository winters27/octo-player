package app.winters.octo.catalog

import app.winters.octo.subsonic.Song

// Whether a server marks a song explicit. OpenSubsonic's `explicitStatus`
// is "explicit", "clean" (an edit with the words taken out) or empty when
// nothing says. Only "explicit" gets the small "E" by a song's title: a
// clean edit carries no mark in a list, as in Apple Music, so most rows
// stay bare and the one copy with the words in stands out. Song Info names
// both ("Explicit" or "Clean").
fun isExplicit(explicitStatus: String?): Boolean = explicitStatus?.trim().equals("explicit", ignoreCase = true)

val Song.isExplicit: Boolean get() = isExplicit(explicitStatus)
