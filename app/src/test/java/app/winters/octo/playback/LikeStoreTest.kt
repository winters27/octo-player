package app.winters.octo.playback

import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.LikedTrackEntity
import app.winters.octo.catalog.mosaicCovers
import app.winters.octo.discovery.OwnedFinds
import app.winters.octo.discovery.Resolved
import app.winters.octo.discovery.heartOwned
import app.winters.octo.discovery.resolveSongs
import app.winters.octo.subsonic.Song
import app.winters.octo.listening.ListeningStore
import app.winters.octo.listening.ListeningSync
import app.winters.octo.listening.MemoryPrefs
import app.winters.octo.listening.TestRebuilds
import app.winters.octo.listening.timed
import app.winters.octo.testing.counted
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// No whole liked set: one song's like, the likes among some songs (asked
// 900 at a time), a count that moves on every like and unlike, and the
// liked songs' covers a page at a time. Counted at the database, no
// statement over the likes reads more than one chunk. Today every liked id
// is read into a set at start.
@RunWith(RobolectricTestRunner::class)
class LikeStoreTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    private fun CatalogDatabase.likes(owned: OwnedFinds = OwnedFinds()): LikeStore {
        val rows = listeningDao()
        val listening = ListeningSync({ null }, sourceDao(), userDao(), dao(), rows, ListeningStore(MemoryPrefs(), MutableStateFlow(null), rows), TestRebuilds())
        return LikeStore(userDao(), dao(), listening, this, app.winters.octo.catalog.SongIds(this, testMerge(null)), owned)
    }

    private suspend fun like(n: Int, at: Long = n.toLong()) = db.userDao().like(LikedTrackEntity("device:s$n", "key:$n", at))

    @Test
    fun oneSongAndSomeSongsAreAskedAboutAlone() = timed(30) {
        db.userDao().likeAll((0 until 2_000).filter { it % 2 == 0 }.map { LikedTrackEntity("device:s$it", "key:$it", it.toLong()) })
        val likes = db.likes()
        db.counted.reset()
        assertTrue(likes.likedFlow("device:s4").first())
        assertFalse(likes.likedFlow("device:s5").first())
        assertTrue(likes.isLiked("device:s6"))
        assertFalse(likes.isLiked("device:s7"))
        val asked = (0 until 1_000).map { "device:s$it" }
        assertEquals(asked.filterIndexed { i, _ -> i % 2 == 0 }.toSet(), likes.likedAmong(asked).first())
        assertEquals(emptySet<String>(), likes.likedAmong(emptyList()).first())
        val bound = db.counted.maxBound!!
        assertTrue("$bound", bound.boundArgs <= 900)
        val most = db.counted.maxRead!!
        assertTrue("$most", most.rowsRead <= 900)
    }

    // An unmarked, unlinked song in a list Octo marked is the server's own:
    // its heart stars it on the server by its server id and shows filled at
    // once, and comes off the same way. A song found online has no heart.
    @Test
    fun anOwnedSongsHeartStarsItsServerIdAndShowsAtOnce() = timed(30) {
        val outside = Song("e1", "Genesis", artist = "Justice", isExternal = true)
        val added = Song("s2", "Aerodynamic", artist = "Daft Punk")
        val owned = OwnedFinds()
        val resolved = resolveSongs(listOf(outside, added), "server:x", emptyMap(), emptyList(), 0).map { it as Resolved.Found }
        owned.note(resolved.filter { it.owned }.map { it.song.id }, resolved.filterNot { it.owned }.map { it.song.id })
        val stars = mutableListOf<Pair<String, Boolean>>()
        assertEquals(true, heartOwned(owned, "find:s2") { id, on -> stars += id to on })
        assertEquals(listOf("s2" to true), stars)
        assertEquals(false, heartOwned(owned, "find:s2") { id, on -> stars += id to on })
        assertEquals(listOf("s2" to true, "s2" to false), stars)
        assertEquals(null, heartOwned(owned, "find:e1") { id, on -> stars += id to on })
        assertEquals(2, stars.size)

        // Through the store, as the player's heart taps it.
        val likes = db.likes(owned)
        likes.toggle("find:s2")
        assertTrue(likes.likedFlow("find:s2").first())
        assertTrue(likes.isLiked("find:s2"))
        assertEquals(setOf("find:s2"), likes.likedAmong(listOf("find:s2", "find:e1", "device:s1")).first())
        likes.toggle("find:e1")
        assertFalse(likes.likedFlow("find:e1").first())
    }

    @Test
    fun anOwnedSongsHeartMovesOntoItsLibrarySongWhenItArrives() = timed(30) {
        val owned = OwnedFinds()
        owned.note(listOf("find:s2"), starred = mapOf("find:s2" to true))
        db.dao().insertTracks(listOf(songRow("server:x:s2", "al1", null)))
        val likes = db.likes(owned)
        likes.ownedArrived("find:s2", "server:x:s2")
        assertTrue(likes.isLiked("server:x:s2"))
        // One the server did not star stays as it was.
        owned.note(listOf("find:s3"), starred = mapOf("find:s3" to false))
        db.dao().insertTracks(listOf(songRow("server:x:s3", "al1", null)))
        likes.ownedArrived("find:s3", "server:x:s3")
        assertFalse(likes.isLiked("server:x:s3"))
    }

    @Test
    fun theCountMovesOnEveryLikeAndUnlike() = timed(30) {
        val likes = db.likes()
        val counts = Channel<Long>(Channel.UNLIMITED)
        coroutineScope {
            val collecting = launch { likes.changes.collect { counts.send(it) } }
            assertEquals(1L, withTimeout(5_000) { counts.receive() })
            like(1)
            assertEquals(2L, withTimeout(5_000) { counts.receive() })
            like(2)
            assertEquals(3L, withTimeout(5_000) { counts.receive() })
            db.userDao().unlike("device:s1")
            assertEquals(4L, withTimeout(5_000) { counts.receive() })
            like(1, at = 9)
            assertEquals(5L, withTimeout(5_000) { counts.receive() })
            collecting.cancel()
        }
    }

    @Test
    fun theLikedCoversAreReadAPageAtATime() = timed(30) {
        // 3,000 liked songs, each liked before the one before it; covers
        // only on the six liked first, so the walk goes to the end.
        val songs = (0 until 3_000).map { songRow("device:s$it", "al${it % 50}", if (it >= 2_994) "art:$it" else null) }
        db.dao().insertTracks(songs)
        db.userDao().likeAll(songs.mapIndexed { i, s -> LikedTrackEntity(s.id, s.relinkKey, likedAt = 10_000L - i) })
        // A like of a song the catalog no longer has is left out.
        db.userDao().like(LikedTrackEntity("device:gone", "", 1))
        val likes = db.likes()
        db.counted.reset()
        val covers = likes.likedCovers()
        assertEquals(mosaicCovers(db.userDao().likedTracks().first().map { it.albumId to it.artwork }), covers)
        assertEquals(listOf("art:2994", "art:2995", "art:2996", "art:2997"), covers)
        // Not counted above: likedTracks is today's whole read, the measure.
        db.counted.reset()
        likes.likedCovers()
        assertTrue("${db.counted.maxRead}", db.counted.maxRead!!.rowsRead <= 100)
    }
}
