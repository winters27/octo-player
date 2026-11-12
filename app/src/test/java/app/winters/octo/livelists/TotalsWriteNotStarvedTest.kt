package app.winters.octo.livelists

import app.winters.octo.catalog.WorkOwners
import app.winters.octo.listening.HoldsReadHold
import app.winters.octo.listening.NOW
import app.winters.octo.listening.TotalsHooks
import app.winters.octo.listening.TotalsWrite
import app.winters.octo.listening.growAndSwap
import app.winters.octo.listening.mergedLibrary
import app.winters.octo.listening.playOn
import app.winters.octo.listening.timed
import app.winters.octo.playback.AUTOPLAY_BATCH
import app.winters.octo.playback.LibraryRadio
import app.winters.octo.playback.RADIO_PAGE
import app.winters.octo.playback.RadioHooks
import app.winters.octo.player.PlayerPrefs
import app.winters.octo.query.LibraryQuery
import app.winters.octo.query.QuerySort
import app.winters.octo.sort.SongSort
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

// The live lists' and the radio's part. Two lists ordered by plays, built
// one after the other, each take a read hold of the play counts page after
// page, while two radio runs for Autoplay (its exclusion function, the
// queue's last songs) keep asking. A refresh of the counts asked for inside
// a page's hold of the first, and a rebuild asked for inside a page's hold
// of the second, each wait for that one hold: no new hold enters, a page's
// or a radio's, until the write has run, so neither write waits for a
// whole build. And a radio's last try that asks for its hold while a write
// waits is turned away, and reads only after the write. Guard: the base
// held no holds.
@RunWith(RobolectricTestRunner::class)
class TotalsWriteNotStarvedTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    @Test
    fun aWriteEntersWithinOneHold() = timed(90) {
        val tracks = db.liveLibrary(60)
        val rig = LiveRig(db, page = 5).started()
        val totals = rig.totals.totals
        val events = CopyOnWriteArrayList<String>()
        var waits = CompletableDeferred<Unit>()
        val applied = CompletableDeferred<Unit>()
        val refreshedOnce = CompletableDeferred<Unit>()
        totals.holds.writeWaits = {
            events += "write waits"
            waits.complete(Unit)
        }
        totals.hooks = object : TotalsHooks() {
            override suspend fun writing(write: TotalsWrite) {
                events += "write $write"
                if (write == TotalsWrite.Apply) applied.complete(Unit)
                if (write == TotalsWrite.Refresh) refreshedOnce.complete(Unit)
            }
        }
        var refreshed = false
        var rebuilt = false
        rig.picks.hooks = object : LiveHooks() {
            override suspend fun plays(owner: String, n: Int) {
                events += "$owner hold $n"
                if (n == 3 && owner == WorkOwners.marked("live:a") && !refreshed) {
                    refreshed = true
                    waits = CompletableDeferred()
                    db.playOn(tracks[2].id, NOW)
                    totals.refresh(listOf(tracks[2].id))
                    waits.await()
                } else if (n == 3 && owner == WorkOwners.marked("live:b") && !rebuilt) {
                    rebuilt = true
                    waits = CompletableDeferred()
                    totals.rebuild()
                    waits.await()
                }
            }
        }
        val radio = LibraryRadio(db, totals, { _, _ -> emptyList() }, flowOf(PlayerPrefs()), RADIO_PAGE) { NOW }
        radio.hooks = object : RadioHooks() {
            override suspend fun reading() {
                if (currentCoroutineContext()[HoldsReadHold] != null) events += "radio hold entered"
            }
        }
        val queued = tracks.take(5).map { it.id }
        coroutineScope {
            val autoplay = List(2) { n ->
                launch(Dispatchers.Default) {
                    while (true) {
                        radio.songsLike(listOf(tracks[n]), AUTOPLAY_BATCH, { ids -> ids.filterTo(HashSet(), queued::contains) }, before = queued, random = Random(n))
                        yield()
                    }
                }
            }
            val a = Watching(this, rig.picks, WorkOwners.marked("live:a"), "a", MutableStateFlow(LibraryQuery(sort = QuerySort(SongSort.MostPlayed.id, true))))
            val b = Watching(this, rig.picks, WorkOwners.marked("live:b"), "b", MutableStateFlow(LibraryQuery(sort = QuerySort(SongSort.RecentlyPlayed.id, true))))
            a.after(0)
            b.after(0)
            applied.await()
            refreshedOnce.await()
            a.job.cancel()
            b.job.cancel()
            autoplay.forEach { it.cancel() }
        }
        val snapshot = events.toList()
        assertEquals("$snapshot", 2, snapshot.count { it == "write waits" })
        // After each write waits, the next to enter is the write, never a hold.
        snapshot.indices.filter { snapshot[it] == "write waits" }.forEach { at ->
            val next = snapshot.drop(at + 1).first { it.startsWith("write ") && it != "write waits" || " hold " in it }
            assertTrue("$snapshot", next.startsWith("write "))
        }
    }

    // A radio whose first two tries each met a swap takes its last try,
    // which holds the counts. Asked for while a refresh waits behind a live
    // list page's hold, that hold is turned away; the refresh enters when
    // the page's hold ends, and the radio reads after it.
    @Test
    fun aRadiosLastTryIsTurnedAwayWhileAWriteWaits() = timed(90) {
        val merge = db.mergedLibrary(60)
        repeat(10) { db.playOn("device:s$it", NOW - it * 1_000L) }
        val rig = LiveRig(db, merge, page = 5).started()
        val totals = rig.totals.totals
        val radio = LibraryRadio(db, totals, { _, _ -> emptyList() }, flowOf(PlayerPrefs()), RADIO_PAGE) { NOW }
        val seed = db.dao().tracksByIdsUnordered(listOf("device:s1")).single()
        val events = CopyOnWriteArrayList<String>()
        val pageHeld = CompletableDeferred<Unit>()
        val pageGo = CompletableDeferred<Unit>()
        val writeWaits = CompletableDeferred<Unit>()
        val radioWaits = CompletableDeferred<Unit>()
        totals.hooks = object : TotalsHooks() {
            override suspend fun writing(write: TotalsWrite) {
                events += "write $write"
            }
        }
        rig.picks.hooks = object : LiveHooks() {
            override suspend fun plays(owner: String, n: Int) {
                if (n == 2 && !pageHeld.isCompleted) {
                    events += "page holds"
                    pageHeld.complete(Unit)
                    pageGo.await()
                }
            }
        }
        var tries = 0
        var list: Watching? = null
        coroutineScope {
            radio.hooks = object : RadioHooks() {
                override suspend fun walked(offered: Int, kept: List<String>) {
                    if (++tries <= 2) {
                        db.growAndSwap(merge, 60 + tries)
                        return
                    }
                    list = Watching(this@coroutineScope, rig.picks, WorkOwners.marked("live:a"), "a", MutableStateFlow(LibraryQuery(sort = QuerySort(SongSort.MostPlayed.id, true))))
                    pageHeld.await()
                    totals.holds.writeWaits = {
                        events += "write waits"
                        writeWaits.complete(Unit)
                    }
                    totals.holds.holdWaits = {
                        events += "a hold waits"
                        radioWaits.complete(Unit)
                    }
                    db.playOn("device:s2", NOW + 1)
                    totals.refresh(listOf("device:s2"))
                    writeWaits.await()
                }

                override suspend fun reading() {
                    if (tries == 3) events += "radio reads"
                }
            }
            val run = async { radio.songsLike(listOf(seed), 10, random = Random(3)) }
            radioWaits.await()
            pageGo.complete(Unit)
            run.await()
            list!!.job.cancel()
        }
        assertEquals(3, tries)
        val order = listOf("page holds", "write waits", "a hold waits", "write Refresh", "radio reads").map { events.indexOf(it) }
        assertTrue("$events", order.all { it >= 0 } && order == order.sorted())
    }
}
