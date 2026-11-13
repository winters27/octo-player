package app.winters.octo.theme

// The sign-in form's fixed word colors, the same on every app. Error words
// are one red for light themes and one for dark, each reading at 4.5:1 on
// every page, card, raised and sheet surface of its themes and over every
// glow they allow. The Optional tag is the accent's words on the accent's
// tonal fill, roles every theme already solves.
const val ERROR_WORDS_LIGHT: Int = 0xFFB42318.toInt()
const val ERROR_WORDS_DARK: Int = 0xFFF97C7C.toInt()

fun errorWordsFor(light: Boolean): Int = if (light) ERROR_WORDS_LIGHT else ERROR_WORDS_DARK

// An error note's words and glyph in this theme.
val ResolvedPalette.errorWords: Int get() = errorWordsFor(light)

// The Optional tag's words, and the fill under them.
val ResolvedPalette.tagWords: Int get() = accents.accent
val ResolvedPalette.tagFill: Int get() = accents.tonal
