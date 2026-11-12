package app.winters.octo.playback

import androidx.media3.common.MediaItem
import app.winters.octo.catalog.HoldKind
import app.winters.octo.catalog.InStableRead
import app.winters.octo.catalog.MergeHooks
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.library
import app.winters.octo.catalog.put
import app.winters.octo.listening.HoldsReadHold
import app.winters.octo.listening.TotalsRig
import app.winters.octo.ui.paging.Keyed
import app.winters.octo.ui.paging.ListLoader
import app.winters.octo.ui.paging.PLAY_TOTAL
import app.winters.octo.ui.paging.RowKey
import app.winters.octo.ui.paging.StaticLoader
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import app.winters.octo.device.DEVICE
import app.winters.octo.health.AlbumPart
import app.winters.octo.health.BestReason
import app.winters.octo.health.DuplicateBasis
import app.winters.octo.health.DuplicateGroup
import app.winters.octo.health.SplitAlbum
import app.winters.octo.ui.library.health.FixSet
import app.winters.octo.ui.library.health.HealthRecheck
import app.winters.octo.ui.library.health.Recheck
import app.winters.octo.listening.growAndSwap
import app.winters.octo.listening.mergedLibrary
import app.winters.octo.listening.timed
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random

// A radio whose first two tries each met a swap takes its last try inside
// the merge lock as a Read: it enters beside a queue build's whole walk (a
// Walk hold) while a save's group waits behind that walk, and finishes there
// with the walk still held and the group still waiting. A Page hold asked
// for meanwhile enters at once too, beside the walk, before the group runs:
// short holds enter while a group waits for a long one. Guard: the base's
// radio read the whole library and never took the lock.
@RunWith(RobolectricTestRunner::class)
class RadioThirdAttemptBesideBuildTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    private class Parked : MergeHooks() {
        @Volatile var waits = CompletableDeferred<Unit>()

        override fun groupWaits() {
            waits.complete(Unit)
        }
    }

    @Test
    fun theLastTryEntersBesideAWalkAndAParkedSwap() = timed(90) {
        coroutineScope {
            val parked = Parked()
            val merge = db.mergedLibrary(60, parked)
            val rig = RadioRig(db, merge).started()
            val seed = db.dao().tracksByIdsUnordered(listOf(db.query("SELECT id FROM track LIMIT 1", null).use { it.moveToFirst(); it.getString(0) })).single()
            val walkHeld = CompletableDeferred<Unit>()
            val leaveWalk = CompletableDeferred<Unit>()
            var walk: Job? = null
            var swap: Deferred<Unit>? = null
            var tries = 0
            var readBeside = false
            rig.radio.hooks = object : RadioHooks() {
                override suspend fun reading() {
                    if (tries == 3) readBeside = merge.holdsMergeLock() && walk!!.isActive && swap?.isCompleted == false
                }

                override suspend fun walked(offered: Int, kept: List<String>) {
                    tries++
                    if (tries <= 2) {
                        db.growAndSwap(merge, 60 + tries)
                        return
                    }
                    walk = launch { merge.betweenRebuilds(HoldKind.Walk) { walkHeld.complete(Unit); leaveWalk.await() } }
                    walkHeld.await()
                    parked.waits = CompletableDeferred()
                    db.put(library(DEVICE, 70))
                    swap = async { merge.rebuild(); Unit }
                    parked.waits.await()
                }
            }
            rig.radio.songsLike(listOf(seed), 10, random = Random(2))
            assertEquals(3, tries)
            assertTrue("the last try did not read beside the walk and the waiting swap", readBeside)
            assertTrue("the walk let go before the radio ended", walk!!.isActive)
            assertFalse("the swap ran before the radio ended", swap!!.isCompleted)
            val stamp = merge.swapStamp.value
            val page = async { merge.betweenRebuilds(HoldKind.Page) { merge.swapStamp.value } }
            assertEquals("the Page hold entered beside the walk, before the group", stamp, page.await())
            assertFalse("the group ran beside the walk", swap!!.isCompleted)
            leaveWalk.complete(Unit)
            swap!!.await()
            assertTrue(merge.swapStamp.value > stamp)
        }
    }

    // Autoplay's own picks whose first two tries each met a swap take their
    // last inside the merge lock as a Read: beside a queue build's whole walk
    // with another swap waiting behind it, the last try only reads its picks
    // again by id; the walks of the artist's albums ran before it, outside
    // the lock. Guard: the base read every album and the whole genre and took
    // no lock.
    @Test
    fun autoplaysLastTryEntersBesideAWalkAndReadsOnlyItsPicks() = timed(90) {
        coroutineScope {
            val parked = Parked()
            val merge = db.mergedLibrary(60, parked)
            val rig = RadioRig(db, merge).started()
            val picker = AutoplayPicker(rig.radio, db, merge, rig.totals.history, QueueStore(db)).also { it.random = Random(4) }
            val seed = db.dao().tracksByIdsUnordered(listOf(db.query("SELECT id FROM track LIMIT 1", null).use { it.moveToFirst(); it.getString(0) })).single()
            var walk: Job? = null
            var swap: Deferred<Unit>? = null
            var tries = 0
            var walkedInLock = false
            var readBeside = false
            picker.hooks = object : AutoplayHooks() {
                override suspend fun walked(artist: List<String>, genre: List<String>) {
                    tries++
                    walkedInLock = walkedInLock || merge.holdsMergeLock()
                    if (tries <= 2) {
                        db.growAndSwap(merge, 60 + tries)
                        return
                    }
                    val walkHeld = CompletableDeferred<Unit>()
                    walk = launch { merge.betweenRebuilds(HoldKind.Walk) { walkHeld.complete(Unit); awaitCancellation() } }
                    walkHeld.await()
                    parked.waits = CompletableDeferred()
                    db.put(library(DEVICE, 70))
                    swap = async { merge.rebuild(); Unit }
                    parked.waits.await()
                }

                override suspend fun reading() {
                    if (tries == 3) readBeside = merge.holdsMergeLock() && walk!!.isActive && swap?.isCompleted == false
                }
            }
            val picks = picker.fallbacks(seed) { emptySet() }
            assertEquals(3, tries)
            assertFalse("a walk ran under the merge lock", walkedInLock)
            assertTrue("the last try did not read beside the walk and the waiting swap", readBeside)
            assertEquals(AUTOPLAY_BATCH, picks.size)
            assertFalse("the swap ran before Autoplay ended", swap!!.isCompleted)
            walk!!.cancel()
            swap!!.await()
        }
    }

    // A car's answer for a list in a listening order, the same: its last
    // try holds the totals still and enters the merge lock as a Read beside
    // a build's walk while a swap waits, and reads there only the rows it
    // sends; the pass over the list (whether it fits) ran before, outside
    // the lock and the hold.
    @Test
    fun aCarListeningAnswersLastTryEntersBesideAWalkAndRunsNoPassThere() = timed(90) {
        coroutineScope {
            val parked = Parked()
            val merge = db.mergedLibrary(60, parked)
            val totals = TotalsRig(db, merge).started()
            val songs = db.dao().tracksByIdsUnordered(db.query("SELECT id FROM track ORDER BY id LIMIT 20", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } })
            val base = StaticLoader(songs)
            var walk: Job? = null
            var swap: Deferred<Unit>? = null
            val reads = ArrayList<Triple<Boolean, Boolean, Boolean>>()
            var blocks = 0
            var readBeside = false
            val loader = object : ListLoader<TrackEntity> by base {
                override val tables = arrayOf("track", PLAY_TOTAL)

                override suspend fun after(key: RowKey?, limit: Int): List<Keyed<TrackEntity>> {
                    val inBlock = currentCoroutineContext()[InStableRead] != null || merge.holdsMergeLock()
                    val held = currentCoroutineContext()[HoldsReadHold] != null
                    reads += Triple(inBlock, merge.holdsMergeLock(), held)
                    if (inBlock && ++blocks <= 2) db.growAndSwap(merge, 60 + blocks)
                    if (!inBlock && blocks == 2 && walk == null) {
                        val walkHeld = CompletableDeferred<Unit>()
                        walk = launch { merge.betweenRebuilds(HoldKind.Walk) { walkHeld.complete(Unit); awaitCancellation() } }
                        walkHeld.await()
                        parked.waits = CompletableDeferred()
                        db.put(library(DEVICE, 70))
                        swap = async { merge.rebuild(); Unit }
                        parked.waits.await()
                    }
                    if (inBlock && blocks == 3) readBeside = held && merge.holdsMergeLock() && walk!!.isActive && swap?.isCompleted == false
                    return base.after(key, limit)
                }
            }
            val node = CarNode.Liked
            val listing = CarPagedListing(loader) { page -> page.map { CarRow(MediaItem.Builder().setMediaId(carId(CarNode.Song(it.id, node))).build(), null) } }
            val rows = CarSplitRows({ h, _, _, _ -> MediaItem.Builder().setMediaId(carId(h)).build() }, { p, _, _, _, _ -> MediaItem.Builder().setMediaId(carId(p)).build() })
            val answer = CarReads(merge, totals.totals).answer(node, node, listing, rows)
            assertEquals(20, answer.size)
            assertEquals(3, blocks)
            assertTrue("the last try did not read beside the walk and the waiting swap", readBeside)
            assertTrue("a pass ran under the lock or the hold: $reads", reads.filter { !it.first }.none { it.second || it.third })
            assertFalse("the swap ran before the answer ended", swap!!.isCompleted)
            walk!!.cancel()
            swap!!.await()
        }
    }

    // The other reads of a tap that exist now: a Fix's or a Join's check of
    // its set again is a Read, so it enters beside a queue build's whole
    // walk while a group waits behind it, and ends there. Fix all's check of
    // a page of sets is a Page, a short hold too, so it enters beside the
    // walk as well and reads before the group.
    @Test
    fun aFixsCheckEntersBesideAWalkAndFixAllWaitsForTheSwap() = timed(90) {
        coroutineScope {
            val parked = Parked()
            val merge = db.mergedLibrary(40, parked)
            val copies = db.sourceDao().tracks(DEVICE).sortedBy { it.seq }.take(2)
            val group = DuplicateGroup(copies, DuplicateBasis.TitleAndLength, BestReason.None)
            val songs = db.dao().tracksByIdsUnordered(copies.map { it.mergedId })
            val album = SplitAlbum(songs[0].album, "", listOf(AlbumPart(songs[0].albumId, listOf(songs[0])), AlbumPart(songs[1].albumId, listOf(songs[1]))), emptyList())
            val recheck = HealthRecheck(db, merge)
            val walkHeld = CompletableDeferred<Unit>()
            val leaveWalk = CompletableDeferred<Unit>()
            val walk = launch { merge.betweenRebuilds(HoldKind.Walk) { walkHeld.complete(Unit); leaveWalk.await() } }
            walkHeld.await()
            parked.waits = CompletableDeferred()
            db.put(library(DEVICE, 41))
            val swap = async { merge.rebuild(); Unit }
            parked.waits.await()
            // Beside the walk and the waiting swap.
            assertTrue(recheck.duplicates(group) is Recheck.Changed)
            assertTrue(recheck.split(album) is Recheck.Changed)
            assertTrue("the walk let go", walk.isActive)
            assertFalse("the swap ran", swap.isCompleted)
            val stamp = merge.swapStamp.value
            val page = async { recheck.samePage(listOf(FixSet(group = group, steps = emptyList()))).also { assertEquals(stamp, merge.swapStamp.value) } }
            assertEquals(listOf(false), page.await())
            assertFalse("the group ran beside the walk", swap.isCompleted)
            leaveWalk.complete(Unit)
            swap.await()
        }
    }
}
