package app.winters.octo.desktop.audio

import app.winters.octo.audio.EndReason
import app.winters.octo.audio.EngineEvent
import app.winters.octo.audio.ErrorKind
import app.winters.octo.audio.PlaybackState
import app.winters.octo.desktop.FakeServer
import app.winters.octo.desktop.player.DEFAULT_OUTPUT
import app.winters.octo.desktop.player.RepeatMode
import app.winters.octo.subsonic.Song
import app.winters.octo.subsonic.SongReplayGain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Test
import kotlin.random.Random
import app.winters.octo.audio.OutputDevice as EngineDevice

// The player's side of the engine: that the engine's queue always holds
// the songs in the order they play, that jumps use the queue it has, and
// that the engine's news turns into the right state.
class EngineSyncTest {
    private val songs = (1..5).map { Song("s$it", "Song $it", albumId = "al", track = it, duration = 100) }
    private val extra = listOf(Song("x", "X", duration = 60), Song("y", "Y", duration = 60))
    private var now = 0L

    private fun setUp(): Pair<EnginePlayer, FakeEngine> {
        val engine = FakeEngine()
        val player = EnginePlayer(engine, { SongAddress("file:///${it.id}.flac") }, Random(3), clock = { now })
        return player to engine
    }

    // What the engine holds, as song ids, and the one it is on.
    private fun EnginePlayer.engineSongs(engine: FakeEngine) =
        engine.ids.map { id -> state.value.queue.first { itemId(it.key) == id }.song.id }

    private fun EnginePlayer.playOrder() = listOfNotNull(state.value.current?.song?.id) + state.value.upcoming.map { it.song.id }

    private fun EnginePlayer.key(id: String) = state.value.queue.first { it.song.id == id }.key

    @Test
    fun playLoadsTheSongsInTheOrderTheyPlay() {
        val (p, engine) = setUp()
        p.play(songs, 2, shuffle = true)
        assertEquals(listOf("load 5 at 0 from 0 playing"), engine.calls)
        assertEquals(p.playOrder(), p.engineSongs(engine))
        val first = engine.queue.first()
        assertEquals(100_000uL, first.durationMs)
        assertEquals("file:///s3.flac", first.source)
        assertEquals("al", first.albumId)
        assertEquals(1003, first.albumOrder)
    }

    @Test
    fun loudnessTheServerKnowsGoesAlong() {
        val (p, engine) = setUp()
        p.play(listOf(Song("g", "G", duration = 10, replayGain = SongReplayGain(trackGain = -6.5f, trackPeak = 0.9f))))
        assertEquals(-6.5f, engine.queue.single().replayGain?.trackGain)
    }

    @Test
    fun songsPutInReachTheEngineInPlayOrder() {
        val (p, engine) = setUp()
        p.play(songs, 0, shuffle = true)
        engine.calls.clear()
        p.playNext(extra)
        assertEquals(listOf("replace 6"), engine.calls)
        assertEquals(p.playOrder(), p.engineSongs(engine))
        p.addToQueue(listOf(Song("z", "Z", duration = 60)))
        assertEquals(p.playOrder(), p.engineSongs(engine))
        p.moveUpcoming(0, 4)
        assertEquals(p.playOrder(), p.engineSongs(engine))
        p.remove(p.state.value.upcoming[1].key)
        assertEquals(p.playOrder(), p.engineSongs(engine))
    }

    @Test
    fun nextAndBackSkipWithinTheEnginesQueue() {
        val (p, engine) = setUp()
        p.play(songs)
        engine.calls.clear()
        p.next()
        assertEquals(listOf("skip 1", "play"), engine.calls)
        engine.calls.clear()
        p.previous()
        assertEquals(listOf("skip 0", "play"), engine.calls)
    }

    @Test
    fun afterShuffleComesOffBackFindsTheSongWhereTheEngineHasIt() {
        val (p, engine) = setUp()
        p.play(songs, 0, shuffle = true)
        p.next()
        p.next()
        p.setShuffle(false)
        assertEquals(p.playOrder(), p.engineSongs(engine).drop(engine.index))
        engine.calls.clear()
        val before = p.state.value.current!!.song.id
        p.previous()
        val back = p.state.value.current!!.song.id
        // Unshuffled, back is the song before in the album, wherever the
        // engine keeps it.
        assertEquals("s${before.drop(1).toInt() - 1}", back)
        assertEquals(back, p.engineSongs(engine)[engine.index])
    }

    @Test
    fun aSkipWhilePausedStartsPlaying() {
        val (p, engine) = setUp()
        p.play(songs)
        p.pause()
        engine.calls.clear()
        p.skipTo(p.key("s4"))
        assertEquals(listOf("skip 3", "play"), engine.calls)
        assertTrue(p.state.value.playing)
    }

    @Test
    fun nextWhilePausedStaysPaused() {
        val (p, engine) = setUp()
        p.play(songs)
        p.pause()
        engine.calls.clear()
        p.next()
        assertEquals(listOf("skip 1"), engine.calls)
    }

    @Test
    fun songsPutIntoAnEmptyQueueAreLoadedPaused() {
        val (p, engine) = setUp()
        p.addToQueue(extra)
        assertEquals(listOf("load 2 at 0 from 0 paused"), engine.calls)
        engine.calls.clear()
        p.resume()
        assertEquals(listOf("play"), engine.calls)
    }

    @Test
    fun clearingEmptiesTheEngine() {
        val (p, engine) = setUp()
        p.play(songs)
        p.clear()
        assertEquals("load 0 at 0 from 0 paused", engine.calls.last())
        assertTrue(engine.queue.isEmpty())
    }

    @Test
    fun repeatAndStopAfterCurrentReachTheEngine() {
        val (p, engine) = setUp()
        p.setRepeat(RepeatMode.One)
        p.setStopAfterCurrent(true)
        assertEquals(listOf("repeat ONE", "stop after true"), engine.calls)
    }

    @Test
    fun theVolumeSliderIsSquared() {
        val (p, engine) = setUp()
        p.setVolume(0.5f)
        assertEquals(0.25f, engine.level)
        assertEquals(0.5f, p.state.value.volume)
    }

    // ---- The engine's news ----

    @Test
    fun aSongStartingByItselfBecomesTheCurrentOne() {
        val (p, engine) = setUp()
        p.play(songs)
        engine.emit(EngineEvent.TrackStarted(itemId(p.key("s1")), 0u, null))
        engine.emit(EngineEvent.TrackStarted(itemId(p.key("s2")), 1u, null))
        assertEquals("s2", p.state.value.current?.song?.id)
        assertEquals(listOf("s3", "s4", "s5"), p.state.value.upcoming.map { it.song.id })
    }

    @Test
    fun newsFromBeforeAJumpIsIgnored() {
        val (p, engine) = setUp()
        p.play(songs)
        engine.emit(EngineEvent.TrackStarted(itemId(p.key("s1")), 0u, null))
        p.skipTo(p.key("s4"))
        // s1 ran into s2 just before the jump reached the engine.
        engine.emit(EngineEvent.TrackStarted(itemId(p.key("s2")), 1u, null))
        assertEquals("s4", p.state.value.current?.song?.id)
        engine.emit(EngineEvent.TrackStarted(itemId(p.key("s4")), 3u, null))
        engine.emit(EngineEvent.TrackStarted(itemId(p.key("s5")), 4u, null))
        assertEquals("s5", p.state.value.current?.song?.id)
    }

    @Test
    fun newsOfAReplacedQueueIsIgnored() {
        val (p, engine) = setUp()
        p.play(songs)
        val old = p.key("s3")
        p.play(extra)
        engine.emit(EngineEvent.TrackStarted(itemId(old), 2u, null))
        assertEquals("x", p.state.value.current?.song?.id)
    }

    @Test
    fun theEndOfTheQueueStopsPlaying() {
        val (p, engine) = setUp()
        p.play(songs, 4)
        engine.emit(EngineEvent.QueueEnded)
        assertFalse(p.state.value.playing)
        assertEquals("s5", p.state.value.current?.song?.id)
    }

    @Test
    fun bufferingShowsWhilePlaying() {
        val (p, engine) = setUp()
        p.play(songs)
        engine.emit(EngineEvent.Buffering(itemId(p.key("s1"))))
        assertTrue(p.state.value.buffering)
        engine.emit(EngineEvent.Ready(itemId(p.key("s1"))))
        assertFalse(p.state.value.buffering)
        p.pause()
        engine.emit(EngineEvent.StateChanged(PlaybackState.BUFFERING))
        assertFalse("paused is not buffering", p.state.value.buffering)
    }

    @Test
    fun aSongThatFailsSaysWhyUntilTheNextStarts() {
        val (p, engine) = setUp()
        p.play(songs)
        engine.emit(EngineEvent.Error(ErrorKind.NETWORK, "timed out", itemId(p.key("s1"))))
        assertEquals("Couldn't reach the server to play that song.", p.state.value.problem)
        engine.emit(EngineEvent.TrackStarted(itemId(p.key("s2")), 1u, null))
        assertEquals("s2", p.state.value.current?.song?.id)
        assertNull(p.state.value.problem)
    }

    @Test
    fun stopAfterCurrentFollowsTheEngineToTheNextSongPaused() {
        val (p, engine) = setUp()
        p.play(songs)
        p.setStopAfterCurrent(true)
        engine.emit(EngineEvent.TrackEnded(itemId(p.key("s1")), EndReason.FINISHED))
        assertEquals("s2", p.state.value.current?.song?.id)
        assertFalse(p.state.value.playing)
        assertFalse(p.state.value.stopAfterCurrent)
    }

    @Test
    fun beingHeardOnAnotherSongMovesThereWhenNothingIsAwaited() {
        val (p, engine) = setUp()
        p.play(songs)
        engine.emit(EngineEvent.Position(itemId(p.key("s1")), 50.0))
        engine.emit(EngineEvent.Position(itemId(p.key("s2")), 10.0))
        assertEquals("s2", p.state.value.current?.song?.id)
    }

    @Test
    fun aJumpShowsItsTargetUntilTheEngineIsHeardThere() {
        val (p, engine) = setUp()
        p.play(songs)
        engine.heard = Heard(itemId(p.key("s1")), 30_000.0, 100_000)
        now = 10
        p.seekTo(60_000)
        assertEquals(60_000, p.positionMs())
        engine.heard = Heard(itemId(p.key("s1")), 60_020.0, 100_000)
        now = 40
        assertEquals(60_020, p.positionMs())
        engine.heard = Heard(itemId(p.key("s1")), 61_000.0, 100_000)
        assertEquals(61_000, p.positionMs())
    }

    @Test
    fun aJumpThatNeverArrivesGivesWayToTheEngine() {
        val (p, engine) = setUp()
        p.play(songs)
        engine.heard = Heard(itemId(p.key("s1")), 5_000.0, 100_000)
        p.seekTo(90_000)
        assertEquals(90_000, p.positionMs())
        now += 10_000
        assertEquals(5_000, p.positionMs())
    }

    @Test
    fun devicesAreListedAndTheOneInUseShown() {
        val (p, engine) = setUp()
        engine.current = EngineDevice("spk", "Speakers", true)
        engine.emit(EngineEvent.DeviceChanged(engine.current))
        val until = System.currentTimeMillis() + 2_000
        while (p.state.value.outputs.size < 3 && System.currentTimeMillis() < until) Thread.sleep(5)
        val state = p.state.value
        assertEquals(listOf(DEFAULT_OUTPUT, "spk", "usb"), state.outputs.map { it.id })
        assertEquals("System default (Speakers)", state.output?.name)
        assertEquals("spk", p.deviceKey.value)
        p.selectOutput("usb")
        assertEquals("usb", engine.device)
        assertEquals("usb", p.state.value.output?.id)
        p.selectOutput(DEFAULT_OUTPUT)
        assertNull(engine.device)
    }

    @Test
    fun streamsAreSignedForTheOriginalFile() {
        FakeServer().use { server ->
            val address = ServerSongs { server.client() }.addressOf(Song("abc", "A"))!!
            val url = address.source.toHttpUrl()
            assertEquals("stream", url.pathSegments.last())
            assertEquals("abc", url.queryParameter("id"))
            assertEquals("raw", url.queryParameter("format"))
            assertEquals("winters", url.queryParameter("u"))
            assertTrue("signed", url.queryParameter("t") != null || url.queryParameter("p") != null)
        }
        assertNull(ServerSongs { null }.addressOf(Song("abc", "A")))
    }
}
