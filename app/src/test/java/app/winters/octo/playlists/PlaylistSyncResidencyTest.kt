package app.winters.octo.playlists

import app.winters.octo.catalog.PlaylistEntity
import app.winters.octo.listening.timed
import app.winters.octo.testing.counted
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// An import, a family pull and a family push with a form body each read
// and write a page at a time, whatever the playlist's size: at 3,000 and at
// 6,000 entries no statement reads more than 500 rows and no transaction
// writes more than a page, the step's emptying of its own work tables
// included, counted at the database, and twice the songs take about twice
// the statements. Today's steps read every list whole and wrote a playlist
// in one transaction.
@RunWith(RobolectricTestRunner::class)
class PlaylistSyncResidencyTest {
    private class Run(val statements: Long, val maxRead: Int, val maxWritten: Long, val maxCleared: Long, val cleared: Long)

    private suspend fun run(n: Int, step: suspend SyncRig.(List<String>) -> Unit): Run =
        SyncRig(PlaylistWorld(41, 40 + n / 10), formPost = true, familyRules = true).use { rig ->
            rig.library()
            val entries = rig.world.entries(n)
            rig.db.counted.reset()
            rig.db.counted.recording = true
            rig.step(entries)
            val own = rig.db.counted.own
            val (clears, writes) = rig.db.counted.transactions.partition { it.label == CLEAR_WRITE }
            Run(own.size.toLong(), own.maxOf { it.rowsRead }, writes.maxOf { it.rowsChanged }, clears.maxOf { it.rowsChanged }, clears.sumOf { it.rowsChanged })
        }

    private fun family(stamp: String) = PlaylistEntity(
        "p", "Ours", 1, 5, serverId = "srv", sourceId = SOURCE, syncedAt = 3, syncedName = "Ours", serverStamp = stamp,
        family = true, familyMayAdd = true, familyMayRemove = true,
    )

    private fun check(name: String, small: Run, big: Run) {
        for (run in listOf(small, big)) {
            assertTrue("$name: a statement read ${run.maxRead} rows", run.maxRead <= 500)
            assertTrue("$name: a transaction wrote ${run.maxWritten} rows", run.maxWritten <= 502)
            assertTrue("$name: the work tables' emptying deleted nothing", run.cleared > 0)
            assertTrue("$name: one transaction of the work tables' emptying deleted ${run.maxCleared} rows", run.maxCleared <= CLEAR_ROWS)
        }
        assertTrue("$name: ${small.statements} then ${big.statements} statements", big.statements <= small.statements * 5 / 2)
    }

    @Test
    fun anImportReadsAndWritesAPageAtATime() = timed(120) {
        val step: suspend SyncRig.(List<String>) -> Unit = { entries ->
            server.add("srv", "Mix", entries)
            steps.run(serverOf(false), PlaylistStep.Import("srv", "s"))
        }
        check("import", run(3_000, step), run(6_000, step))
    }

    @Test
    fun aFamilyPullReadsAndWritesAPageAtATime() = timed(120) {
        val step: suspend SyncRig.(List<String>) -> Unit = { entries ->
            server.add("srv", "Ours", entries, owner = "Family", octoFamily = true)
            phonePlaylist(family("old"), world.phoneRows(entries.size), entries)
            this.db.counted.reset()
            steps.run(serverOf(false), PlaylistStep.Pull("p", "srv", "new", keepName = false))
        }
        check("pull", run(3_000, step), run(6_000, step))
    }

    @Test
    fun aFamilyPushReadsAndWritesAPageAtATime() = timed(120) {
        val step: suspend SyncRig.(List<String>) -> Unit = { entries ->
            val held = server.add("srv", "Ours", entries, owner = "Family", octoFamily = true)
            phonePlaylist(family(stampOf("2026-11-01T00:00:00Z", held.entries.size)), world.phoneRows(entries.size), world.base(entries))
            this.db.counted.reset()
            steps.run(serverOf(false), PlaylistStep.Push("p", "srv"))
        }
        check("push", run(3_000, step), run(6_000, step))
    }
}
