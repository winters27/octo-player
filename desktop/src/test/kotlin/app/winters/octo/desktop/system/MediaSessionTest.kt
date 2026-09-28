package app.winters.octo.desktop.system

import app.winters.octo.desktop.player.DesktopPlayer
import app.winters.octo.desktop.player.PlayerState
import app.winters.octo.desktop.player.QueueEntry
import app.winters.octo.desktop.player.RepeatMode
import app.winters.octo.desktop.player.SilentPlayer
import app.winters.octo.subsonic.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.system.measureTimeMillis

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

    override fun showModes(shuffle: Boolean, repeat: RepeatMode) {
        calls += "modes $shuffle $repeat"
    }

    override fun close() {
        closed = true
    }
}

// A player whose state and place the test sets by hand.
private class HandPlayer : DesktopPlayer by SilentPlayer() {
    val flow = MutableStateFlow(PlayerState())
    var position = 0L
    override val state: StateFlow<PlayerState> get() = flow

    override fun positionMs() = position
}

@OptIn(ExperimentalCoroutinesApi::class)
class MediaSessionTest {
    private var clock = 0L
    private val songs = (1..3).map { Song("s$it", "Song $it", artist = "Artist", duration = 100, coverArt = "c$it") }
    private val covers = CoverSource { id -> CoverArt(byteArrayOf(1, 2, 3), File("$id.img")) }

    private fun TestScope.session(controls: FakeControls, others: (SystemEvent) -> Unit = {}): Pair<SilentPlayer, MediaSession> {
        val player = SilentPlayer(clock = { clock })
        return player to started(player, controls, others)
    }

    private fun TestScope.started(player: DesktopPlayer, controls: FakeControls, others: (SystemEvent) -> Unit = {}): MediaSession {
        val session = MediaSession(
            player, controls, covers, backgroundScope, others,
            clock = { clock }, checkEveryMs = 1_000, refreshEveryMs = 5_000, startOn = EmptyCoroutineContext,
        )
        session.start()
        runCurrent()
        return session
    }

    private fun playing(speed: Float = 1f, buffering: Boolean = false): PlayerState {
        val entry = QueueEntry(1, songs[0])
        return PlayerState(queue = listOf(entry), current = entry, playing = true, durationMs = 100_000, speed = speed, buffering = buffering)
    }

    @Test
    fun theTimeMovesOnAtTheSongsSpeed() = runTest(UnconfinedTestDispatcher()) {
        val controls = FakeControls()
        val player = HandPlayer()
        val session = started(player, controls)
        player.flow.value = playing(speed = 1.5f)
        runCurrent()
        controls.calls.clear()
        clock += 4_000
        player.position = 6_000
        session.check()
        assertTrue("playing at 1.5x is not a jump", controls.calls.isEmpty())
        clock += 1_000
        player.position = 7_500
        session.check()
        assertEquals(listOf("playback playing at 7"), controls.calls)
    }

    @Test
    fun waitingForSoundIsNotAJump() = runTest(UnconfinedTestDispatcher()) {
        val controls = FakeControls()
        val player = HandPlayer()
        val session = started(player, controls)
        player.flow.value = playing(buffering = true)
        runCurrent()
        controls.calls.clear()
        repeat(6) {
            clock += 1_000
            session.check()
        }
        assertTrue("the place stands still while waiting", controls.calls.isEmpty())
        player.flow.value = playing()
        runCurrent()
        assertEquals("once sound comes the place is told again", listOf("playback playing at 0 jumped"), controls.calls)
    }

    @Test
    fun shuffleAndRepeatAreShownAndCanBeSet() = runTest(UnconfinedTestDispatcher()) {
        val controls = FakeControls()
        val (player, _) = session(controls)
        player.play(songs)
        runCurrent()
        controls.calls.clear()
        controls.events!!(SystemEvent.SetShuffle(true))
        runCurrent()
        assertTrue(player.state.value.shuffle)
        assertEquals("modes true Off", controls.calls.last())
        controls.events!!(SystemEvent.SetRepeat(RepeatMode.One))
        runCurrent()
        assertEquals(RepeatMode.One, player.state.value.repeat)
        assertEquals("modes true One", controls.calls.last())
    }

    @Test
    fun startingNeverWaitsForTheSystem() {
        val answer = CountDownLatch(1)
        val heard = LinkedBlockingQueue<Boolean>()
        val slow = object : SystemMediaControls by FakeControls() {
            override fun start(events: (SystemEvent) -> Unit): Boolean {
                answer.await(10, TimeUnit.SECONDS)
                return true
            }
        }
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val session = MediaSession(SilentPlayer(), slow, null, scope)
        val took = measureTimeMillis { session.start { heard += it } }
        assertTrue("start came back at once, not after $took ms", took < 1_000)
        answer.countDown()
        assertEquals(true, heard.poll(5, TimeUnit.SECONDS))
        session.close()
        scope.cancel()
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
