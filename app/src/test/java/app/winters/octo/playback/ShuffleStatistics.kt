package app.winters.octo.playback

import kotlin.math.abs
import kotlin.math.sqrt

// An order of a list's n songs (positions in the list, in the order they
// play), the r-th of many.
internal fun interface Shuffler {
    fun order(n: Int, run: Int): IntArray
}

// Whether a statistic accepts a shuffler, and what it saw.
internal class Verdict(val name: String, val accepted: Boolean, val detail: String)

// The statistics that tell a random order from one that only looks random:
// rank correlation with the list (its mean and its spread), list neighbors
// and successors kept together, every order different, and one song's
// place uniform. Each is run over many orders of one size.
internal object ShuffleStatistics {
    fun ranks(order: IntArray): IntArray = IntArray(order.size).also { r -> order.forEachIndexed { pos, song -> r[song] = pos } }

    // Spearman's correlation between a song's list place and its place in
    // the order.
    fun spearman(order: IntArray): Double {
        val n = order.size
        val r = ranks(order)
        var d2 = 0.0
        for (i in 0 until n) {
            val d = (i - r[i]).toDouble()
            d2 += d * d
        }
        return 1 - 6 * d2 / (n.toDouble() * (n.toDouble() * n - 1))
    }

    private fun correlations(s: Shuffler, n: Int, runs: Int): DoubleArray = DoubleArray(runs) { spearman(s.order(n, it)) }

    fun meanNearZero(s: Shuffler, n: Int, runs: Int): Verdict {
        val c = correlations(s, n, runs)
        val mean = c.average()
        val bound = 4 * (1 / sqrt(n - 1.0)) / sqrt(runs.toDouble())
        return Verdict("spearman mean n=$n", abs(mean) < bound, "mean $mean, bound $bound")
    }

    fun spreadMatches(s: Shuffler, n: Int, runs: Int): Verdict {
        val c = correlations(s, n, runs)
        val mean = c.average()
        val sd = sqrt(c.sumOf { (it - mean) * (it - mean) } / (runs - 1))
        val expected = 1 / sqrt(n - 1.0)
        return Verdict("spearman spread n=$n", abs(sd - expected) <= 0.15 * expected, "sd $sd, expected $expected")
    }

    // List neighbors ending up next to each other: 2(n-1)/n a run by chance.
    fun neighborsAtChance(s: Shuffler, n: Int, runs: Int): Verdict {
        var total = 0L
        repeat(runs) { run ->
            val r = ranks(s.order(n, run))
            for (i in 0 until n - 1) if (abs(r[i] - r[i + 1]) == 1) total++
        }
        val expected = runs * 2.0 * (n - 1) / n
        val sd = sqrt(expected)
        return Verdict("neighbors n=$n", abs(total - expected) <= 4 * sd, "seen $total, expected $expected")
    }

    // Song r+1 played right after song r: (n-1)/n a run by chance.
    fun successionsAtChance(s: Shuffler, n: Int, runs: Int): Verdict {
        var total = 0L
        repeat(runs) { run ->
            val order = s.order(n, run)
            for (p in 0 until n - 1) if (order[p + 1] == order[p] + 1) total++
        }
        val expected = runs * (n - 1.0) / n
        val sd = sqrt(expected)
        return Verdict("successions n=$n", abs(total - expected) <= 4 * sd, "seen $total, expected $expected")
    }

    // Each order differs from every other.
    fun noRepeats(s: Shuffler, n: Int, runs: Int): Verdict {
        val seen = HashSet<List<Int>>()
        val repeats = (0 until runs).count { !seen.add(s.order(n, it).toList()) }
        return Verdict("no repeats n=$n", repeats == 0, "$repeats repeats")
    }

    // A song's place in the order is uniform over the places after the
    // first song, which a fixed start holds.
    fun placeUniform(s: Shuffler, n: Int, runs: Int, song: Int, critical: Double): Verdict {
        val counts = LongArray(n - 1)
        repeat(runs) { run -> ranks(s.order(n, run))[song].let { if (it > 0) counts[it - 1]++ else counts[0] += n.toLong() } }
        val chi = chiSquare(counts)
        return Verdict("place of song $song after a fixed start n=$n", chi < critical, "chi-square $chi, critical $critical")
    }

    fun chiSquare(counts: LongArray): Double {
        val expected = counts.sum().toDouble() / counts.size
        return counts.sumOf { (it - expected) * (it - expected) / expected }
    }

    // Every statistic at its sizes; `fixed` is the same shuffle with its
    // first song fixed (song 0).
    fun battery(s: Shuffler, fixed: Shuffler = s): List<Verdict> = listOf(
        meanNearZero(s, 12, 2_000),
        meanNearZero(s, 200, 400),
        meanNearZero(s, 5_000, 40),
        spreadMatches(s, 12, 2_000),
        spreadMatches(s, 200, 400),
        spreadMatches(s, 5_000, 320),
        neighborsAtChance(s, 12, 2_000),
        neighborsAtChance(s, 200, 400),
        neighborsAtChance(s, 5_000, 40),
        successionsAtChance(s, 12, 2_000),
        successionsAtChance(s, 200, 400),
        successionsAtChance(s, 5_000, 40),
        noRepeats(s, 12, 50),
        // 10 degrees of freedom at p = 0.001.
        placeUniform(fixed, 12, 6_000, song = 1, critical = 29.59),
        placeUniform(fixed, 12, 6_000, song = 11, critical = 29.59),
    )
}
