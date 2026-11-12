package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.winters.octo.discovery.FindLengths
import app.winters.octo.discovery.adoptFind
import app.winters.octo.discovery.libraryTrack
import app.winters.octo.discovery.markRequested
import app.winters.octo.discovery.putLibrary
import app.winters.octo.subsonic.Song
import app.winters.octo.testing.counted
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

// A find a start plays has no row until the queue's first rows keep it.
// With that keep parked, the find plays, shows and is acted on from the
// find map; a download asked for, a length the player learned and an
// adoption each wait for the keep and land on its row, the length too. A
// keep that fails keeps its finds while their queue plays, keeps them
// again with the next queue write, and lets them go once a new queue
// replaces theirs. Every reader of finds reads the map.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(UnstableApi::class)
class ServerStartFindRowTest {
    private val songs = (0 until 40).associate { "s$it" to Song("s$it", title = "Song ${wordOf(it)}", artist = "Artist") } +
        mapOf("x" to Song("x", title = "Found ${wordOf(1)}", artist = "Nobody"), "y" to Song("y", title = "Found ${wordOf(2)}", artist = "Nobody"))
    private val rig = ServerStartRig(songs)

    @After
    fun close() = rig.close()

    private fun setUp() {
        rig.db.putLibrary((0 until 40).map { libraryTrack("t$it", "Song ${wordOf(it)}", "Artist") }) { "s${it.id.removePrefix("t")}" }
        rig.db.putLibrary(listOf(libraryTrack("became", "Found ${wordOf(1)}", "Someone")), serverId = { null })
        rig.server.albums["A"] = "Album" to (listOf("x", "s0", "y") + (1 until 10).map { "s$it" })
    }

    private fun row(id: String, column: String): Long? =
        rig.db.query("SELECT $column FROM online_song WHERE id = ?", arrayOf(id)).use { if (it.moveToFirst()) it.getLong(0) else null }

    private fun holdWorker(): CompletableDeferred<Unit> {
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        rig.store.post {
            held.complete(Unit)
            release.await()
        }
        runBlocking { withTimeout(10_000) { held.await() } }
        return release
    }

    @Test
    fun writersWaitForTheKeepAndLandOnItsRow() {
        setUp()
        val release = holdWorker()
        assertTrue(rig.playWhileParked(PlayRequest(ListSpec.Online(OnlineKind.Album, "A"), token = runBlocking { rig.store.claimList() })).started)
        // The find plays and shows from the map, with no row yet.
        assertEquals("find:x", rig.current())
        assertEquals("Found ${wordOf(1)}", rig.finds["find:x"]!!.title)
        assertEquals(null, row("find:x", "seenAt"))
        // Each write by id waits for the keep: one that did not would find
        // no row, and the keep would then write the row without it.
        val stored = ArrayList<Int>()
        val lengths = FindLengths({ id, ms -> rig.db.onlineDao().setLength(id, ms).also { synchronized(stored) { stored += it } } }, rig.real, rig.finds::awaitKept)
        runBlocking {
            val asked = async(Dispatchers.IO) { markRequested(rig.db.onlineDao(), rig.finds, listOf("find:x"), 77) }
            val learned = async(Dispatchers.IO) { lengths.learn("find:y", listedMs = 0, playerMs = 215_000) }
            val adopted = async(Dispatchers.IO) { adoptFind(rig.db.onlineDao(), rig.finds, "find:x", "became") }
            // The keep goes only once all three wait for it.
            val end = System.nanoTime() + 15_000_000_000L
            while (rig.finds.waiting("find:x") < 2 || rig.finds.waiting("find:y") < 1) {
                if (System.nanoTime() > end) {
                    release.complete(Unit)
                    throw AssertionError("a write did not wait for the keep: x ${rig.finds.waiting("find:x")}, y ${rig.finds.waiting("find:y")}")
                }
                Thread.sleep(5)
            }
            release.complete(Unit)
            withTimeout(10_000) {
                asked.await()
                learned.await()
                adopted.await()
            }
        }
        rig.settle()
        assertEquals(77L, row("find:x", "requestedAt"))
        // The length learned meanwhile is on the row, and was stored there.
        assertEquals(215_000L, row("find:y", "durationMs"))
        assertEquals(listOf(1), stored)
        assertEquals("became", rig.db.query("SELECT adoptedId FROM online_song WHERE id = 'find:x'", null).use { it.moveToFirst(); it.getString(0) })
        assertTrue("the map let the kept finds go", !rig.finds.holds("find:x") && !rig.finds.holds("find:y"))
    }

    @Test
    fun aFailedKeepKeepsItsFindsUntilTheNextQueueWriteOrANewQueue() {
        setUp()
        rig.db.counted.fullDisk = { it.contains("online_song") && it.trimStart().startsWith("INSERT") }
        assertTrue(rig.play(PlayRequest(ListSpec.Online(OnlineKind.Album, "A"), token = runBlocking { rig.store.claimList() })).started)
        rig.settle()
        assertTrue("a failed keep keeps its finds", rig.finds.holds("find:x"))
        assertEquals(null, row("find:x", "seenAt"))
        assertEquals("find:x", rig.current())
        // Room again: the next queue write keeps them.
        rig.db.counted.fullDisk = null
        rig.window.removeEntries(listOf(entryOfKey(rig.player.getMediaItemAt(rig.player.mediaItemCount - 1).entryId)!!))
        rig.settle()
        assertNotNull("the next queue write kept the find", row("find:x", "seenAt"))
        assertTrue(!rig.finds.holds("find:x"))
        // Failed again, then a new queue in place of theirs lets them go.
        rig.db.counted.fullDisk = { it.contains("online_song") && it.trimStart().startsWith("INSERT") }
        rig.server.albums["B"] = "Album B" to listOf("y", "s3")
        rig.db.openHelper.writableDatabase.execSQL("DELETE FROM online_song")
        assertTrue(rig.play(PlayRequest(ListSpec.Online(OnlineKind.Album, "B"), token = runBlocking { rig.store.claimList() })).started)
        rig.settle()
        assertTrue(rig.finds.holds("find:y"))
        // Still no room as the new queue comes, so only its coming lets go.
        rig.server.albums["C"] = "Album C" to listOf("s4", "s5")
        assertTrue(rig.play(PlayRequest(ListSpec.Online(OnlineKind.Album, "C"), token = runBlocking { rig.store.claimList() })).started)
        rig.settle()
        assertTrue("a new queue lets the failed finds go", !rig.finds.holds("find:y"))
        rig.db.counted.fullDisk = null
    }

    // A length stored on no row (none was there) is not taken as stored:
    // once the row is there, the same length is stored again.
    @Test
    fun aLengthCountsAsStoredOnlyOnARow() = runBlocking {
        val rows = HashMap<String, Long>()
        val lengths = FindLengths({ id, ms -> if (id in rows) 1.also { rows[id] = ms } else 0 }, RealLengths())
        lengths.learn("find:z", listedMs = 0, playerMs = 215_000)
        rows["find:z"] = 0
        lengths.learn("find:z", listedMs = 0, playerMs = 215_000)
        assertEquals(215_000L, rows["find:z"])
    }

    // Each reader of finds reads the map before the row is there.
    @Test
    fun everyReaderOfFindsReadsTheMap() {
        val readers = mapOf(
            "playback/PlayableSongs.kt" to "held[it]",
            "player/PlayerViewModel.kt" to "finds[id]",
            "ui/menu/SongMenu.kt" to "finds[id]",
            "discovery/Downloads.kt" to "finds[findId]",
            "ui/menu/SongInfo.kt" to "finds[trackId]",
            "animated/AnimatedArts.kt" to "finds[trackId]",
            "listening/ListenBrainzSync.kt" to "finds[trackId]",
            "playback/Autoplay.kt" to "finds?.get(id)",
            "playback/CarLibrary.kt" to "finds[id]",
            "playback/OctoPlaybackService.kt" to "finds[id]",
        )
        for ((file, read) in readers) {
            val text = File("src/main/java/app/winters/octo/$file").readText()
            assertTrue("$file reads the find map", read in text)
        }
    }
}
