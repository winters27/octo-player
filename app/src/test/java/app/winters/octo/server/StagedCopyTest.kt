package app.winters.octo.server

import android.database.DatabaseUtils
import app.winters.octo.catalog.COPY_PAGES_CLEAR
import app.winters.octo.catalog.FreeSpace
import app.winters.octo.catalog.NotEnoughSpace
import app.winters.octo.catalog.STAGE_NONE
import app.winters.octo.data.userMessage
import app.winters.octo.subsonic.Album
import app.winters.octo.subsonic.Library
import app.winters.octo.subsonic.Song
import app.winters.octo.subsonic.Starred
import app.winters.octo.testing.FakeSubsonic
import app.winters.octo.testing.GenShape
import app.winters.octo.testing.LibraryGen
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.rawConnection
import app.winters.octo.testing.testMerge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Collections
import kotlin.math.abs

private const val SOURCE = "server:music.test"
private const val ACCOUNT = "acc-1"

@RunWith(RobolectricTestRunner::class)
class StagedCopyTest {
    private val db = openTestCatalog()
    private val raw = rawConnection(db)
    private val dao = db.copyDao()
    private val merge = db.testMerge(SOURCE)
    @Volatile private var free = Long.MAX_VALUE
    private val copy = StagedCopy(db, merge, FreeSpace { free })
    private val fakes = mutableListOf<FakeSubsonic>()

    @After
    fun close() {
        fakes.forEach { it.server.close() }
        raw.close()
        db.close()
    }

    private fun fake(library: Library) = FakeSubsonic(library, Starred()).also { fakes += it }

    private fun count(sql: String, vararg args: String): Long = DatabaseUtils.longForQuery(raw, sql, args)

    private fun stageEmpty() =
        count("SELECT COUNT(*) FROM stage_track") + count("SELECT COUNT(*) FROM stage_album") + count("SELECT COUNT(*) FROM stage_artist") == 0L

    // A copy that ended leaves its stage and its pages in the background,
    // after it: waits for both.
    private suspend fun clearedAfter() {
        merge.awaitStageClear(SOURCE)
        merge.awaitClear(COPY_PAGES_CLEAR)
    }

    // Each song page is kept before the next is asked for: when the request
    // for the page at offset n arrives, exactly n songs are already written.
    private fun assertPagesWrittenAsRead(songs: Int) = runBlocking {
        val library = LibraryGen.server(11, GenShape(songs = songs, albums = songs / 10, artists = songs / 50))
        val seen = Collections.synchronizedList(mutableListOf<Pair<Long, Long>>())
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url
            if (url.pathSegments.last() == "search3") {
                seen += url.queryParameter("songOffset")!!.toLong() to count("SELECT COUNT(*) FROM sync_song")
            }
            chain.proceed(chain.request())
        }.build()
        copy.start(ACCOUNT, SOURCE)
        copy.read(fake(library).client(http), SOURCE)
        assertEquals(songs / 500 + 1, seen.size)
        seen.forEach { (offset, written) -> assertEquals("written before the page at $offset", offset, written) }
    }

    // Removing a source takes its record, but its next copy still gets a
    // number no earlier copy in this process had.
    @Test
    fun aSourceRemovedAndAddedAgainNeverRepeatsAGeneration() = runBlocking {
        val first = copy.start(ACCOUNT, SOURCE)
        db.sourceDao().deleteSource(SOURCE)
        val second = copy.start(ACCOUNT, SOURCE)
        assertTrue("$second after $first", second > first)
    }

    @Test
    fun aCopyWritesEachPageAsItReads2k() = assertPagesWrittenAsRead(2_000)

    @Test
    fun aCopyWritesEachPageAsItReads20k() = assertPagesWrittenAsRead(20_000)

    @Test
    fun musicTakenInDuringACopySurvivesItsSwap() = runBlocking {
        val fakeServer = fake(LibraryGen.server(1, GenShape(songs = 1_200, albums = 60, artists = 20)))
        val paused = CompletableDeferred<Unit>()
        val go = CompletableDeferred<Unit>()
        copy.reached = { point ->
            if (point == CopyPoint.Page && !paused.isCompleted) {
                paused.complete(Unit)
                go.await()
            }
        }
        val run = async(Dispatchers.Default) { copy.copy(ACCOUNT, SOURCE, fakeServer.client()) }
        paused.await()
        val late = Song(id = "late-1", title = "Arrived Late", album = "Late Album", albumId = "late-al", artist = "Late Artist", artistId = "late-ar", duration = 200)
        val lateAlbum = Album(id = "late-al", name = "Late Album", artist = "Late Artist", artistId = "late-ar", songCount = 1, duration = 200)
        assertTrue(copy.takeIn(SOURCE, buildServerCatalog(SOURCE, Library(listOf(late), listOf(lateAlbum), emptyList())), readAlbums = setOf("late-al")))
        merge.rebuild()
        go.complete(Unit)
        run.await()
        val mergedId = DatabaseUtils.stringForQuery(raw, "SELECT mergedId FROM source_track WHERE id = ?", arrayOf("$SOURCE:late-1"))
        assertNotEquals("", mergedId)
        assertEquals(1L, count("SELECT COUNT(*) FROM track WHERE id = ?", mergedId))
        assertEquals(0L, count("SELECT COUNT(*) FROM take_in_log"))
    }

    @Test
    fun stoppingACopyAfterItsStageIsReadyLeavesTheLibraryAndClearsTheStage() = runBlocking {
        val fakeServer = fake(LibraryGen.server(2, GenShape(songs = 1_200, albums = 60, artists = 20)))
        val ready = CompletableDeferred<Unit>()
        copy.reached = { point ->
            if (point == CopyPoint.Ready) {
                ready.complete(Unit)
                awaitCancellation()
            }
        }
        val run = launch(Dispatchers.Default) { copy.copy(ACCOUNT, SOURCE, fakeServer.client()) }
        ready.await()
        assertFalse(stageEmpty())
        run.cancelAndJoin()
        clearedAfter()
        assertTrue(stageEmpty())
        assertEquals(0L, count("SELECT COUNT(*) FROM source_track"))
        assertEquals(0L, count("SELECT COUNT(*) FROM sync_song"))
        assertEquals(STAGE_NONE, dao.meta(SOURCE)!!.stage)
    }

    @Test
    fun runningShortOfSpaceBeforeTheBuildEndsWithTheWordedProblem() = runBlocking {
        val fakeServer = fake(LibraryGen.server(5, GenShape(songs = 1_200, albums = 60, artists = 20)))
        // 1,200 songs are three pages, so the next look at the free space is the build's.
        copy.reached = { point -> if (point == CopyPoint.Page) free = 0 }
        try {
            copy.copy(ACCOUNT, SOURCE, fakeServer.client())
            fail("the copy went on without room")
        } catch (e: NotEnoughSpace) {
            assertEquals(2 * 1_200 * STAGE_BYTES_PER_SONG, e.neededBytes)
        }
        clearedAfter()
        assertTrue(stageEmpty())
        assertEquals(0L, count("SELECT COUNT(*) FROM source_track"))
        assertEquals(0L, count("SELECT COUNT(*) FROM sync_song"))
        assertEquals(STAGE_NONE, dao.meta(SOURCE)!!.stage)
    }

    @Test
    fun freeSpaceDroppingMidReadEndsWithTheWordedProblem() = runBlocking {
        val fakeServer = fake(LibraryGen.server(6, GenShape(songs = 6_000, albums = 300, artists = 60)))
        var pages = 0
        copy.reached = { point -> if (point == CopyPoint.Page && ++pages == 5) free = 0 }
        try {
            copy.copy(ACCOUNT, SOURCE, fakeServer.client())
            fail("the copy went on without room")
        } catch (e: NotEnoughSpace) {
            // The look after the tenth page caught it, before that page's pause point.
            assertEquals(9, pages)
            assertNotEquals("Something went wrong.", e.userMessage())
        }
        clearedAfter()
        assertTrue(stageEmpty())
        assertEquals(0L, count("SELECT COUNT(*) FROM sync_song"))
        assertEquals(STAGE_NONE, dao.meta(SOURCE)!!.stage)
    }

    @Test
    fun everyStepAfterACopyRunsBeforeItIsDone() = runBlocking {
        val fakeServer = fake(LibraryGen.server(8, GenShape(songs = 600, albums = 30, artists = 10)))
        val order = mutableListOf<Int>()
        val steps = (1..5).map { n ->
            suspend {
                assertFalse("step $n ran after the copy was done", dao.accountSync(ACCOUNT)!!.afterSyncDone)
                order += n
            }
        }
        val done = CopyRun(copy, dao, steps, clock = { 42L }).run(ACCOUNT, SOURCE, fakeServer.client(), copyAll = true, swapped = {})
        assertEquals(listOf(1, 2, 3, 4, 5), order)
        val row = dao.accountSync(ACCOUNT)!!
        assertTrue(row.afterSyncDone)
        assertEquals(42L, row.syncedAt)
        assertEquals(done.songs, row.songs)
        assertEquals(600, row.songs)
        assertEquals(ACCOUNT, dao.copyOf(SOURCE))
    }

    // Also: a whole copy says it is in the library once it is swapped in,
    // before the steps after it, so a step that fails cannot take that back;
    // a run of the steps alone never says it.
    @Test
    fun stepsCutOffAfterASwapRunAgainWithoutACopy() = runBlocking {
        val fakeServer = fake(LibraryGen.server(9, GenShape(songs = 600, albums = 30, artists = 10)))
        var cut = true
        var swaps = 0
        val ran = mutableListOf<Int>()
        val steps = (1..5).map { n ->
            suspend {
                if (cut) assertEquals("step $n ran before the copy said it was in", 1, swaps)
                if (n == 3 && cut) error("the app closed")
                ran += n
            }
        }
        val swapped: suspend () -> Unit = {
            swaps++
            assertEquals(ACCOUNT, dao.copyOf(SOURCE))
            assertFalse(dao.accountSync(ACCOUNT)!!.afterSyncDone)
        }
        try {
            CopyRun(copy, dao, steps).run(ACCOUNT, SOURCE, fakeServer.client(), copyAll = true, swapped)
            fail("the third step should have stopped the run")
        } catch (e: IllegalStateException) {
            assertEquals("the app closed", e.message)
        }
        assertEquals(1, swaps)
        val row = dao.accountSync(ACCOUNT)!!
        assertFalse(row.afterSyncDone)
        assertEquals(CopyNeed.AfterSync, copyNeed(row, dao.copyOf(SOURCE), ACCOUNT, SOURCE, System.currentTimeMillis()))
        cut = false
        ran.clear()
        val asked = fakeServer.requests.size
        CopyRun(copy, dao, steps).run(ACCOUNT, SOURCE, fakeServer.client(), copyAll = false, swapped)
        assertEquals(listOf(1, 2, 3, 4, 5), ran)
        assertEquals(1, swaps)
        assertTrue(fakeServer.requests.drop(asked).none { "search3" in it || "getAlbumList2" in it })
        assertTrue(dao.accountSync(ACCOUNT)!!.afterSyncDone)
    }

    @Test
    fun diskConstantsHoldWithinTenPercent() = runBlocking {
        val songs = 4_000
        val library = LibraryGen.server(7, GenShape(songs = songs, albums = 400, artists = 100))
        // The file's pages in use, every write checkpointed in.
        fun usedBytes(): Long {
            count("PRAGMA wal_checkpoint(TRUNCATE)")
            return (count("PRAGMA page_count") - count("PRAGMA freelist_count")) * count("PRAGMA page_size")
        }
        val base = usedBytes()
        val generation = copy.start(ACCOUNT, SOURCE)
        copy.read(fake(library).client(), SOURCE)
        val sync = (usedBytes() - base) / songs
        copy.build(SOURCE, generation, songs.toLong())
        val stage = (usedBytes() - base) / songs
        println("MEASURED copy sync=$sync stage=$stage constants=$SYNC_BYTES_PER_SONG,$STAGE_BYTES_PER_SONG")
        assertTrue("sync $sync against $SYNC_BYTES_PER_SONG", abs(sync - SYNC_BYTES_PER_SONG) <= SYNC_BYTES_PER_SONG / 10)
        // The stage's constant holds what a song takes, with room to spare.
        assertTrue("measured stage $stage, constant $STAGE_BYTES_PER_SONG", stage <= STAGE_BYTES_PER_SONG)
        assertTrue("measured stage $stage, constant $STAGE_BYTES_PER_SONG", STAGE_BYTES_PER_SONG <= stage * 11 / 10 + 10)
    }
}
