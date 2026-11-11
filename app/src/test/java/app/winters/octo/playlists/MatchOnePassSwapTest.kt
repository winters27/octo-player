package app.winters.octo.playlists

import app.winters.octo.catalog.CatalogMerge
import app.winters.octo.catalog.SourceCatalog
import app.winters.octo.catalog.SpaceFor
import app.winters.octo.catalog.albumRow
import app.winters.octo.catalog.artistRow
import app.winters.octo.catalog.put
import app.winters.octo.catalog.song
import app.winters.octo.device.DEVICE
import app.winters.octo.listening.HOME
import app.winters.octo.listening.timed
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// A swap at page 7 of a matching walk, in pages of 3: song 5 gains a
// phone copy and is renamed to it, and song 7, only on the phone, goes.
// The walk ends in one pass, never taking the merge lock, and marks the
// job crossed; a backup's like of song 5 by relink key and another by
// title both read as song 5's new id, and the likes of song 7 read as
// nothing; a playlist file's question with no artist whose only answer
// is song 5 still finds it, counted once. Guard: the base matched in
// memory over one snapshot.
@RunWith(RobolectricTestRunner::class)
class MatchOnePassSwapTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    private fun phone(skip: Int) = SourceCatalog(
        DEVICE,
        true,
        (0 until 40).filter { it != skip }.map { song(DEVICE, "s$it", "Song $it", "a") },
        listOf(albumRow(DEVICE, "a")),
        listOf(artistRow(DEVICE)),
    )

    private val server = SourceCatalog(
        HOME,
        false,
        (0 until 40).filter { it != 7 }.map { song(HOME, "s$it", "Song $it", "a") },
        listOf(albumRow(HOME, "a")),
        listOf(artistRow(HOME)),
    )

    private suspend fun library(): CatalogMerge {
        db.put(phone(skip = 5))
        db.put(server)
        return db.testMerge(HOME).also { it.rebuild() }
    }

    private fun swapAtPage7(merge: CatalogMerge, jobs: MatchJobs, pages: MutableList<Int>) {
        jobs.hooks = object : MatchHooks() {
            override suspend fun page(n: Int) {
                pages += n
                assertFalse("the walk holds the merge lock", merge.holdsMergeLock())
                if (n == 7) {
                    db.put(phone(skip = 7))
                    merge.rebuild()
                }
            }
        }
    }

    private fun idOf(title: String): String =
        db.query("SELECT id FROM track WHERE title = ?", arrayOf(title)).use { if (it.moveToFirst()) it.getString(0) else "" }

    @Test
    fun aBackupsLikesFollowTheSwap() = timed(60) {
        val merge = library()
        assertEquals("server:h:s5", idOf("Song 5"))
        val jobs = matchJobs(db, page = 3)
        val pages = ArrayList<Int>()
        swapAtPage7(merge, jobs, pages)
        val likes = listOf(
            MatchSong(relinkKey = "drake|song 5"),
            MatchSong(title = "Song 5", artist = "Drake", album = "Views", durationMs = 200_000),
            MatchSong(relinkKey = "drake|song 7"),
            MatchSong(title = "Song 7", artist = "Drake", album = "Views", durationMs = 200_000),
            MatchSong(title = "Song 30", artist = "Drake", album = "Views", durationMs = 200_000),
        )
        val (rows, crossed) = jobs.run(MatchKind.Backup, SpaceFor.Restore, likes.size.toLong()) { job ->
            job.stageSongs(0, likes)
            job.walk()
            job.finish()
            job.allRows(0) to job.crossed
        }
        assertTrue("the job did not see the swap", crossed)
        assertEquals("device:s5", idOf("Song 5"))
        assertEquals((1..pages.size).toList(), pages)
        assertEquals(listOf(0L to "device:s5", 1L to "device:s5", 4L to idOf("Song 30")), rows.map { it.entry to it.trackId })
    }

    @Test
    fun aQuestionWithNoArtistCountsARenamedSongOnce() = timed(60) {
        val merge = library()
        val jobs = matchJobs(db, page = 3)
        val pages = ArrayList<Int>()
        swapAtPage7(merge, jobs, pages)
        val entries = listOf(M3uEntry("x/a.mp3", title = "Song 5"), M3uEntry("x/b.mp3", title = "Song 7"), M3uEntry("x/c.mp3", title = "Song 31"))
        val rows = jobs.run(MatchKind.Playlist, SpaceFor.KeepList, entries.size.toLong()) { job ->
            job.stageEntries(entries)
            job.walk()
            job.finish()
            job.allRows(0)
        }
        assertEquals((1..pages.size).toList(), pages)
        assertEquals(listOf(0L to "device:s5", 2L to idOf("Song 31")), rows.map { it.entry to it.trackId })
    }
}
