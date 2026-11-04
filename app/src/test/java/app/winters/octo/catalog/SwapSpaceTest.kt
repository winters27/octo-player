package app.winters.octo.catalog

import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.io.path.createTempDirectory

// The space a first copy takes as it is swapped in, per song, matches the
// constant the swap asks for.
@RunWith(RobolectricTestRunner::class)
class SwapSpaceTest {
    @Test
    fun theSwapTakesWhatItsConstantSays() = runBlocking {
        val dir = createTempDirectory("catalog").toFile()
        val db = openTestCatalog(dir)
        try {
            val songs = 3_000
            val (_, server) = MergeRowsGen.sources(41, songs, phone = true)
            db.stage(server, generation = 1)
            db.longOf("PRAGMA wal_checkpoint(TRUNCATE)")
            val before = used(dir)
            val merge = db.testMerge(MergeRowsGen.SERVER)
            merge.rebuild()
            merge.awaitStage(MergeRowsGen.SERVER, 1)
            val perSong = (used(dir) - before) / server.tracks.size
            println("RESULT swap_bytes_per_song=$perSong")
            assertTrue("measured $perSong, constant $SWAP_BYTES_PER_SONG", perSong <= SWAP_BYTES_PER_SONG)
            assertTrue("measured $perSong, constant $SWAP_BYTES_PER_SONG", SWAP_BYTES_PER_SONG <= perSong * 11 / 10 + 100)
        } finally {
            db.close()
        }
    }

    private fun used(dir: File): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
