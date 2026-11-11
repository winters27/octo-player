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
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

// A filtered list's jumps land only on rows the filter keeps: a shuffled
// start picks a kept row, and keyAt(p) is the p-th row of today's filtered
// list, from the survey's marks, for every p in a sample across the list.
class FilteredJumpTest {
    private fun song(i: Int) = TrackEntity(
        id = "device:$i", sourceId = "device", nativeId = "$i", title = "Song $i", searchKey = "song $i", sortKey = "song $i",
        artist = "Band", artistId = "ar", album = "", albumId = "al", trackNo = null, discNo = null, year = null,
        durationMs = 0, addedAt = 0, mimeType = null, sizeBytes = null, artwork = null, uri = null,
        // Kept rows come in uneven runs, with long dropped stretches.
        genre = if (i % 7 == 0 || i in 1_200 until 1_900) "Rock" else "Jazz",
    )

    @Test
    fun jumpsLandOnKeptRows() = runBlocking {
        val rows = (0 until 4_000).map(::song)
        val query = LibraryQuery(rules = listOf(QueryRule(QueryField.Genre, QueryOp.Is, text = "Rock")))
        val inputs = FilterInputs(
            liked = { emptySet() },
            plays = { emptyMap() },
            songs = { ids -> ids.toSet().let { wanted -> rows.filter { it.id in wanted } } },
            now = { 0L },
            random = Random(3),
        )
        val loader = FilteredLoader(StaticLoader(rows), query, inputs)
        val today = query.places(rows, 0L, TrackFields()).map { rows[it].id }
        val kept = today.toSet()

        val survey = loader.survey(null)
        assertEquals(today.size, survey.count)
        val start = survey.start!!
        assertEquals(today.first(), start.first!!.trackId)
        assertTrue(start.picks.isNotEmpty())
        start.picks.forEach { assertTrue(it.trackId, it.trackId in kept) }

        for (p in (0 until today.size step 37) + (today.size - 1)) {
            assertEquals("row $p", today[p], loader.keyAt(p)!!.id)
        }
        assertEquals(null, loader.keyAt(today.size))
    }
}
