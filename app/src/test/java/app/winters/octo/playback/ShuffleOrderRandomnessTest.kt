package app.winters.octo.playback

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

// A big list's shuffled order is random and never the same pattern twice:
// orders differ, the first song is uniform, the order keeps no trace of the
// list's order (rank correlation, neighbors, successors, and the distances
// of every short and power-of-two lag both ways), and every place plays
// once. Two orders are checked: the places sorted by their shuffled keys
// alone, and a Shuffle all's play, a start drawn at random and then every
// other place by its shuffled key from its play number after the start,
// which also holds the start uniform over the list. Seeds come from
// fixed-seed sources so every run reads the same numbers; the production
// seeds come from SecureRandom. Every check is also run against planted
// orders that only look random, each of which some check must reject.
class ShuffleOrderRandomnessTest {
    private val p = 0.001

    private fun orders(s: Shuffler, n: Int, runs: Int) = List(runs) { s.order(n, it) }

    // Every check at one size; the names of those that rejected.
    private fun checks(s: Shuffler, n: Int, runs: Int): List<String> {
        val many = orders(s, n, runs)
        val firsts = orders(s, n, 2_000)
        return listOfNotNull(
            OrderChecks.allDifferent(many),
            OrderChecks.placeUniform(firsts, n, 0, 20, p),
            OrderChecks.correlation(many, n),
            OrderChecks.neighbors(many, n),
            OrderChecks.lagDistances(many, n, p),
            OrderChecks.everyOnce(many, n),
        )
    }

    // The places sorted by their shuffled key from their play key and the
    // Shuffle's own seed.
    private fun keyed(seeds: Random = Random(0xB111D)): Shuffler =
        Shuffler { n, _ ->
            val seed = seeds.nextLong()
            (0 until n).sortedBy { shuffledKey(seed, playKey(it.toLong())) }.toIntArray()
        }

    // A Shuffle all's play: the start drawn as the first read draws it,
    // then every other place by its shuffled key from its play number after
    // the start and the Shuffle's seed.
    private fun played(seeds: Random = Random(0xB0B), starts: Random = Random(0xA11)): Shuffler =
        Shuffler { n, _ ->
            val seed = seeds.nextLong()
            val start = starts.nextInt(n)
            (0 until n).sortedBy { playShuffledKey(playNumber(it, start), seed, firstShuffled = true) }.toIntArray()
        }

    private val sizes = listOf(2_000 to 200, 600 to 200)

    @Test
    fun theKeyedOrderPassesEveryCheck() {
        for ((n, runs) in sizes) {
            val failed = checks(keyed(), n, runs)
            assertTrue("n=$n: " + failed.joinToString("; "), failed.isEmpty())
        }
    }

    // The start and the five songs after it: uniform over the list, and
    // over their distance after the start (around the list's end).
    private fun startsUniform(orders: List<IntArray>, n: Int): String? {
        for (j in 0..5) {
            OrderChecks.placeUniform(orders, n, j, 20, p / 12)?.let { return "place $j: $it" }
            if (j == 0) continue
            val distances = orders.map { o -> intArrayOf(Math.floorMod(o[j] - o[0], n)) }
            OrderChecks.placeUniform(distances, n, 0, 20, p / 12)?.let { return "place $j distance from the start: $it" }
        }
        return null
    }

    private fun playChecks(s: Shuffler, n: Int): List<String> {
        val many = orders(s, n, 200)
        val starts = orders(s, n, 2_000)
        return listOfNotNull(
            OrderChecks.allDifferent(many),
            startsUniform(starts, n),
            OrderChecks.correlation(many, n),
            OrderChecks.neighbors(many, n),
            OrderChecks.lagDistances(many, n, p),
            OrderChecks.everyOnce(many, n),
        )
    }

    // The same checks, start and all, hold a Shuffle all's play to the
    // same bar.
    @Test
    fun aShuffleAllsPlayPassesEveryCheck() {
        val failed = playChecks(played(), 2_000)
        assertTrue(failed.joinToString("; "), failed.isEmpty())
    }

    // P8's battery, at its own sizes, over a Shuffle all's play.
    @Test
    fun aShuffleAllsPlayPassesTheBattery() {
        val fixed = Random(0xF1)
        val fixedStart = Shuffler { n, _ ->
            val seed = fixed.nextLong()
            (0 until n).sortedBy { playShuffledKey(playNumber(it, 0), seed, firstShuffled = true) }.toIntArray()
        }
        val failed = ShuffleStatistics.battery(played(Random(0xB0C), Random(0xA12)), fixedStart).filterNot { it.accepted }
        assertTrue(failed.joinToString("\n") { "${it.name}: ${it.detail}" }, failed.isEmpty())
    }

    private fun planted(): Map<String, Shuffler> {
        val draw = Random(77)
        return mapOf(
            "identity" to Shuffler { n, _ -> IntArray(n) { it } },
            "one seed reused" to Shuffler { n, _ -> (0 until n).sortedBy { shuffledKey(1234567L, playKey(it.toLong())) }.toIntArray() },
            "affine a*i + b mod n" to Shuffler { n, _ ->
                // A step coprime to n, so it is an order.
                var a = 1 + draw.nextInt(n - 1)
                while (gcd(a, n) != 1) a = 1 + draw.nextInt(n - 1)
                val b = draw.nextInt(n)
                IntArray(n) { ((a.toLong() * it + b) % n).toInt() }
            },
            "sk = qk xor seed, unmixed" to Shuffler { n, _ ->
                val seed = draw.nextLong()
                (0 until n).sortedBy { playKey(it.toLong()) xor seed }.toIntArray()
            },
        )
    }

    // Plays that only look random: the rows after a random start in list
    // order, and a start that is always the list's first song.
    private fun plantedPlays(): Map<String, Shuffler> {
        val seeds = Random(91)
        val starts = Random(92)
        return mapOf(
            "the rows after the start" to Shuffler { n, _ ->
                val start = starts.nextInt(n)
                IntArray(n) { (start + it) % n }
            },
            "always the first song first" to Shuffler { n, _ ->
                val seed = seeds.nextLong()
                (0 until n).sortedBy { playShuffledKey(playNumber(it, 0), seed, firstShuffled = true) }.toIntArray()
            },
        )
    }

    private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

    @Test
    fun everyPlantedOrderIsRejected() {
        for ((name, s) in planted()) {
            val rejected = sizes.flatMap { (n, runs) -> checks(s, n, runs).map { "n=$n $it" } }
            println("RESULT planted order $name rejected by ${rejected.size}: ${rejected.joinToString("; ")}")
            assertTrue("$name passed every check", rejected.isNotEmpty())
        }
        for ((name, s) in plantedPlays()) {
            val rejected = playChecks(s, 2_000)
            println("RESULT planted play $name rejected by ${rejected.size}: ${rejected.joinToString("; ")}")
            assertTrue("$name passed every check", rejected.isNotEmpty())
        }
    }
}
