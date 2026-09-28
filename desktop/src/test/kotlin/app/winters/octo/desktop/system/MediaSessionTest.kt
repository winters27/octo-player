package app.winters.octo.desktop.system

import app.winters.octo.desktop.player.SilentPlayer
import app.winters.octo.subsonic.Song
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// Media controls that only write down what they were told.
class FakeControls(private val works: Boolean = true) : SystemMediaControls {
    override val label = "Fake"
    val calls = ArrayList<String>()
    var events: ((SystemEvent) -> Unit)? = null
    var closed = false

    override fun start(events: (SystemEvent) -> Unit): Boolean {
        this.events = events
        return works
    }

    override fun showTrack(now: NowPlaying, art: CoverArt?) {
        calls += "track ${now.title}" + if (art != null) " with cover" else ""
    }

    override fun showPlayback(now: NowPlaying, positionMs: Long, jumped: Boolean) {
        calls += "playback ${if (now.playing) "playing" else "paused"} at ${positionMs / 1000}" + if (jumped) " jumped" else ""
    }

    override fun clear() {
        calls += "clear"
    }

    override fun showVolume(volume: Float) {
        calls += "volume $volume"
    }

    override fun close() {
        closed = true
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MediaSessionTest {
    private var clock = 0L
    private val songs = (1..3).map { Song("s$it", "Song $it", artist = "Artist", duration = 100, coverArt = "c$it") }
    private val covers = CoverSource { id -> CoverArt(byteArrayOf(1, 2, 3), File("$id.img")) }

    private fun TestScope.session(controls: FakeControls, others: (SystemEvent) -> Unit = {}): Pair<SilentPlayer, MediaSession> {
        val player = SilentPlayer(clock = { clock })
        val session = MediaSession(player, controls, covers, backgroundScope, others, clock = { clock }, checkEveryMs = 1_000, refreshEveryMs = 5_000)
        session.start()
        runCurrent()
        return player to session
    }

    @Test
    fun aNewSongIsShownThenShownAgainWithItsCover() = runTest(UnconfinedTestDispatcher()) {
        val controls = FakeControls()
        val (player, _) = session(controls)
        controls.calls.clear()
        player.play(songs)
        runCurrent()
        assertEquals(
            listOf("track Song 1", "playback playing at 0", "track Song 1 with cover", "playback playing at 0"),
            controls.calls,
        )
    }

    @Test
    fun pausingAndSkippingAreShown() = runTest(UnconfinedTestDispatcher()) {
        val controls = FakeControls()
        val (player, _) = session(controls)
        player.play(songs)
        runCurrent()
        controls.calls.clear()
        clock += 20_000
        player.pause()
        runCurrent()
        assertEquals(listOf("playback paused at 20"), controls.calls)
        controls.calls.clear()
        player.next()
        runCurrent()
        assertEquals("track Song 2", controls.calls.first())
        controls.calls.clear()
        player.clear()
        runCurrent()
        assertEquals(listOf("clear"), controls.calls)
    }

    @Test
    fun theSystemButtonsDriveThePlayer() = runTest(UnconfinedTestDispatcher()) {
        val controls = FakeControls()
        val others = ArrayList<SystemEvent>()
        val (player, _) = session(controls) { others += it }
        player.play(songs)
        runCurrent()
        val press = controls.events!!
        press(SystemEvent.Pause)
        runCurrent()
        assertFalse(player.state.value.playing)
        press(SystemEvent.Toggle)
        runCurrent()
        assertTrue(player.state.value.playing)
        press(SystemEvent.Next)
        runCurrent()
        assertEquals("s2", player.state.value.current?.song?.id)
        press(SystemEvent.SeekTo(42_000))
        runCurrent()
        assertEquals(42_000L, player.positionMs())
        press(SystemEvent.SeekBy(-2_000))
        runCurrent()
        assertEquals(40_000L, player.positionMs())
        press(SystemEvent.Stop)
        runCurrent()
        assertFalse("stop pauses and keeps the queue", player.state.value.playing)
        assertEquals(3, player.state.value.queue.size)
        press(SystemEvent.Raise)
        press(SystemEvent.SetVolume(0.3f))
        runCurrent()
        assertEquals(listOf(SystemEvent.Raise, SystemEvent.SetVolume(0.3f)), others)
    }

    @Test
    fun sleepPausesTheMusic() = runTest(UnconfinedTestDispatcher()) {
        val controls = FakeControls()
        val others = ArrayList<SystemEvent>()
        val (player, _) = session(controls) { others += it }
        player.play(songs)
        runCurrent()
        controls.events!!(SystemEvent.Sleep)
        runCurrent()
        assertFalse(player.state.value.playing)
        controls.events!!(SystemEvent.Wake)
        runCurrent()
        assertFalse("waking does not start the music again", player.state.value.playing)
        assertEquals(listOf(SystemEvent.Sleep, SystemEvent.Wake), others)
    }

    @Test
    fun aJumpMadeInTheWindowIsTold() = runTest(UnconfinedTestDispatcher()) {
        val controls = FakeControls()
        val (player, session) = session(controls)
        player.play(songs)
        runCurrent()
        controls.calls.clear()
        // Seeking while playing changes nothing the state flow shows.
        player.seekTo(70_000)
        session.check()
        assertEquals(listOf("playback playing at 70 jumped"), controls.calls)
        controls.calls.clear()
        clock += 1_000
        session.check()
        assertTrue("time moving on as it should is not a jump", controls.calls.isEmpty())
        clock += 5_000
        session.check()
        assertEquals("refreshed now and then while playing", listOf("playback playing at 76"), controls.calls)
    }

    @Test
    fun volumeIsShown() = runTest(UnconfinedTestDispatcher()) {
        val controls = FakeControls()
        val (player, _) = session(controls)
        player.setVolume(0.5f)
        runCurrent()
        assertEquals("volume 0.5", controls.calls.last())
    }

    @Test
    fun closingLetsGoOfTheControls() = runTest(UnconfinedTestDispatcher()) {
        val controls = FakeControls()
        val (_, session) = session(controls)
        session.close()
        assertTrue(controls.closed)
        advanceUntilIdle()
    }

    @Test
    fun withoutControlsTheSessionSaysSo() = runTest(UnconfinedTestDispatcher()) {
        val (_, session) = session(FakeControls(works = false))
        assertFalse(session.started)
        assertFalse(NoMediaControls().start {})
    }
}
