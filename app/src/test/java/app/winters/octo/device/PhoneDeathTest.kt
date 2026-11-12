package app.winters.octo.device

import app.winters.octo.catalog.MERGE_WORK_CLEAR
import app.winters.octo.catalog.STAGE_BUILDING
import app.winters.octo.catalog.STAGE_NONE
import app.winters.octo.catalog.SourceMetaEntity
import app.winters.octo.catalog.exec
import app.winters.octo.catalog.longOf
import app.winters.octo.catalog.reopen
import app.winters.octo.catalog.stringOf
import app.winters.octo.testing.FakeMediaStore
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.phoneLibrary
import app.winters.octo.testing.testMerge
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.file.Files

// The app stopped in the middle of a scan: the next open marks the copy no
// longer being built (its stage to clear), no digest stands, and the next
// scan's start clears what the scan had written of its working rows and its
// copy, then builds the whole phone again, as today's scan would.
@RunWith(RobolectricTestRunner::class)
class PhoneDeathTest {
    @Test
    fun aScanCutOffIsClearedAtOpenAndBuiltAgain() = runBlocking {
        val dir = Files.createTempDirectory("catalog").toFile()
        var db = openTestCatalog(dir)
        val phone = skewedPhone(big = 300)
        val merge = db.testMerge(null)
        db.phoneLibrary(phone.listed(), merge, now = PHONE_NOW).rescan()
        // The copy leaves the stage, and the merge's work rows go, in the
        // background a page at a time: both done before the rows are set
        // up and the file opened again.
        merge.awaitStageClear(DEVICE)
        merge.awaitClear(MERGE_WORK_CLEAR)

        // As a scan leaves things between its start and its swap.
        val work = db.workDao()
        val stamp = work.metas().single { it.sourceId == DEVICE }.stamp
        work.putMeta(SourceMetaEntity(DEVICE, null, null, stamp, STAGE_BUILDING, 9, 0, 0, 0))
        db.exec("UPDATE phone_scan_state SET digest = NULL")
        db.exec("INSERT INTO scan_file (mediaId, seq, uri, fileName, modifiedAt, sizeBytes, durationMs, addedAt) VALUES (1, 0, 'u', 'f', 0, 0, 0, 0)")
        db.exec("INSERT INTO scan_row (mediaId, seq, groupId, discKey, numKey, nameOrder) VALUES (1, 0, 1, 1, 1, '')")
        db.exec("INSERT INTO scan_cluster (nameKey, groupId) VALUES ('x', 1)")
        db.exec("INSERT INTO scan_group (nameKey, splitKey, clusterSeq, firstSeq) VALUES ('x', '', 0, 0)")
        db.exec("INSERT INTO scan_tally (owner, value) VALUES ('g:x', 'X')")
        db.exec("INSERT INTO scan_genre_name (genreKey, name) VALUES ('x', 'X')")
        db.exec("INSERT INTO scan_artist (artistId) VALUES ('a')")
        db.exec("INSERT INTO stage_track SELECT * FROM source_track WHERE sourceId = 'device' LIMIT 10")

        db = reopen(db, dir)
        try {
            // The open deletes nothing: the copy is no longer being built,
            // its stage marked clearing, and the next scan's start clears
            // the rest.
            val tables = listOf("scan_file", "scan_row", "scan_cluster", "scan_group", "scan_tally", "scan_genre_name", "scan_artist", "stage_track")
            val kept = tables.associateWith { db.longOf("SELECT COUNT(*) FROM $it") }
            assertTrue("rows the open deleted: $kept", kept.values.all { it > 0L })
            assertEquals(STAGE_NONE, db.stringOf("SELECT stage FROM source_meta WHERE sourceId = 'device'"))
            assertEquals(1L, db.longOf("SELECT stageClearing FROM source_meta WHERE sourceId = 'device'"))
            assertNull(db.stringOf("SELECT digest FROM phone_scan_state WHERE id = 0"))

            val saved = db.dao().fileTags()
            val again = db.phoneLibrary(phone.listed(), now = PHONE_NOW)
            var built = false
            var left: Map<String, Long>? = null
            again.scan.reached = {
                if (it == ScanPoint.Started) {
                    built = true
                    left = tables.associateWith { t -> db.longOf("SELECT COUNT(*) FROM $t") }
                }
            }
            again.rescan()
            assertTrue("the next scan builds", built)
            assertTrue("rows left at the scan's start: $left", left!!.values.all { it == 0L })
            assertEquals(1, FakeMediaStore.current!!.queries.get())
            assertPhoneIsToday(db, again, todayOf(saved))
        } finally {
            db.close()
        }
    }
}
