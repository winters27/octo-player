package app.winters.octo.catalog

import app.winters.octo.device.DEVICE
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.ui.library.health.HealthBuild
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// The oracle compares rows by their keys and not their rowids, since a save
// in groups writes rows in another order than one swap did. No reader
// depends on a rowid: the same library written in two orders gives the
// same health report (whose walks number copies by rowid) and the same
// play totals (whose walk takes a song's copies in rowid order), though
// the rowids differ.
@RunWith(RobolectricTestRunner::class)
class RowidBlindTest {
    private val server = "server:rowid.test"

    @Test
    fun theSameLibraryInTwoOrdersReadsTheSame() = runBlocking { withTimeout(60_000) { check() } }

    private suspend fun check() {
        val forward = openTestCatalog()
        val backward = openTestCatalog()
        try {
            val phone = library(DEVICE, 30)
            // Copies that read as duplicates of each other, so the report has
            // sets to number.
            val copy = library(server, 30).let { c -> c.copy(tracks = c.tracks + c.tracks.take(6).map { it.copy(id = it.id + "b", nativeId = it.nativeId + "b", durationMs = it.durationMs + 1) }) }
            val played = copy.copy(tracks = copy.tracks.mapIndexed { i, t -> if (i % 4 == 0) t.copy(playCount = 2 + i, lastPlayedAt = 1_000L + i) else t })
            for ((db, reversed) in listOf(forward to false, backward to true)) {
                for (source in listOf(phone, played)) {
                    db.put(source)
                    // The same rows again, written last to first, so only
                    // their rowids differ.
                    if (reversed) {
                        db.exec("DELETE FROM source_track WHERE sourceId = ?", source.sourceId)
                        db.sourceDao().putTracks(source.tracks.mapIndexed { i, t -> t.copy(seq = i.toLong(), mergedId = "").withKeys() }.reversed())
                    }
                }
                rebuildSettled(db, server)
                for (i in 0 until 30) {
                    if (i % 3 == 0) db.exec("INSERT INTO play_event (trackId, relinkKey, startedAt, playedMs, durationMs) VALUES (?, '', ?, 1, 1)", "$DEVICE:s$i", 100L + i)
                }
            }
            val rowids = { db: CatalogDatabase -> db.query("SELECT id, rowid FROM source_track ORDER BY id", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getLong(1)) } } }
            assertNotEquals("the rows went in in one order", rowids(forward), rowids(backward))
            val rowidOf = { db: CatalogDatabase, id: String -> db.longOf("SELECT rowid FROM source_track WHERE id = ?", id) }
            assertTrue("the rows were not reversed", rowidOf(backward, "$DEVICE:s0") > rowidOf(backward, "$DEVICE:s29"))
            assertTrue("the rows were reversed", rowidOf(forward, "$DEVICE:s0") < rowidOf(forward, "$DEVICE:s29"))
            val differences = SaveOracle.differences(SaveOracle.image(forward.openHelper.writableDatabase), SaveOracle.image(backward.openHelper.writableDatabase))
            assertTrue(differences.joinToString("\n"), differences.isEmpty())
            val totals = forward.recountTotals("home")
            assertTrue("no totals counted", totals.isNotEmpty())
            assertEquals(totals, backward.recountTotals("home"))
            val reports = listOf(forward, backward).map { db ->
                val merge = db.boundMerge(server, SAVE_GROUP_ENTRIES)
                // A merge that has run has its keys ready for the report.
                merge.rebuild()
                HealthBuild(db, merge, WorkOwners(), FreeSpace { Long.MAX_VALUE }, 500, 100).report()
            }
            assertTrue("the report checked nothing", reports[0].checked > 0)
            assertEquals(reports[0].counts, reports[1].counts)
            assertEquals(reports[0].checked, reports[1].checked)
        } finally {
            forward.close()
            backward.close()
        }
    }
}
