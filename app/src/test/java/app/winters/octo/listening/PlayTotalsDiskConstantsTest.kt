package app.winters.octo.listening

import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.io.path.createTempDirectory

// The disk a first build of the totals takes, per row, matches the
// constants a rebuild asks for: what the diff adds per diff row, and what
// the apply adds per stored row, on a build of about 20k rows with ids as
// long as a real server's.
@RunWith(RobolectricTestRunner::class)
class PlayTotalsDiskConstantsTest {
    @Test
    fun theBuildTakesWhatItsConstantsSay() = timed(60) {
        val dir = createTempDirectory("catalog").toFile()
        val db = openTestCatalog(dir)
        try {
            db.fillPlays(seed = 101, songs = 24_000, albums = 1_200, ids = PlayIds.server)
            db.longOf("PRAGMA wal_checkpoint(TRUNCATE)")
            val before = used(dir)
            var atApply = 0L
            var diffRows = 0L
            val rig = TotalsRig(db, db.testMerge(HOME))
            rig.totals.hooks = object : TotalsHooks() {
                override suspend fun beforeApply() {
                    atApply = used(dir)
                    diffRows = rig.dao.diffCount()
                }
            }
            rig.started()
            val rows = rig.dao.storedCount()
            val perDiff = (atApply - before) / diffRows
            val perRow = (used(dir) - atApply) / rows
            println("MEASURED play totals rows=$rows diffRows=$diffRows perDiffRow=$perDiff perRow=$perRow")
            assertTrue("$rows rows", rows >= 20_000)
            assertTrue("measured $perDiff, constant $PLAY_TOTAL_DIFF_BYTES_PER_ROW", perDiff <= PLAY_TOTAL_DIFF_BYTES_PER_ROW)
            assertTrue("measured $perDiff, constant $PLAY_TOTAL_DIFF_BYTES_PER_ROW", PLAY_TOTAL_DIFF_BYTES_PER_ROW <= perDiff * 11 / 10 + 10)
            assertTrue("measured $perRow, constant $PLAY_TOTAL_BYTES_PER_ROW", perRow <= PLAY_TOTAL_BYTES_PER_ROW)
            assertTrue("measured $perRow, constant $PLAY_TOTAL_BYTES_PER_ROW", PLAY_TOTAL_BYTES_PER_ROW <= perRow * 11 / 10 + 10)
        } finally {
            db.close()
            dir.deleteRecursively()
        }
    }

    private fun used(dir: File): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
