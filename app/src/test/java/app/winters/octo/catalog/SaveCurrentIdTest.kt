package app.winters.octo.catalog

import app.winters.octo.favourites.PinKey
import app.winters.octo.listening.FavouriteKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

// Every user writer that takes a library id writes the id it names now,
// while a save is part way: parked between its id read and its write, it
// holds the save's next group out until it has written, and the write and
// every follow-on (the server's calls, the play's count) name the id it
// read. A writer of many pages takes one hold a page, so a group lands
// between its pages and moves a row it wrote on an id renamed since. An
// unlike during a run also takes the like still on the old id. Every store
// method that takes a library id is listed here with how it resolves it.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SaveCurrentIdTest {
    private fun CatalogDatabase.strings(sql: String, vararg args: Any?): List<String> =
        query(sql, args).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    // Waits for work the stores run on their own scopes, under the test's
    // guard.
    private suspend fun until(what: String, check: suspend () -> Boolean) {
        try {
            withTimeout(20_000) { while (!check()) delay(5) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("never: $what")
        }
    }

    // The renames a park can work with, each old id to new: published
    // songs whose like is still on the old id, published songs never
    // liked, published albums whose favorite is still on the old id, ones
    // neither a favorite nor pinned, ones whose pin is still on the old id,
    // and the artist while its favorite is still on the old id.
    private class Park(
        val turn: Long,
        val likedSongs: List<Pair<String, String>>,
        val freeSongs: List<Pair<String, String>>,
        val likedAlbums: List<Pair<String, String>>,
        val freeAlbums: List<Pair<String, String>>,
        val pinnedAlbums: List<Pair<String, String>>,
        val artist: Pair<String, String>?,
    ) {
        val ready get() = likedSongs.size >= 3 && freeSongs.size >= 4 && likedAlbums.isNotEmpty() && freeAlbums.size >= 2 && pinnedAlbums.size >= 2 && artist != null
    }

    private fun IdsRig.pairs(sql: String): List<Pair<String, String>> =
        db.query(sql, null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) } }

    private fun IdsRig.park(turn: Long): Park = Park(
        turn,
        pairs("SELECT r.`old`, r.`new` FROM save_rename r JOIN liked_track l ON l.trackId = r.`old` WHERE r.kind = 'track' ORDER BY r.`old`"),
        pairs("SELECT r.`old`, r.`new` FROM save_rename r WHERE r.kind = 'track' AND NOT EXISTS (SELECT 1 FROM liked_track l WHERE l.trackId IN (r.`old`, r.`new`)) ORDER BY r.`old`"),
        pairs("SELECT r.`old`, r.`new` FROM save_rename r JOIN liked_album l ON l.albumId = r.`old` WHERE r.kind = 'album' ORDER BY r.`old`"),
        pairs(
            "SELECT r.`old`, r.`new` FROM save_rename r WHERE r.kind = 'album' AND EXISTS (SELECT 1 FROM album a WHERE a.id = r.`new`) " +
                "AND NOT EXISTS (SELECT 1 FROM liked_album l WHERE l.albumId IN (r.`old`, r.`new`)) " +
                "AND NOT EXISTS (SELECT 1 FROM pinned_item p WHERE p.itemId IN (r.`old`, r.`new`)) ORDER BY r.`old`",
        ),
        pairs("SELECT r.`old`, r.`new` FROM save_rename r JOIN pinned_item p ON p.kind = 'album' AND p.itemId = r.`old` WHERE r.kind = 'album' ORDER BY r.`old`"),
        pairs("SELECT r.`old`, r.`new` FROM save_rename r JOIN liked_artist l ON l.artistId = r.`old` WHERE r.kind = 'artist'").firstOrNull(),
    )

    // Whether the server was asked `endpoint` for the copy `native`.
    private fun IdsRig.called(endpoint: String, native: String, extra: String? = null) =
        server.calls(endpoint).any { call -> call.split(' ').let { native in it && (extra == null || extra in it) } }

    // The user's rows on the server's ids before the phone's copies join:
    // every even song liked, the first albums favorites and pinned, the
    // artist a favorite.
    private suspend fun IdsRig.userState(songs: Int) {
        val keys = db.query("SELECT id, relinkKey FROM track", null).use { c -> buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) } }
        db.userDao().likeAll((0 until songs step 2).map { LikedTrackEntity(serverSong(it), keys.getValue(serverSong(it)), it.toLong()) })
        val albums = db.strings("SELECT id FROM album ORDER BY id")
        val albumKeys = db.query("SELECT id, searchKey FROM album", null).use { c -> buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) } }
        db.favouritesDao().likeAlbums(albums.take(2).map { LikedAlbumEntity(it, albumKeys.getValue(it), 1) })
        db.favouritesDao().savePins(albums.drop(3).take(3).mapIndexed { i, a -> PinnedItemEntity("album", a, albumKeys.getValue(a), i) })
        val artist = db.query("SELECT id, searchKey FROM artist", null).use { c -> c.moveToFirst(); c.getString(0) to c.getString(1) }
        db.favouritesDao().likeArtists(listOf(LikedArtistEntity(artist.first, artist.second, 1)))
    }

    private fun songsFor(bound: Long) = if (bound == SAVE_GROUP_FLOOR) 80 else 1_200

    @Test
    fun eachWriterHoldsTheNextGroupOutAndWritesTheIdItRead() = guarded(240) {
        for (bound in listOf(SAVE_GROUP_FLOOR, SAVE_GROUP_ENTRIES)) {
            IdsRig(bound).use { rig ->
                val songs = songsFor(bound)
                val phone = rig.serverLibraryThenPhone(songs)
                rig.userState(songs)
                rig.db.put(phone)
                val writers = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val reads = ParkedReads(times = 16, rig.merge)
                rig.parkAt(reads)
                var park: Park? = null
                var waitedAt = -1L
                var playAtNextGroup = -1L
                val removed = CopyOnWriteArrayList<LikedTrackEntity>()
                rig.hooks.inside = { turn ->
                    val at = park
                    if (at != null && turn == at.turn + 1 && playAtNextGroup < 0) {
                        playAtNextGroup = rig.db.longOf("SELECT COUNT(*) FROM play_event WHERE trackId = ?", at.freeSongs[2].second)
                    }
                }
                rig.hooks.between = between@{ turn ->
                    if (park != null) return@between
                    val at = rig.park(turn)
                    if (!at.ready) return@between
                    park = at
                    val (l1, l2, l3) = at.likedSongs
                    val (f1, f2, f3, f4) = at.freeSongs
                    // Each writer goes with the old id; every one parks at its
                    // first id read, inside its hold (the play's own sync
                    // job too).
                    writers.launch { rig.likes.toggle(l1.first) }
                    writers.launch { rig.likes.toggle(f1.first) }
                    writers.launch { rig.likes.unlike(l2.first) { removed += it } }
                    writers.launch { rig.likes.restore(LikedTrackEntity(f2.first, rig.db.stringOf("SELECT relinkKey FROM track WHERE id = ?", f2.second)!!, 7)) }
                    writers.launch { rig.plays.record(f3.first, startedAt = 1, playedMs = 200_000, durationMs = 200_000) }
                    writers.launch { rig.ratings.rate(l3.first, 4) }
                    writers.launch { rig.ratings.restoreNow(f4.first, 3) }
                    writers.launch { rig.favourites.set(FavouriteKind.Album, at.likedAlbums[0].first, favourite = false) }
                    writers.launch { rig.favourites.set(FavouriteKind.Album, at.freeAlbums[0].first, favourite = true) }
                    writers.launch { rig.favourites.set(FavouriteKind.Artist, at.artist!!.first, favourite = false) }
                    writers.launch { rig.pins.pin(PinKey(PinKind.Album, at.freeAlbums[1].first)) }
                    writers.launch { rig.pins.unpin(PinKey(PinKind.Album, at.pinnedAlbums[0].first)) }
                    writers.launch { rig.pins.moveToFront(PinKey(PinKind.Album, at.pinnedAlbums[1].first)) }
                    writers.launch {
                        rig.db.userDao().insertPlaylist(PlaylistEntity("p", "P", 1, 1))
                        rig.edits.add("p", { emit -> rig.playlistSongs(500).pages(listOf(l3.first, f4.first), emit) }, distinct = false, newOnly = false, now = 2)
                    }
                    writers.launch { rig.listening.likeChanged(l3.first, liked = true) }
                    reads.allParked()
                    // The next group asks for its turn while every writer is
                    // inside: it waits, and only then are they let go.
                    rig.hooks.onWait = {
                        if (waitedAt < 0) {
                            waitedAt = rig.hooks.turns.get().toLong()
                            reads.go.complete(Unit)
                        }
                    }
                }
                rig.merge.rebuild()
                val at = park ?: throw AssertionError("bound $bound: no park had renames to write through")
                assertEquals("bound $bound: the next group ran before the writers had written", at.turn, waitedAt)
                assertTrue("bound $bound: a run was parked", rig.hooks.inRun(at.turn))
                assertTrue("bound $bound: ${rig.hooks.saves} groups", (rig.hooks.saves.lastOrNull() ?: 0) >= 2)
                assertEquals("bound $bound: the play was in when the next group began", 1L, playAtNextGroup)
                val (l1, l2, l3) = at.likedSongs
                val (f1, f2, f3, f4) = at.freeSongs
                val native = { song: String -> song.substringAfterLast(':') }
                until("bound $bound: the writers' follow-ons") {
                    rig.called("unstar", native(l1.first)) && rig.called("star", native(f1.first)) && rig.called("star", native(l3.first)) &&
                        rig.called("setRating", native(l3.first), "rating=4") && rig.called("setRating", native(f4.first), "rating=3") &&
                        rig.heard.size >= 2 && removed.size == 1 && rig.db.longOf("SELECT COUNT(*) FROM playlist_item WHERE playlistId = 'p'") == 2L
                }
                // The heart off: the like still on the old id went too.
                assertEquals("bound $bound: an unlike left a like", 0L, rig.db.longOf("SELECT COUNT(*) FROM liked_track WHERE trackId IN (?, ?)", l1.first, l1.second))
                assertEquals("bound $bound: an unlike by Undo's row left a like", 0L, rig.db.longOf("SELECT COUNT(*) FROM liked_track WHERE trackId IN (?, ?)", l2.first, l2.second))
                assertEquals(l2.first, removed.single().trackId)
                assertEquals(listOf(f1.second), rig.db.strings("SELECT trackId FROM liked_track WHERE trackId IN (?, ?)", f1.first, f1.second))
                assertEquals(listOf(f2.second), rig.db.strings("SELECT trackId FROM liked_track WHERE trackId IN (?, ?)", f2.first, f2.second))
                assertEquals(listOf(f3.second), rig.db.strings("SELECT trackId FROM play_event WHERE trackId IN (?, ?)", f3.first, f3.second))
                assertEquals(listOf("played ${f3.second}", "counted ${f3.second}").sorted(), rig.heard.sorted())
                assertEquals(listOf("4"), rig.db.strings("SELECT rating FROM track_rating WHERE trackId IN (?, ?)", l3.first, l3.second).also { assertEquals(1, it.size) })
                assertEquals(l3.second, rig.db.stringOf("SELECT trackId FROM track_rating WHERE trackId IN (?, ?)", l3.first, l3.second))
                assertEquals(f4.second, rig.db.stringOf("SELECT trackId FROM track_rating WHERE rating = 3"))
                // Favorites and pins, on the albums' and the artist's ids now.
                val (likedAlbum, freeAlbum, pinAlbum) = Triple(at.likedAlbums[0], at.freeAlbums[0], at.freeAlbums[1])
                val (unpinned, fronted) = at.pinnedAlbums
                until("bound $bound: the favorites") {
                    rig.db.longOf("SELECT COUNT(*) FROM liked_album WHERE albumId IN (?, ?)", likedAlbum.first, likedAlbum.second) == 0L &&
                        rig.db.longOf("SELECT COUNT(*) FROM liked_album WHERE albumId = ?", freeAlbum.second) == 1L &&
                        rig.db.longOf("SELECT COUNT(*) FROM liked_artist") == 0L
                }
                val pins = rig.db.favouritesDao().pins().sortedBy { it.position }
                assertTrue("bound $bound: $pins", pins.none { it.itemId == unpinned.first || it.itemId == unpinned.second })
                assertEquals("bound $bound: $pins", fronted.second, pins.first().itemId)
                assertTrue("bound $bound: $pins", pins.any { it.itemId == pinAlbum.second })
                assertEquals(listOf(l3.second, f4.second).sorted(), rig.db.strings("SELECT trackId FROM playlist_item WHERE playlistId = 'p'").sorted())
                assertTrue("bound $bound: the writers' reads ${reads.reads}", reads.reads.size >= 16)
                assertTrue("bound $bound: ids read outside a writer's hold: ${reads.unheld}", reads.unheld.isEmpty())
                writers.cancel()
            }
        }
    }

    // A playlist add of two pages, one hold a page: a group lands between
    // them, and a row the first page wrote on an id a later group renamed
    // moves to the id it became.
    @Test
    fun aWriterOfManyPagesHoldsOnePageAtATime() = guarded(240) {
        for (bound in listOf(SAVE_GROUP_FLOOR, SAVE_GROUP_ENTRIES)) {
            IdsRig(bound).use { rig ->
                val songs = songsFor(bound)
                val phone = rig.serverLibraryThenPhone(songs)
                rig.db.put(phone)
                rig.db.userDao().insertPlaylist(PlaylistEntity("p", "P", 1, 1))
                val pageReads = CopyOnWriteArrayList<Long>()
                var asked: List<String> = emptyList()
                var add: kotlinx.coroutines.Job? = null
                val writers = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val firstPage = kotlinx.coroutines.CompletableDeferred<Unit>()
                val firstGo = kotlinx.coroutines.CompletableDeferred<Unit>()
                rig.parkAt(
                    object : SongIdHooks() {
                        override suspend fun read(kind: String, id: String, now: String) {
                            pageReads += rig.hooks.turns.get().toLong()
                            if (pageReads.size == 1) {
                                firstPage.complete(Unit)
                                firstGo.await()
                            }
                        }
                    },
                )
                var parkTurn = -1L
                rig.hooks.between = between@{ turn ->
                    if (add != null) return@between
                    // Songs whose renames are still to come.
                    val waiting = (0 until songs).map(rig::serverSong).filter { old ->
                        rig.db.longOf("SELECT COUNT(*) FROM save_rename WHERE kind = 'track' AND `old` = ?", old) == 0L &&
                            rig.db.longOf("SELECT COUNT(*) FROM track WHERE id = ?", old) == 1L
                    }
                    if (waiting.size < 4) return@between
                    asked = waiting.take(4)
                    parkTurn = turn
                    add = writers.launch {
                        rig.edits.add("p", { emit -> rig.playlistSongs(2).pages(asked, emit) }, distinct = false, newOnly = false, now = 2)
                    }
                    firstPage.await()
                    rig.hooks.onWait = { firstGo.complete(Unit) }
                }
                rig.merge.rebuild()
                assertTrue("bound $bound: no park with renames still to come", parkTurn >= 0)
                add!!.join()
                assertEquals("bound $bound: page reads at turns $pageReads", 2, pageReads.size)
                assertTrue("bound $bound: no group landed between the pages: $pageReads", pageReads[1] > pageReads[0])
                assertTrue("bound $bound: a run was parked", rig.hooks.inRun(parkTurn))
                // Every row is on the song's id now.
                assertEquals(asked.map { phoneSong(it.substringAfterLast(":s").toInt()) }.sorted(), rig.db.strings("SELECT trackId FROM playlist_item WHERE playlistId = 'p'").sorted())
                writers.cancel()
            }
        }
    }

    // A download of songs and its removal, with a song a save renamed by its
    // key while it runs (a server's song whose id changed): the download is
    // of the song's id now, and the removal reaches it through the old id.
    @Test
    fun aDownloadAndItsRemovalWriteTheIdItRead() = guarded(120) {
        IdsRig(SAVE_GROUP_FLOOR).use { rig ->
            val songs = 40
            rig.db.put(library(rig.source, songs))
            rig.db.boundMerge(rig.source, SAVE_GROUP_FLOOR).rebuild()
            // The server gives every song a new id.
            val moved = library(rig.source, songs).let { lib -> lib.copy(tracks = lib.tracks.map { t -> t.copy(id = t.id + "x", nativeId = t.nativeId + "x") }) }
            rig.db.put(moved)
            var renamed: Pair<String, String>? = null
            var removedOld: Pair<String, String>? = null
            rig.hooks.between = between@{ turn ->
                if (renamed != null) return@between
                val pairs = rig.db.query("SELECT `old`, `new` FROM save_rename WHERE kind = 'track' AND byKey = 1 ORDER BY `old`", null)
                    .use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) } }
                if (pairs.size < 2) return@between
                renamed = pairs[0]
                removedOld = pairs[1]
                rig.offline.rules.download(listOf(pairs[0].first, pairs[1].first), refused = null)
            }
            rig.merge.rebuild()
            val (old, new) = renamed ?: throw AssertionError("no park with renames by key")
            assertTrue(rig.hooks.inRun(1) || (rig.hooks.saves.lastOrNull() ?: 0) >= 2)
            assertEquals(listOf(new), rig.db.strings("SELECT trackId FROM download WHERE trackId IN (?, ?)", old, new))
            // Taken back through its old id.
            val gone = rig.offline.rules.unkeep(removedOld!!.first) { held -> held }
            assertEquals(listOf(removedOld!!.second), gone)
        }
    }

    // A download's Undo while a save is part way: the rows a removal took go
    // back inside a Read hold and then the downloads lock, each on the song
    // its id names now (renamed by follow), and each song is asked for by
    // hand again. At the floor bound and the default.
    @Test
    fun aDownloadsUndoHoldsThenLocksAndPutsItsRowsOnTheIdsNow() = guarded(240) {
        for (bound in listOf(SAVE_GROUP_FLOOR, SAVE_GROUP_ENTRIES)) {
            IdsRig(bound).use { rig ->
                val phone = rig.serverLibraryThenPhone(songsFor(bound))
                val asked = (0 until 20).map(rig::serverSong)
                val rules = rig.offline.rules
                rules.download(asked, refused = null)
                val dao = rig.db.downloadDao()
                // Removed by hand, as Remove download takes them.
                val taken = asked.flatMap { id ->
                    rules.unkeep(id) { held -> held.flatMap { song -> dao.forTrack(song).onEach { rig.offline.book.delete(it) } } }.orEmpty()
                }
                assertEquals("bound $bound: the downloads taken", asked.size, taken.size)
                rig.db.put(phone)
                val reads = ParkedReads(times = 0, rig.merge)
                rig.parkAt(reads)
                val locked = CopyOnWriteArrayList<Boolean>()
                rules.hooks = object : app.winters.octo.offline.RulesHooks() {
                    override suspend fun downloadLocked() {
                        locked += rig.merge.holdsMergeLock()
                    }
                }
                var parkTurn = -1L
                var putBack: Map<String, String> = emptyMap()
                rig.hooks.between = between@{ turn ->
                    if (parkTurn >= 0) return@between
                    val renames = rig.db.query("SELECT `old`, `new` FROM save_rename WHERE kind = 'track' AND byKey = 0", null)
                        .use { c -> buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) } }
                    val ready = taken.filter { it.trackId in renames }
                    if (ready.isEmpty()) return@between
                    parkTurn = turn
                    putBack = ready.associate { it.trackId to renames.getValue(it.trackId) }
                    // Undo, between two groups.
                    rules.putBack(ready) { it }
                }
                rig.merge.rebuild()
                assertTrue("bound $bound: no park had a download's song renamed", parkTurn >= 0)
                assertTrue("bound $bound: ${rig.hooks.saves} groups", (rig.hooks.saves.lastOrNull() ?: 0) >= 2)
                assertTrue("bound $bound: no run was parked", rig.hooks.inRun(parkTurn))
                assertEquals("bound $bound: the Undo's writes under its hold", listOf(true), locked.toList())
                assertTrue("bound $bound: the Undo read no id", reads.reads.isNotEmpty())
                assertEquals("bound $bound: ids read outside a hold", emptyList<String>(), reads.unheld.toList())
                for ((old, new) in putBack) {
                    assertEquals("bound $bound: $old put back on", listOf(new), rig.db.strings("SELECT trackId FROM download WHERE trackId IN (?, ?)", old, new))
                }
                val reasons = rules.reasonsOf(putBack.values)
                assertTrue("bound $bound: a song put back is not asked for by hand: $reasons", putBack.values.all { app.winters.octo.offline.Reasons.MANUAL in reasons[it].orEmpty() })
            }
        }
    }

    // Every public method of the stores that takes an id, by name: how it
    // reads the id it writes. A new one fails here until it is listed, and
    // each listed one that names a library song, album or artist is driven
    // through an old id above.
    @Test
    fun everyStoreMethodThatTakesAnIdIsListed() {
        val listed = mapOf(
            "LikeStore.toggle" to "SongIds.song, one hold",
            "LikeStore.unlike" to "SongIds.song, one hold, old ids' likes deleted",
            "LikeStore.restore" to "SongIds.row, one hold",
            "LikeStore.restored" to "ListeningSync resolves",
            "LikeStore.likedFlow" to "a read of either id",
            "LikeStore.likedAmong" to "a read of either id",
            "LikeStore.isLiked" to "a read of either id",
            "LikeStore.ownedArrived" to "SongIds.song, one hold",
            "PlayStore.record" to "SongIds.song, one hold",
            "PlayStore.started" to "ListeningSync resolves; ListenBrainz reads the playing song",
            "RatingStore.rate" to "SongIds.song, one hold",
            "RatingStore.restoreNow" to "SongIds.song, one hold",
            "FavouriteStore.set" to "SongIds.album or artist, one hold, old ids' favorites deleted",
            "FavouriteStore.toggle" to "FavouriteStore.set",
            "FavouriteStore.ids" to "not an id: a kind",
            "PinStore.pin" to "SongIds.album or artist, one hold",
            "PinStore.unpin" to "SongIds.album or artist, one hold, old ids' pins deleted",
            "PinStore.moveToFront" to "SongIds.album or artist, one hold",
            "PinStore.current" to "a read of the id now",
            "PlaylistStore.create" to "PlaylistSongs.pages, a hold a page",
            "PlaylistStore.createDistinct" to "PlaylistSongs.heldSongs, a hold a page",
            "PlaylistStore.createFrom" to "PlaylistSongs.heldItems, a hold a page",
            "PlaylistStore.duplicate" to "createFrom",
            "PlaylistStore.add" to "PlaylistSongs.pages, a hold a page",
            "PlaylistStore.addPages" to "PlaylistSongs.heldSongs, a hold a page",
            "PlaylistStore.summary" to "not a library id: a playlist's",
            "PlaylistStore.rename" to "not a library id: a playlist's",
            "PlaylistStore.delete" to "not a library id: a playlist's",
            "PlaylistStore.undoAdd" to "not a library id: rows by key",
            "PlaylistStore.remove" to "not a library id: a row's key",
            "PlaylistStore.removeAll" to "not a library id: rows' keys",
            "PlaylistStore.restoreAll" to "rows put back as they were; the rename triggers move them",
            "PlaylistStore.restore" to "a row put back as it was; the rename triggers move it",
            "PlaylistStore.move" to "not a library id: rows' keys",
            "PlaylistStore.saveToServer" to "not a library id: a playlist's",
            "PlaylistStore.holdsFind" to "not a library id: a playlist's",
            "OfflineRules.download" to "SongIds.songs, a hold a page, then the lock",
            "OfflineRules.downloadAlbum" to "SongIds.album, a hold a page, then the lock",
            "OfflineRules.downloadPlaylist" to "a playlist's rows, each page's songs as download",
            "OfflineRules.unkeep" to "SongIds.song, one hold, then the lock; old ids by follow",
            "OfflineRules.putBack" to "SongIds.followed, one hold, then the lock",
            "OfflineRules.reasonsOf" to "a read under the lock, of ids already resolved",
            "OfflineRules.setReasons" to "a write under the lock, of ids already resolved",
            "OfflineRules.walk" to "waits for no save to be part way",
            "OfflineRules.forgetSource" to "not a library id: a source's",
            "OfflineDownloads.download" to "OfflineRules.download",
            "OfflineDownloads.downloadAlbum" to "OfflineRules.downloadAlbum",
            "OfflineDownloads.downloadPlaylist" to "OfflineRules.downloadPlaylist",
            "OfflineDownloads.remove" to "OfflineRules.unkeep",
            "OfflineDownloads.isDone" to "a read; the walk's opening moves downloads",
            "OfflineDownloads.doneAmong" to "a read; the walk's opening moves downloads",
            "OfflineDownloads.keptAmong" to "a read; the walk's opening moves downloads",
            "OfflineDownloads.keptRow" to "a read; the walk's opening moves downloads",
            "OfflineDownloads.progressOf" to "not a library id: a copy's",
            "OfflineDownloads.retry" to "the download row's own song id, as the downloads list shows it",
            "OfflineDownloads.playableCopies" to "a read of the playing songs' downloads; the player heals its ids",
            "OfflineDownloads.setPlaylistKept" to "not a library id: a playlist's",
            "OfflineDownloads.forgetSource" to "not a library id: a source's",
            "OfflineDownloads.dropStoredHtml" to "the playing song's rows kept in that file; a stale id's copy heals when its file is found gone",
            "OfflineDownloads.isDownloadFile" to "not a library id: a file's address",
            "OfflineDownloads.serverCopyOfFile" to "a read; the walk's opening moves downloads",
            "ListeningSync.likeChanged" to "copiesOf: SongIds.song, one hold",
            "ListeningSync.ratingChanged" to "copiesOf: SongIds.song, one hold",
            "ListeningSync.nowPlaying" to "copiesOf: SongIds.song, one hold",
            "ListeningSync.played" to "copiesOf: SongIds.song, one hold",
        )
        val stores = listOf(
            app.winters.octo.playback.LikeStore::class.java, app.winters.octo.playback.PlayStore::class.java,
            app.winters.octo.playback.RatingStore::class.java, app.winters.octo.favourites.FavouriteStore::class.java,
            app.winters.octo.favourites.PinStore::class.java, app.winters.octo.playback.PlaylistStore::class.java,
            app.winters.octo.offline.OfflineRules::class.java, app.winters.octo.offline.OfflineDownloads::class.java,
            app.winters.octo.listening.ListeningSync::class.java,
        )
        val idTypes = setOf(String::class.java, List::class.java, Collection::class.java, PinKey::class.java, LikedTrackEntity::class.java)
        val found = stores.flatMap { store ->
            store.declaredMethods
                .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) && !it.isSynthetic && '$' !in it.name && !it.name.startsWith("get") }
                .filter { m -> m.parameterTypes.any { it in idTypes } }
                .map { "${store.simpleName}.${m(it.name)}" }
        }.toSet()
        val unlisted = found - listed.keys
        assertTrue("store methods that take an id and are not listed: $unlisted", unlisted.isEmpty())
        assertTrue("the listing found nothing", found.size >= 20)
    }

    private fun m(name: String) = name.substringBefore('-')
}
