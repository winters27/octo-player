package app.winters.octo.desktop.listening

import app.winters.octo.desktop.FakeServer
import app.winters.octo.desktop.player.SilentPlayer
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.listening.PendingPlay
import app.winters.octo.subsonic.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class PlayReporterTest {
    @get:Rule val temp = TemporaryFolder()

    private val server = FakeServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var now = 0L
    private val player = SilentPlayer(clock = { now })
    private val songs = listOf(Song("s1", "One", duration = 100), Song("s2", "Two", duration = 100), Song("s3", "Three", duration = 100))

    @After
    fun stop() {
        scope.cancel()
        server.close()
    }

    private fun settings() = SettingsStore(File(temp.root, "settings.json"), 0)

    private fun reporter(settings: SettingsStore = settings()): PlayReporter {
        val connection = server.connection()
        return PlayReporter(player, settings, { connection }, temp.root, scope, io = Dispatchers.Unconfined, clock = { now }, wallClock = { 5_000 + now })
    }

    private fun scrobbles(submission: Boolean) =
        server.calls.filter { it.url.pathSegments.last() == "scrobble" && it.url.queryParameter("submission") == "$submission" }

    // Waits a little for the fake server to hear a call, since the client
    // answers on its own thread. Returns as soon as `what` holds.
    private fun waitFor(what: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!what() && System.currentTimeMillis() < until) Thread.sleep(20)
    }

    private fun folder() = listeningFolder(temp.root, "winters", server.address)

    @Test
    fun theSongPlayingIsAnnouncedAndAPlayThatCountsIsSent() {
        server.answer("scrobble")
        reporter().start()
        player.play(songs)
        waitFor { scrobbles(false).isNotEmpty() }
        assertEquals("s1", scrobbles(false).single().url.queryParameter("id"))
        now += 60_000
        player.next()
        waitFor { scrobbles(true).isNotEmpty() }
        val sent = scrobbles(true).single().url
        assertEquals("s1", sent.queryParameter("id"))
        assertEquals("5000", sent.queryParameter("time"))
        waitFor { PendingPlays(File(folder(), "pending.txt")).all().isEmpty() }
        assertEquals(emptyList<PendingPlay>(), PendingPlays(File(folder(), "pending.txt")).all())
        assertEquals(listOf("s1"), PlayLog(File(folder(), "plays.jsonl")).read().map { it.song.id })
    }

    @Test
    fun aPlayTheServerCannotTakeWaitsForNextTime() {
        server.fail("scrobble", 0, "busy")
        val reporter = reporter()
        reporter.start()
        player.play(songs)
        now += 60_000
        player.next()
        waitFor { scrobbles(true).isNotEmpty() }
        // A plain failure is the server refusing that play: it is let go.
        waitFor { PendingPlays(File(folder(), "pending.txt")).all().isEmpty() }
        assertEquals(emptyList<PendingPlay>(), PendingPlays(File(folder(), "pending.txt")).all())

        // Wrong credentials might be fixed by signing in again: it waits.
        server.fail("scrobble", 40, "Wrong username or password")
        now += 60_000
        player.next()
        waitFor { scrobbles(true).size >= 2 }
        Thread.sleep(100)
        assertEquals(listOf("s2"), PendingPlays(File(folder(), "pending.txt")).all().map { it.serverId })

        server.answer("scrobble")
        reporter.signedIn()
        waitFor { PendingPlays(File(folder(), "pending.txt")).all().isEmpty() }
        assertEquals(emptyList<PendingPlay>(), PendingPlays(File(folder(), "pending.txt")).all())
    }

    // Something else may have the waiting file open just as a sent play is
    // taken off it: a virus scanner, a backup, a look at it from elsewhere.
    // The play still leaves the file, and is not sent a second time.
    @Test
    fun aSentPlayLeavesTheWaitingFileWhileSomethingHasItOpen() {
        val file = File(folder(), "pending.txt")
        val readers = CopyOnWriteArrayList<Thread>()
        server.answerBy("scrobble") { request ->
            if (request.url.queryParameter("submission") == "true") {
                // Opened the way java.io opens a file, held past the answer.
                val open = FileInputStream(file)
                readers += thread {
                    Thread.sleep(300)
                    open.close()
                }
            }
            server.ok()
        }
        reporter().start()
        player.play(songs)
        now += 60_000
        player.next()
        waitFor { scrobbles(true).isNotEmpty() && PendingPlays(file).all().isEmpty() }
        readers.forEach(Thread::join)
        assertEquals(emptyList<PendingPlay>(), PendingPlays(file).all())
        assertEquals(1, scrobbles(true).size)
    }

    // A waiting file that cannot be saved for longer than the reporter
    // waits: the sent play stays in it for now, and next time it is taken
    // off without being sent again.
    @Test
    fun aSentPlayThatCannotBeTakenOffYetIsNotSentAgain() {
        val file = File(folder(), "pending.txt")
        val readers = CopyOnWriteArrayList<Thread>()
        server.answerBy("scrobble") { request ->
            if (request.url.queryParameter("submission") == "true" && readers.isEmpty()) {
                // A folder where the save writes first: nothing can be saved.
                val blocker = File(folder(), "pending.txt.tmp").apply { mkdirs() }
                readers += thread {
                    Thread.sleep(3_000)
                    blocker.delete()
                }
            }
            server.ok()
        }
        val reporter = reporter()
        reporter.start()
        player.play(songs)
        now += 60_000
        player.next()
        waitFor { readers.isNotEmpty() }
        readers.forEach(Thread::join)
        assertEquals(listOf("s1"), PendingPlays(file).all().map { it.serverId })
        reporter.signedIn()
        waitFor { PendingPlays(file).all().isEmpty() }
        assertEquals(emptyList<PendingPlay>(), PendingPlays(file).all())
        Thread.sleep(100)
        assertEquals(1, scrobbles(true).size)
    }

    // Something keeping the file open for longer than the wait keeps it from
    // being moved over; the play is kept beside it, and found there.
    @Test
    fun aPlayGoesInWhileSomethingKeepsTheFileOpen() {
        val file = File(temp.root, "pending.txt")
        val plays = PendingPlays(file)
        plays.add(PendingPlay("s1", 1))
        val open = FileInputStream(file)
        val closing = thread {
            Thread.sleep(2_500)
            open.close()
        }
        plays.add(PendingPlay("s2", 2))
        closing.join()
        assertEquals(listOf(PendingPlay("s1", 1), PendingPlay("s2", 2)), plays.all())
    }

    // Windows may refuse to open the file for a moment while it is being
    // removed; a read then waits rather than fails.
    @Test
    fun theWaitingFileCanBeReadWhilePlaysComeAndGo() {
        val file = File(temp.root, "pending.txt")
        val plays = PendingPlays(file)
        val going = AtomicBoolean(true)
        val failures = CopyOnWriteArrayList<Throwable>()
        val reader = thread {
            while (going.get()) runCatching { PendingPlays(file).all() }.onFailure { failures += it }
        }
        try {
            repeat(200) { i ->
                val play = PendingPlay("s$i", i.toLong())
                plays.add(play)
                plays.remove(play)
            }
        } finally {
            going.set(false)
            reader.join()
        }
        assertEquals(emptyList<Throwable>(), failures)
        assertEquals(emptyList<PendingPlay>(), plays.all())
    }

    // The same, many times over, with the file read over and over the whole
    // time: each play is sent once and none is left waiting.
    @Test
    fun manyPlaysSentWhileTheFileIsReadAreEachSentOnceAndCleared() {
        server.answer("scrobble")
        reporter().start()
        val file = File(folder(), "pending.txt")
        val reading = AtomicBoolean(true)
        val reader = thread {
            while (reading.get()) {
                runCatching { FileInputStream(file).use { it.readBytes() } }
                Thread.sleep(1)
            }
        }
        val rounds = 20
        try {
            repeat(rounds) { round ->
                player.play(songs)
                now += 60_000
                player.next()
                val until = System.currentTimeMillis() + 3_000
                while ((scrobbles(true).size <= round || PendingPlays(file).all().isNotEmpty()) && System.currentTimeMillis() < until) Thread.sleep(1)
                assertEquals("round $round", emptyList<PendingPlay>(), PendingPlays(file).all())
            }
        } finally {
            reading.set(false)
            reader.join()
        }
        val times = scrobbles(true).map { it.url.queryParameter("time") }
        assertEquals(rounds, times.size)
        assertEquals(rounds, times.toSet().size)
    }

    @Test
    fun withReportingOffPlaysStayOnThisComputer() {
        server.answer("scrobble")
        val settings = settings()
        settings.update { it.copy(listening = it.listening.copy(reportPlays = false)) }
        reporter(settings).start()
        player.play(songs)
        now += 60_000
        player.next()
        Thread.sleep(200)
        assertTrue(scrobbles(false).isEmpty() && scrobbles(true).isEmpty())
        assertEquals(listOf("s1"), PlayLog(File(folder(), "plays.jsonl")).read().map { it.song.id })
    }

    @Test
    fun quittingCountsTheSongPlayingAtOnce() {
        server.answer("scrobble")
        val reporter = reporter()
        reporter.start()
        player.play(songs)
        now += 70_000
        reporter.flush()
        assertEquals(listOf("s1"), PlayLog(File(folder(), "plays.jsonl")).read().map { it.song.id })
    }

    @Test
    fun eachAccountKeepsItsOwnFolder() {
        assertNotEquals(listeningFolder(temp.root, "a", "https://x/"), listeningFolder(temp.root, "b", "https://x/"))
        assertEquals(listeningFolder(temp.root, "a", "https://x/"), listeningFolder(temp.root, "a", "https://x/"))
    }

    @Test
    fun theLogKeepsTheNewestAndReadsNewestFirst() {
        val log = PlayLog(File(temp.root, "plays.jsonl"), max = 8)
        (1..20).forEach { log.add(LoggedPlay(it.toLong(), 1, LoggedSong("s$it", "Song $it"))) }
        val read = log.read()
        assertTrue("cut back, but never below the most kept", read.size in 8..10)
        assertEquals("s20", read.first().song.id)
    }

    // Each time Octo opens, the log starts out long: it is still cut back.
    @Test
    fun theLogStaysBoundedAcrossRestarts() {
        val file = File(temp.root, "plays.jsonl")
        (1..50).forEach { PlayLog(file, max = 8).add(LoggedPlay(it.toLong(), 1, LoggedSong("s$it", "Song $it"))) }
        val read = PlayLog(file, max = 8).read()
        assertTrue("${read.size} plays kept", read.size in 8..10)
        assertEquals("s50", read.first().song.id)
    }

    // Plays counted through the reporter keep the log bounded as designed.
    @Test
    fun theReportersLogIsCutBack() {
        server.answer("scrobble")
        val connection = server.connection()
        val reporter = PlayReporter(player, settings(), { connection }, temp.root, scope, io = Dispatchers.Unconfined, clock = { now }, wallClock = { 5_000 + now }, logMax = 8)
        reporter.start()
        repeat(30) {
            player.play(songs)
            now += 60_000
            player.next()
        }
        waitFor { scrobbles(true).size >= 30 }
        val read = reporter.log()!!.read()
        assertTrue("${read.size} plays kept", read.size in 8..10)
        assertEquals(5_000L + 29 * 60_000, read.first().at)
    }
}
