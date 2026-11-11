package app.winters.octo.ui.common.today

import app.winters.octo.ui.common.LetterRun
import app.winters.octo.ui.common.RailStop

// A labeled pin: today's letter runs and rail stops, as shared
// ui/common/Letters.kt had them at c2208eaa, frozen as the measure the
// letter ranges must match. Never edited to follow the app.

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

// The first letter a list is grouped under; anything else goes under "#".
fun indexLetter(sortKey: String): Char =
    sortKey.firstOrNull()?.uppercaseChar()?.takeIf { it in 'A'..'Z' } ?: '#'
