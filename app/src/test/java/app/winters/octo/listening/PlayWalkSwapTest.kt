package app.winters.octo.listening

import app.winters.octo.catalog.CatalogMerge
import app.winters.octo.catalog.SourceCatalog
import app.winters.octo.catalog.albumRow
import app.winters.octo.catalog.artistRow
import app.winters.octo.catalog.put
import app.winters.octo.catalog.song
import app.winters.octo.device.DEVICE
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

// A rebuild across a save that renames a played song: whether the save
// lands between two pages of the diff (the song's id moving from behind
// the walk to ahead of it, or the other way) or between two pages of the
// apply, the rebuild ends in one pass and no other follows it, since the
// save's groups kept the totals and the apply counts each song again; the
// totals never hold the song under two ids, so no album counts its plays
// twice, and a reader whose wait began after the save reads the song once
// under its new id.
@RunWith(RobolectricTestRunner::class)
class PlayWalkSwapTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    // Where in the rebuild the swap lands.
    private enum class At { SecondStoredPage, FirstPhonePage, FirstApplyPage }

    private fun phone(vararg natives: String) = SourceCatalog(
        DEVICE,
        true,
        natives.map { song(DEVICE, it, "Song ${it.drop(1)}", "a") },
        listOf(albumRow(DEVICE, "a")),
        listOf(artistRow(DEVICE)),
    )

    private val server = SourceCatalog(
        HOME,
        false,
        listOf(song(HOME, "s0", "Song 0", "a"), song(HOME, "s1", "Song 1", "a")),
        listOf(albumRow(HOME, "a")),
        listOf(artistRow(HOME)),
    )

    private fun byAlbum(): Map<String, Long> = db.query("SELECT albumId, SUM(plays) FROM play_total GROUP BY albumId", null).use { c ->
        buildMap { while (c.moveToNext()) put(c.getString(0), c.getLong(1)) }
    }

    private suspend fun todayByAlbum() = TodayPlays(db, "home").tracks().groupBy { it.track.albumId }.mapValues { (_, songs) -> songs.sumOf { it.plays.toLong() } }

    // The library from these phone songs and the server's two, merged.
    private suspend fun library(vararg phoneSongs: String): CatalogMerge {
        db.put(phone(*phoneSongs))
        db.put(server)
        return db.testMerge(HOME).also { it.rebuild() }
    }

    // Builds the totals, runs `before`, then a rebuild with `swap` landing
    // `at` its point, and checks what that rebuild left once it ends and
    // the totals are built through the save: it opened once and no rebuild
    // followed, no stored row names a song the library lacks, every album
    // counts what today does, and a reader that begins then reads `renamed`
    // as played `plays` times.
    private suspend fun acrossASwap(merge: CatalogMerge, at: At, renamed: String, plays: Int, before: () -> Unit = {}, swap: suspend () -> Unit) {
        val rig = TotalsRig(db, merge, page = 1).started()
        before()
        val armed = AtomicBoolean(true)
        val loads = AtomicInteger()
        val opened = AtomicInteger()
        suspend fun swapOnce(here: Boolean) {
            if (here && armed.getAndSet(false)) swap()
        }
        rig.totals.hooks = object : TotalsHooks() {
            override suspend fun opened() {
                opened.incrementAndGet()
            }

            override suspend fun page(stream: PlayStream) = when (at) {
                At.SecondStoredPage -> swapOnce(stream == PlayStream.Stored && loads.incrementAndGet() == 2)
                At.FirstPhonePage -> swapOnce(stream == PlayStream.Phone)
                At.FirstApplyPage -> Unit
            }

            override suspend fun applied() = swapOnce(at == At.FirstApplyPage)
        }
        val applying = rig.totals.applying.value
        rig.totals.rebuild()
        rig.totals.applying.first { it >= applying + 2 && !armed.get() }
        rig.totals.builtThrough.first { it >= merge.swapStamp.value }
        rig.built()
        assertEquals("the rebuild that crossed the save opened once, and none followed", 1, opened.get())
        assertEquals(0L, db.longOf("SELECT COUNT(*) FROM play_total WHERE trackId NOT IN (SELECT id FROM track)"))
        assertEquals(todayByAlbum(), byAlbum())
        assertEquals(plays, withTimeoutOrNull(10_000) { rig.history.played(renamed)?.plays })
        assertEquals(1L, db.longOf("SELECT COUNT(*) FROM play_total WHERE trackId = ?", renamed))
    }

    // Song 0 moves from behind the walk to ahead of it: the phone's copy
    // goes, so its id becomes the server's, after the walk passed the old id.
    @Test
    fun aRenameFromBehindTheWalkToAheadOfIt() = timed(20) {
        val merge = library("s0", "s1")
        repeat(3) { db.playOn("device:s0", NOW - it, relinkKey = "drake|song 0") }
        db.playOn("device:s1", NOW - 10, relinkKey = "drake|song 1")
        acrossASwap(merge, At.SecondStoredPage, "server:h:s0", 3) {
            db.put(phone("s1"))
            merge.rebuild()
        }
    }

    // Song 0 moves from ahead of the walk to behind it: the phone gains its
    // copy, so its id becomes the phone's, which sorts before the server's,
    // once the walk is past the phone's id.
    @Test
    fun aRenameFromAheadOfTheWalkToBehindIt() = timed(20) {
        val merge = library("s1")
        repeat(3) { db.playOn("server:h:s0", NOW - it, relinkKey = "drake|song 0") }
        db.playOn("device:s1", NOW - 10, relinkKey = "drake|song 1")
        acrossASwap(merge, At.FirstPhonePage, "device:s0", 3) {
            db.put(phone("s0", "s1"))
            merge.rebuild()
        }
    }

    // Song 1 is renamed between two pages of the apply: its diff row, read
    // before the save, is counted again at its page, finds no song under
    // the old id and takes its row out.
    @Test
    fun aRenameBetweenTwoPagesOfTheApply() = timed(20) {
        val merge = library("s0", "s1")
        repeat(3) { db.playOn("device:s0", NOW - it, relinkKey = "drake|song 0") }
        db.playOn("device:s1", NOW - 10, relinkKey = "drake|song 1")
        val more = {
            repeat(2) { db.playOn("device:s0", NOW + it, relinkKey = "drake|song 0") }
            db.playOn("device:s1", NOW + 5, relinkKey = "drake|song 1")
        }
        acrossASwap(merge, At.FirstApplyPage, "server:h:s1", 2, before = more) {
            db.put(phone("s0"))
            merge.rebuild()
        }
        assertEquals(5L, db.longOf("SELECT plays FROM play_total WHERE trackId = 'device:s0'"))
    }
}
