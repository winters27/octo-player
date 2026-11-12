package app.winters.octo.ui.paging

import app.winters.octo.catalog.TrackEntity
import app.winters.octo.query.LibraryQuery
import app.winters.octo.query.QueryField
import app.winters.octo.query.QueryOp
import app.winters.octo.query.QueryRule
import app.winters.octo.query.places
import app.winters.octo.sort.TrackFields
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.exp
import kotlin.random.Random

// Shuffle on a filtered list starts from a song the filter keeps, picked at
// random by the count's pass: 16 picks a pass, each slot replaced by the
// i-th kept row with chance 1/i by a draw of its own. On a 20,000-row list
// keeping about a third, with a fixed-seed random driving the production
// pick code:
// - the picks of many passes are uniform over the kept rows, every slot on
//   its own and all together (chi-square under p = 0.001);
// - no two slots agree more often than chance;
// - in production the draws come from an unseeded source, never one made
//   from the list or the filter, so two passes of one filter pick apart;
// - the same statistics reject planted bad pickers: always the first kept
//   row, one draw shared by every slot, a fixed seed.
// Play's start is the list's first kept row however far the page has
// scrolled, and a pass hands Shuffle 16 kept rows to start from without
// reading again.
class FilteredShufflePickTest {
    private val rows = (0 until 20_000).map(::song)
    private val query = LibraryQuery(rules = listOf(QueryRule(QueryField.Genre, QueryOp.Is, text = "Rock")))
    private val kept: List<String> = query.places(rows, 0L, TrackFields()).map { rows[it].id }
    private val placeOf: Map<String, Int> = kept.withIndex().associate { (i, id) -> id to i }

    // Kept rows come in uneven runs: one in three, and a solid stretch.
    private fun song(i: Int) = TrackEntity(
        id = "device:$i", sourceId = "device", nativeId = "$i", title = "Song $i", searchKey = "song $i", sortKey = "song %05d".format(i),
        artist = "Band", artistId = "ar", album = "", albumId = "al", trackNo = null, discNo = null, year = null,
        durationMs = 0, addedAt = 0, mimeType = null, sizeBytes = null, artwork = null, uri = null,
        genre = if (i % 3 == 0 || i in 7_000 until 8_500) "Rock" else "Jazz",
    )

    // The list in memory, each page read by its place, so a pass costs what
    // the matcher costs.
    private inner class InMemory : ListLoader<TrackEntity> {
        private fun key(i: Int) = RowKey(0, listOf(SqlValue.Int64(i.toLong())), rows[i].id)
        private fun place(key: RowKey) = ((key.values[0] as SqlValue.Int64).v).toInt()
        override val tables = emptyArray<String>()
        override val order: Comparator<RowKey> = SqlOrder.rows()
        override val songsOnce = true
        override suspend fun count() = rows.size
        override suspend fun survey(anchor: RowKey?) = Survey(rows.size, null, anchor?.let(::place))
        override suspend fun after(key: RowKey?, limit: Int): List<Keyed<TrackEntity>> {
            val from = key?.let { place(it) + 1 } ?: 0
            return (from until minOf(rows.size, from + limit)).map { Keyed(key(it), rows[it]) }
        }
        override suspend fun before(key: RowKey, limit: Int): List<Keyed<TrackEntity>> =
            (place(key) - 1 downTo maxOf(0, place(key) - limit)).map { Keyed(key(it), rows[it]) }
        override suspend fun keyAt(position: Int) = if (position in rows.indices) key(position) else null
        override suspend fun keyOf(trackId: String) = rows.indexOfFirst { it.id == trackId }.takeIf { it >= 0 }?.let(::key)
        override suspend fun rekey(key: RowKey): RowKey? = key
        override suspend fun present(ids: List<String>) = ids.toSet()
    }

    // What a filtered list reads besides its songs, as production gives it
    // (`random` left to its default) or with the random given.
    private fun inputs(random: Random? = null): FilterInputs {
        val liked: suspend (List<String>) -> Set<String> = { emptySet() }
        val plays: suspend (List<String>) -> Map<String, app.winters.octo.sort.Listening> = { emptyMap() }
        val songs: suspend (List<String>) -> List<TrackEntity> = { ids -> ids.map { rows[it.removePrefix("device:").toInt()] } }
        return if (random == null) FilterInputs(liked, plays, songs, now = { 0L }) else FilterInputs(liked, plays, songs, now = { 0L }, random = random)
    }

    // One pass's 16 picks, as places among the kept rows.
    private fun picks(inputs: FilterInputs): List<Int> = runBlocking {
        val start = FilteredLoader(InMemory(), query, inputs).survey(null).start!!
        assertEquals(SURVEY_PICKS, start.picks.size)
        start.picks.map { placeOf[it.trackId] ?: throw AssertionError("a pick the filter drops: ${it.trackId}") }
    }

    // The 0.999 points of chi-square for the degrees of freedom used here.
    private val critical = mapOf(19 to 43.820)

    private val bins = 20

    private fun chiSquare(places: List<Int>): Double {
        val counts = IntArray(bins)
        places.forEach { counts[it * bins / kept.size]++ }
        // Every bin holds the same number of kept rows give or take one.
        return counts.indices.sumOf { b ->
            val width = (b + 1) * kept.size / bins - b * kept.size / bins
            val expected = places.size.toDouble() * width / kept.size
            (counts[b] - expected).let { it * it / expected }
        }
    }

    // The smallest count a Poisson count of mean `mean` stays at or under
    // with chance 0.999.
    private fun poissonBound(mean: Double): Int {
        var p = exp(-mean)
        var sum = p
        var k = 0
        while (sum < 0.999) {
            k++
            p *= mean / k
            sum += p
        }
        return k
    }

    // How the statistics judge a picker over `surveys` passes: the
    // uniformity of all picks and of each slot, and the slots' agreements.
    private class Verdict(val pooled: Double, val slots: List<Double>, val agreements: Int, val agreementBound: Int)

    private fun judge(surveys: Int, inputsOf: () -> FilterInputs): Verdict {
        val passes = List(surveys) { picks(inputsOf()) }
        val pooled = chiSquare(passes.flatten())
        val slots = (0 until SURVEY_PICKS).map { slot -> chiSquare(passes.map { it[slot] }) }
        var agreements = 0
        for (pass in passes) for (i in 0 until SURVEY_PICKS) for (j in i + 1 until SURVEY_PICKS) if (pass[i] == pass[j]) agreements++
        val pairs = SURVEY_PICKS * (SURVEY_PICKS - 1) / 2
        return Verdict(pooled, slots, agreements, poissonBound(surveys.toDouble() * pairs / kept.size))
    }

    private fun Verdict.uniform() = pooled < critical.getValue(bins - 1) && slots.all { it < critical.getValue(bins - 1) }

    private fun Verdict.independent() = agreements <= agreementBound

    @Test
    fun picksAreUniformOverTheKeptRowsEachSlotOnItsOwnAndApart() {
        assertTrue("the filter keeps uneven runs", kept.size in 7_000..8_500)
        val random = Random(20_261_106)
        val verdict = judge(300) { inputs(random) }
        assertTrue("all picks uniform, chi-square ${verdict.pooled}", verdict.pooled < critical.getValue(bins - 1))
        verdict.slots.forEachIndexed { slot, chi -> assertTrue("slot $slot uniform, chi-square $chi", chi < critical.getValue(bins - 1)) }
        assertTrue("slots agree ${verdict.agreements} times, chance allows ${verdict.agreementBound}", verdict.independent())
    }

    @Test
    fun productionDrawsAreUnseededSoTwoPassesOfOneFilterPickApart() {
        val production = inputs()
        assertSame("production draws from the unseeded default", Random.Default, production.random)
        val first = picks(production)
        val second = picks(inputs())
        val third = picks(production)
        assertNotEquals(first, second)
        assertNotEquals(first, third)
        assertNotEquals(second, third)
        // Every production filter leaves the source to its default: none is
        // seeded from the list or the filter.
        val root = File(System.getProperty("octo.mainSources")!!)
        val made = root.walkTopDown().filter { it.extension == "kt" }.flatMap { file ->
            val text = file.readText()
            Regex("""\bFilterInputs\(""").findAll(text).filterNot { text.lastIndexOf("class ", it.range.first) > text.lastIndexOf('\n', it.range.first) }
                .map { file.name to callText(text, it.range.last) }
        }.toList()
        assertTrue("production makes its filters' inputs", made.isNotEmpty())
        made.forEach { (file, call) -> assertFalse("$file gives a filter's inputs a random of its own: $call", Regex("""\brandom\s*=""").containsMatchIn(call) || "Random(" in call) }
    }

    // The text of a call from its opening parenthesis to the one closing it.
    private fun callText(text: String, open: Int): String {
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return text.substring(open, i + 1)
            }
        }
        return text.substring(open)
    }

    @Test
    fun theStatisticsRejectPlantedBadPickers() {
        // Always the first kept row: no draw ever replaces the first pick.
        val first = judge(60) { inputs(FirstOnly) }
        assertFalse("always the first kept row passes as uniform", first.uniform())
        // One draw for every slot: each slot uniform, all of them alike.
        val shared = Random(7)
        val oneDraw = judge(60) { inputs(SharedDraw(shared)) }
        assertTrue("a shared draw is uniform slot by slot", oneDraw.slots.count { it < critical.getValue(bins - 1) } >= SURVEY_PICKS - 1)
        assertFalse("one shared draw passes as independent: ${oneDraw.agreements}", oneDraw.independent())
        // A fixed seed: every pass picks the same rows.
        val seeded = judge(60) { inputs(Random(42)) }
        assertFalse("a fixed seed passes as uniform", seeded.uniform())
        assertEquals("a fixed seed picks one pass's rows again", picks(inputs(Random(42))), picks(inputs(Random(42))))
    }

    @Test
    fun playStartsAtTheFirstKeptRowAndShuffleHasSixteenKeptRowsAtHand() = runBlocking {
        val loader = FilteredLoader(InMemory(), query, inputs(Random(5)))
        // Pages read far down the list change nothing a pass keeps.
        loader.after(loader.keyAt(5_000), 300)
        val start = loader.survey(null).start!!
        assertEquals(kept.first(), start.first!!.trackId)
        assertEquals(kept.size, start.count)
        assertEquals(SURVEY_PICKS, start.picks.size)
        start.picks.forEach { assertTrue(it.trackId in placeOf) }
        assertEquals(start.picks.map { it.trackId }, start.picks.map { rows[(it.key.values[0] as SqlValue.Int64).v.toInt()].id })
    }

    // A source that never draws 0 past the first row: every slot keeps the
    // first kept row.
    private object FirstOnly : Random() {
        override fun nextBits(bitCount: Int): Int = 0

        override fun nextInt(until: Int): Int = until - 1
    }

    // A source whose draws come in runs of SURVEY_PICKS alike: every slot of
    // one row shares one draw.
    private class SharedDraw(private val inner: Random) : Random() {
        private var calls = 0
        private var draw = 0

        override fun nextBits(bitCount: Int): Int = inner.nextBits(bitCount)

        override fun nextInt(until: Int): Int {
            if (calls++ % SURVEY_PICKS == 0) draw = inner.nextInt(until)
            return draw
        }
    }
}
