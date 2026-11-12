package app.winters.octo.playback

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

// Statistics over many orders of one list, each answering whether the
// orders look like uniform random orders: an order is the list places in
// the order they play. Critical values are chi-square's at a given p, from
// the Wilson-Hilferty approximation, Bonferroni-corrected where one check
// runs many tests.
internal object OrderChecks {
    // erfc to about 1e-7 (Numerical Recipes' erfcc).
    private fun erfc(x: Double): Double {
        val z = abs(x)
        val t = 1.0 / (1.0 + 0.5 * z)
        val r = t * exp(
            -z * z - 1.26551223 + t * (1.00002368 + t * (0.37409196 + t * (0.09678418 + t * (-0.18628806 +
                t * (0.27886807 + t * (-1.13520398 + t * (1.48851587 + t * (-0.82215223 + t * 0.17087277)))))))),
        )
        return if (x >= 0) r else 2 - r
    }

    // The z a standard normal passes with chance `p`.
    fun normalUpper(p: Double): Double {
        var lo = 0.0
        var hi = 40.0
        repeat(200) {
            val mid = (lo + hi) / 2
            if (0.5 * erfc(mid / sqrt(2.0)) > p) lo = mid else hi = mid
        }
        return (lo + hi) / 2
    }

    // The chi-square value with `df` degrees of freedom passed with chance `p`.
    fun chiCritical(df: Int, p: Double): Double {
        val k = 2.0 / (9.0 * df)
        val c = 1 - k + normalUpper(p) * sqrt(k)
        return df * c * c * c
    }

    fun chiSquare(counts: LongArray, expected: DoubleArray): Double =
        counts.indices.sumOf { (counts[it] - expected[it]) * (counts[it] - expected[it]) / expected[it] }

    // Each order differs from every other, and no two share their first
    // ten songs.
    fun allDifferent(orders: List<IntArray>): String? {
        if (orders.map { it.toList() }.toSet().size != orders.size) return "two orders are the same"
        val heads = orders.map { it.take(10) }
        if (heads.toSet().size != heads.size) return "two orders share their first ten songs"
        return null
    }

    // The songs at `place` across the orders fall evenly over `buckets`
    // stretches of the list.
    fun placeUniform(orders: List<IntArray>, n: Int, place: Int, buckets: Int, p: Double): String? {
        val counts = LongArray(buckets)
        for (o in orders) counts[(o[place].toLong() * buckets / n).toInt()]++
        val expected = DoubleArray(buckets) { orders.size.toDouble() / buckets }
        val chi = chiSquare(counts, expected)
        val critical = chiCritical(buckets - 1, p)
        return if (chi < critical) null else "place $place chi-square $chi over $critical"
    }

    // Spearman's rho between list place and play place: its mean near 0,
    // and each under 5/sqrt(n-1).
    fun correlation(orders: List<IntArray>, n: Int): String? {
        val rhos = orders.map { ShuffleStatistics.spearman(it) }
        val each = 5 / sqrt(n - 1.0)
        rhos.firstOrNull { abs(it) >= each }?.let { return "rho $it over $each" }
        val bound = 4 * (1 / sqrt(n - 1.0)) / sqrt(orders.size.toDouble())
        val mean = rhos.average()
        return if (abs(mean) < bound) null else "mean rho $mean over $bound"
    }

    // List neighbors next to each other in play, and song w + 1 right
    // after song w, each at chance within four standard deviations.
    fun neighbors(orders: List<IntArray>, n: Int): String? {
        var adjacent = 0L
        var after = 0L
        for (o in orders) {
            val r = ShuffleStatistics.ranks(o)
            for (i in 0 until n - 1) {
                if (abs(r[i] - r[i + 1]) == 1) adjacent++
                if (r[i + 1] == r[i] + 1) after++
            }
        }
        val runs = orders.size
        val expectA = runs * 2.0 * (n - 1) / n
        val expectS = runs * (n - 1.0) / n
        if (abs(adjacent - expectA) > 4 * sqrt(expectA)) return "neighbors $adjacent, expected $expectA"
        if (abs(after - expectS) > 4 * sqrt(expectS)) return "successions $after, expected $expectS"
        return null
    }

    // Lags of play and of the list: the distance between the songs `lag`
    // apart in play (and the play places of songs `lag` apart in the list)
    // falls as it does between two distinct uniform places, in 20 bins of
    // equal chance, by chi-square at `p` spread over every lag.
    fun lagDistances(orders: List<IntArray>, n: Int, p: Double): String? {
        val lags = (listOf(1, 2, 3) + generateSequence(4) { it * 2 }.takeWhile { it < n / 2 }).distinct()
        val tests = lags.size * 2
        val bins = 20
        // P(D = d) = 2(n - d) / (n(n - 1)); bins of equal mass by its CDF.
        val edges = IntArray(bins + 1)
        run {
            var cum = 0.0
            var bin = 1
            edges[0] = 1
            for (d in 1 until n) {
                cum += 2.0 * (n - d) / (n.toDouble() * (n - 1))
                while (bin < bins && cum >= bin.toDouble() / bins) {
                    edges[bin] = d + 1
                    bin++
                }
            }
            edges[bins] = n
        }
        val mass = DoubleArray(bins) { b -> (edges[b] until edges[b + 1]).sumOf { d -> 2.0 * (n - d) / (n.toDouble() * (n - 1)) } }
        val critical = chiCritical(bins - 1, p / tests)
        fun binOf(d: Int): Int {
            var lo = 0
            var hi = bins - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (edges[mid] <= d) lo = mid else hi = mid - 1
            }
            return lo
        }
        for (lag in lags) {
            for (byList in listOf(false, true)) {
                val counts = LongArray(bins)
                var total = 0L
                for (o in orders) {
                    val seq = if (byList) ShuffleStatistics.ranks(o) else o
                    for (t in 0 until n - lag) {
                        counts[binOf(abs(seq[t + lag] - seq[t]))]++
                        total++
                    }
                }
                val expected = DoubleArray(bins) { mass[it] * total }
                if (expected.any { it < 5 }) continue
                val chi = chiSquare(counts, expected)
                if (chi >= critical) return "${if (byList) "list" else "play"} lag $lag chi-square $chi over $critical"
            }
        }
        return null
    }

    // Every place once.
    fun everyOnce(orders: List<IntArray>, n: Int): String? =
        orders.firstOrNull { o -> o.size != n || o.toSet().size != n || o.any { it !in 0 until n } }?.let { "an order of ${it.size} misses or repeats a song" }
}
