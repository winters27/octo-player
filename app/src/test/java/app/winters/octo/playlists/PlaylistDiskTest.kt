package app.winters.octo.playlists

import app.winters.octo.catalog.PlaylistEntity
import app.winters.octo.catalog.PlaylistSets
import app.winters.octo.data.Drains
import app.winters.octo.listening.timed
import app.winters.octo.testing.testMerge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

// A step asks for room before it reads: for the answer's file, the work
// rows and the new rows beside the old, from the song count the list of
// playlists gave, or as the answer and the rows grow when it gave none.
// Too little room stops the step, counted as failed (PlaylistNoRoom, which
// PlaylistSync counts as it counts a failed call), and leaves the playlist,
// the work tables and the drain folder as they were. The disk an entry
// takes in the work tables and in the answer is measured here.
@RunWith(RobolectricTestRunner::class)
class PlaylistDiskTest {
    private fun stepsWithRoom(rig: SyncRig, bytes: Long, page: Int = 500): PlaylistSteps {
        val reads = PlaylistReads(rig.db, rig.drains, { bytes }, { SOURCE }, { NOW }, page)
        return PlaylistSteps(rig.db, PlaylistSets(rig.db), rig.db.testMerge(SOURCE), reads, PlaylistWalks(rig.db, rig.sets, page), { null }, { _, _ -> }, { NOW })
    }

    private suspend fun SyncRig.linked() {
        library()
        server.add("srv", "Mix", world.entries(100))
        phonePlaylist(PlaylistEntity("p", "Mix", 1, 5, serverId = "srv", sourceId = SOURCE, syncedAt = 5, syncedName = "Mix", serverStamp = "old"), world.phoneRows(20), null)
    }

    private fun SyncRig.untouched() {
        assertEquals(20L, db.count("SELECT COUNT(*) FROM playlist_item"))
        assertEquals(listOf("old"), db.strings("SELECT serverStamp FROM playlist"))
        for (table in PLAYLIST_WORK_TABLES) assertEquals(table, 0L, db.count("SELECT COUNT(*) FROM $table"))
        assertTrue(drains.folder.listFiles().isNullOrEmpty())
    }

    @Test
    fun tooLittleRoomForAKnownCountStopsBeforeTheRead() = timed(30) {
        SyncRig(PlaylistWorld(51)).use { rig ->
            rig.linked()
            val failed = runCatching {
                stepsWithRoom(rig, 100_000).run(rig.serverOf(false), PlaylistStep.Pull("p", "srv", "new", false), counts = mapOf("srv" to 100))
            }.exceptionOrNull()
            assertTrue("$failed", failed is PlaylistNoRoom)
            assertEquals(emptyList<PlaylistServer.Call>(), rig.server.calls.filter { it.endpoint == "getPlaylist" })
            rig.untouched()
        }
    }

    @Test
    fun tooLittleRoomFoundWhileFillingStopsTheStep() = timed(30) {
        SyncRig(PlaylistWorld(52)).use { rig ->
            rig.linked()
            val failed = runCatching {
                stepsWithRoom(rig, 100_000, page = 7).run(rig.serverOf(false), PlaylistStep.Pull("p", "srv", "new", false))
            }.exceptionOrNull()
            assertTrue("$failed", failed is PlaylistNoRoom)
            rig.untouched()
        }
    }

    // What one entry of a read takes: its rows and indexes in the work
    // tables once resolved, and its share of the drained answer.
    @Test
    fun anEntrysDiskIsWithinTheConstants() = timed(60) {
        SyncRig(PlaylistWorld(53, 400)).use { rig ->
            rig.library()
            val entries = rig.world.entries(3_000)
            rig.server.add("srv", "Mix", entries)
            val file = File(Files.createTempDirectory("answer").toFile(), "answer.json")
            rig.server.client().playlistInto("srv", file) {}
            val json = file.length() / entries.size
            file.parentFile.deleteRecursively()
            fun size() = rig.db.count("PRAGMA page_count") * rig.db.count("PRAGMA page_size")
            val before = size()
            val read = rig.reads.read(rig.server.client(), "srv", 1)
            rig.reads.resolve(read.number)
            val step = (size() - before) / entries.size
            println("MEASURED playlist entries=${entries.size} jsonPerEntry=$json stepPerEntry=$step")
            assertTrue("an answer's entry took $json bytes", json <= PLAYLIST_JSON_BYTES)
            assertTrue("an entry's work rows took $step bytes", step <= PLAYLIST_STEP_BYTES)
        }
    }
}
