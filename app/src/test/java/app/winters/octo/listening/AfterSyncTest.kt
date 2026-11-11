package app.winters.octo.listening

import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.LikedAlbumEntity
import app.winters.octo.catalog.LikedTrackEntity
import app.winters.octo.catalog.SongIds
import app.winters.octo.catalog.TrackRatingEntity
import app.winters.octo.catalog.longOf
import app.winters.octo.catalog.renameLikeASave
import app.winters.octo.testing.testMerge
import app.winters.octo.data.SessionState
import app.winters.octo.playback.RatingStore
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

// The steps after a copy walk the server's copies a chunk at a time by
// server id, and decide nothing from a partial answer.
@RunWith(RobolectricTestRunner::class)
class AfterSyncTest {
    private lateinit var db: CatalogDatabase
    private val rebuilds = TestRebuilds()

    @Before
    fun open() {
        db = openTestCatalog()
    }

    @After
    fun close() = db.close()

    // Counts what each walk step read.
    private class CountingListeningDao(private val inner: ListeningDao) : ListeningDao by inner {
        var largest = 0
        var starChunks = 0
        var ratingChunks = 0
        val seen = HashSet<String>()

        override suspend fun starChunk(sourceId: String, account: String, generation: Long, after: String): StarChunk =
            inner.starChunk(sourceId, account, generation, after).also { chunk ->
                starChunks++
                largest = maxOf(largest, chunk.copies.size)
                seen += chunk.copies.map { it.serverId }
            }

        override suspend fun ratingChunk(sourceId: String, account: String, after: String): RatingChunk =
            inner.ratingChunk(sourceId, account, after).also { chunk ->
                ratingChunks++
                largest = maxOf(largest, chunk.copies.size)
            }
    }

    private fun store(dao: ListeningDao, account: String = "home") = ListeningStore(MemoryPrefs(), MutableStateFlow(account), dao)

    private fun listeningSync(server: AfterSyncServer, dao: ListeningDao = db.listeningDao(), store: ListeningStore = store(dao)) =
        ListeningSync({ server.session }, db.sourceDao(), db.userDao(), db.dao(), dao, store, rebuilds)

    private fun favouriteSync(server: AfterSyncServer, favourites: FavouriteSyncStore) =
        FavouriteSync({ SessionState.SignedIn(server.session) }, db.favouritesDao(), favourites, db.listeningDao())

    private fun id(n: Int) = "s%04d".format(n)

    @Test
    fun copiesAreReadFiveHundredAtATime() = runBlocking {
        AfterSyncServer().use { server ->
            val source = server.session.sourceId
            db.runInTransaction {
                for (n in 0 until 1_200) {
                    db.song("t${id(n)}")
                    db.copy(source, id(n), "t${id(n)}", rating = if (n % 3 == 0) 3 else null)
                }
            }
            // Every fourth song liked on the phone and never starred: each is starred now.
            for (n in 0 until 1_200 step 4) db.userDao().like(LikedTrackEntity("t${id(n)}", "rk", 1))
            val counted = CountingListeningDao(db.listeningDao())
            listeningSync(server, counted).afterSync()
            assertTrue("largest read ${counted.largest}", counted.largest <= AFTER_SYNC_CHUNK)
            assertEquals(3, counted.starChunks)
            assertEquals(3, counted.ratingChunks)
            assertEquals(1_200, counted.seen.size)
            val starred = server.calls("star").flatMap { it.split(" ").drop(1) }.toSet()
            assertEquals((0 until 1_200 step 4).map(::id).toSet(), starred)
            // A rating only on the server comes to the phone.
            assertEquals(3, db.userDao().rating("t${id(0)}"))
        }
    }

    @Test
    fun aSongMovedBetweenChunksIsNeitherUnstarredNorUnliked() = runBlocking {
        AfterSyncServer().use { server ->
            val source = server.session.sourceId
            val serverOnly = "$source:z1"
            db.runInTransaction {
                // A full first chunk the moving song is not in.
                for (n in 0 until AFTER_SYNC_CHUNK) {
                    db.song("ta$n")
                    db.copy(source, "a%03d".format(n), "ta$n")
                }
                // The song is only on the server: its library id is its copy's.
                db.song(serverOnly, relinkKey = "rk-x")
                db.copy(source, "z1", serverOnly)
            }
            db.userDao().like(LikedTrackEntity(serverOnly, "rk-x", 5))
            val dao = db.listeningDao()
            val store = store(dao)
            store.addSyncedStars("home", listOf("z1"))
            server.starred = starredAnswer(songs = listOf("z1"))
            val sync = listeningSync(server, dao, store)
            var moved = false
            sync.afterChunk = {
                if (!moved) {
                    moved = true
                    // A rebuild: the phone's copy turns up and takes the song's place.
                    rebuilds.between {
                        db.song("device:9", relinkKey = "rk-x")
                        db.exec("UPDATE source_track SET mergedId = 'device:9' WHERE id = ?", serverOnly)
                        db.exec("DELETE FROM track WHERE id = ?", serverOnly)
                        db.renameLikeASave(serverOnly, "device:9", raise = false)
                    }
                }
            }
            sync.afterSync()
            assertTrue(moved)
            assertEquals(emptyList<String>(), server.calls("unstar"))
            assertEquals(listOf("device:9"), db.userDao().likedIds().first())
            assertEquals(listOf("z1"), dao.syncedStarsAmong("home", listOf("z1")))
        }
    }

    // A liked song, starred and in the record, that the answer does not list.
    private suspend fun likedAndStarredBefore(server: AfterSyncServer, favourites: FavouriteSyncStore, store: ListeningStore) {
        val source = server.session.sourceId
        db.song("device:1", albumId = "lib-al1")
        db.copy(source, "z9", "device:1", albumId = "$source:al1")
        db.sourceAlbum("$source:al1", source)
        db.userDao().like(LikedTrackEntity("device:1", "rk", 1))
        store.addSyncedStars("home", listOf("z9"))
        db.favouritesDao().likeAlbums(listOf(LikedAlbumEntity("lib-al1", "album", 1)))
        favourites.updateSynced("home", FavouriteKind.Album) { it + "al1" }
    }

    private fun partialAnswerChangesNothing(answer: String) = runBlocking {
        AfterSyncServer().use { server ->
            val dao = db.listeningDao()
            val store = store(dao)
            val favourites = FavouriteSyncStore(MemoryPrefs())
            likedAndStarredBefore(server, favourites, store)
            server.starred = answer
            listeningSync(server, dao, store).afterSync()
            favouriteSync(server, favourites).afterSync()
            assertEquals(emptyList<String>(), server.calls("unstar"))
            assertEquals(listOf("device:1"), db.userDao().likedIds().first())
            assertEquals(listOf("lib-al1"), db.favouritesDao().likedAlbumIds().first())
            assertNull(dao.completeStarred())
        }
    }

    @Test
    fun aCutOffAnswerUnlikesAndUnfavoritesNothing() =
        partialAnswerChangesNothing("""{"subsonic-response":{"status":"ok","starred2":{"song":[{"id":"z1","starred":"2026-01-02T03:04:05Z"}""")

    @Test
    fun anErrorAfterTheListsUnlikesAndUnfavoritesNothing() =
        partialAnswerChangesNothing("""{"subsonic-response":{"starred2":{"song":[{"id":"z1"}],"album":[]},"status":"failed","error":{"code":0,"message":"Try later"}}}""")

    @Test
    fun withNoServerCopiesNothingIsUnliked() = runBlocking {
        AfterSyncServer().use { server ->
            db.song("device:1")
            db.userDao().like(LikedTrackEntity("device:1", "rk", 1))
            listeningSync(server).afterSync()
            assertEquals(listOf("device:1"), db.userDao().likedIds().first())
            assertEquals(emptyList<String>(), server.calls("unstar"))
        }
    }

    @Test
    fun aRatingMadeOfflineReachesTheServer() = runBlocking {
        AfterSyncServer().use { server ->
            val source = server.session.sourceId
            db.song("device:1")
            db.copy(source, "s1", "device:1", rating = 2)
            val dao = db.listeningDao()
            val store = store(dao)
            store.setSyncedRatings("home", listOf("s1"), 2)
            db.userDao().rate(TrackRatingEntity("device:1", "rk-device:1", 4, 1))
            server.refuseRatings = true
            val sync = listeningSync(server, dao, store)
            sync.afterSync()
            assertEquals(listOf("setRating s1 rating=4"), server.calls("setRating"))
            assertEquals(2, dao.syncedRatingsAmong("home", listOf("s1")).single().rating)
            assertEquals(4, db.dao().track("device:1")!!.rating)
            server.refuseRatings = false
            sync.afterSync()
            assertEquals(listOf("setRating s1 rating=4", "setRating s1 rating=4"), server.calls("setRating"))
            assertEquals(4, dao.syncedRatingsAmong("home", listOf("s1")).single().rating)
            assertEquals(4, db.sourceDao().copies("device:1").single().rating)
        }
    }

    // A tap made offline wrote 4 into the server copy too, and no copy came
    // since: the record still says 2, so the sync sends the 4.
    @Test
    fun aTapTheServerNeverTookIsSentWithNoCopyBetween() = runBlocking {
        AfterSyncServer().use { server ->
            val source = server.session.sourceId
            db.song("device:1")
            db.copy(source, "s1", "device:1", rating = 4)
            val dao = db.listeningDao()
            val store = store(dao)
            store.setSyncedRatings("home", listOf("s1"), 2)
            db.userDao().rate(TrackRatingEntity("device:1", "rk-device:1", 4, 1))
            listeningSync(server, dao, store).afterSync()
            assertEquals(listOf("setRating s1 rating=4"), server.calls("setRating"))
            assertEquals(4, dao.syncedRatingsAmong("home", listOf("s1")).single().rating)
            assertEquals(4, db.userDao().rating("device:1"))
        }
    }

    // While the sync re-sends a tap the server never confirmed, the user
    // taps again: the library and the server copy show the new tap, never
    // the one being re-sent.
    @Test
    fun aTapWhileATapIsResentStays() = runBlocking {
        AfterSyncServer().use { server ->
            val source = server.session.sourceId
            db.song("device:1")
            db.copy(source, "s1", "device:1", rating = 4)
            val dao = db.listeningDao()
            val store = store(dao)
            store.setSyncedRatings("home", listOf("s1"), 2)
            db.userDao().noteRating("device:1", 4, "rk-device:1", 1, copies = true)
            server.whileRating = {
                server.whileRating = null
                runBlocking { db.userDao().noteRating("device:1", 3, "rk-device:1", 2, copies = true) }
            }
            listeningSync(server, dao, store).afterSync()
            assertEquals(listOf("setRating s1 rating=4"), server.calls("setRating"))
            assertEquals(3, db.userDao().rating("device:1"))
            assertEquals(3, db.dao().track("device:1")!!.rating)
            assertEquals(3, db.sourceDao().copies("device:1").single().rating)
        }
    }

    // An unrated song tapped to 4 while the server could not take it: the
    // copy shows 4 and there is no record, so the sync sends it, once.
    @Test
    fun aTapOnAnUnratedSongTheServerNeverTookIsSentOnce() = runBlocking {
        AfterSyncServer().use { server ->
            val source = server.session.sourceId
            db.song("device:1")
            db.copy(source, "s1", "device:1", rating = 0)
            val dao = db.listeningDao()
            val store = store(dao)
            db.userDao().noteTap("device:1", 4, "rk-device:1", 1)
            val sync = listeningSync(server, dao, store)
            sync.afterSync()
            assertEquals(listOf("setRating s1 rating=4"), server.calls("setRating"))
            assertEquals(4, dao.syncedRatingsAmong("home", listOf("s1")).single().rating)
            // Its tap is answered, so nothing is sent again.
            assertEquals(0L, db.longOf("SELECT COUNT(*) FROM rating_tap"))
            sync.afterSync()
            assertEquals(listOf("setRating s1 rating=4"), server.calls("setRating"))
        }
    }

    // A rating restored from a backup that the server copy already shows,
    // never agreed on and never tapped: taken as agreed, nothing sent.
    @Test
    fun aRestoredRatingTheCopyShowsSendsNothing() = runBlocking {
        AfterSyncServer().use { server ->
            val source = server.session.sourceId
            db.song("device:1")
            db.copy(source, "s1", "device:1", rating = 4)
            val dao = db.listeningDao()
            db.userDao().rate(TrackRatingEntity("device:1", "rk-device:1", 4, 1))
            listeningSync(server, dao, store(dao)).afterSync()
            assertEquals(emptyList<String>(), server.calls("setRating"))
            assertEquals(4, dao.syncedRatingsAmong("home", listOf("s1")).single().rating)
        }
    }

    // A restored rating every server copy already shows is the phone's own
    // and nothing to send; one any copy lacks (a second copy, none) is a
    // tap, sent like one; a song only on the phone is only the phone's.
    @Test
    fun aRestoreTapsOnlyWhatTheServerLacks() = runBlocking {
        AfterSyncServer().use { server ->
            val source = server.session.sourceId
            (1..5).forEach { db.song("device:$it") }
            db.copy(source, "s1", "device:1", rating = 4)
            db.copy(source, "s2", "device:2", rating = 1)
            db.copy(source, "s3a", "device:3", rating = 3)
            db.copy(source, "s3b", "device:3", rating = 2)
            db.copy(source, "s4", "device:4", rating = 2)
            val dao = db.listeningDao()
            // Signed out for the sends, so only what is kept is looked at.
            val ratings = RatingStore(db.userDao(), ListeningSync({ null }, db.sourceDao(), db.userDao(), db.dao(), dao, store(dao), rebuilds), SongIds(db, db.testMerge(null)))
            assertFalse(ratings.restoreNow("device:1", 4))
            assertTrue(ratings.restoreNow("device:2", 5))
            assertTrue(ratings.restoreNow("device:3", 3))
            assertTrue(ratings.restoreNow("device:4", 0))
            assertFalse(ratings.restoreNow("device:5", 5))
            assertEquals(4, db.userDao().rating("device:1"))
            assertEquals(5, db.userDao().rating("device:5"))
            assertEquals(
                listOf("$source:s2" to 5L, "$source:s3a" to 3L, "$source:s3b" to 3L, "$source:s4" to 0L),
                listOf("s2", "s3a", "s3b", "s4").map { "$source:$it" to db.longOf("SELECT rating FROM rating_tap WHERE sourceRowId = ?", "$source:$it") },
            )
            assertEquals(4L, db.longOf("SELECT COUNT(*) FROM rating_tap"))
        }
    }

    // A restore the copies already show keeps a pending tap of the same
    // rating (still to be sent) and overrules one of another rating.
    @Test
    fun aRestoreKeepsATapOfItsRatingAndOverrulesAnother() = runBlocking {
        AfterSyncServer().use { server ->
            val source = server.session.sourceId
            db.song("device:1")
            db.song("device:2")
            db.copy(source, "s1", "device:1", rating = 0)
            db.copy(source, "s2", "device:2", rating = 0)
            val user = db.userDao()
            user.noteTap("device:1", 4, "rk-device:1", 1)
            user.noteTap("device:2", 4, "rk-device:2", 1)
            // A copy since put the server's 2 back on the second song.
            db.exec("UPDATE source_track SET rating = 2 WHERE nativeId = 's2'")
            assertFalse(user.noteRestored("device:1", 4, "rk-device:1", 2))
            assertFalse(user.noteRestored("device:2", 2, "rk-device:2", 2))
            assertEquals(4L, db.longOf("SELECT rating FROM rating_tap WHERE sourceRowId = ?", "$source:s1"))
            assertEquals(1L, db.longOf("SELECT COUNT(*) FROM rating_tap"))
        }
    }

    // A backup restores ratings as restores, never as taps.
    @Test
    fun aBackupRestoresRatingsAsRestores() {
        val text = File(System.getProperty("octo.mainSources")!!, "app/winters/octo/backup/Backups.kt").readText()
        assertTrue(text.contains("ratings.restoreNow("))
        assertFalse(text.contains("ratings.rate("))
    }

    // Removing a server takes its taps with its copies.
    @Test
    fun aServerTakenAwayTakesItsTaps() = runBlocking {
        AfterSyncServer().use { server ->
            val source = server.session.sourceId
            db.song("device:1")
            db.copy(source, "s1", "device:1", rating = 0)
            db.userDao().noteTap("device:1", 4, "rk-device:1", 1)
            assertEquals(1L, db.longOf("SELECT COUNT(*) FROM rating_tap"))
            db.sourceDao().deleteSource(source)
            assertEquals(0L, db.longOf("SELECT COUNT(*) FROM rating_tap"))
        }
    }

    // A tap made while the sync sends an earlier one outlives that send,
    // and the next sync sends it.
    @Test
    fun aTapMadeWhileAnotherIsSentIsSentNext() = runBlocking {
        AfterSyncServer().use { server ->
            val source = server.session.sourceId
            db.song("device:1")
            db.copy(source, "s1", "device:1", rating = 0)
            val dao = db.listeningDao()
            val store = store(dao)
            db.userDao().noteTap("device:1", 4, "rk-device:1", 1)
            server.whileRating = {
                server.whileRating = null
                runBlocking { db.userDao().noteTap("device:1", 2, "rk-device:1", 2) }
            }
            val sync = listeningSync(server, dao, store)
            sync.afterSync()
            assertEquals(listOf("setRating s1 rating=4"), server.calls("setRating"))
            assertEquals(1L, db.longOf("SELECT COUNT(*) FROM rating_tap WHERE rating = 2"))
            sync.afterSync()
            assertEquals(listOf("setRating s1 rating=4", "setRating s1 rating=2"), server.calls("setRating"))
            assertEquals(2, db.dao().track("device:1")!!.rating)
            assertEquals(0L, db.longOf("SELECT COUNT(*) FROM rating_tap"))
        }
    }

    @Test
    fun sentPlaysFoldAsTheyDidInThePreferencesFile() = runBlocking {
        AfterSyncServer().use { server ->
            val source = server.session.sourceId
            db.song("device:1")
            db.song("device:2")
            db.copy(source, "s1", "device:1", lastPlayedAt = 3_000)
            db.copy(source, "s2", "device:2")
            val dao = db.listeningDao()
            val store = store(dao)
            for ((id, start) in listOf("s1" to 1_000L, "s1" to 5_000L, "s2" to 2_000L, "s3" to 4_000L)) {
                store.addPending("home", PendingPlay(id, start))
                store.finishPending("home", PendingPlay(id, start), sent = true)
            }
            val before = store.sent.first()
            store.foldSent("home", source)
            val expected = before.foldedInto(mapOf("s1" to 3_000L, "s2" to null))
            assertEquals(expected, store.sent.first())
            assertEquals(mapOf("s1" to SentPlays(1, listOf(5_000)), "s2" to SentPlays(0, listOf(2_000))), expected)
        }
    }
}
