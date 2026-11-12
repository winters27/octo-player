package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.winters.octo.catalog.AlbumEntity
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.listening.timed
import app.winters.octo.sort.SongScope
import app.winters.octo.sort.SongSort
import app.winters.octo.sort.SortOrder
import app.winters.octo.testing.counted
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.random.Random

// Autoplay's own picks when the radio finds nothing: songs by the seed's
// artist, then in its genre, walked a page at a time into fair samples of
// ten, never a song left out (played lately, the seed, or one the queue
// holds anywhere, past the player's window too). Guard: the base read every
// album of the artist and the whole genre in one query each, and left out
// only what the player itself held.
@RunWith(RobolectricTestRunner::class)
class AutoplayFallbackTest {
    private fun song(id: String, artist: String, album: String, genre: String, order: Int) = TrackEntity(
        id = id, sourceId = "device", nativeId = id, title = "Song $id", searchKey = "song $id", sortKey = "song $id",
        artist = artist, artistId = artist, album = album, albumId = album, trackNo = order, discNo = 1, year = 2000,
        durationMs = 200_000, addedAt = 0, mimeType = null, sizeBytes = null, artwork = null, uri = null, albumOrder = order, genre = genre,
    )

    private fun album(id: String, artist: String) = AlbumEntity(
        id = id, sourceId = "device", nativeId = id, title = id, searchKey = id, sortKey = id, artist = artist, artistId = artist,
        year = 2000, songCount = 10, durationMs = 0, addedAt = 0, artwork = null,
    )

    // `artistSongs` songs by "ar" on albums of ten, then `genreSongs` by
    // other artists, all in Rock.
    private fun CatalogDatabase.library(artistSongs: Int, genreSongs: Int, genre: String = "Rock") = runBlocking {
        val mine = (0 until artistSongs).map { song("a$it", "ar", "al${it / 10}", genre, it % 10) }
        val others = (0 until genreSongs).map { song("g$it", "other${it % 7}", "og${it / 10}", if (it % 2 == 0) genre.uppercase() else genre, it % 10) }
        (mine + others).chunked(500).forEach { dao().insertTracks(it) }
        dao().insertAlbums(mine.map { it.albumId }.distinct().map { album(it, "ar") } + others.map { it.albumId }.distinct().map { album(it, "other") })
        mine to others
    }

    private fun picker(db: CatalogDatabase, seed: Int = 1): AutoplayPicker {
        val rig = runBlocking { RadioRig(db).started() }
        return AutoplayPicker(rig.radio, db, rig.totals.merge, rig.totals.history, QueueStore(db)).also { it.random = Random(seed) }
    }

    @Test
    fun aGenreOfThousandsIsReadAPageAtATimeAndAnExcludedSongNeverPicked() = timed(60) {
        val db = openTestCatalog()
        try {
            val (mine, others) = db.library(artistSongs = 6, genreSongs = 3_000)
            val left = setOf("a1", "a3") + others.take(1_500).map { it.id }
            val picker = picker(db)
            val (picks, reads) = db.counted.record { runBlocking { picker.fallbacks(mine[0], { ids -> ids.filterTo(HashSet(), left::contains) }) } }
            val own = reads.filterNot { it.bookkeeping }
            assertTrue("reads were recorded", own.isNotEmpty())
            assertTrue("rows per read: ${own.maxByOrNull { it.rowsRead }}", own.all { it.rowsRead <= AUTOPLAY_PAGE + 1 })
            assertEquals(AUTOPLAY_BATCH, picks.size)
            assertEquals(picks.size, picks.toSet().size)
            assertTrue("$picks", picks.none { it in left })
            // The artist's eligible songs first, then the genre's, spelt any
            // way but for case.
            assertEquals(setOf("a0", "a2", "a4", "a5"), picks.take(4).toSet())
            assertTrue(picks.drop(4).all { it.startsWith("g") })
        } finally {
            db.close()
        }
    }

    // Each eligible song by the artist is as likely to be among the ten as
    // any other: over 600 draws, each of 30 is picked within a binomial
    // bound of a third of the time. Seeded, so the same every run.
    @Test
    fun eachEligibleSongIsAsLikelyAsAnother() = timed(60) {
        val db = openTestCatalog()
        try {
            val (mine, _) = db.library(artistSongs = 31, genreSongs = 0)
            val seen = HashMap<String, Int>()
            val picker = picker(db)
            repeat(600) { n ->
                picker.random = Random(n)
                picker.fallbacks(mine[0]) { ids -> ids.filterTo(HashSet()) { it == "a0" } }.forEach { seen.merge(it, 1, Int::plus) }
            }
            assertEquals(30, seen.size)
            seen.forEach { (id, times) -> assertTrue("$id picked $times times", abs(times - 200) <= 60) }
        } finally {
            db.close()
        }
    }

    @Test
    fun withNoGenreOnlyTheArtistsSongsArePicked() = timed(30) {
        val db = openTestCatalog()
        try {
            val (mine, _) = db.library(artistSongs = 3, genreSongs = 20, genre = "")
            assertEquals(setOf("a1", "a2"), picker(db).fallbacks(mine[0]) { ids -> ids.filterTo(HashSet()) { it == "a0" } }.toSet())
        } finally {
            db.close()
        }
    }
}

// The same through the queue: a song the queue holds is never added again,
// even far past the player's window, and the question of which it holds is
// one read of the queue's rows, never a job of its worker.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(UnstableApi::class)
class AutoplayLeavesTheQueueOutTest {
    @Test
    fun aSongTheQueueHoldsPastItsWindowIsNeverAdded() {
        val rig = QueueRig()
        try {
            val queued = rig.db.songs(600, genre = { "Rock" })
            runBlocking { rig.db.dao().insertAlbums(listOf(AlbumEntity("al", "device", "al", "al", "al", "al", "ar", "ar", null, 600, 0, 0, null))) }
            rig.play(PlayRequest(ListSpec.Songs(SongScope.All, SortOrder(SongSort.Title, false)), null, shownOverWindow = true))
            rig.joined()
            assertTrue("the player holds a window of the queue", rig.held().size < 600)
            val radio = runBlocking { RadioRig(rig.db, rig.merge).started() }
            val picker = AutoplayPicker(radio.radio, rig.db, rig.merge, radio.totals.history, rig.store).also { it.random = Random(3) }
            // The queue's worker is busy all the while.
            val busy = CompletableDeferred<Unit>()
            rig.store.post { busy.await() }
            val held = rig.held().toSet()
            val none = rig.run { withTimeout(10_000) { picker.picksAfter(queued[599].id, rig.held().takeLast(30), emptyList(), held) } }
            assertEquals(emptyList<String>(), none)
            val more = rig.db.songs(15, album = "al", prefix = "n", genre = { "Rock" })
            val picks = rig.run { withTimeout(10_000) { picker.picksAfter(queued[599].id, rig.held().takeLast(30), emptyList(), held) } }
            busy.complete(Unit)
            assertTrue("$picks", picks.isNotEmpty() && picks.all { id -> more.any { it.id == id } })
        } finally {
            rig.close()
        }
    }
}
