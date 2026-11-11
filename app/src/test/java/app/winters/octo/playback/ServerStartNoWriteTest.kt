package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.winters.octo.catalog.SAVE_GROUP_ENTRIES
import app.winters.octo.catalog.SAVE_GROUP_FLOOR
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.discovery.SERVER_SOURCE
import app.winters.octo.discovery.id
import app.winters.octo.discovery.libraryTrack
import app.winters.octo.discovery.putLibrary
import app.winters.octo.discovery.resolveEachRead
import app.winters.octo.discovery.serverReads
import app.winters.octo.subsonic.Song
import app.winters.octo.testing.counted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

// The songs and lists every route here plays: 2,100 server songs, every
// other one in the library and linked to its copy, one the server marks
// outside the library, one only found.
internal val routeSongs: Map<String, Song> = (0 until 2_100).associate { "srv$it" to Song("srv$it", title = "Title ${wordOf(it)}", artist = "Artist ${it % 7}") } +
    mapOf("ext" to Song("ext", title = "Title ${wordOf(4)}", artist = "Artist 4", isExternal = true), "found" to Song("found", title = "Found one", artist = "Nobody"))

internal fun ServerStartRig.routeLibrary() =
    db.putLibrary((0 until 2_100 step 2).map { libraryTrack("t$it", "Title ${wordOf(it)}", "Artist ${it % 7}") }) { "srv${it.id.removePrefix("t")}" }

internal fun ServerStartRig.routeLists() {
    server.albums["A"] = "Album" to (listOf("srv0", "ext", "found") + (1 until 50).map { "srv$it" })
    server.lists["L"] = (0 until 700).map { "srv$it" } + "found"
    server.lists["S"] = (0 until 700).map { "srv$it" }
    server.lists["D"] = listOf("srv0", "srv1", "srv0", "srv2", "srv1", "found", "found")
    server.albums["B"] = "Album B" to (0 until 900).map { "srv$it" }
    server.queue = (0 until 600).map { "srv$it" }
    server.current = "srv10"
    server.position = 12_000
    server.folders["f"] = listOf("g") to listOf("srv0", "srv1")
    server.folders["g"] = emptyList<String>() to (2 until 40).map { "srv$it" }
    server.folders["big"] = emptyList<String>() to (0 until 2_001).map { "srv${it % 2_100}" }
}

// The renames a save's group publishes: t0, the first song of the lists
// here, is now t0-new, which stands beside it, as during a run's pages.
internal fun ServerStartRig.routeRenames() {
    db.putLibrary(listOf(libraryTrack("t0-new", "Title ${wordOf(0)}", "Artist 0")), serverId = { null })
    rename("t0", "t0-new")
}

// Every route that plays a list the server answers for gets its first song
// to the player from reads alone: no write statement and no transaction
// begun, of any kind, before the start hands over, with a save's renames in
// force too. Each route runs on a library of its own, so nothing another
// play left running is counted.
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(UnstableApi::class)
class ServerStartNoWriteTest(private val route: String, private val withRenames: Boolean) {
    companion object {
        private val routes = listOf(
            "album", "list", "station", "repeats", "libraryOnly", "queue", "folder", "cut", "tapped",
            "carList", "carQueue", "spoken", "playNext", "addToQueue",
        )

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0} renames={1}")
        fun cases(): List<Array<Any>> = routes.flatMap { listOf(arrayOf<Any>(it, false), arrayOf<Any>(it, true)) }
    }

    private val rig = ServerStartRig(routeSongs)

    @After
    fun close() = rig.close()

    private fun token(): Long = runBlocking { rig.store.claimList() }

    private fun play(spec: ListSpec, shuffle: Boolean = false, shownOverWindow: Boolean = false, start: ListStart? = null): PlayAnswer {
        var answer: PlayAnswer? = null
        rig.counted { answer = rig.play(PlayRequest(spec, start, shuffle, shownOverWindow = shownOverWindow, token = token())) }.assertNoWrite("$route renames=$withRenames")
        assertTrue("$spec did not start: $answer", answer!!.started)
        return answer!!
    }

    @Test
    fun theStartHandsOverFromReads() {
        rig.routeLibrary()
        rig.routeLists()
        if (withRenames) rig.routeRenames()
        val first = if (withRenames) "t0-new" else "t0"
        when (route) {
            // An online album, whole: its find and its outside-the-library
            // entry play from reads, its first song through the renames.
            "album" -> {
                play(ListSpec.Online(OnlineKind.Album, "A"))
                assertEquals(first, rig.current())
                assertTrue("the album's find plays", rig.held().contains("find:found"))
            }
            // An Octo list too big to play whole: its first song.
            "list" -> {
                play(ListSpec.Online(OnlineKind.List, "L"))
                assertEquals(first, rig.current())
            }
            "station" -> play(ListSpec.Online(OnlineKind.Station, "S"), shuffle = true)
            // A list of repeats, each song once.
            "repeats" -> {
                play(ListSpec.Online(OnlineKind.List, "D"))
                assertEquals(listOf(first, "find:srv1", "t2", "find:found"), rig.held())
            }
            // An album keeping library songs alone, its page knowing it is big.
            "libraryOnly" -> {
                play(ListSpec.Online(OnlineKind.Album, "B", libraryOnly = true), shownOverWindow = true)
                assertEquals(first, rig.current())
            }
            // The server's play queue (Home's Resume card), at its song.
            "queue" -> {
                play(ListSpec.ServerQueue)
                assertEquals("t10", rig.current())
            }
            // A server folder and everything under it, after its walk.
            "folder" -> {
                play(ListSpec.ServerFolder("f"))
                assertEquals("t2", rig.current())
            }
            // One cut at its limit.
            "cut" -> assertTrue("the folder stopped at its limit", play(ListSpec.ServerFolder("big")).cut)
            // A song tapped on a page that no longer holds its read, by the
            // id the page had, which plays as the renames name it.
            "tapped" -> {
                play(ListSpec.Online(OnlineKind.List, "L"), start = ListStart.Row("t0", null))
                assertEquals(first, rig.current())
            }
            // The car's browse and its resume, which Media3 sets.
            "carList" -> rig.counted { assertTrue(rig.playForMedia3(PlayRequest(ListSpec.Online(OnlineKind.List, "L"))) is CarStarted.Items) }.assertNoWrite(route)
            "carQueue" -> rig.counted { assertTrue(rig.playForMedia3(PlayRequest(ListSpec.ServerQueue)) is CarStarted.Items) }.assertNoWrite(route)
            // A spoken request's songs, played by id as the car plays them.
            "spoken" -> {
                var started: CarStarted? = null
                rig.counted { started = rig.playForMedia3(PlayRequest(ListSpec.Ids(listOf("t0", "t2")))) }.assertNoWrite(route)
                assertEquals(listOf(first, "t2"), (started as CarStarted.Items).items.map { it.mediaId })
            }
            // Play next and Add to queue, whole and big, after a play.
            "playNext", "addToQueue" -> {
                play(ListSpec.Online(OnlineKind.Album, "A"))
                rig.settle()
                val spec = if (route == "playNext") ListSpec.Online(OnlineKind.Album, "A") else ListSpec.Online(OnlineKind.List, "L")
                rig.counted { assertTrue(rig.add(spec, next = route == "playNext") != null) }.assertNoWrite(route)
            }
        }
        rig.joined()
    }
}

// A list's start while a real save is parked inside a page of a run, with
// renames of earlier pages committed: it hands over from reads alone, with
// no write and no transaction begun before, while the parked page holds its
// transaction, and its songs are on the ids they are now. Once the save
// ends, the queue's rows are on library songs. At the floor bound and the
// default (a whole start and a big one).
@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(UnstableApi::class)
class ServerStartDuringSaveTest {
    private val writes = Regex("^\\s*(INSERT|UPDATE|DELETE|REPLACE)\\b", RegexOption.IGNORE_CASE)

    @Test
    fun aStartDuringAParkedSaveHandsOverFromReadsOnTheIdsNow() {
        for (bound in listOf(SAVE_GROUP_FLOOR, SAVE_GROUP_ENTRIES)) {
            ParkedSave(bound).use { save ->
                val rig = save.rig
                val id = runBlocking { rig.store.claimList() }
                save.park()
                val (native, old, new) = save.renamedSong()
                rig.server.lists["L"] = listOf(native) + (0 until save.songs).map { "s$it" }.filter { it != native }
                synchronized(rig.handed) { rig.handed.clear() }
                rig.db.counted.reset()
                val from = rig.db.counted.mark()
                rig.db.counted.recording = true
                val answer = try {
                    rig.playWhileParked(PlayRequest(ListSpec.Online(OnlineKind.List, "L"), token = id))
                } finally {
                    rig.db.counted.recording = false
                }
                assertTrue("bound $bound: the list did not start while the save was parked: $answer", answer.started)
                val mark = synchronized(rig.handed) { rig.handed[id] } ?: throw AssertionError("bound $bound: the start never handed over")
                val before = rig.db.counted.statements.filter { it.seq in (from + 1)..mark && !it.bookkeeping }
                val begun = rig.db.counted.begins.filter { it.beginSeq in (from + 1)..mark && (it.statements > 0 || it.rowsChanged > 0) }
                println("RESULT m-route list bound=$bound turn=${save.turn}: ${before.count { writes.containsMatchIn(it.sql) }} write statements, ${begun.size} transactions begun, ${before.size} statements before the hand-off")
                assertTrue("bound $bound: the start recorded nothing", before.isNotEmpty())
                assertTrue("bound $bound: wrote before handing over: $before", before.none { writes.containsMatchIn(it.sql) })
                assertTrue("bound $bound: began a transaction before handing over: $begun", begun.isEmpty())
                // The renamed song plays as the id it is now; no song held is
                // an old id with a rename in force, and each is a library song.
                assertEquals("bound $bound: $old played as", new, rig.current())
                val held = rig.held()
                assertTrue("bound $bound: an old id held: $held", held.none { it in save.renames })
                assertTrue("bound $bound: a held id names no library song: $held", held.all(save::inLibrary))
                val groups = save.release()
                rig.joined()
                assertTrue("bound $bound: $groups groups", groups >= 2)
                assertTrue("bound $bound: no run was parked", save.turn >= 2 && save.hooks.inRun(save.turn - 1))
                val songs = rig.songs()
                assertEquals("bound $bound: the queue holds the list", save.songs, songs.size)
                assertTrue("bound $bound: a queue row names no library song", songs.all(save::inLibrary))
            }
        }
    }
}

// A refusal writes nothing; a held write lock keeps no start waiting; a
// search's resolve writes nothing and keeps its finds behind.
@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(UnstableApi::class)
class ServerStartNoWaitTest {
    private val rig = ServerStartRig(routeSongs)

    @After
    fun close() = rig.close()

    private fun token(): Long = runBlocking { rig.store.claimList() }

    private val writes = Regex("^\\s*(INSERT|UPDATE|DELETE|REPLACE)\\b", RegexOption.IGNORE_CASE)

    // An empty list, one the server no longer has, and no server signed in.
    @Test
    fun aRefusalWritesNothing() {
        rig.routeLibrary()
        rig.server.lists["E"] = emptyList()
        rig.server.gone += "G"
        rig.settle()
        for (spec in listOf(ListSpec.Online(OnlineKind.List, "E"), ListSpec.Online(OnlineKind.List, "G"))) {
            val (answer, ran) = rig.db.counted.record { rig.play(PlayRequest(spec, token = token())) }
            assertTrue(!answer.started)
            assertTrue("$spec wrote: $ran", ran.none { !it.bookkeeping && writes.containsMatchIn(it.sql) })
            assertTrue(rig.db.counted.begins.none { it.statements > 0 })
        }
        val signedOut = QueueStarts(rig.db, { error("no loaders") }, { error("no live") }, { error("no health") }, WorkOwners(), { rig.db.serverReads(null) })
        val (read, ran) = rig.db.counted.record { runBlocking { signedOut.read(ListSpec.Online(OnlineKind.List, "E"), null, false, false, null, 99L) } }
        assertEquals(StartRead.Refused(SIGN_IN_TO_PLAY), read)
        assertTrue(ran.none { !it.bookkeeping && writes.containsMatchIn(it.sql) })
        assertEquals(emptyList<String>(), rig.files())
    }

    // While another writer holds the lock, every kind of start still hands
    // over and plays, with nothing written before; what it posted lands
    // once the lock goes.
    @Test
    fun aHeldWriteLockKeepsNoStartWaiting() {
        rig.routeLibrary()
        rig.routeLists()
        rig.routeRenames()
        val plays = listOf(
            ListSpec.Online(OnlineKind.Album, "A") to false,
            ListSpec.Online(OnlineKind.List, "L") to false,
            ListSpec.Online(OnlineKind.Station, "S") to true,
            ListSpec.ServerQueue to false,
            ListSpec.ServerFolder("f") to false,
        )
        for ((spec, shuffle) in plays) {
            rig.settle()
            val id = token()
            synchronized(rig.handed) { rig.handed.clear() }
            ParkedWrite(rig.db).use {
                rig.db.counted.reset()
                val from = rig.db.counted.mark()
                rig.db.counted.recording = true
                val answer = try {
                    rig.playWhileParked(PlayRequest(spec, null, shuffle, token = id))
                } finally {
                    rig.db.counted.recording = false
                }
                assertTrue("$spec did not start under a held lock: $answer", answer.started)
                val mark = synchronized(rig.handed) { rig.handed[id] } ?: throw AssertionError("$spec never handed over")
                val before = rig.db.counted.statements.filter { it.seq in (from + 1)..mark && !it.bookkeeping }
                assertTrue("$spec wrote before handing over: $before", before.none { writes.containsMatchIn(it.sql) })
                assertTrue(rig.current() != null)
            }
            rig.joined()
            assertTrue("$spec's rows landed once the lock went", rig.songs().isNotEmpty())
        }
    }

    // A spoken request, a search and an artist's top songs resolve what the
    // server sent with no write; their finds are held, and kept behind.
    @Test
    fun aSearchResolvesWithNoWriteAndKeepsItsFindsBehind() {
        rig.routeLibrary()
        rig.settle()
        // The map's writer waits at a gate, so the keep it was posted runs
        // only once the answer is read.
        val gate = CountDownLatch(1)
        val writer = Executors.newSingleThreadExecutor()
        writer.execute { gate.await() }
        val finds = FindMap(rig.db.onlineDao(), RealLengths(), CoroutineScope(writer.asCoroutineDispatcher()))
        try {
            val sent = listOf(routeSongs.getValue("srv0"), routeSongs.getValue("found"), routeSongs.getValue("srv3"))
            val (resolved, ran) = rig.db.counted.record {
                runBlocking { resolveEachRead(rig.db.sourceDao(), rig.db.dao(), rig.db.onlineDao(), finds, SERVER_SOURCE, sent) }
            }
            assertTrue("the resolve wrote: $ran", ran.none { !it.bookkeeping && writes.containsMatchIn(it.sql) })
            assertEquals(listOf("t0", "find:found", "find:srv3"), resolved.map { it.id })
            assertTrue("the finds are held", finds.holds("find:found") && finds.holds("find:srv3"))
            assertEquals(0, rig.count("SELECT COUNT(*) FROM online_song"))
            // Its keep runs behind the answer.
            gate.countDown()
            rig.until("the finds are kept") { rig.count("SELECT COUNT(*) FROM online_song") == 2 && !finds.holds("find:found") }
        } finally {
            gate.countDown()
            writer.shutdown()
        }
    }
}
