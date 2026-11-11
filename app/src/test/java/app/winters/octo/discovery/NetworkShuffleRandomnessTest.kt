package app.winters.octo.discovery

import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.playback.ListSpec
import app.winters.octo.playback.OnlineKind
import app.winters.octo.playback.QueueStarts
import app.winters.octo.playback.StartRead
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.ui.paging.ListLoader
import app.winters.octo.ui.paging.StaticLoader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random

// A Shuffle of a big list the server answers for starts at the shown row
// whose song hashes smallest under a seed drawn for that Shuffle: every
// song is as likely as any other, however many times the list names it,
// and two Shuffles start apart. The statistics run on a fixed-seed test
// random through the production pick (seed, hash, smallest), so they are
// the same every run; the app's source is unseeded. Each check is shown to
// catch a planted bad picker.
@RunWith(RobolectricTestRunner::class)
class NetworkShuffleRandomnessTest {
    private fun track(id: String) = libraryTrack(id, "Song $id")

    // A pick of one row of `rows` for run `run`.
    private fun interface Picker {
        fun pick(rows: List<TrackEntity>, run: Int): String
    }

    private val base = openTestCatalog()
    private val reads = base.serverReads(null)

    @org.junit.After
    fun close() = base.close()

    // The production pick: the seeded hash, smallest wins, a seed per run
    // from one fixed-seed random.
    private fun production(seed: Long): Picker {
        val random = Random(seed)
        return Picker { rows, _ -> runBlocking { reads.shuffledRow(StaticLoader(rows), random.nextLong())!!.row.id } }
    }

    private fun planted(): Map<String, Picker> = mapOf(
        "always the first row" to Picker { rows, _ -> rows.first().id },
        "one seed for every Shuffle" to Picker { rows, _ -> runBlocking { reads.shuffledRow(StaticLoader(rows), 77L)!!.row.id } },
        // Uniform over rows: a song listed twice comes up twice as often.
        "a row at random" to Random(9).let { r -> Picker { rows, _ -> rows[r.nextInt(rows.size)].id } },
        // The hash with no seed mixed in: the same song every time.
        "an unseeded hash" to Picker { rows, _ -> rows.minBy { seededHash(0, it.id) }.id },
    )

    private fun chiSquare(counts: LongArray): Double {
        val expected = counts.sum().toDouble() / counts.size
        return counts.sumOf { (it - expected) * (it - expected) / expected }
    }

    // 19 degrees of freedom at p = 0.001.
    private val critical = 43.82

    private val songs = (0 until 20).map { track("s$it") }

    // The first song over 20 songs, 20,000 Shuffles.
    private fun uniform(p: Picker): Boolean {
        val counts = LongArray(20)
        repeat(20_000) { run -> counts[p.pick(songs, run).removePrefix("s").toInt()]++ }
        return chiSquare(counts) < critical
    }

    // A list naming song s0 thirty times among the 20: still one in 20.
    private fun twiceNotFavored(p: Picker): Boolean {
        val rows = songs + List(30) { songs[0] }
        val counts = LongArray(20)
        repeat(20_000) { run -> counts[p.pick(rows.shuffled(Random(run)), run).removePrefix("s").toInt()]++ }
        return chiSquare(counts) < critical
    }

    // Two Shuffles in a row start apart about as often as chance says (19
    // times in 20), never always together.
    private fun apart(p: Picker): Boolean {
        var same = 0
        repeat(2_000) { run -> if (p.pick(songs, 2 * run) == p.pick(songs, 2 * run + 1)) same++ }
        return same in 40..180
    }

    @Test
    fun theFirstSongIsUniform() {
        assertTrue(uniform(production(2_024)))
    }

    @Test
    fun aSongListedTwiceIsNotFavored() {
        assertTrue(twiceNotFavored(production(4_048)))
    }

    @Test
    fun twoShufflesStartApart() {
        assertTrue(apart(production(8_096)))
    }

    // Every planted picker fails at least one check, and the order is
    // never the list's own.
    @Test
    fun everyPlantedPickerIsCaught() {
        planted().forEach { (name, p) ->
            val passed = listOf(uniform(p), twiceNotFavored(p), apart(p))
            assertTrue("$name passed every check", passed.contains(false))
        }
    }

    // Through the play's own start, over a list a page holds: a fixed seed
    // gives the same start, and the app's own source gives different starts.
    @Test
    fun aPlaysShuffledStartComesFromItsOwnDraw() = runBlocking {
        val db = openTestCatalog()
        val server = ListServer()
        try {
            db.putLibrary((0 until 600).map { track("t$it") })
            server.lists["L"] = (0 until 600).map { "t$it" }
            val reads = db.serverReads(server, page = 200)
            reads.read(ServerListKind.List, "L", WorkOwners.marked("vm:1"))
            val starts = QueueStarts(db, { error("no loaders") }, { error("no live") }, { error("no health") }, app.winters.octo.catalog.WorkOwners(), { reads })
            fun first(seed: Long?) = runBlocking {
                seed?.let { starts.random = Random(it) }
                (starts.read(ListSpec.Online(OnlineKind.List, "L"), null, true, false, null, 0L) as StartRead.Big).also { assertTrue(it.firstShuffled) }.trackId
            }
            assertEquals(first(5), first(5))
            starts.random = Random.Default
            val picks = (0 until 12).map { first(null) }.toSet()
            assertTrue("$picks", picks.size > 1)
            @Suppress("UNCHECKED_CAST")
            val shown = reads.loader(WorkOwners.marked("vm:1")) as ListLoader<TrackEntity>
            val seed = Random(11).nextLong()
            starts.random = Random(11)
            assertEquals(reads.shuffledRow(shown, seed)!!.row.id, first(null))
            assertNotEquals(0, picks.size)
        } finally {
            server.close()
            db.close()
        }
    }
}
