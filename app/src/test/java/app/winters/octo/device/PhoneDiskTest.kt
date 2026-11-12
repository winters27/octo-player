package app.winters.octo.device

import android.database.sqlite.SQLiteFullException
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.GroupHooks
import app.winters.octo.catalog.MergeHooks
import app.winters.octo.catalog.SAVE_GROUP_ENTRIES
import app.winters.octo.catalog.SAVE_GROUP_FLOOR
import app.winters.octo.catalog.boundMerge
import app.winters.octo.catalog.longOf
import app.winters.octo.catalog.stringOf
import app.winters.octo.testing.FakeMediaStore
import app.winters.octo.testing.PhoneFiles
import app.winters.octo.testing.counted
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.phoneLibrary
import app.winters.octo.testing.testMerge
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// A disk that fills while the phone is scanned ends the scan without
// failing it: the library and the saved tags stay as the last scan left
// them, no working rows are left, and the next scan finishes the job.
// The disk fills as the tags are saved, as the albums are written, and in
// the merge's swap.
@RunWith(RobolectricTestRunner::class)
class PhoneDiskTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    private val before = PhoneFiles.albums(files = 600, perAlbum = 12, unknownAlbum = 100)

    // The same phone with every file changed and 50 more.
    private val after = PhoneFiles(before.count + 50) { i ->
        if (i < before.count) before.file(i).let { it.copy(modifiedSeconds = it.modifiedSeconds + 60, title = "${it.title} (edit)") }
        else before.file(0).copy(id = 10_000L + i, displayName = "extra $i.mp3", title = "Extra $i")
    }

    // The saved tags stay as they were.
    @Test
    fun aFullDiskWhileTagsAreSavedLeavesTheLibrary() = fullDiskAt(swapFails = false, tagsSaved = false) { "file_tags" in it && it.trimStart().startsWith("INSERT") }

    // The tags read by then are saved, each as its file is now.
    @Test
    fun aFullDiskWhileAlbumsAreWrittenLeavesTheLibrary() = fullDiskAt(swapFails = false, tagsSaved = true) { "stage_" in it && it.trimStart().startsWith("INSERT") }

    @Test
    fun aFullDiskInTheSwapLeavesTheLibrary() = fullDiskAt(swapFails = true, tagsSaved = true) { false }

    // A scan while a save is part applied and its resume stopped (the disk
    // still full) ends without failing whoever asked: the scan's start is
    // refused (ApplyPending) as a dropped copy is, and the library waits for
    // the save's resume.
    @Test
    fun aScanWhileASaveIsPartWayEndsWithoutFailing() = runBlocking {
        val db = openTestCatalog()
        try {
            val hooks = GroupHooks()
            val ended = kotlinx.coroutines.CompletableDeferred<Throwable?>()
            val merge = db.boundMerge(null, SAVE_GROUP_FLOOR, object : GroupHooks() {
                override suspend fun betweenGroups(turn: Long) = hooks.betweenGroups(turn)

                override fun resumed(failure: Throwable?) {
                    ended.complete(failure)
                }
            })
            val phone = db.phoneLibrary(before, merge)
            phone.rescan()
            FakeMediaStore.current!!.files = after
            // The disk fills once, after the save's first group, and stays
            // full until the test frees it.
            val filled = java.util.concurrent.atomic.AtomicBoolean()
            hooks.between = { turn -> if (turn == 1L && filled.compareAndSet(false, true)) db.counted.fullDisk = { it.startsWith("UPDATE merge_state SET swapCount") } }
            phone.rescan()
            assertTrue("the disk never filled", filled.get())
            assertTrue("the resume ended the save", kotlinx.coroutines.withTimeout(30_000) { ended.await() } != null)
            assertTrue(db.longOf("SELECT COUNT(*) FROM merge_state WHERE applyClaim IS NOT NULL") == 1L)
            val outcome = runCatching { phone.rescan() }
            assertNull("the scan failed: ${outcome.exceptionOrNull()}", outcome.exceptionOrNull())
            db.counted.fullDisk = null
            merge.rebuild()
            phone.rescan()
            assertEquals(750L, db.longOf("SELECT COUNT(*) FROM source_track WHERE sourceId = 'device'"))
        } finally {
            db.counted.fullDisk = null
            db.close()
        }
    }

    // A disk that fills after the save's first group is in stops the save
    // with its groups kept: the scan ends without failing, the stop starts
    // one resume, and once the disk has room again the resume ends the save
    // with the scan's library, at the floor and at the default bound.
    @Test
    fun aFullDiskAfterTheFirstGroupStopsAndResumes() = runBlocking {
        for (bound in listOf(SAVE_GROUP_FLOOR, SAVE_GROUP_ENTRIES)) {
            val db = openTestCatalog()
            try {
                val hooks = GroupHooks()
                val resumes = java.util.concurrent.atomic.AtomicInteger()
                val ended = kotlinx.coroutines.CompletableDeferred<Throwable?>()
                val merge = db.boundMerge(null, bound, object : GroupHooks() {
                    override suspend fun betweenGroups(turn: Long) = hooks.betweenGroups(turn)

                    override suspend fun resuming(auto: Boolean) {
                        resumes.incrementAndGet()
                    }

                    override fun resumed(failure: Throwable?) {
                        ended.complete(failure)
                    }
                })
                val phone = db.phoneLibrary(before, merge)
                phone.rescan()
                FakeMediaStore.current!!.files = after
                // The disk refuses the next group's write, once.
                val refused = java.util.concurrent.atomic.AtomicBoolean(false)
                var parks = 0
                hooks.between = { turn ->
                    parks++
                    if (turn == 1L) db.counted.fullDisk = { it.startsWith("UPDATE merge_state SET swapCount") && refused.compareAndSet(false, true) }
                }
                val outcome = runCatching { phone.rescan() }
                assertNull("the scan failed: ${outcome.exceptionOrNull()}", outcome.exceptionOrNull())
                assertTrue("bound $bound: the disk never refused", refused.get())
                assertNull("bound $bound: the resume failed", kotlinx.coroutines.withTimeout(30_000) { ended.await() })
                db.counted.fullDisk = null
                assertEquals("bound $bound: one resume", 1, resumes.get())
                assertTrue("bound $bound: $parks parks", parks >= 2)
                assertNull(db.stringOf("SELECT applyClaim FROM merge_state"))
                assertEquals(750L, db.longOf("SELECT COUNT(*) FROM source_track WHERE sourceId = 'device'"))
                assertEquals(750L, db.longOf("SELECT COUNT(*) FROM track WHERE title LIKE '% (edit)' OR title LIKE 'Extra %'"))
            } finally {
                db.counted.fullDisk = null
                db.close()
            }
        }
    }

    private fun fullDiskAt(swapFails: Boolean, tagsSaved: Boolean, refused: (String) -> Boolean) = runBlocking {
        var failing = false
        val hooks = object : MergeHooks() {
            override suspend fun inSwap() {
                if (failing && swapFails) throw SQLiteFullException("database or disk is full")
            }
        }
        val phone = db.phoneLibrary(before, db.testMerge(null, hooks))
        phone.rescan()
        val library = db.snapshot()
        val tags = db.savedTags()

        FakeMediaStore.current!!.files = after
        failing = true
        db.counted.fullDisk = refused
        val outcome = runCatching { phone.rescan() }
        db.counted.fullDisk = null
        failing = false
        assertNull("the scan failed: ${outcome.exceptionOrNull()}", outcome.exceptionOrNull())
        assertEquals("the library as the last scan left it", library, db.snapshot())
        val now = (0 until after.count).joinToString(",") { after.file(it).let { f -> "${f.id}@${f.modifiedSeconds}" } }
        if (tagsSaved) assertEquals("saved tags", now.split(',').sortedBy { it.substringBefore('@').toLong() }.joinToString(","), db.savedTags())
        else assertEquals("saved tags", tags, db.savedTags())
        assertEquals("working rows left", 0L, db.workingRows())

        phone.rescan()
        assertEquals(750L, db.longOf("SELECT COUNT(*) FROM source_track WHERE sourceId = 'device'"))
        assertEquals(750L, db.longOf("SELECT COUNT(*) FROM track WHERE title LIKE '% (edit)' OR title LIKE 'Extra %'"))
    }
}

// What the library holds of the phone, as text.
private fun CatalogDatabase.snapshot(): List<String?> = listOf(
    stringOf("SELECT group_concat(id || '=' || title || '@' || seq, ',') FROM (SELECT * FROM source_track WHERE sourceId = 'device' ORDER BY id)"),
    stringOf("SELECT group_concat(id || '=' || title, ',') FROM (SELECT * FROM track ORDER BY id)"),
)

// Each saved file's tags by when the file was changed, as text.
private fun CatalogDatabase.savedTags(): String? =
    stringOf("SELECT group_concat(mediaId || '@' || modifiedAt, ',') FROM (SELECT * FROM file_tags ORDER BY mediaId)")

private fun CatalogDatabase.workingRows(): Long =
    listOf("scan_file", "scan_row", "scan_cluster", "scan_group", "scan_tally", "scan_genre_name", "scan_artist", "stage_track", "stage_album", "stage_artist")
        .sumOf { longOf("SELECT COUNT(*) FROM $it") }
