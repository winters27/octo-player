package app.winters.octo.ui.paging

import app.winters.octo.ui.common.LetterRun
import app.winters.octo.ui.common.indexLetter

// A stretch of text values filed under one letter: every value whose first
// character's code point is at least `lo` and below `hi` (null: no bound).
data class LetterRange(val letter: Char, val lo: String?, val hi: String?)

// The letters a name sorts under, as stretches of code points: built once
// from indexLetter itself, over every character it can be asked about, with
// neighbors filed under the same letter merged. Code point order is SQLite's
// own order of text, so each stretch is one range of an index on the name,
// and a range count per stretch gives the list's letter runs.
object LetterRanges {
    val ranges: List<LetterRange> by lazy {
        val out = ArrayList<LetterRange>()
        var letter = indexLetter("")
        var lo: Int? = null
        for (point in 0..0xFFFF) {
            if (point in 0xD800..0xDFFF) continue
            val l = indexLetter(String(Character.toChars(point)))
            if (l != letter) {
                out += LetterRange(letter, lo?.let(::text), text(point))
                letter = l
                lo = point
            }
        }
        out += LetterRange(letter, lo?.let(::text), null)
        out
    }

    private fun text(point: Int) = String(Character.toChars(point))

    // The letter runs of a list from how many of its values fall in each
    // range, in range order: empty ranges dropped, the order reversed for a
    // list that runs Z to A, and neighbors under the same letter merged.
    fun runs(counts: List<Int>, descending: Boolean): List<LetterRun> {
        val stretches = ranges.indices.map { ranges[it].letter to counts[it] }.filter { it.second > 0 }
        val ordered = if (descending) stretches.asReversed() else stretches
        val runs = ArrayList<LetterRun>()
        var start = 0
        for ((letter, count) in ordered) {
            val last = runs.lastOrNull()
            if (last != null && last.letter == letter) {
                runs[runs.lastIndex] = last.copy(count = last.count + count)
            } else {
                runs += LetterRun(letter, start, count)
            }
            start += count
        }
        return runs
    }

    // The range a value falls in.
    fun indexOf(value: String): Int {
        val point = if (value.isEmpty()) -1 else value.codePointAt(0)
        return ranges.indexOfFirst { r ->
            (r.lo == null || point >= r.lo.codePointAt(0)) && (r.hi == null || point < r.hi.codePointAt(0))
        }
    }
}
