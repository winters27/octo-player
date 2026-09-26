package app.winters.octo.ui.common

// Filing a list ordered by name under letters, for its headings and the
// letter rail.

// A stretch of a list under one letter: the letter, where it starts, and
// how many items.
data class LetterRun(val letter: Char, val start: Int, val count: Int)

// The items under each letter, in list order. A letter can come back, since
// names starting with a number (at the top) and names in other scripts (at
// the end) both file under "#", so each stretch gets its own run.
fun letterRuns(names: List<String>): List<LetterRun> {
    val letters = names.map(::indexLetter)
    val runs = ArrayList<LetterRun>()
    var start = 0
    for (index in 1..letters.size) {
        if (index == letters.size || letters[index] != letters[start]) {
            runs += LetterRun(letters[start], start, index - start)
            start = index
        }
    }
    return runs
}

// A letter on the rail and the row of the lazy list it jumps to.
data class RailStop(val letter: Char, val index: Int)

// Where each letter first starts in a lazy list. With `headed`, the list
// puts a heading row before each run; `before` counts any rows above the
// items. A letter that comes back jumps to its first run.
fun railStops(runs: List<LetterRun>, headed: Boolean, before: Int = 0): List<RailStop> {
    val seen = HashSet<Char>()
    return runs.mapIndexedNotNull { runIndex, run ->
        if (!seen.add(run.letter)) return@mapIndexedNotNull null
        RailStop(run.letter, before + run.start + if (headed) runIndex else 0)
    }
}
