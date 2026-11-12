package app.winters.octo.playback

import app.winters.octo.catalog.WorkOwners
import app.winters.octo.discovery.ListServer
import app.winters.octo.discovery.ServerHooks
import app.winters.octo.discovery.ServerListKind
import app.winters.octo.discovery.ServerStart
import app.winters.octo.discovery.libraryTrack
import app.winters.octo.discovery.putLibrary
import app.winters.octo.discovery.serverReads
import app.winters.octo.subsonic.Song
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.ui.paging.RowKey
import app.winters.octo.ui.paging.SqlValue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.pow
import kotlin.math.sqrt

// A big shuffled start walks the answer's places in a seeded order, each
// once: each place's entry alone, then whether an earlier entry has its
// song, and the first entry a row shows for wins. Over an answer of more
// than three pages, with repeats, finds and songs shown only once: the
// pick is uniform over the songs the list shows (chi-square, fixed seed),
// never an entry the copy hides, from every page; no walk tries a place
// twice, and each place tried is one resolve. With nothing to show, the
// walk ends empty after every place. Unshuffled, a list keeping library
// songs starts with its first one.
@RunWith(RobolectricTestRunner::class)
class ServerShuffleStartTest {
    private val db = openTestCatalog()
    private val songs = LinkedHashMap<String, Song>()
    private val server = ListServer(songs)
    private val owners = WorkOwners()
    private val reads = db.serverReads(server, page = 50, owners = owners)

    @After
    fun close() {
        server.close()
        db.close()
    }

    // 180 entries: 120 songs, every third one a library song by link, the
    // rest finds, with 60 repeats among them (each song at most twice).
    private fun answer(): String {
        db.putLibrary((0 until 120 step 3).map { libraryTrack("t$it", "Song ${wordOf(it)}", "Artist") }) { "s${it.id.removePrefix("t")}" }
        (0 until 120).forEach { songs["s$it"] = Song("s$it", title = "Song ${wordOf(it)}", artist = "Artist") }
        val entries = (0 until 120).map { "s$it" } + (0 until 60).map { "s${(it * 7) % 120}" }
        server.lists["S"] = entries.shuffled(kotlin.random.Random(9))
        return "S"
    }

    private fun drained(id: String): ServerStart = runBlocking { reads.drainStart(ServerListKind.Station, id, WorkOwners.marked("walk:${owners.next()}"))!! }

    // The copy's shown rows, (place, song), as the build makes them.
    private fun shown(id: String, libraryOnly: Boolean = false): List<Pair<Long, String>> = runBlocking {
        val owner = WorkOwners.marked("copy:${owners.next()}")
        reads.read(ServerListKind.Station, id, owner)
        val loader = reads.loader(owner, libraryOnly)
        val out = ArrayList<Pair<Long, String>>()
        var after: RowKey? = null
        while (true) {
            val rows = loader.after(after, 100)
            rows.forEach { out += ((it.key.values.first() as SqlValue.Int64).v to it.row.id) }
            if (rows.size < 100) break
            after = rows.last().key
        }
        out
    }

    // Every row of the copy, hidden ones included: place to song.
    private fun everyPlace(id: String): Map<Long, String> = runBlocking {
        val owner = WorkOwners.marked("copy:${owners.next()}")
        reads.read(ServerListKind.Station, id, owner)
        db.query("SELECT position, trackId FROM server_entry WHERE owner = ?", arrayOf(owner)).use { c ->
            buildMap { while (c.moveToNext()) put(c.getLong(0), c.getString(1)) }
        }
    }

    // The places each walk tried, in order, and the earlier entries it
    // checked for a tried entry's song.
    private class Tries : ServerHooks() {
        val tried = ArrayList<Long>()
        val checked = ArrayList<Pair<Long, Long>>()

        override fun tried(position: Long) {
            tried += position
        }

        override fun checked(position: Long) {
            checked += (tried.lastOrNull() ?: -1L) to position
        }
    }

    // A chi-square goodness of fit against uniform, against its 0.1% critical
    // value (Wilson and Hilferty's approximation).
    private fun uniform(counts: Collection<Int>): Boolean {
        val n = counts.sum().toDouble()
        val e = n / counts.size
        val chi = counts.sumOf { (it - e).pow(2) / e }
        val df = counts.size - 1.0
        val critical = df * (1 - 2 / (9 * df) + 3.09 * sqrt(2 / (9 * df))).pow(3)
        return chi < critical
    }

    @Test
    fun theShuffledPickIsUniformOverTheShownSongsFromEveryPage() {
        val id = answer()
        val copy = shown(id)
        val start = drained(id)
        val tries = Tries()
        reads.hooks = tries
        val resolver = reads.resolver(start)
        val counts = HashMap<String, Int>()
        val pages = HashSet<Long>()
        repeat(2_400) { draw ->
            tries.tried.clear()
            tries.checked.clear()
            val pick = runBlocking { resolver.shuffled(1_000L + draw, libraryOnly = false) }!!
            assertTrue("the pick ${pick.position} ${pick.trackId} is a row the copy shows", (pick.position to pick.trackId) in copy)
            assertEquals("no place tried twice", tries.tried.size, tries.tried.toSet().size)
            assertEquals("the walk ends at its pick", pick.position, tries.tried.last())
            // Each place tried is one resolve; only entries before it are
            // resolved again, to check for its song.
            assertTrue(tries.checked.all { (at, earlier) -> earlier < at })
            counts.merge(pick.trackId, 1, Int::plus)
            pages += pick.position / 50
        }
        assertEquals("every shown song was picked", copy.size, counts.size)
        assertTrue("uniform over the shown songs: $counts", uniform(counts.values))
        assertEquals("every page of the answer was hit", (0 until (start.count + 49) / 50).map { it.toLong() }.toSet(), pages)
        // The power check: a uniform place's song, whatever an earlier entry
        // has, favors songs listed twice and is caught.
        val places = everyPlace(id)
        val planted = HashMap<String, Int>()
        val random = kotlin.random.Random(1_000)
        repeat(2_400) { planted.merge(places.getValue(random.nextInt(start.count).toLong()), 1, Int::plus) }
        assertTrue("the power check rejects a pick that favors repeated songs", !uniform(planted.values))
        start.delete()
    }

    @Test
    fun aLibraryOnlyWalkPicksLibrarySongsAloneAndUniformly() {
        val id = answer()
        val copy = shown(id, libraryOnly = true)
        val start = drained(id)
        val resolver = reads.resolver(start)
        val counts = HashMap<String, Int>()
        repeat(1_200) { draw ->
            val pick = runBlocking { resolver.shuffled(5_000L + draw, libraryOnly = true) }!!
            assertTrue((pick.position to pick.trackId) in copy)
            counts.merge(pick.trackId, 1, Int::plus)
        }
        assertEquals(copy.size, counts.size)
        assertTrue("uniform over the library songs: $counts", uniform(counts.values))
        // Unshuffled, the first library song the copy shows.
        val first = runBlocking { resolver.first(libraryOnly = true) }!!
        assertEquals(copy.first(), first.position to first.trackId)
        start.delete()
    }

    @Test
    fun aWalkWithNothingToShowEndsEmptyAfterEveryPlace() {
        // Finds alone, kept to library songs: nothing shows.
        (0 until 150).forEach { songs["f$it"] = Song("f$it", title = "Found ${wordOf(it)}", artist = "Nobody") }
        server.lists["F"] = (0 until 150).map { "f$it" }
        assertEquals(emptyList<Pair<Long, String>>(), shown("F", libraryOnly = true))
        val start = drained("F")
        val tries = Tries()
        reads.hooks = tries
        assertNull(runBlocking { reads.resolver(start).shuffled(7, libraryOnly = true) })
        assertEquals("every place tried once", (0L until 150L).toList(), tries.tried.sorted())
        start.delete()
    }
}
