package app.winters.octo.lyrics.engine

// The lyrics as the flowing view draws them: one flat list of lines, each
// holding its timed words. Times are in seconds on the lyrics' own clock.

// A timed word, or one syllable of a word. Syllables of one word sit flush;
// only the last has `trailingSpace`, so the word wraps as one.
data class SyncWord(
    val text: String,
    val start: Double,
    val end: Double,
    val trailingSpace: Boolean,
)

data class SyncLine(
    // Its place in the list.
    val index: Int,
    // The whole line, for reading aloud.
    val text: String,
    // Empty for an interlude.
    val words: List<SyncWord>,
    val start: Double,
    val end: Double,
    // Read right to left, as Arabic or Hebrew are.
    val rtl: Boolean = false,
    val translation: String? = null,
    val romanization: String? = null,
    // A gap where nobody sings: it shows dots.
    val isInterlude: Boolean = false,
    // Sung by the backing voices.
    val isBackground: Boolean = false,
    // Sung by a second lead voice, set on the other side.
    val isDuet: Boolean = false,
    // Timed by line only: no fill word by word, no bloom.
    val isLineTimed: Boolean = false,
    // The closing "Written by" line, which never ends.
    val isCredit: Boolean = false,
) {
    // Set against the right edge: right-to-left lines, and duet lines on the
    // side away from the lead, which flips for right-to-left songs.
    val alignRight: Boolean get() = rtl xor isDuet
}
