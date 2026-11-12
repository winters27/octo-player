package app.winters.octo.discovery

import app.winters.octo.catalog.TrackEntity
import app.winters.octo.ui.paging.Keyed
import app.winters.octo.ui.paging.RowKey
import app.winters.octo.ui.paging.SqlValue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// A big album's main song, found by one walk of its rows a page at a time,
// equals albumHighlight over the whole list: matched by id, by title alone
// with other extras ("Song [feat. Y]" against "Song (feat. X)"), or not at
// all; and the walk stops once the best rank has its id.
class HugeAlbumHighlightTest {
    private fun song(i: Int, title: String = "Track $i", artist: String = "Band") = TrackEntity(
        id = "device:$i", sourceId = "device", nativeId = "$i", title = title, searchKey = title.lowercase(), sortKey = title.lowercase(),
        artist = artist, artistId = "ar", album = "Unknown album", albumId = "al", trackNo = null, discNo = null, year = null,
        durationMs = 0, addedAt = 0, mimeType = null, sizeBytes = null, artwork = null, uri = null, albumOrder = i,
    )

    private val page = 400

    // The rows a page at a time, counting the pages read.
    private class Pages(val rows: List<TrackEntity>, val size: Int) {
        var read = 0
        private val keyed = rows.mapIndexed { i, t -> Keyed(RowKey(0, listOf(SqlValue.Int64(i.toLong())), t.id), t) }

        suspend fun after(key: RowKey?): List<Keyed<TrackEntity>> {
            read++
            val from = if (key == null) 0 else keyed.indexOfFirst { it.key == key } + 1
            return keyed.subList(from, minOf(from + size, keyed.size))
        }
    }

    private fun album(): MutableList<TrackEntity> = (0 until 3_000).map { song(it) }.toMutableList()

    private fun check(rows: List<TrackEntity>, ranked: List<RankedSong>): Pages {
        val pages = Pages(rows, page)
        val walked = runBlocking { highlightWalk(ranked) { pages.after(it) } }
        assertEquals(albumHighlight(rows, ranked), walked)
        return pages
    }

    @Test
    fun byIdOnPageSeven() {
        val rows = album()
        val ranked = listOf(RankedSong("server:1", "Elsewhere", "Other"), RankedSong("device:2500", "Track 2500", "Band"))
        check(rows, ranked)
    }

    @Test
    fun byTitleAloneWithOtherExtras() {
        val rows = album()
        rows[2_600] = song(2_600, "Song [feat. Y]")
        rows[2_700] = song(2_700, "Song (feat. Z)")
        val ranked = listOf(RankedSong("server:9", "Song (feat. X)", "Band"))
        val pages = check(rows, ranked)
        // No id matched, so every page was read, and the empty one after.
        assertEquals(9, pages.read)
        assertTrue(rows.size > page * 7)
    }

    @Test
    fun aBetterRankByTitleBeatsAWorseRankById() {
        val rows = album()
        rows[2_450] = song(2_450, "Hit")
        val ranked = listOf(RankedSong("server:hit", "Hit", "Band"), RankedSong("device:10", "Track 10", "Band"))
        check(rows, ranked)
    }

    @Test
    fun noSongAmongTheTopSongs() {
        val ranked = listOf(RankedSong("server:1", "Nothing here", "Band"), RankedSong("server:2", "", "Band"))
        check(album(), ranked)
    }

    @Test
    fun theWalkStopsOnceTheBestRankHasItsId() {
        val rows = album()
        val pages = check(rows, listOf(RankedSong("device:5", "Track 5", "Band")))
        assertEquals(1, pages.read)
    }
}
