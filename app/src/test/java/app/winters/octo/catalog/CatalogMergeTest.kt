package app.winters.octo.catalog

import android.database.sqlite.SQLiteException
import app.winters.octo.data.userMessage
import app.winters.octo.device.DEVICE
import app.winters.octo.testing.counted
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.rawConnection
import app.winters.octo.testing.testMerge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.io.path.createTempDirectory

@RunWith(RobolectricTestRunner::class)
class CatalogMergeTest {
    private val server = "server:home.test"
    private lateinit var dir: File
    private lateinit var db: CatalogDatabase

    @Before
    fun open() {
        dir = createTempDirectory("catalog").toFile()
        db = openTestCatalog(dir)
    }

    @After
    fun close() {
        db.close()
    }

    private fun <T> run(block: suspend kotlinx.coroutines.CoroutineScope.() -> T): T = runBlocking { withTimeout(10_000) { block() } }

    private suspend fun ratingOf(trackId: String) = db.longOf("SELECT rating FROM track WHERE id = ?", trackId).toInt()

    // Each merge, and each one skipped because nothing changed, records the
    // server it put in the library with its result, so whoever asks which
    // server the library holds is never told another.
    @Test
    fun aMergeRecordsTheServerItPutIn() = run {
        db.put(library(DEVICE, 10))
        db.put(library(server, 20, msShift = 100))
        var inUse: String? = server
        val merge = db.testMerge(null, inUse = { inUse })
        assertEquals(null, merge.mergedFor())
        merge.rebuild()
        assertEquals(server, merge.mergedFor())
        // A skipped merge writes it too, whatever the record said.
        db.exec("UPDATE merge_state SET server = 'server:other'")
        merge.rebuild()
        assertEquals(server, merge.mergedFor())
        inUse = null
        merge.rebuild()
        assertEquals("", merge.mergedFor())
        assertEquals(10, db.longOf("SELECT COUNT(*) FROM track").toInt())
    }

    // The server in use changes while a merge runs: the merge records the
    // server it read and put in, never the one in use when it ends.
    @Test
    fun aMergeRecordsItsServerWhenTheServerChangesMidway() = run {
        db.put(library(DEVICE, 10))
        db.put(library(server, 20, msShift = 100))
        var inUse: String? = server
        val merge = db.testMerge(null, inUse = { inUse }, hooks = object : MergeHooks() {
            override suspend fun claimed() {
                inUse = "server:other.test"
            }
        })
        merge.rebuild()
        assertEquals(server, merge.mergedFor())
        assertTrue(db.longOf("SELECT COUNT(*) FROM track") > 10)
        // The next merge is for the server in use now, which has no music.
        merge.rebuild()
        assertEquals("server:other.test", merge.mergedFor())
        assertEquals(10, db.longOf("SELECT COUNT(*) FROM track").toInt())
    }

    @Test
    fun theMergeReadsSourcesAPageAtATime() = run {
        db.put(library(DEVICE, 300))
        db.put(library(server, 1_200, msShift = 100))
        val counting = CountingWorkDao(db.workDao())
        db.testMerge(server, work = counting).rebuild()
        assertTrue(counting.pages.isNotEmpty())
        assertTrue("largest page ${counting.pages.max()}", counting.pages.max() <= MERGE_PAGE)
        assertEquals(1_200, db.longOf("SELECT COUNT(*) FROM track").toInt())
    }

    @Test
    fun writersAreNotBlockedWhileTheMergePlacesSongs() = run {
        db.put(library(DEVICE, 1_200))
        val pause = Pause()
        val hooks = TestHooks(atFirstOutput = pause, firstOutput = { assertFalse(db.inTransaction()) })
        val merging = launch(Dispatchers.Default) { db.testMerge(server, hooks).rebuild() }
        pause.reached.await()
        val other = rawConnection(db)
        try {
            other.execSQL("INSERT INTO liked_track (trackId, relinkKey, likedAt) VALUES ('device:s1', 'k', 1)")
            other.execSQL("INSERT INTO play_event (trackId, relinkKey, startedAt, playedMs, durationMs) VALUES ('device:s1', 'k', 1, 1, 1)")
        } catch (e: SQLiteException) {
            fail("a write waited on the merge: ${e.javaClass.simpleName}")
        } finally {
            other.close()
        }
        pause.release()
        merging.join()
        assertEquals(1L, db.longOf("SELECT COUNT(*) FROM liked_track"))
    }

    @Test
    fun aLikeDuringTheSwapLandsRightAfterIt() = run {
        db.put(library(DEVICE, 50))
        val pause = Pause()
        val hooks = TestHooks(atSwap = pause)
        val merging = launch(Dispatchers.Default) { db.testMerge(server, hooks).rebuild() }
        pause.reached.await()
        val like = launch(Dispatchers.IO) {
            db.userDao().like(LikedTrackEntity("device:s1", "k", 1))
            hooks.events += "like"
        }
        assertFalse(like.isCompleted)
        pause.release()
        merging.join()
        like.join()
        assertEquals(listOf("swap", "like"), hooks.events.toList())
        assertTrue(db.userDao().isLiked("device:s1"))
    }

    @Test
    fun writersWaitForTheSwapAndNothingDeadlocks() = run {
        db.put(library(DEVICE, 40))
        db.put(library("server:gone.test", 10))
        val pause = Pause()
        val hooks = TestHooks(atClaim = pause)
        val merge = db.testMerge(server, hooks)
        val merging = launch(Dispatchers.Default) { merge.rebuild() }
        pause.reached.await()
        val asked = List(3) { CompletableDeferred<Unit>() }
        val writers = listOf(
            async(Dispatchers.Default) {
                asked[0].complete(Unit)
                merge.sourceWrite { db.sourceDao().takeInRows(server, listOf(song(server, "t1", "Taken", "albumT")), listOf(albumRow(server, "albumT")), emptySet(), listOf(artistRow(server))) }
                hooks.events += "take-in"
            },
            async(Dispatchers.Default) {
                asked[1].complete(Unit)
                merge.sourceWrite { db.put(library(DEVICE, 41)) }
                hooks.events += "scan"
            },
            async(Dispatchers.Default) {
                asked[2].complete(Unit)
                merge.sourceWrite { db.sourceDao().deleteSource("server:gone.test") }
                hooks.events += "drop"
            },
        )
        // Each writer has asked for the gate and none gets it while the
        // merge holds it.
        asked.awaitAll()
        assertNull(withTimeoutOrNull(300) { writers.awaitAll() })
        pause.release()
        writers.awaitAll()
        merging.join()
        val events = hooks.events.toList()
        assertEquals("swap", events.first())
        assertEquals(setOf("take-in", "scan", "drop"), events.drop(1).toSet())
        merge.rebuild()
        assertEquals(41 + 1L, db.longOf("SELECT COUNT(*) FROM track"))
    }

    @Test
    fun aPhoneScanDuringAMergeLeadsToOneMoreMerge() = run {
        val before = library(DEVICE, 30)
        db.put(before)
        val pause = Pause()
        val hooks = TestHooks(atClaim = pause)
        val merge = db.testMerge(server, hooks)
        val first = launch(Dispatchers.Default) { merge.rebuild() }
        pause.reached.await()
        val after = library(DEVICE, 31)
        val scan = launch(Dispatchers.Default) {
            merge.sourceWrite { db.put(after) }
            merge.rebuild()
        }
        pause.release()
        first.join()
        scan.join()
        assertEquals(2, hooks.merges.get())
        val expected = oldMergeCatalogs(listOf(after))
        assertEquals(expected.tracks.sortedBy { it.id }, db.library().tracks.sortedBy { it.id })
    }

    @Test
    fun ratingTapsDuringAMergeAllStay() = run {
        db.put(library(DEVICE, 10))
        db.put(library(server, 10))
        val merge = db.testMerge(server, TestHooks())
        merge.rebuild()
        // The next copy brings the server's old ratings of the same songs.
        db.stage(library(server, 10).let { s -> s.copy(tracks = s.tracks.map { it.copy(rating = 2) }) }, generation = 3)
        val pause = Pause()
        merge.hooks = TestHooks(atClaim = pause)
        val merging = launch(Dispatchers.Default) { merge.rebuild() }
        pause.reached.await()
        (0 until 10).forEach { db.userDao().noteRating("device:s$it", 5 - it % 5, "k", 1, copies = true) }
        pause.release()
        merging.join()
        (0 until 10).forEach { i ->
            assertEquals(5 - i % 5, ratingOf("device:s$i"))
            // The copies keep what the server said; the steps after the copy settle the two.
            assertEquals(2L, db.longOf("SELECT rating FROM source_track WHERE id = ?", "$server:s$i"))
        }
        assertEquals(0L, db.longOf("SELECT COUNT(*) FROM rating_note"))
    }

    // A tap while a copy is being built, before any merge claims it, is put
    // back over the rating the copy read.
    @Test
    fun aTapWhileACopyIsBuiltStays() = run {
        db.put(library(DEVICE, 4))
        db.put(library(server, 4))
        val merge = db.testMerge(server, TestHooks())
        merge.rebuild()
        db.stage(library(server, 4).let { s -> s.copy(tracks = s.tracks.map { it.copy(rating = 2) }) }, generation = 5)
        db.userDao().noteRating("device:s1", 5, "k", 1, copies = true)
        merge.rebuild()
        assertEquals(5, ratingOf("device:s1"))
        // The copy keeps what the server said; the steps after it settle the two.
        assertEquals(2L, db.longOf("SELECT rating FROM source_track WHERE id = ?", "$server:s1"))
        assertEquals(0L, db.longOf("SELECT COUNT(*) FROM rating_note"))
    }

    // A copy that no longer has a song takes its tap with it; a tap on a
    // song the copy still has stays to be sent.
    @Test
    fun aSwapForgetsTapsOnSongsTheServerLost() = run {
        db.put(library(DEVICE, 4))
        db.put(library(server, 4))
        val merge = db.testMerge(server, TestHooks())
        merge.rebuild()
        db.userDao().noteTap("device:s1", 5, "k", 1)
        db.userDao().noteTap("device:s2", 3, "k", 1)
        assertEquals(2L, db.longOf("SELECT COUNT(*) FROM rating_tap"))
        db.stage(library(server, 4).let { s -> s.copy(tracks = s.tracks.filter { it.nativeId != "s2" }) }, generation = 5)
        merge.rebuild()
        assertEquals(1L, db.longOf("SELECT COUNT(*) FROM rating_tap"))
        assertEquals(5L, db.longOf("SELECT rating FROM rating_tap WHERE sourceRowId = ?", "$server:s1"))
    }

    // A merge while the copy is still being built (a phone scan) keeps the
    // copy's notes, and the copy's swap later still shows the tap.
    @Test
    fun aNoteOutlivesAMergeWhileItsCopyIsStillBuilding() = run {
        db.put(library(DEVICE, 4))
        db.put(library(server, 4))
        val merge = db.testMerge(server, TestHooks())
        merge.rebuild()
        db.stage(library(server, 4).let { s -> s.copy(tracks = s.tracks.map { it.copy(rating = 2) }) }, generation = 6)
        db.exec("UPDATE source_meta SET stage = '$STAGE_BUILDING' WHERE sourceId = ?", server)
        db.userDao().noteRating("device:s1", 5, "k", 1, copies = true)
        db.put(library(DEVICE, 5))
        merge.rebuild()
        assertEquals(1L, db.longOf("SELECT COUNT(*) FROM rating_note"))
        db.exec("UPDATE source_meta SET stage = '$STAGE_READY' WHERE sourceId = ?", server)
        merge.rebuild()
        assertEquals(5, ratingOf("device:s1"))
        assertEquals(0L, db.longOf("SELECT COUNT(*) FROM rating_note"))
    }

    // An abort whose own writes fail (a full disk) still tells its copy, and
    // the next merge clears the claim it left.
    @Test
    fun anAbortThatCannotWriteStillEndsItsCopy() = run {
        db.put(library(DEVICE, 3))
        val inner = db.workDao()
        var failClear = false
        val work = object : WorkDao by inner {
            override suspend fun clearStage(sourceId: String) {
                if (failClear) {
                    failClear = false
                    throw android.database.sqlite.SQLiteFullException("database or disk is full")
                }
                inner.clearStage(sourceId)
            }
        }
        var free = 0L
        val merge = CatalogMerge(db, { free }, { server }, work)
        db.stage(library(server, 3), generation = 2)
        failClear = true
        runCatching { merge.rebuild() }
        try {
            merge.awaitStage(server, 2)
            fail("the copy was not told its abort")
        } catch (e: StageAborted) {
            // told, though the abort could not write
        }
        free = Long.MAX_VALUE
        merge.rebuild()
        assertEquals(0L, db.longOf("SELECT COUNT(*) FROM source_meta WHERE stage != 'none'"))
        assertEquals(0L, db.longOf("SELECT COUNT(*) FROM merge_state WHERE claimActive = 1"))
    }

    // A copy's outcome is its own: a later copy of the same source under
    // the same number (the source removed and added back) is told its own.
    @Test
    fun aCopyAgainUnderTheSameGenerationGetsItsOwnOutcome() = run {
        db.put(library(DEVICE, 3))
        var free = Long.MAX_VALUE
        val merge = CatalogMerge(db, { free }, { server }, db.workDao())
        db.stage(library(server, 3), generation = 1)
        merge.rebuild()
        merge.awaitStage(server, 1)
        db.sourceDao().deleteSource(server)
        db.stage(library(server, 3), generation = 1)
        free = 0
        merge.rebuild()
        try {
            merge.awaitStage(server, 1)
            fail("the second copy was told the first one's outcome")
        } catch (e: StageAborted) {
            // its own: no room to swap it in
        }
    }

    @Test
    fun ratingNotesNeverGoStale() = run {
        db.put(library(DEVICE, 5))
        db.put(library(server, 5).let { s -> s.copy(tracks = s.tracks.map { it.copy(rating = 4) }) })
        val merge = db.testMerge(server, TestHooks(), space = 0)
        merge.rebuild()
        // A tap during a merge that is aborted (no room for its copy).
        db.stage(library(server, 5), generation = 8)
        val pause = Pause()
        merge.hooks = TestHooks(atClaim = pause)
        val aborted = launch(Dispatchers.Default) { merge.rebuild() }
        pause.reached.await()
        db.userDao().noteRating("device:s0", 1, "k", 1, copies = true)
        pause.release()
        aborted.join()
        assertEquals(0L, db.longOf("SELECT COUNT(*) FROM rating_note"))
        assertEquals(0L, db.longOf("SELECT claimActive FROM merge_state WHERE id = 0"))
        // A later tap with no merge, then a merge: the later one shows.
        db.userDao().noteRating("device:s0", 3, "k", 2, copies = true)
        db.put(library(DEVICE, 6))
        merge.hooks = TestHooks()
        merge.rebuild()
        assertEquals(3, ratingOf("device:s0"))
    }

    @Test
    fun aNoteLeftAtRestartIsIgnored() = run {
        db.put(library(DEVICE, 3))
        db.testMerge(server).rebuild()
        db.exec("INSERT INTO rating_note (sourceRowId, rating) VALUES ('device:s0', 5)")
        db = reopen(db, dir)
        // The open deletes nothing; the next merge clears the note no copy
        // keeps before it claims.
        assertEquals(1L, db.longOf("SELECT COUNT(*) FROM rating_note"))
        db.put(library(DEVICE, 4))
        db.testMerge(server).rebuild()
        assertEquals(0, ratingOf("device:s0"))
        assertEquals(0L, db.longOf("SELECT COUNT(*) FROM rating_note"))
    }

    @Test
    fun aTapOnASongWhoseIdTheSwapChangesLandsOnTheNewId() = run {
        db.put(library(server, 3))
        val merge = db.testMerge(server, TestHooks())
        merge.rebuild()
        // The phone gets the same songs, so the library ids become the phone's.
        db.put(library(DEVICE, 3))
        val pause = Pause()
        merge.hooks = TestHooks(atClaim = pause)
        val merging = launch(Dispatchers.Default) { merge.rebuild() }
        pause.reached.await()
        db.userDao().noteRating("$server:s1", 4, "k", 1, copies = true)
        pause.release()
        merging.join()
        assertEquals(4, ratingOf("device:s1"))
    }

    @Test
    fun anUnrateDuringAMergeStays() = run {
        db.put(library(DEVICE, 3))
        db.put(library(server, 3).let { s -> s.copy(tracks = s.tracks.map { it.copy(rating = 4) }) })
        val merge = db.testMerge(server, TestHooks())
        merge.rebuild()
        assertEquals(4, ratingOf("device:s2"))
        db.sourceDao().takeInRows(server, listOf(song(server, "x", "Extra", "albumX")), listOf(albumRow(server, "albumX")), emptySet(), emptyList())
        val pause = Pause()
        merge.hooks = TestHooks(atClaim = pause)
        val merging = launch(Dispatchers.Default) { merge.rebuild() }
        pause.reached.await()
        db.userDao().noteRating("device:s2", 0, "k", 1, copies = true)
        pause.release()
        merging.join()
        assertEquals(0, ratingOf("device:s2"))
    }

    @Test
    fun aSkippedMergeStillHealsALikeOnAnOldId() = run {
        db.put(library(server, 3))
        db.put(library(DEVICE, 3))
        val hooks = TestHooks()
        val merge = db.testMerge(server, hooks)
        merge.rebuild()
        // A like written with a server copy's id, which is no library song.
        db.exec("INSERT INTO liked_track (trackId, relinkKey, likedAt) VALUES ('$server:s0', '', 1)")
        merge.rebuild()
        assertEquals(1, hooks.skips.get())
        assertTrue(db.userDao().isLiked("device:s0"))
    }

    @Test
    fun aCopyWithNoRoomIsDroppedAndOtherWaitersStillMerge() = run {
        db.put(library(DEVICE, 5))
        db.stage(library(server, 20), generation = 9)
        val merge = db.testMerge(server, TestHooks(), space = 10)
        db.put(library(DEVICE, 6))
        val counts = merge.rebuild()
        assertEquals(6, counts.tracks)
        assertEquals(6L, db.longOf("SELECT COUNT(*) FROM track"))
        try {
            merge.awaitStage(server, 9)
            fail("the copy should not be swapped in")
        } catch (e: StageAborted) {
            // 20 staged songs, and 20 library songs (6 phone songs, 14 more
            // from the copy), asked as a first copy: the library holds no
            // song before its first merge. The log's room beside it is its
            // checkpoint's span and two groups, each this small save's songs
            // (fewer than its bound's entries) at a bound entry's bytes.
            val log = LOG_CHECKPOINT_BYTES + 2 * (20L + 20L) * SAVE_LOG_BYTES_PER_ENTRY
            assertEquals(NotEnoughSpace((20L + 20L) * FIRST_COPY_BYTES_PER_SONG + log).userMessage(), e.reason)
        }
        assertEquals(0L, db.longOf("SELECT COUNT(*) FROM stage_track"))
        assertEquals(STAGE_NONE, db.stringOf("SELECT stage FROM source_meta WHERE sourceId = ?", server))
        assertEquals(0L, db.longOf("SELECT COUNT(*) FROM source_track WHERE sourceId = ?", server))
    }

    // A build that fills the disk drops its copy with the room its save
    // would ask, worked out before the build wrote from the copy's songs and
    // the library's, and reads nothing between the full disk and the abort.
    @Test
    fun aBuildThatFillsTheDiskDropsItsCopyWithTheRoomItsSaveAsks() = run {
        db.put(library(DEVICE, 300))
        db.testMerge(server).also { it.rebuild() }.awaitClear(MERGE_WORK_CLEAR)
        val inner = db.workDao()
        val log = db.counted
        var fail = true
        var failedAt = Long.MAX_VALUE
        val work = object : WorkDao by inner {
            override suspend fun clearMergeWork() {
                if (fail) {
                    fail = false
                    failedAt = log.mark()
                    throw android.database.sqlite.SQLiteFullException("database or disk is full")
                }
                inner.clearMergeWork()
            }
        }
        val merge = CatalogMerge(db, { Long.MAX_VALUE }, { server }, work)
        db.stage(library(server, 400), generation = 3)
        log.reset()
        log.recording = true
        try {
            merge.rebuild()
        } finally {
            log.recording = false
        }
        val reason = try {
            merge.awaitStage(server, 3)
            fail("the copy was swapped in")
            ""
        } catch (e: StageAborted) {
            e.reason
        }
        // The copy's 400 songs and the library's 300, a source the library
        // holds no songs from (a switch), at the default bound: about 17 MB,
        // where the copy's songs alone would ask about 10.
        val songs = 400L + 300L
        val needed = songs * SWITCH_BYTES_PER_SONG + LOG_CHECKPOINT_BYTES + 2 * minOf(SAVE_GROUP_ENTRIES, songs) * SAVE_LOG_BYTES_PER_ENTRY
        assertEquals(NotEnoughSpace(needed).userMessage(), reason)
        val abortAt = log.transactions.filter { it.label == "abort" && it.beginSeq > failedAt }.minOf { it.beginSeq }
        val between = log.statements.filter { !it.bookkeeping && it.seq in (failedAt + 1) until abortAt }
        assertEquals("statements between the full disk and the abort", emptyList<String>(), between.map { it.sql })
        assertEquals(0L, db.longOf("SELECT COUNT(*) FROM stage_track"))
        assertEquals(300L, db.longOf("SELECT COUNT(*) FROM track"))
    }

    @Test
    fun nothingChangedMeansNoMergeAcrossARestartToo() = run {
        db.put(library(DEVICE, 20))
        val hooks = TestHooks()
        db.testMerge(server, hooks).rebuild()
        db.testMerge(server, hooks).rebuild()
        assertEquals(1, hooks.merges.get())
        assertEquals(1, hooks.skips.get())
        db = reopen(db, dir)
        val after = TestHooks()
        db.testMerge(server, after).rebuild()
        assertEquals(0, after.merges.get())
        assertEquals(1, after.skips.get())
    }

    @Test
    fun twoLaunchScansWithNothingChangedMergeNothing() = run {
        val phone = library(DEVICE, 20)
        db.put(phone)
        db.testMerge(server).rebuild()
        val hooks = TestHooks()
        val merge = db.testMerge(server, hooks)
        repeat(2) {
            merge.sourceWrite { db.put(phone) }
            merge.rebuild()
        }
        assertEquals(0, hooks.merges.get())
    }

    @Test
    fun everyInputChangeMerges() = run {
        var inUse: String? = server
        db.put(library(DEVICE, 5))
        db.put(library(server, 5, msShift = 50_000))
        db.put(library("server:other.test", 5, msShift = 60_000))
        val hooks = TestHooks()
        val merge = db.testMerge(null, hooks = hooks, inUse = { inUse })
        merge.rebuild()
        inUse = "server:other.test"
        merge.rebuild()
        db.sourceDao().takeInRows("server:other.test", listOf(song("server:other.test", "t", "New", "albumT")), emptyList(), emptySet(), emptyList())
        merge.rebuild()
        // A server outside the library going changes nothing the library
        // holds: no merge.
        db.sourceDao().deleteSource(server)
        merge.rebuild()
        db.put(library(DEVICE, 6))
        merge.rebuild()
        db.sourceDao().deleteSource("server:other.test")
        merge.rebuild()
        assertEquals(5, hooks.merges.get())
        assertEquals(1, hooks.skips.get())
    }

    @Test
    fun aRatingAloneMergesNothingAndShows() = run {
        db.put(library(DEVICE, 3))
        db.put(library(server, 3))
        val hooks = TestHooks()
        val merge = db.testMerge(server, hooks)
        merge.rebuild()
        db.userDao().noteRating("device:s1", 5, null, 1, copies = true)
        merge.rebuild()
        assertEquals(1, hooks.merges.get())
        assertEquals(5, ratingOf("device:s1"))
    }

    @Test
    fun askingTenTimesDuringAMergeRunsAtMostTwo() = run {
        db.put(library(DEVICE, 20))
        val pause = Pause()
        val hooks = TestHooks(atClaim = pause)
        val merge = db.testMerge(server, hooks)
        val first = launch(Dispatchers.Default) { merge.rebuild() }
        pause.reached.await()
        db.put(library(DEVICE, 21))
        val asks = (1..10).map { async(Dispatchers.Default) { merge.rebuild() } }
        pause.release()
        first.join()
        asks.awaitAll()
        assertTrue("merges ${hooks.merges.get()}", hooks.merges.get() <= 2)
        assertEquals(21L, db.longOf("SELECT COUNT(*) FROM track"))
    }

    @Test
    fun aRecopyWithNothingNewWritesNoLibraryRow() = run {
        db.put(library(DEVICE, 30))
        db.put(library(server, 30, msShift = 100))
        val merge = db.testMerge(server)
        merge.rebuild()
        db.exec("CREATE TABLE track_writes (n INTEGER)")
        db.exec("CREATE TRIGGER count_insert AFTER INSERT ON track BEGIN INSERT INTO track_writes VALUES (1); END")
        db.exec("CREATE TRIGGER count_update AFTER UPDATE ON track BEGIN INSERT INTO track_writes VALUES (1); END")
        db.exec("CREATE TRIGGER count_delete AFTER DELETE ON track BEGIN INSERT INTO track_writes VALUES (1); END")
        db.stage(library(server, 30, msShift = 100), generation = 4)
        merge.rebuild()
        merge.awaitStage(server, 4)
        assertEquals(0L, db.longOf("SELECT COUNT(*) FROM track_writes"))
    }

    @Test
    fun takeInsLoggedDuringACopySurviveItsSwap() = run {
        db.put(library(DEVICE, 5))
        db.put(library(server, 5, msShift = 100))
        val merge = db.testMerge(server)
        merge.rebuild()
        // The copy began (generation 6); a download was taken in while it ran.
        val taken = db.sourceDao().takeInRows(server, listOf(song(server, "dl", "Downloaded", "albumDl")), listOf(albumRow(server, "albumDl", "Fresh")), emptySet(), emptyList())
        taken.tracks.forEach { db.exec("INSERT INTO take_in_log (generation, kind, id) VALUES (6, 'track', ?)", it) }
        taken.albums.forEach { db.exec("INSERT INTO take_in_log (generation, kind, id) VALUES (6, 'album', ?)", it) }
        db.stage(library(server, 5, msShift = 100), generation = 6)
        merge.rebuild()
        merge.awaitStage(server, 6)
        val id = "$server:dl"
        assertEquals(id, db.stringOf("SELECT id FROM track WHERE id = ?", id))
        assertEquals(id, db.stringOf("SELECT mergedId FROM source_track WHERE id = ?", id))
        assertEquals(0L, db.longOf("SELECT COUNT(*) FROM take_in_log"))
    }

    @Test
    fun aSwappedCopyBecomesItsAccountsAndOwesItsSteps() = run {
        db.put(library(DEVICE, 3))
        db.exec(
            "INSERT INTO account_sync (account, sourceId, syncedAt, songs, albums, rowsVersion, lastScan, afterSyncDone) " +
                "VALUES ('acct', ?, 1, 3, 1, 6, NULL, 1)",
            server,
        )
        db.stage(library(server, 3), generation = 7, owner = "acct")
        val merge = db.testMerge(server)
        merge.rebuild()
        merge.awaitStage(server, 7)
        assertEquals("acct", db.stringOf("SELECT copyOf FROM source_meta WHERE sourceId = ?", server))
        assertNull(db.stringOf("SELECT stageOwner FROM source_meta WHERE sourceId = ?", server))
        assertEquals(0L, db.longOf("SELECT afterSyncDone FROM account_sync WHERE account = 'acct'"))
    }

    @Test
    fun anAbortedCopyLeavesTheLiveRowsAlone() = run {
        db.put(library(DEVICE, 5))
        db.put(library(server, 5, msShift = 100))
        val merge = db.testMerge(server, space = 0)
        merge.rebuild()
        val stamp = db.longOf("SELECT stamp FROM source_meta WHERE sourceId = ?", server)
        val rows = db.longOf("SELECT COUNT(*) FROM source_track WHERE sourceId = ?", server)
        db.stage(library(server, 50), generation = 2)
        merge.rebuild()
        try {
            merge.awaitStage(server, 2)
            fail("the copy should not be swapped in")
        } catch (e: StageAborted) {
            assertTrue(e.reason.isNotBlank())
        }
        assertEquals(stamp, db.longOf("SELECT stamp FROM source_meta WHERE sourceId = ?", server))
        assertEquals(rows, db.longOf("SELECT COUNT(*) FROM source_track WHERE sourceId = ?", server))
        assertEquals(0L, db.longOf("SELECT COUNT(*) FROM stage_track"))
    }

    @Test
    fun keysFromAnOlderReadingMergeAsFresh() = run {
        db.put(library(DEVICE, 4))
        db.put(library(server, 4, msShift = 100))
        db.exec("UPDATE source_track SET titleKey = 'stale', matchKey = 'stale', keysVersion = 0")
        db.testMerge(server).rebuild()
        assertEquals(4L, db.longOf("SELECT COUNT(*) FROM track"))
        assertEquals("device:s2", db.stringOf("SELECT mergedId FROM source_track WHERE id = ?", "$server:s2"))
        assertEquals(0L, db.longOf("SELECT COUNT(*) FROM source_track WHERE keysVersion != ?", SONG_KEYS_VERSION))
    }

    @Test
    fun repairMergesRowsLeftUnmerged() = run {
        db.put(library(DEVICE, 4))
        assertEquals(0L, db.longOf("SELECT COUNT(*) FROM track"))
        db.testMerge(server).repair()
        assertEquals(4L, db.longOf("SELECT COUNT(*) FROM track"))
    }

    @Test
    fun aCopyForAServerNoLongerInUseIsDropped() = run {
        db.put(library(DEVICE, 3))
        db.stage(library("server:old.test", 3), generation = 1)
        val merge = db.testMerge(server)
        merge.rebuild()
        try {
            merge.awaitStage("server:old.test", 1)
            fail("the copy should not be swapped in")
        } catch (e: StageAborted) {
            assertEquals("Another server is in use now.", e.reason)
        }
        // Its rows leave the stage after the merge.
        merge.awaitStageClear("server:old.test")
        assertNull(db.stringOf("SELECT id FROM stage_track LIMIT 1"))
    }

    @Test
    fun artistCountsComeFromOnePass() = run {
        fun many(source: String, artists: Int) = SourceCatalog(
            source, source == DEVICE,
            (0 until artists).map { song(source, "s$it", "Song $it", "album$it").copy(artistId = "$source:artist$it", artist = "Artist $it") },
            (0 until artists).map { albumRow(source, "album$it", "Album $it").copy(artistId = "$source:artist$it", artist = "Artist $it") },
            (0 until artists).map { SourceArtistEntity("$source:artist$it", source, "Artist $it", "artist $it", "artist $it", 1, 1, null) },
        )
        val small = CountingWorkDao(db.workDao())
        db.put(many(DEVICE, 500))
        db.testMerge(server, work = small).rebuild()
        val big = CountingWorkDao(db.workDao())
        db.put(many(DEVICE, 2_000))
        db.testMerge(server, work = big).rebuild()
        assertTrue("pages ${small.pages.size} then ${big.pages.size}", big.pages.size <= small.pages.size * 4 + 4)
        val artist = db.library().artists.single { it.id == "device:artist1999" }
        assertEquals(1, artist.songCount)
        assertEquals(1, artist.albumCount)
    }
}
