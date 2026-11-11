package app.winters.octo.server

import app.winters.octo.catalog.FreeSpace
import app.winters.octo.catalog.MergeHooks
import app.winters.octo.catalog.StageAborted
import app.winters.octo.catalog.StageProgress
import app.winters.octo.subsonic.Album
import app.winters.octo.subsonic.Library
import app.winters.octo.subsonic.Song
import app.winters.octo.subsonic.Starred
import app.winters.octo.testing.FakeSubsonic
import app.winters.octo.testing.GenShape
import app.winters.octo.testing.LibraryGen
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Collections

private const val SOURCE = "server:music.test"
private const val ACCOUNT = "acc-1"

// What a copy says of how far it is, from the server's pages to the swap.
@RunWith(RobolectricTestRunner::class)
class CopyProgressTest {
    private val db = openTestCatalog()
    private val dao = db.copyDao()
    private val progress = Collections.synchronizedList(mutableListOf<StageProgress?>())
    private val hooks = object : MergeHooks() {
        override fun progress(progress: StageProgress?) {
            this@CopyProgressTest.progress += progress
        }
    }
    private val fakes = mutableListOf<FakeSubsonic>()
    private val tracker = CopyTracker()
    private val seen = Collections.synchronizedList(mutableListOf<CopyRunState?>())

    init {
        tracker.seen = { seen += it }
    }

    @After
    fun close() {
        fakes.forEach { it.server.close() }
        db.close()
    }

    private fun fake(library: Library) = FakeSubsonic(library, Starred()).also { fakes += it }

    private fun copier(space: Long = Long.MAX_VALUE) = StagedCopy(db, db.testMerge(SOURCE, hooks, space), FreeSpace { Long.MAX_VALUE })

    // A first copy's report, at pass 1, planned whole.
    private fun report(): CopyReport {
        val token = tracker.begin(ACCOUNT, "Home", arriving = true, whole = true)
        val pass = tracker.nextPass(token, arriving = true, whole = true)!!
        tracker.planned(token, pass, arriving = true, whole = true)
        return tracker.report(token, pass)
    }

    private fun seenAt(step: CopyStep) = seen.toList().filterNotNull().filter { it.step == step }

    // Each Read count is of rows that went in: a song listed twice counts
    // once, and the total is the listed albums' songs once all are listed.
    @Test
    fun readingCountsSongsThatWentInOfTheListedAlbumsSongs() = runBlocking {
        val shape = GenShape(songs = 2_500, albums = 50, artists = 10, unlisted = 0.0, songless = 0, albumless = 0, repeats = 40)
        val copy = copier()
        copy.start(ACCOUNT, SOURCE)
        copy.read(fake(LibraryGen.server(3, shape)).client(), SOURCE, report())
        val reads = seenAt(CopyStep.Read)
        assertTrue(reads.all { it.done <= 2_500 })
        assertEquals(2_500L, reads.last().done)
        assertEquals(2_500L, reads.last().of)
        assertEquals(2_500L, dao.syncSongCount().toLong())
        // A cover from the first page shows at once.
        assertTrue(reads.first { it.done > 0 }.artwork != null)
    }

    // An album a shifted page lists again counts toward the total once.
    @Test
    fun anAlbumListedTwiceCountsOnce() = runBlocking {
        val library = LibraryGen.server(4, GenShape(songs = 600, albums = 12, artists = 4, unlisted = 0.0, songless = 0, albumless = 0))
        val twice = Library(library.songs, library.albums + library.albums.first(), library.artists)
        val copy = copier()
        copy.start(ACCOUNT, SOURCE)
        copy.read(fake(twice).client(), SOURCE, report())
        assertEquals(600L, seenAt(CopyStep.Read).last().of)
    }

    // A server that lists fewer songs than it gives (albums missing from
    // its list, songs with no album) still moves the ring on every page.
    @Test
    fun readingMovesOnEveryPageWithAnUndercountingTotal() = runBlocking {
        val copy = copier()
        copy.start(ACCOUNT, SOURCE)
        copy.read(fake(LibraryGen.server(5, GenShape(songs = 6_000, albums = 300, artists = 60))).client(), SOURCE, report())
        val reads = seenAt(CopyStep.Read).filter { it.fraction != null }
        assertTrue(reads.size >= 10)
        reads.zipWithNext().forEach { (a, b) -> if (b.done > a.done) assertTrue("${a.done} ${a.fraction} then ${b.done} ${b.fraction}", b.fraction!! > a.fraction!!) }
        assertTrue(reads.last().fraction!! < READ_SHARE)
    }

    // Albums are counted each time some are written, and end at every
    // album with songs.
    @Test
    fun buildingCountsAlbumsAsTheyAreWritten() = runBlocking {
        val copy = copier()
        val generation = copy.start(ACCOUNT, SOURCE)
        val report = report()
        val songs = copy.read(fake(LibraryGen.server(6, GenShape(songs = 10_000, albums = 100, artists = 20, songless = 0))).client(), SOURCE, report)
        copy.build(SOURCE, generation, songs, report)
        val builds = seenAt(CopyStep.Build)
        val albums = builds.last().of!!
        assertEquals(albums, builds.last().done)
        // At most one report a flush of 2,000 songs, and the start.
        assertTrue("${builds.size} reports", builds.size <= songs / GIANT_ALBUM + 3)
        assertTrue(builds.size >= 3)
    }

    // The merge says how far it is with the copy it claimed, page by page,
    // then that it is saving, and nothing once the claim is over.
    @Test
    fun theMergeSaysSongsPlacedThenSavingThenNothing() = runBlocking {
        val copy = copier()
        copy.copy(ACCOUNT, SOURCE, fake(LibraryGen.server(7, GenShape(songs = 1_800, albums = 60, artists = 20, onPhone = 0.0, phoneOnly = 0))).client(), report())
        val claimed = progress.filterNotNull()
        val of = claimed.first().of
        assertEquals(0, claimed.first().placed)
        assertEquals(claimed.map { it.placed }, claimed.map { it.placed }.sorted())
        assertEquals(of, claimed.last().placed)
        assertTrue(claimed.last().saving)
        // Saving says once that every song is placed, then how much of the
        // save is in after each group, never moving back.
        val saving = claimed.filter { it.saving }
        assertEquals(1, saving.count { it.saved == null })
        assertNull(saving.first().saved)
        val parts = saving.mapNotNull { it.saved }
        assertTrue("parts $parts", parts.isNotEmpty() && parts.zipWithNext().all { (a, b) -> b >= a } && parts.last() == 1f)
        assertNull(progress.last())
        // The copy reached Add, with the staged songs as its total.
        val adds = seenAt(CopyStep.Add)
        assertEquals(0L, adds.first().done)
        assertEquals(of.toLong(), adds.first().of)
        assertTrue(tracker.run.value!!.step in setOf(CopyStep.Add, CopyStep.Save))
    }

    // Music taken in ahead of the first copy (a search, a download) leaves
    // the server's music still to come; the first whole copy's swap ends it,
    // for every account on the address.
    @Test
    fun aTakeInKeepsTheMusicComingAndAWholeCopyEndsIt() = runBlocking {
        val copy = copier()
        val late = Song(id = "late-1", title = "Arrived Early", album = "Early Album", albumId = "early-al", artist = "Early Artist", artistId = "early-ar", duration = 200)
        val album = Album(id = "early-al", name = "Early Album", artist = "Early Artist", artistId = "early-ar", songCount = 1, duration = 200)
        assertTrue(copy.takeIn(SOURCE, buildServerCatalog(SOURCE, Library(listOf(late), listOf(album), emptyList())), readAlbums = setOf("early-al")))
        assertTrue(arrivingFrom(dao.copyOf(SOURCE), null))
        copy.copy(ACCOUNT, SOURCE, fake(LibraryGen.server(10, GenShape(songs = 600, albums = 30, artists = 10))).client())
        assertEquals(ACCOUNT, dao.copyOf(SOURCE))
        assertFalse(arrivingFrom(dao.copyOf(SOURCE), null))
    }

    @Test
    fun aMergeWithNoCopySaysNothing() = runBlocking {
        db.testMerge(SOURCE, hooks).rebuild()
        assertTrue(progress.isEmpty())
    }

    @Test
    fun aClaimThatIsAbortedEndsWithNothing() = runBlocking {
        val copy = copier(space = 0)
        try {
            copy.copy(ACCOUNT, SOURCE, fake(LibraryGen.server(8, GenShape(songs = 1_200, albums = 60, artists = 20))).client(), report())
            fail("the swap had no room")
        } catch (e: StageAborted) {
            // The merge's own check for room.
        }
        assertTrue(progress.filterNotNull().isNotEmpty())
        assertNull(progress.last())
    }
}
