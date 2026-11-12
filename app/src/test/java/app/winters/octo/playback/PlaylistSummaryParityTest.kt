package app.winters.octo.playback

import app.winters.octo.listening.timed
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random

// A labeled pin: the summaries, read a playlist at a time, equal today's
// over every entry (catalog/today/TodayPlaylistSummaries.kt) on generated
// playlists with library songs, finds with and without an album or a
// cover, songs the library no longer has, songs with no cover, albums
// repeated, empty playlists and playlists of another server.
@RunWith(RobolectricTestRunner::class)
class PlaylistSummaryParityTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    @Test
    fun summariesEqualTodaysOnGeneratedPlaylists() = timed(30) {
        val random = Random(47)
        val songs = (0 until 600).map { n -> songRow("device:s$n", album = "al${n / 7}", art = if (random.nextInt(4) == 0) null else "art:$n", ms = 1_000L + n) }
        db.dao().insertTracks(songs)
        val finds = (0 until 40).map { n ->
            findRow("find:f$n", album = if (n % 5 == 0) null else "fa${n / 3}", cover = if (n % 4 == 0) null else "c$n", ms = 5_000L + n)
        }
        db.onlineDao().insert(finds)
        repeat(30) { p ->
            val size = when (p % 6) {
                0 -> 0
                1 -> random.nextInt(1, 4)
                else -> random.nextInt(5, 250)
            }
            val items = List(size) {
                when (random.nextInt(10)) {
                    0 -> "find:f${random.nextInt(40)}"
                    // A song the library no longer has.
                    1 -> "device:gone${random.nextInt(9)}"
                    else -> "device:s${random.nextInt(600)}"
                }
            }
            val source = when (p % 5) {
                3 -> "server:h"
                4 -> "server:other"
                else -> null
            }
            db.playlist("p$p", items, updatedAt = 1_000L + p * 7, sourceId = source)
        }
        assertEquals(db.todaySummaries("server:h"), db.summaries("server:h", page = 9).all.first())
        assertEquals(db.todaySummaries(null), db.summaries(null).all.first())
    }

    // One playlist read alone, as a pin reads it, equals its row in the list.
    @Test
    fun oneSummaryEqualsItsRowInTheList() = timed {
        db.dao().insertTracks((0 until 12).map { songRow("device:s$it", "al$it", "art:$it") })
        db.playlist("a", (0 until 12).map { "device:s$it" }, updatedAt = 5)
        db.playlist("b", listOf("device:s3"), updatedAt = 4, sourceId = "server:other")
        val summaries = db.summaries("server:h")
        assertEquals(db.todaySummaries("server:h").single(), summaries.one("a").first())
        assertEquals("another server's playlist is not shown", null, summaries.one("b").first())
        assertEquals(null, summaries.one("missing").first())
    }
}
