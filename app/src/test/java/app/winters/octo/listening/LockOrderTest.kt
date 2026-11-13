package app.winters.octo.listening

import androidx.paging.PagingSource
import app.winters.octo.catalog.HoldKind
import app.winters.octo.catalog.MERGE_WORK_CLEAR
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.catalog.library
import app.winters.octo.catalog.put
import app.winters.octo.catalog.serverLibraryThenPhone
import app.winters.octo.catalog.serverSong
import app.winters.octo.catalog.titleLookup
import app.winters.octo.sort.SongScope
import app.winters.octo.sort.SortOrder
import app.winters.octo.ui.paging.KeysetPagingSource
import app.winters.octo.ui.paging.Keyed
import app.winters.octo.ui.paging.LibraryReads
import app.winters.octo.ui.paging.ListLoader
import app.winters.octo.ui.paging.RowKey
import app.winters.octo.ui.paging.SongLists
import app.winters.octo.ui.paging.Survey
import app.winters.octo.data.FixRuns
import app.winters.octo.device.DEVICE
import app.winters.octo.health.FixStep
import app.winters.octo.health.findDuplicates
import app.winters.octo.livelists.LiveHooks
import app.winters.octo.livelists.LiveRig
import app.winters.octo.livelists.Watching
import app.winters.octo.livelists.liveLibrary
import app.winters.octo.discovery.ListServer
import app.winters.octo.discovery.SERVER_SOURCE
import app.winters.octo.discovery.ServerHooks
import app.winters.octo.discovery.ServerListKind
import app.winters.octo.discovery.libraryTrack
import app.winters.octo.discovery.putLibrary
import app.winters.octo.discovery.serverReads
import app.winters.octo.discovery.shownIds
import app.winters.octo.playback.AUTOPLAY_BATCH
import app.winters.octo.playback.AutoplayHooks
import app.winters.octo.playback.AutoplayPicker
import app.winters.octo.playback.RadioHooks
import app.winters.octo.playback.RadioRig
import app.winters.octo.subsonic.Song
import app.winters.octo.testing.counted
import app.winters.octo.playback.songs
import app.winters.octo.query.LibraryQuery
import app.winters.octo.query.QuerySort
import app.winters.octo.sort.SongSort
import app.winters.octo.subsonic.LibraryActionResult
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import app.winters.octo.ui.library.health.FixAllSteps
import app.winters.octo.ui.library.health.FixSet
import app.winters.octo.ui.library.health.HealthRecheck
import app.winters.octo.ui.library.health.SourceCopyHealth
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

// Nothing waits inside the merge lock, and the totals take only their own
// lock and the database's transactions, so no wait goes round in a circle:
// a totals reader's last try ends beside an apply waiting for its hold and
// a swap waiting for the lock; a rebuild stopped inside its transaction
// holds up neither a reader nor anything but the next write; every one
// finishes once let go.
@RunWith(RobolectricTestRunner::class)
class LockOrderTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    // A queue build's last attempt holds the merge lock shared for its one
    // walk, waiting on nothing inside: a tap's one-read hold enters beside
    // it and ends, and the build ends too, with every song once.
    @Test
    @Config(sdk = [35])
    fun aQueueBuildsLastAttemptLetsAReadInBeside() {
        val rig = app.winters.octo.playback.QueueRig(page = 50)
        try {
            rig.db.songs(300)
            val readBeside = AtomicBoolean(false)
            rig.builds.buildHooks = object : app.winters.octo.playback.BuildHooks() {
                var inside = false

                override suspend fun beforePage(listId: Long, page: Int) {
                    if (!inside) rig.db.workDao().raiseSwapCount()
                }

                override suspend fun inLastAttempt(listId: Long) {
                    inside = true
                    coroutineScope {
                        val read = async(Dispatchers.IO) { rig.merge.betweenRebuilds(HoldKind.Read) { readBeside.set(true) } }
                        assertTrue("a read waited for the build's walk", withTimeoutOrNull(5_000) { read.await() } != null)
                    }
                }
            }
            rig.play(app.winters.octo.playback.PlayRequest(app.winters.octo.playback.ListSpec.Songs(app.winters.octo.sort.SongScope.All, app.winters.octo.sort.SortOrder(SongSort.Title, false)), null, shownOverWindow = true))
            rig.joined()
            assertTrue(readBeside.get())
            assertEquals(300, rig.songs().toSet().size)
        } finally {
            rig.close()
        }
    }

    @Test
    fun aReadersLastTryEndsBesideAWaitingApplyAndAWaitingSwap() = timed(20) {
        val merge = db.mergedLibrary(30)
        repeat(10) { db.playOn("device:s$it", NOW - it) }
        val rig = TotalsRig(db, merge).started()
        val before = db.longOf("SELECT plays FROM play_total WHERE trackId = 'device:s1'")
        coroutineScope {
            val tries = AtomicInteger()
            val last = CompletableDeferred<Unit>()
            val letGo = CompletableDeferred<Unit>()
            val reader = async(Dispatchers.Default) {
                rig.totals.read(HoldKind.Read) {
                    // A swap during each free try sends the read on to its last.
                    if (tries.incrementAndGet() < 3) {
                        merge.rebuild()
                    } else {
                        last.complete(Unit)
                        letGo.await()
                    }
                    rig.dao.totalOf("device:s1")?.plays
                }
            }
            last.await()
            assertEquals(3, tries.get())
            // An apply waits for the reader's hold.
            val applyWaits = CompletableDeferred<Unit>()
            rig.totals.holds.writeWaits = { applyWaits.complete(Unit) }
            db.playOn("device:s1", NOW + 1)
            rig.totals.rebuild()
            applyWaits.await()
            // A swap waits for the lock the reader shares: it turns away
            // new page holds while it waits.
            val swap = launch(Dispatchers.Default) { db.growAndSwap(merge, 31) }
            while (withTimeoutOrNull(100) { merge.betweenRebuilds(HoldKind.Page) {} } != null) Unit
            letGo.complete(Unit)
            assertEquals(before.toInt(), reader.await())
            swap.join()
        }
        rig.totals.builtThrough.first { it >= merge.swapStamp.value }
        rig.built()
        assertEquals(before + 1, db.longOf("SELECT plays FROM play_total WHERE trackId = 'device:s1'"))
    }

    @Test
    fun aRebuildStoppedInItsTransactionHoldsNoReaderUp() = timed(20) {
        val merge = db.mergedLibrary(30)
        repeat(10) { db.playOn("device:s$it", NOW - it) }
        val rig = TotalsRig(db, merge).started()
        val before = db.longOf("SELECT plays FROM play_total WHERE trackId = 'device:s2'")
        val inside = Gate()
        val armed = AtomicBoolean(true)
        rig.totals.hooks = object : TotalsHooks() {
            override suspend fun inDiffWrite() {
                if (armed.get()) inside.hit()
            }
        }
        db.playOn("device:s2", NOW + 1)
        rig.totals.rebuild()
        inside.reached.await()
        coroutineScope {
            // A reader reads the totals as they stand.
            assertEquals(before.toInt(), rig.totals.read(HoldKind.Read) { rig.dao.totalOf("device:s2")?.plays })
            // A refresh only asks, and a swap waits for the transaction.
            rig.totals.refresh(listOf("device:s3"))
            val swap = launch(Dispatchers.Default) { db.growAndSwap(merge, 31) }
            armed.set(false)
            inside.open()
            swap.join()
        }
        rig.totals.builtThrough.first { it >= merge.swapStamp.value }
        rig.built()
        assertEquals(before + 1, db.longOf("SELECT plays FROM play_total WHERE trackId = 'device:s2'"))
    }

    // The library's other readers (G4). A radio forced to its last try
    // walks the library outside the read hold and the merge lock every try;
    // only its reads of the songs it kept run inside both, and a rebuild
    // asked for meanwhile waits for them and then lands.
    @Test
    fun aRadiosLastTryRunsOnlyItsReadsInsideTheHoldAndTheLock() = timed(30) {
        val merge = db.mergedLibrary(40)
        repeat(5) { db.playOn("device:s$it", NOW - it) }
        val rig = RadioRig(db, merge).started()
        val seed = db.dao().tracksByIdsUnordered(listOf("device:s1")).single()
        val walks = ArrayList<Pair<Boolean, Boolean>>()
        var read: Pair<Boolean, Boolean>? = null
        var tries = 0
        val queued = CompletableDeferred<Unit>()
        rig.radio.hooks = object : RadioHooks() {
            override suspend fun walked(offered: Int, kept: List<String>) {
                walks += merge.holdsMergeLock() to (currentCoroutineContext()[HoldsReadHold] != null)
                if (++tries <= 2) db.growAndSwap(merge, 40 + tries)
            }

            override suspend fun reading() {
                if (tries < 3) return
                read = merge.holdsMergeLock() to (currentCoroutineContext()[HoldsReadHold] != null)
                rig.totals.totals.holds.writeWaits = { queued.complete(Unit) }
                db.playOn("device:s3", NOW + 1)
                rig.totals.totals.rebuild()
                queued.await()
            }
        }
        rig.radio.songsLike(listOf(seed), 10, random = Random(5))
        assertEquals(3, tries)
        assertEquals(List(3) { false to false }, walks)
        assertEquals(true to true, read)
        rig.totals.built()
    }

    // A live list's walk holds its build lock and one page's read hold. A
    // copy of a list for a queue that waits for that lock holds no read
    // hold meanwhile, so a refresh asked for then waits only for the page's
    // hold and lands before the copy walks.
    @Test
    fun aLiveCopyWaitingForTheBuildLockHoldsNoCounts() = timed(60) {
        val tracks = db.liveLibrary(60)
        val rig = LiveRig(db, page = 5).started()
        val totals = rig.totals.totals
        val events = CopyOnWriteArrayList<String>()
        val pageHeld = CompletableDeferred<Unit>()
        val pageGo = CompletableDeferred<Unit>()
        val writeWaits = CompletableDeferred<Unit>()
        val copyWaits = CompletableDeferred<Unit>()
        val byPlays = LibraryQuery(sort = QuerySort(SongSort.MostPlayed.id, true))
        rig.picks.hooks = object : LiveHooks() {
            override suspend fun opened(owner: String) {
                events += "$owner walks"
            }

            override suspend fun copying(into: String) {
                copyWaits.complete(Unit)
            }

            override suspend fun plays(owner: String, n: Int) {
                if (owner == WorkOwners.marked("live:a") && n == 2 && !pageHeld.isCompleted) {
                    pageHeld.complete(Unit)
                    pageGo.await()
                }
            }
        }
        totals.hooks = object : TotalsHooks() {
            override suspend fun writing(write: TotalsWrite) {
                events += "write $write"
            }
        }
        coroutineScope {
            val list = Watching(this, rig.picks, WorkOwners.marked("live:a"), "a", MutableStateFlow(byPlays))
            pageHeld.await()
            val copy = async { rig.picks.copy(null, byPlays, WorkOwners.marked("queue:1")) }
            copyWaits.await()
            totals.holds.writeWaits = { writeWaits.complete(Unit) }
            db.playOn(tracks[2].id, NOW + 1)
            totals.refresh(listOf(tracks[2].id))
            writeWaits.await()
            pageGo.complete(Unit)
            assertEquals(rig.today(byPlays).size, copy.await().count)
            list.job.cancel()
        }
        val order = listOf("${WorkOwners.marked("live:a")} walks", "write Refresh", "${WorkOwners.marked("queue:1")} walks").map { events.indexOf(it) }
        assertTrue("$events", order.all { it >= 0 } && order == order.sorted())
    }

    // Fix all checks each page of sets again inside a Page hold of the
    // merge lock, just before that page's steps, and sends the steps after,
    // outside the lock.
    @Test
    fun fixAllSendsItsStepsOutsideTheLock() = timed(60) {
        val sql = db.openHelper.writableDatabase
        repeat(6) { i ->
            for ((n, mime) in listOf("audio/flac", "audio/mpeg").withIndex()) {
                val id = "s$i-$n"
                sql.execSQL(
                    "INSERT INTO track (id, sourceId, nativeId, title, searchKey, sortKey, artist, artistId, album, albumId, durationMs, addedAt, relinkKey, genre, " +
                        "lookupKey, looseKey) VALUES (?, 'device', ?, ?, ?, ?, 'Artist', 'ar', 'Album', 'al', ?, 0, '', '', ?, ?)",
                    arrayOf<Any>(
                        id, id, "Song $i", "song $i", "song $i", 200_000L + i * 10_000 + n * 500,
                        titleLookup("Song $i", "Artist").lookupKey, titleLookup("Song $i", "Artist").looseKey,
                    ),
                )
                sql.execSQL(
                    "INSERT INTO source_track (id, sourceId, nativeId, title, searchKey, sortKey, artist, artistId, album, albumId, durationMs, addedAt, albumOrder, " +
                        "relinkKey, genre, mimeType, mergedId, seq) VALUES (?, 'device', ?, ?, ?, ?, 'Artist', 'ar', 'Album', 'al', ?, 0, 0, '', '', ?, ?, ?)",
                    arrayOf<Any>("c$id", "c$id", "Song $i", "song $i", "song $i", 200_000L + i * 10_000 + n * 500, mime, id, i * 2L + n),
                )
            }
        }
        val merge = db.testMerge(null)
        val groups = findDuplicates(db.sourceDao().tracks(DEVICE).sortedBy { it.seq }, SourceCopyHealth)
        assertEquals(6, groups.size)
        val sets = groups.map { FixSet(group = it, steps = listOf(FixStep.Remove(it.copies[1].nativeId, it.copies[1].title, copy = true))) }
        val recheck = HealthRecheck(db, merge)
        val events = ArrayList<String>()
        val source = FixAllSteps(sets, { page -> events += "check ${page.size}"; recheck.samePage(page) }, page = 2)
        FixRuns(db, WorkOwners(), 2).run(source, send = { step ->
            events += if (merge.holdsMergeLock()) "send inside" else "send"
            LibraryActionResult(step.id, step.action, "applied")
        })
        assertEquals(List(3) { listOf("check 2", "send", "send") }.flatten(), events)
    }

    // A list by plays whose free tries a swap crosses reads its third try
    // inside the merge lock, and there only its page reads: its survey, its
    // totals wait and every lookup run before the lock (a probe on the list
    // notes what runs while the lock is held). A swap that comes meanwhile
    // waits behind the lock, and both end.
    @Test
    fun aListsThirdTryHoldsTheLockForItsPageReadsAlone() = timed(30) {
        val merge = db.mergedLibrary(30)
        repeat(10) { db.playOn("device:s$it", NOW - it) }
        val rig = TotalsRig(db, merge).started()
        val surveys = AtomicInteger()
        val inLock = CopyOnWriteArrayList<String>()
        val waiting = CompletableDeferred<kotlinx.coroutines.Deferred<Unit>>()
        val inner = SongLists(db.listsDao()).loader(SongScope.All, SortOrder(SongSort.MostPlayed, true))
        val probe = object : ListLoader<TrackEntity> by inner {
            private suspend fun note(what: String) {
                if (merge.holdsMergeLock()) inLock += what
            }

            override suspend fun survey(anchor: RowKey?): Survey {
                note("survey")
                val survey = inner.survey(anchor)
                // A swap crosses each free try.
                if (surveys.incrementAndGet() <= 2) db.growAndSwap(merge, 30 + surveys.get())
                return survey
            }

            override suspend fun after(key: RowKey?, limit: Int): List<Keyed<TrackEntity>> {
                note("after")
                if (merge.holdsMergeLock() && !waiting.isCompleted) {
                    waiting.complete(kotlinx.coroutines.CoroutineScope(Dispatchers.Default).async { db.growAndSwap(merge, 40) })
                }
                return inner.after(key, limit)
            }

            override suspend fun before(key: RowKey, limit: Int): List<Keyed<TrackEntity>> = inner.before(key, limit).also { note("before") }

            override suspend fun count(): Int = inner.count().also { note("count") }

            override suspend fun keyAt(position: Int): RowKey? = inner.keyAt(position).also { note("keyAt") }

            override suspend fun rekey(key: RowKey): RowKey? = inner.rekey(key).also { note("rekey") }
        }
        val source = KeysetPagingSource(probe, 1, LibraryReads(merge, rig.totals), publish = {}, hint = { null }, reanchor = {})
        val page = source.load(PagingSource.LoadParams.Refresh(null, 300, false)) as PagingSource.LoadResult.Page
        assertEquals("three tries", 3, surveys.get())
        assertEquals("only the page reads ran inside the lock", listOf("after"), inLock.distinct())
        assertEquals(32, page.data.size)
        withTimeoutOrNull(10_000) { waiting.await().await() } ?: throw AssertionError("the swap behind the lock never ran")
        assertEquals(40, db.longOf("SELECT COUNT(*) FROM track").toInt())
    }

    // Autoplay's own picks forced to their last try run only their reads by
    // id under the merge lock: from the moment the try holds it, every
    // statement asks for at most its picks, by id; the walks of the artist's
    // albums all ran before, outside it. Guard: the base read every album
    // whole and took no lock.
    @Test
    fun autoplaysLastTryRunsOnlyItsReadsByIdUnderTheLock() = timed(60) {
        val merge = db.mergedLibrary(80)
        // The play counts are never rebuilt here, and each swap's clears end
        // before the next try, so nothing else reads.
        val rig = RadioRig(db, merge)
        val picker = AutoplayPicker(rig.radio, db, merge, rig.totals.history, app.winters.octo.playback.QueueStore(db))
        val seed = db.dao().tracksByIdsUnordered(listOf(db.query("SELECT id FROM track ORDER BY id LIMIT 1", null).use { it.moveToFirst(); it.getString(0) })).single()
        var tries = 0
        var walkedInLock = false
        picker.hooks = object : AutoplayHooks() {
            override suspend fun walked(artist: List<String>, genre: List<String>) {
                walkedInLock = walkedInLock || merge.holdsMergeLock()
                if (++tries <= 2) {
                    db.growAndSwap(merge, 80 + tries)
                    // The swap's work rows and stage go in the background,
                    // outside the merge lock.
                    merge.awaitClear(MERGE_WORK_CLEAR)
                    merge.awaitStageClear(DEVICE)
                }
            }

            override suspend fun reading() {
                if (!merge.holdsMergeLock()) return
                db.counted.reset()
                db.counted.recording = true
            }
        }
        val picks = try {
            picker.fallbacks(seed) { emptySet() }
        } finally {
            db.counted.recording = false
        }
        val underLock = db.counted.own
        assertEquals(3, tries)
        assertTrue("a walk ran under the lock", !walkedInLock)
        assertTrue("the last try read nothing", underLock.isNotEmpty())
        assertTrue("$underLock", underLock.all { " IN (" in it.sql && it.rowsRead <= AUTOPLAY_BATCH })
        assertEquals(AUTOPLAY_BATCH, picks.size)
    }

    // A download page takes its Read hold before the downloads lock, as the
    // rules walk's pages do. The park: the page holds the lock; the walk's
    // opening holds a Page hold and waits for the lock; a save's group asks
    // for its turn and waits for the walk. Had the page taken the lock
    // first and its hold after, its hold would queue behind the group,
    // which waits for the walk, which waits for the lock: none would end.
    @Test
    fun aDownloadPageTakesItsHoldBeforeTheLock() = timed(60) {
        val source = app.winters.octo.offline.IN_USE
        val tracks = (0 until 20).map { app.winters.octo.offline.librarySong("t$it") }
        db.dao().insertTracks(tracks)
        db.sourceDao().putTracks(tracks.map { app.winters.octo.offline.serverCopy(it.id, "n${it.id}") })
        val waits = CompletableDeferred<Unit>()
        val merge = db.testMerge(source, object : app.winters.octo.catalog.MergeHooks() {
            override fun groupWaits() {
                waits.complete(Unit)
            }
        })
        val lock = WatchedLock()
        val sets = app.winters.octo.catalog.PlaylistSets(db)
        val rules = app.winters.octo.offline.OfflineRules(db, merge, lock, app.winters.octo.offline.DownloadBook(db.downloadDao()), WorkOwners(), { source }, sets = sets)
        val locked = CompletableDeferred<Unit>()
        val go = CompletableDeferred<Unit>()
        rules.hooks = object : app.winters.octo.offline.RulesHooks() {
            override suspend fun downloadLocked() {
                if (locked.complete(Unit)) go.await()
            }
        }
        coroutineScope {
            val download = async(Dispatchers.Default) { rules.download(listOf("t1"), refused = null) }
            locked.await()
            val walk = async(Dispatchers.Default) { rules.walk(keepLiked = true, playlists = emptySet(), refused = null) }
            // The walk's opening holds its Page hold and waits for the lock.
            withTimeoutOrNull(10_000) { while (lock.asked.get() < 2) kotlinx.coroutines.delay(5) } ?: throw AssertionError("the walk never asked for the lock")
            db.put(library(DEVICE, 5))
            val save = async(Dispatchers.Default) { merge.rebuild() }
            withTimeoutOrNull(10_000) { waits.await() } ?: throw AssertionError("the save's group never waited for the walk")
            go.complete(Unit)
            val ended = withTimeoutOrNull(20_000) {
                download.await()
                walk.await()
                save.await()
            }
            assertTrue("the download page, the walk and the save hung on each other", ended != null)
        }
        assertEquals(1L, db.longOf("SELECT COUNT(*) FROM download"))
    }

    // A playlist add takes a Read hold a page inside the playlist's set
    // mutex, which it holds across its pages, and no holder of the merge
    // lock waits for a set mutex: a pin let go inside a hold leaves its old
    // set's deletion, a set writer, to PlaylistSets' own scope. The park:
    // the add holds the mutex and a page's hold, stopped at its id read; a
    // walk inside a Page hold lets go of its pin of the playlist's old set,
    // whose deletion waits for the mutex; a save's group asks for its turn
    // and waits for the add's hold, so the add's next page queues behind
    // the group. Had the pin's deletion waited inside the hold, the group
    // would wait for it, it for the add's mutex, and the add for the group:
    // none would end. All end, the old set goes, and the playlist holds
    // every song the add brought, on the songs they are now.
    @Test
    fun aPlaylistAddsPageHoldInsideItsSetMutexEndsBesideAGroup() = timed(120) {
        app.winters.octo.catalog.IdsRig(app.winters.octo.catalog.SAVE_GROUP_FLOOR).use { rig ->
            val phone = rig.serverLibraryThenPhone(40)
            val songs = (0 until 6).map(rig::serverSong)
            rig.db.userDao().insertPlaylist(app.winters.octo.catalog.PlaylistEntity("p", "P", 1, 1))
            rig.edits.add("p", { emit -> rig.playlistSongs(500).pages(songs.take(2), emit) }, distinct = false, newOnly = false, now = 1)
            // A walk pins the set shown now, which a respacing then leaves.
            val walker = WorkOwners().walk()
            val old = rig.sets.pin(walker, "p")!!.itemSet
            rig.edits.respace("p")
            assertTrue("the respacing shows a new set", rig.db.longOf("SELECT itemSet FROM playlist WHERE id = 'p'") != old)
            val reads = app.winters.octo.catalog.ParkedReads(times = 1, rig.merge)
            rig.parkAt(reads)
            val waited = CompletableDeferred<Unit>()
            rig.hooks.onWait = { waited.complete(Unit) }
            coroutineScope {
                val add = async(Dispatchers.Default) {
                    rig.edits.add("p", { emit -> rig.playlistSongs(2).pages(songs.drop(2), emit) }, distinct = false, newOnly = false, now = 2)
                }
                withTimeoutOrNull(20_000) { reads.allParked() } ?: throw AssertionError("the add never read its ids")
                val letGo = async(Dispatchers.Default) { rig.merge.betweenRebuilds(HoldKind.Page) { rig.sets.releasePin(walker, "p") } }
                withTimeoutOrNull(5_000) { letGo.await() }
                rig.db.put(phone)
                val save = async(Dispatchers.Default) { rig.merge.rebuild() }
                withTimeoutOrNull(20_000) { waited.await() } ?: throw AssertionError("the save's group never waited for the add's hold")
                reads.go.complete(Unit)
                val ended = withTimeoutOrNull(30_000) {
                    add.await()
                    letGo.await()
                    save.await()
                    rig.sets.idle()
                }
                assertTrue("the add, the pin let go and the save hung on each other", ended != null)
            }
            assertTrue("the add read no id", reads.reads.isNotEmpty())
            assertTrue("the save took no group", (rig.hooks.saves.lastOrNull() ?: 0) >= 1)
            assertEquals("the old set's rows", 0L, rig.db.longOf("SELECT COUNT(*) FROM playlist_item WHERE playlistId = 'p' AND itemSet = ?", old))
            val shown = rig.db.query("SELECT i.trackId FROM playlist_item i JOIN playlist p ON p.id = i.playlistId AND p.itemSet = i.itemSet WHERE i.playlistId = 'p' ORDER BY i.ord", null)
                .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
            assertEquals("the playlist's songs", songs.map { "$DEVICE:${it.substringAfterLast(':')}" }, shown)
        }
    }

    // No holder of a merge hold waits for the listening sync's own locks: a
    // like's copies are read in one short hold that ends before the like
    // waits for the stars lock, which the steps after a sync hold across
    // their chunks. So a save runs to its end while a sync's chunk walk is
    // stopped holding that lock and a like waits for it.
    @Test
    fun noHolderOfAMergeHoldWaitsForTheSyncsLocks() = timed(120) {
        app.winters.octo.catalog.IdsRig(app.winters.octo.catalog.SAVE_GROUP_FLOOR).use { rig ->
            rig.serverLibraryThenPhone(AFTER_SYNC_CHUNK + 100)
            val phone = library(DEVICE, 10)
            rig.server.starred = starredAnswer()
            val chunk = CompletableDeferred<Unit>()
            val chunkGo = CompletableDeferred<Unit>()
            rig.listening.afterChunk = {
                if (chunk.complete(Unit)) chunkGo.await()
            }
            coroutineScope {
                val sync = async(Dispatchers.Default) { rig.listening.afterSync() }
                chunk.await()
                // The like reads its song's copies and waits for the stars lock.
                rig.listening.likeChanged(rig.serverSong(1), liked = true)
                rig.db.put(phone)
                val saved = withTimeoutOrNull(60_000) { rig.merge.rebuild() }
                assertTrue("the save waited for a hold whose holder waits for the stars lock", saved != null)
                assertTrue("the sync's chunk walk went on", !sync.isCompleted)
                chunkGo.complete(Unit)
                sync.await()
            }
            withTimeoutOrNull(10_000) { while (rig.server.calls("star").none { "s1" in it.split(' ') }) kotlinx.coroutines.delay(5) }
                ?: throw AssertionError("the like was never sent")
        }
    }

    // The downloads lock, noting each time it is asked for.
    private class WatchedLock(private val inner: kotlinx.coroutines.sync.Mutex = kotlinx.coroutines.sync.Mutex()) : kotlinx.coroutines.sync.Mutex by inner {
        val asked = AtomicInteger()

        override suspend fun lock(owner: Any?) {
            asked.incrementAndGet()
            inner.lock(owner)
        }
    }

    // An Online list's read and its resolution never take the merge lock:
    // made while a save's group waits behind another's hold (so any hold
    // that waits behind a group would wait for that hold), it fills,
    // resolves and completes, and at no page of it is the lock held. Guard:
    // server lists are read outside every lock.
    @Test
    fun anOnlineReadAndItsResolutionNeverTakeTheMergeLock() = timed(60) {
        val songs = (0 until 120).associate { "srv$it" to Song("srv$it", title = "Title $it", artist = "Artist") }
        val server = ListServer(songs)
        try {
            db.putLibrary((0 until 120).map { libraryTrack("t$it", "Title $it", "Artist", 0) }) { t -> t.id.removePrefix("t").toInt().takeIf { it % 2 == 0 }?.let { "srv$it" } }
            server.lists["L"] = (0 until 120).map { "srv$it" }
            val parked = object : app.winters.octo.catalog.MergeHooks() {
                val waits = CompletableDeferred<Unit>()

                override fun groupWaits() {
                    waits.complete(Unit)
                }
            }
            val merge = db.testMerge(SERVER_SOURCE, parked)
            val reads = db.serverReads(server, page = 20, merge = merge)
            var held = false
            reads.hooks = object : ServerHooks() {
                override suspend fun filled(owner: String, pages: Int) {
                    held = held || merge.holdsMergeLock()
                }

                override suspend fun resolved(owner: String, pages: Int) {
                    held = held || merge.holdsMergeLock()
                }
            }
            coroutineScope {
                val walkHeld = CompletableDeferred<Unit>()
                val walk = launch(Dispatchers.Default) { merge.betweenRebuilds(HoldKind.Walk) { walkHeld.complete(Unit); kotlinx.coroutines.awaitCancellation() } }
                walkHeld.await()
                db.put(library(DEVICE, 5))
                val swap = launch(Dispatchers.Default) { merge.rebuild() }
                parked.waits.await()
                val head = withTimeoutOrNull(20_000) { reads.read(ServerListKind.List, "L", WorkOwners.marked("vm:9")) }
                assertTrue("the read waited for the lock", head?.complete == true)
                assertTrue("the lock was held at a page of the read", !held)
                assertEquals(120, reads.shownIds(WorkOwners.marked("vm:9")).size)
                assertTrue(swap.isActive)
                walk.cancel()
                swap.join()
            }
        } finally {
            server.close()
        }
    }
}
