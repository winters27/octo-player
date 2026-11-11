package app.winters.octo.playback

import app.winters.octo.catalog.TrackEntity
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.ui.paging.ListLoader
import app.winters.octo.ui.paging.StaticLoader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Random
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.asKotlinRandom

// A shuffle is random and never the same pattern twice: the first song is
// picked uniformly, and the order after it (each row's shuffled key, a
// hash of its play key and a fresh seed per list) shows no trace of the
// list's order, for small lists and big ones alike. The seeds that drive
// the statistics come from a fixed-seed generator, through the queue's own
// key function and first pick, so every run reads the same numbers. Each
// statistic is also run against planted bad shuffles, which it must reject,
// so the test catches what it guards.
@RunWith(RobolectricTestRunner::class)
class ShuffleRandomnessTest {
    // An order of a list's n songs (positions in the list, in the order
    // they play), the r-th of many.
    private fun interface Shuffler {
        fun order(n: Int, run: Int): IntArray
    }

    // The queue's order for one seed: every row's shuffled key, as a play
    // from row `start` writes it.
    private fun queueOrder(seed: Long, n: Int, firstShuffled: Boolean = false, start: Int = 0): IntArray =
        byKeys(n) { i -> playShuffledKey(playNumber(i, start), seed, firstShuffled && i == start) }

    private fun byKeys(n: Int, key: (Int) -> Long): IntArray {
        val keys = LongArray(n) { key(it) }
        return (0 until n).sortedWith(compareBy({ keys[it] }, { it })).toIntArray()
    }

    // The queue's order with a fresh seed each run.
    private fun queue(): Shuffler {
        val draw = Random(0xC0FFEE)
        return Shuffler { n, _ -> queueOrder(draw.nextLong(), n) }
    }

    // Planted bad shuffles, each a way a shuffle can look random and not be.
    private fun planted(): Map<String, Shuffler> {
        val draw = Random(77)
        return mapOf(
            "identity" to Shuffler { n, _ -> IntArray(n) { it } },
            "constant seed" to Shuffler { n, _ -> queueOrder(1234567L, n) },
            "affine key" to Shuffler { n, _ ->
                val seed = draw.nextLong()
                byKeys(n) { i -> -0x61c8864680b583ebL * i + seed }
            },
            "xor with no mix" to Shuffler { n, _ ->
                val seed = draw.nextLong()
                byKeys(n) { i -> playKey(playNumber(i, 0)) xor seed }
            },
        )
    }

    // ---- The statistics: each answers whether it accepts a shuffler ----

    private class Verdict(val name: String, val accepted: Boolean, val detail: String)

    private fun ranks(order: IntArray): IntArray = IntArray(order.size).also { r -> order.forEachIndexed { pos, song -> r[song] = pos } }

    // Spearman's correlation between a song's list place and its place in
    // the order.
    private fun spearman(order: IntArray): Double {
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

    private fun meanNearZero(s: Shuffler, n: Int, runs: Int): Verdict {
        val c = correlations(s, n, runs)
        val mean = c.average()
        val bound = 4 * (1 / sqrt(n - 1.0)) / sqrt(runs.toDouble())
        return Verdict("spearman mean n=$n", abs(mean) < bound, "mean $mean, bound $bound")
    }

    private fun spreadMatches(s: Shuffler, n: Int, runs: Int): Verdict {
        val c = correlations(s, n, runs)
        val mean = c.average()
        val sd = sqrt(c.sumOf { (it - mean) * (it - mean) } / (runs - 1))
        val expected = 1 / sqrt(n - 1.0)
        return Verdict("spearman spread n=$n", abs(sd - expected) <= 0.15 * expected, "sd $sd, expected $expected")
    }

    // List neighbors ending up next to each other: 2(n-1)/n a run by chance.
    private fun neighborsAtChance(s: Shuffler, n: Int, runs: Int): Verdict {
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
    private fun successionsAtChance(s: Shuffler, n: Int, runs: Int): Verdict {
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
    private fun noRepeats(s: Shuffler, n: Int, runs: Int): Verdict {
        val seen = HashSet<List<Int>>()
        val repeats = (0 until runs).count { !seen.add(s.order(n, it).toList()) }
        return Verdict("no repeats n=$n", repeats == 0, "$repeats repeats")
    }

    // A song's place in the order is uniform over the places after the
    // first song, which a fixed start holds.
    private fun placeUniform(s: Shuffler, n: Int, runs: Int, song: Int, critical: Double): Verdict {
        val counts = LongArray(n - 1)
        repeat(runs) { run -> ranks(s.order(n, run))[song].let { if (it > 0) counts[it - 1]++ else counts[0] += n.toLong() } }
        val chi = chiSquare(counts)
        return Verdict("place of song $song after a fixed start n=$n", chi < critical, "chi-square $chi, critical $critical")
    }

    private fun chiSquare(counts: LongArray): Double {
        val expected = counts.sum().toDouble() / counts.size
        return counts.sumOf { (it - expected) * (it - expected) / expected }
    }

    // `fixed` is the same shuffle with its first song fixed (song 0).
    private fun battery(s: Shuffler, fixed: Shuffler = s): List<Verdict> = listOf(
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

    // The queue's order with the first song fixed, as a shuffled play's
    // first pick holds it.
    private fun queueFromFixedStart(): Shuffler {
        val draw = Random(0xBEEF)
        return Shuffler { n, _ -> queueOrder(draw.nextLong(), n, firstShuffled = true, start = 0) }
    }

    @Test
    fun theQueuesOrderPassesEveryCheck() {
        val failed = battery(queue(), queueFromFixedStart()).filterNot { it.accepted }
        assertTrue(failed.joinToString("\n") { "${it.name}: ${it.detail}" }, failed.isEmpty())
    }

    @Test
    fun everyPlantedShuffleIsRejected() {
        val report = StringBuilder()
        for ((name, s) in planted()) {
            val verdicts = battery(s)
            val rejected = verdicts.filterNot { it.accepted }
            report.append("RESULT planted $name rejected by ${rejected.size} of ${verdicts.size}: ${rejected.joinToString { it.name }}\n")
            assertTrue("$name passed every check:\n" + verdicts.joinToString("\n") { "${it.name}: ${it.detail}" }, rejected.isNotEmpty())
        }
        println(report)
    }

    // The checks that guard a fresh order per shuffle reject a seed reused.
    @Test
    fun aSeedReusedIsCaught() {
        val constant = planted().getValue("constant seed")
        assertTrue(!noRepeats(constant, 12, 50).accepted)
        assertTrue(!spreadMatches(constant, 12, 2_000).accepted)
    }

    // ---- The first song ----

    // 19 degrees of freedom at p = 0.001.
    private val firstSongCritical = 43.82

    @Test
    fun theSmallPathsFirstSongIsUniform() {
        val random = Random(2024).asKotlinRandom()
        val counts = LongArray(20)
        repeat(20_000) { counts[firstPick(random, 20)]++ }
        val chi = chiSquare(counts)
        assertTrue("chi-square $chi", chi < firstSongCritical)
    }

    @Test
    fun theBigPathsFirstSongIsUniform() = runBlocking {
        val db = openTestCatalog()
        val starts = QueueStarts(db, { error("no loaders") }, { error("no live lists") }, { error("no health") }, app.winters.octo.catalog.WorkOwners())
        starts.random = Random(4048).asKotlinRandom()
        @Suppress("UNCHECKED_CAST")
        val loader = StaticLoader((0 until 20).map { track("s$it") }) as ListLoader<Any>
        val counts = LongArray(20)
        repeat(20_000) {
            val read = starts.shuffledStart(loader) as StartRead.Big
            assertTrue(read.firstShuffled)
            counts[read.trackId.removePrefix("s").toInt()]++
        }
        db.close()
        val chi = chiSquare(counts)
        assertTrue("chi-square $chi", chi < firstSongCritical)
    }

    // The first song plays first, and then every other song once.
    @Test
    fun aShuffledStartPlaysFirstThenEverySongOnce() {
        val draw = Random(99)
        repeat(200) {
            val n = 2 + draw.nextInt(300)
            val start = draw.nextInt(n)
            val order = queueOrder(draw.nextLong(), n, firstShuffled = true, start = start)
            assertEquals(start, order[0])
            assertEquals((0 until n).toSet(), order.toSet())
            assertEquals(n, order.size)
        }
    }

    private fun track(id: String) = TrackEntity(
        id = id, sourceId = "device", nativeId = id, title = id, searchKey = id, sortKey = id,
        artist = "", artistId = "", album = "", albumId = "", trackNo = null, discNo = null, year = null,
        durationMs = 0, addedAt = 0, mimeType = null, sizeBytes = null, artwork = null, uri = null,
    )
}
