package app.winters.octo.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.searchKey
import app.winters.octo.sort.SongScope
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortOrder
import app.winters.octo.testing.counted
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.ui.common.LetterRun
import app.winters.octo.ui.common.letterRuns
import app.winters.octo.ui.paging.ListLoader
import app.winters.octo.ui.paging.RowKey
import app.winters.octo.ui.paging.SongLists
import app.winters.octo.ui.paging.StaticLoader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// A long list's answer for a car, read a page at a time from the list's
// own loader: where today's whole list fits, its letters, its parts, a
// letter's parts and a part are today's answer item for item (a labeled
// pin against today's CarListing answers, which the car still uses for its
// album-sized lists). Past where today breaks, part rows that would not
// fit one answer get a second level, and nothing ever passes the budget.
@RunWith(RobolectricTestRunner::class)
class CarPagedTest {
    private fun item(id: String, title: String, cover: String? = null) = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setSubtitle("Artist").setArtworkUri(cover?.let { Uri.parse("content://a/512/$it") }).build())
        .build()

    // Rows that show where they run, what they start with, what they say of
    // their count and the covers they take their colors from.
    private val rows = CarSplitRows(
        letter = { node, _, covers, count -> item(carId(node), "${node.heading} ${count.orEmpty()}", covers.joinToString(",").ifEmpty { null }) },
        part = { node, range, first, _, covers -> item(carId(node), "${partTitle(range)} ${first.orEmpty()}", covers.joinToString(",").ifEmpty { null }) },
    )

    private fun song(n: Int, key: String) = TrackEntity(
        id = "s$n", sourceId = "device", nativeId = "s$n", title = key, searchKey = searchKey(key), sortKey = key,
        artist = "Artist", artistId = "ar", album = "Album", albumId = "al${n / 3}", trackNo = n, discNo = 1, year = null,
        durationMs = 1_000, addedAt = 0, mimeType = null, sizeBytes = null, artwork = "cover-${n / 3}", uri = null,
    )

    // A list over songs in memory, filed under the letters of their keys
    // when `lettered`.
    private class Lettered(private val base: StaticLoader, private val runs: List<LetterRun>?) : ListLoader<TrackEntity> by base {
        override suspend fun survey(anchor: RowKey?) = base.survey(anchor).copy(runs = runs)
    }

    private fun listing(songs: List<TrackEntity>, node: CarNode, lettered: Boolean) = CarPagedListing(
        Lettered(StaticLoader(songs), if (lettered) letterRuns(songs.map { it.sortKey }) else null),
        style = CarStyle.List,
        countLine = { "$it songs" },
        lettered = lettered,
    ) { page -> page.map { CarRow(item(carId(CarNode.Song(it.id, node)), it.title), it.artwork) } }

    private fun today(songs: List<TrackEntity>, node: CarNode, lettered: Boolean) = CarListing(
        songs.size,
        if (lettered) carLetters(songs.map { it.sortKey }) else null,
        CarStyle.List,
        { "$it songs" },
        { songs[it].artwork },
    ) { item(carId(CarNode.Song(songs[it].id, node)), songs[it].title) }

    private fun paged(node: CarNode, base: CarNode, listing: CarPagedListing<TrackEntity>, budget: Int) =
        runBlocking { carPagedSend(listing, carPagedPlan(node, base, listing, rows, budget), budget) }

    private fun shown(items: List<MediaItem>) = items.map { listOf(it.mediaId, it.mediaMetadata.title.toString(), it.mediaMetadata.artworkUri?.toString()) }

    private fun keys(n: Int, letters: String = "ABC") = (0 until n).map { i -> "${letters[i * letters.length / n]}name %05d".format(i) }

    private val budget = 40 * carBytes(item(carId(CarNode.Song("s00000", CarNode.Songs)), "Bname 00000"))

    @Test
    fun aListThatFitsIsTodaysWholeAnswer() {
        val songs = keys(30).mapIndexed(::song)
        val got = paged(CarNode.Songs, CarNode.Songs, listing(songs, CarNode.Songs, lettered = true), budget)
        assertEquals(30, got.size)
        assertEquals(shown(carListAnswer(CarNode.Songs, today(songs, CarNode.Songs, true), rows, budget)), shown(got))
    }

    @Test
    fun aLongListByNameIsTodaysLetters() {
        val songs = keys(120).mapIndexed(::song)
        val got = paged(CarNode.Songs, CarNode.Songs, listing(songs, CarNode.Songs, lettered = true), budget)
        assertEquals(listOf("A", "B", "C").map { carId(CarNode.Heading(it, CarNode.Songs)) }, got.map { it.mediaId })
        assertEquals(shown(carListAnswer(CarNode.Songs, today(songs, CarNode.Songs, true), rows, budget)), shown(got))
    }

    @Test
    fun aLongListInAnotherOrderIsTodaysParts() {
        val songs = keys(150).mapIndexed(::song)
        val node = CarNode.Liked
        val got = paged(node, node, listing(songs, node, lettered = false), budget)
        assertTrue(got.size > 1)
        assertEquals(shown(carListAnswer(node, today(songs, node, false), rows, budget)), shown(got))
    }

    @Test
    fun aLettersPartsAndAPartsItemsAreTodays() {
        val songs = keys(200, "AB").mapIndexed(::song)
        val heading = CarNode.Heading("A", CarNode.Songs)
        val list = today(songs, CarNode.Songs, true)
        val got = paged(heading, CarNode.Songs, listing(songs, CarNode.Songs, lettered = true), budget)
        assertTrue("the letter is cut into parts", got.size > 1)
        assertEquals(shown(carLetterAnswer(heading, list, rows, budget)), shown(got))
        // Each part of the letter, and each part of a list in another order.
        for (index in 0..got.size) {
            val part = CarNode.Part(index, heading)
            assertEquals("part $index", shown(carPartItems(index, list.under("A"), budget)), shown(paged(part, CarNode.Songs, listing(songs, CarNode.Songs, lettered = true), budget)))
        }
        val other = today(songs, CarNode.Liked, false)
        for (index in 0..6) {
            val part = CarNode.Part(index, CarNode.Liked)
            assertEquals("part $index", shown(carPartItems(index, other.all(), budget)), shown(paged(part, CarNode.Liked, listing(songs, CarNode.Liked, lettered = false), budget)))
        }
    }

    // A genre's albums come before its songs, and count in its parts.
    @Test
    fun whatComesBeforeTheRowsIsPartOfTheList() {
        val songs = keys(80).mapIndexed(::song)
        val node = CarNode.Genre("Rock")
        val albums = (0 until 30).map { CarRow(item("album:$it", "Album $it"), null) }
        val list = CarPagedListing(Lettered(StaticLoader(songs), null), lead = albums) { page -> page.map { CarRow(item(carId(CarNode.Song(it.id, node)), it.title), null) } }
        val whole = albums.map { it.item } + songs.map { item(carId(CarNode.Song(it.id, node)), it.title) }
        val todays = CarListing.of(whole)
        assertEquals(shown(carListAnswer(node, todays, rows, budget)), shown(paged(node, node, list, budget)))
        for (index in 0..4) assertEquals(shown(carPartItems(index, whole, budget)), shown(paged(CarNode.Part(index, node), node, list, budget)))
    }

    // Past where today's parts answer would itself pass the budget, the part
    // rows are packed again into a second level: the list answers rows that
    // each open the part rows they cover, and those open their items.
    @Test
    fun partsThatWouldNotFitGetASecondLevel() {
        val songs = (0 until 3_000).map { song(it, "name %05d".format(it)) }
        val node = CarNode.Liked
        val small = 20 * carBytes(item(carId(CarNode.Song("s00000", node)), "name 00000"))
        val list = listing(songs, node, lettered = false)
        val top = paged(node, node, list, small)
        assertTrue("the answer fits", top.sumOf(::carBytes) <= small)
        assertTrue("today's parts answer would not", carListAnswer(node, today(songs, node, false), rows, small).sumOf(::carBytes) > small)
        val all = songs.map { item(carId(CarNode.Song(it.id, node)), it.title) }
        val firstLevel = carParts(all.map(::carBytes), small)
        var covered = 0
        top.forEachIndexed { j, row ->
            val group = CarNode.Part(j, node)
            assertEquals(carId(group), row.mediaId)
            assertTrue(group.isBrowsable)
            assertEquals(group, parseCarId(carId(group)))
            val inner = paged(group, node, list, small)
            assertTrue(inner.isNotEmpty())
            assertTrue(inner.sumOf(::carBytes) <= small)
            for (rowItem in inner) {
                val nested = parseCarId(rowItem.mediaId) as CarNode.Part
                assertEquals(group, nested.parent)
                assertTrue(nested.isBrowsable)
                // A part of the second level answers its own items, the
                // same as the list's part with its number.
                assertEquals(shown(carPartItems(nested.index, all, small)), shown(paged(nested, node, list, small)))
                assertEquals(firstLevel[nested.index].count(), paged(nested, node, list, small).size)
                // Under another row of the second level it answers nothing.
                assertEquals(emptyList<MediaItem>(), paged(CarNode.Part(nested.index, CarNode.Part(j + 1, node)), node, list, small))
                covered++
            }
        }
        assertEquals("every part sits under one row", firstLevel.size, covered)
        assertEquals("a part past the end answers nothing", emptyList<MediaItem>(), paged(CarNode.Part(top.size, node), node, list, small))
    }

    // A plan made before the list grew (a swap between a last try's passes
    // and its reads) still sends no more than one answer.
    @Test
    fun aPlanOutdatedByAChangeStaysWithinTheBudget() {
        val node = CarNode.Liked
        val before = listing(keys(20).mapIndexed(::song), node, lettered = false)
        val plan = runBlocking { carPagedPlan(node, node, before, rows, budget) }
        assertTrue(plan is CarPlan.Items)
        val after = listing(keys(400).mapIndexed(::song), node, lettered = false)
        val sent = runBlocking { carPagedSend(after, plan, budget) }
        assertTrue(sent.sumOf(::carBytes) <= budget)
        assertTrue(sent.size in 21..400)
    }

    // The car's Songs over the library, a page at a time: no read asks for
    // more than a page, however long the list; the answer and its parts
    // each fit one answer.
    @Test
    fun aLongLibraryListIsReadAPageAtATime() {
        val db = openTestCatalog()
        try {
            db.songs(5_000)
            val loader = SongLists(db.listsDao()).loader(SongScope.All, SortOrder(SongSort.Title, false))
            val node = CarNode.Downloads
            val list = CarPagedListing(loader) { page -> page.map { CarRow(item(carId(CarNode.Song(it.id, node)), it.title), null) } }
            val (answers, reads) = db.counted.record {
                val top = paged(node, node, list, budget)
                listOf(top, paged(CarNode.Part(0, node), node, list, budget), paged(CarNode.Part(top.size - 1, node), node, list, budget))
            }
            val own = reads.filterNot { it.bookkeeping }
            assertTrue("reads were recorded", own.isNotEmpty())
            assertTrue("rows per read: ${own.maxByOrNull { it.rowsRead }}", own.all { it.rowsRead <= CAR_PAGE + 1 })
            assertTrue("the list was cut into parts", answers[0].size > 1)
            answers.forEach { assertTrue(it.isNotEmpty() && it.sumOf(::carBytes) <= budget) }
        } finally {
            db.close()
        }
    }
}
