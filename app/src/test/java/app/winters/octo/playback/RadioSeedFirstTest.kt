package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.winters.octo.catalog.AlbumEntity
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.listening.Gate
import app.winters.octo.listening.NOW
import app.winters.octo.listening.PlayedIn
import app.winters.octo.listening.TotalsHooks
import app.winters.octo.listening.TotalsRig
import app.winters.octo.listening.growAndSwap
import app.winters.octo.listening.mergedLibrary
import app.winters.octo.listening.playOn
import app.winters.octo.listening.timed
import app.winters.octo.testing.counted
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// The seed half: an album's or an artist's radio starts from its most
// played song as the stored totals have it, read at once even while the
// totals are not built through a save (a rebuild's apply runs), in one
// statement; with no
// stored play of its songs, from its first song (an album's first in its
// order, an artist's newest album with songs, first), and from nothing
// when it has no songs. Today it read every played song first, waiting for
// nothing but reading them all.
//
// The play half: a radio plays its seed at once, from reads, while the
// songs like it are drawn; they join after the seed as one list, the seed
// never twice. An album's radio draws its likeness from the album's first
// songs and leaves the album out; with nothing found the seed plays and the
// words say so.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(UnstableApi::class)
class RadioSeedFirstTest {
    private val db = openTestCatalog()
    private var queue: QueueRig? = null

    @After
    fun close() {
        queue?.close()
        db.close()
    }

    private fun ids(sql: String, vararg args: Any?): List<String> =
        db.query(sql, args).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    @Test
    fun afterASwapTheSeedIsTheStoredMostPlayedAtOnce() = timed(60) {
        val merge = db.mergedLibrary(40)
        val album = ids("SELECT albumId FROM track ORDER BY albumId LIMIT 1").single()
        val songs = ids("SELECT id FROM track WHERE albumId = ? ORDER BY albumOrder, id", album)
        val artist = ids("SELECT artistId FROM track WHERE id = ?", songs[3]).single()
        db.playOn(songs[3], NOW - 3_000)
        db.playOn(songs[3], NOW - 2_000)
        db.playOn(songs[1], NOW - 1_000)
        val rig = TotalsRig(db, merge).started()
        // A rebuild parked inside its apply, so the save that lands next is
        // not yet built through.
        val held = Gate()
        rig.totals.hooks = object : TotalsHooks() {
            override suspend fun applied() = held.hit()
        }
        db.playOn(songs[2], NOW - 500)
        rig.totals.rebuild()
        held.reached.await()
        db.growAndSwap(merge, 50)
        assertTrue("the totals are not built through the swap", rig.totals.builtThrough.value < merge.swapStamp.value)
        val seeds = RadioSeeds(db)
        db.counted.reset()
        assertEquals(songs[3], seeds.of(PlayedIn.Album(album))?.id)
        assertEquals(songs[3], seeds.of(PlayedIn.Artist(artist))?.id)
        assertTrue("${db.counted.maxRead}", db.counted.maxRead!!.rowsRead <= 1)
        held.open()
    }

    @Test
    fun withNoStoredPlayTheSeedIsTheFirstSong() = timed(30) {
        fun album(id: String, year: Int?, sortKey: String) = AlbumEntity(id, "device", id, sortKey, sortKey, sortKey, "X", "arX", year, 1, 1, 0, null)
        db.dao().insertAlbums(listOf(album("old", 1999, "old"), album("new", 2005, "newest"), album("mid", 2001, "mid"), album("solo", 2010, "solo")))
        db.putTracks(
            listOf(
                radioTrack("m2", albumId = "mid", artistId = "arX").copy(albumOrder = 2),
                radioTrack("m0", albumId = "mid", artistId = "arX").copy(albumOrder = 0),
                radioTrack("m1", albumId = "mid", artistId = "arX").copy(albumOrder = 1),
                radioTrack("o0", albumId = "old", artistId = "arX").copy(albumOrder = 0),
            ),
        )
        val seeds = RadioSeeds(db)
        assertEquals("m0", seeds.of(PlayedIn.Album("mid"))?.id)
        // The newest album has no songs; the next newest has.
        assertEquals("m0", seeds.of(PlayedIn.Artist("arX"))?.id)
        assertEquals("o0", seeds.of(PlayedIn.Album("old"))?.id)
        assertNull(seeds.of(PlayedIn.Album("new")))
        assertNull(seeds.of(PlayedIn.Artist("nobody")))
    }

    // A fixed seed, so every run keys the radio's songs alike. The app's own
    // source stays SecureRandom.
    private fun rig(): QueueRig = QueueRig(random = java.util.Random(5)).also { queue = it }

    @Test
    fun theSeedPlaysAtOnceAndThePoolJoinsAfterIt() {
        val rig = rig()
        rig.db.songs(60)
        val drawn = CompletableDeferred<Unit>()
        var seeds: List<TrackEntity> = emptyList()
        rig.builds.pool = { s, count, _, before ->
            seeds = s
            drawn.await()
            assertEquals(listOf("s5"), before)
            (10 until 10 + count.coerceAtMost(20)).map { "s$it" } + "s5"
        }
        rig.play(PlayRequest(ListSpec.Radio("s5", "s5 radio")))
        rig.settle()
        assertEquals(listOf("s5"), rig.held())
        assertTrue(rig.window.building.value.isNotEmpty())
        drawn.complete(Unit)
        rig.joined()
        assertEquals(listOf("s5") + (10 until 30).map { "s$it" }, rig.songs())
        assertEquals(listOf("s5"), seeds.map { it.id })
        rig.until("the picks are in the window") { rig.held() == rig.songs().take(1 + rig.sizes.ahead) }
    }

    @Test
    fun anAlbumsRadioLeavesTheAlbumOut() {
        val rig = rig()
        rig.db.songs(30, album = "a1")
        rig.db.songs(30, album = "a2", prefix = "o")
        var seedCount = 0
        rig.builds.pool = { s, _, excluded, _ ->
            seedCount = s.size
            val asked = (0 until 30).map { "s$it" } + (0 until 30).map { "o$it" }
            asked.filterNot { it in excluded(asked) }
        }
        rig.play(PlayRequest(ListSpec.Radio("s3", "a1 radio", RadioAround.Album("a1"))))
        rig.joined()
        assertEquals(listOf("s3") + (0 until 30).map { "o$it" }, rig.songs())
        assertEquals(30, seedCount)
    }

    @Test
    fun withNothingFoundTheSeedPlaysAndTheWordsSaySo() {
        val rig = rig()
        rig.db.songs(10)
        rig.builds.pool = { _, _, _, _ -> emptyList() }
        rig.play(PlayRequest(ListSpec.Radio("s1", "s1 radio")))
        rig.joined()
        assertEquals("s1", rig.current())
        assertEquals(listOf("s1"), rig.songs())
        rig.until("the words show") { "No similar songs found" in rig.words }
    }
}
