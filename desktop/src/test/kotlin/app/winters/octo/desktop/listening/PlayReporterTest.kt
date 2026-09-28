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
    // answers on its own thread.
    // Returns as soon as `what` holds; the bound is generous so a machine
    // busy with other builds doesn't fail the test.
    private fun waitFor(what: () -> Boolean) {
        val until = System.currentTimeMillis() + 30_000
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
}
