package app.winters.octo.desktop.audio

import app.winters.octo.desktop.player.DesktopPlayer
import app.winters.octo.desktop.player.DesktopPlayerContract
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.random.Random

// The engine's player against the same contract as the silent one, on the
// real Rust engine with its silent device: real decoding, the real queue
// and the real audio clock, with no sound card needed. Every song is the
// same 100 second tone, made here.
class EnginePlayerTest : DesktopPlayerContract() {
    private val made = mutableListOf<DesktopPlayer>()

    override fun newPlayer(): DesktopPlayer =
        EnginePlayer(NativeAudioEngine.open(silent = true), { SongAddress(tone.path) }, Random(7)).also { made += it }

    // Playing time passes as the engine plays. Most of a long wait is
    // skipped by seeking ahead within the song, never past its end, so the
    // ends of songs are still reached by playing; the last stretch plays
    // out, followed on the engine's clock.
    override fun elapse(player: DesktopPlayer, ms: Long) {
        val p = player as EnginePlayer
        if (!p.state.value.playing) {
            Thread.sleep(minOf(ms, 400))
            return
        }
        var left = ms
        val start = p.positionMs()
        val skip = minOf(left, p.state.value.durationMs - start) - PLAY_OUT_MS
        if (skip > 0) {
            p.seekTo(start + skip)
            left -= skip
        }
        var (key, last) = p.spot()
        var played = 0L
        val deadline = System.currentTimeMillis() + left * 3 + 5_000
        while (played < left && p.state.value.playing) {
            check(System.currentTimeMillis() < deadline) { "the engine stopped moving at $last" }
            Thread.sleep(1)
            val (nowKey, now) = p.spot()
            played += when {
                nowKey != key -> (p.lengthOf(key) - last) + now
                // Repeat one went round.
                now < last - 1_000 -> (p.lengthOf(key) - last) + now
                else -> now - last
            }
            key = nowKey
            last = now
        }
    }

    private fun EnginePlayer.lengthOf(key: Long?): Long =
        (state.value.queue.firstOrNull { it.key == key }?.song?.duration ?: 0) * 1000L

    @After
    fun closePlayers() {
        made.forEach { it.close() }
    }

    @Test
    fun soundComesOutOfTheEngine() {
        val p = newPlayer() as EnginePlayer
        p.play(songs)
        val started = System.currentTimeMillis()
        while (p.positionMs() < 300 && System.currentTimeMillis() - started < 5_000) Thread.sleep(10)
        assertTrue("the clock moved: ${p.positionMs()}", p.positionMs() >= 300)
        assertEquals(app.winters.octo.audio.PlaybackState.PLAYING, p.engine.state())
    }

    @Test
    fun theStateSaysWhatTheSongAndTheDeviceAre() {
        val p = newPlayer() as EnginePlayer
        p.play(songs)
        val until = System.currentTimeMillis() + 5_000
        while ((p.state.value.format?.song?.bits == null || p.state.value.format?.output == null) && System.currentTimeMillis() < until) Thread.sleep(10)
        val format = p.state.value.format!!
        // The tone is a 48 kHz 16-bit mono WAV; the silent device takes
        // 48 kHz stereo floats.
        assertEquals(app.winters.octo.desktop.player.SongFormat("pcm", true, 48_000, 16, 1), format.song)
        assertEquals(app.winters.octo.desktop.player.DeviceFormat(48_000, 2, 32, float = true), format.output)
        assertFalse(format.resampled)
    }

    @Test
    fun stopAfterCurrentPausesAtTheNextSong() {
        val p = newPlayer()
        p.play(songs)
        p.setStopAfterCurrent(true)
        assertTrue(p.state.value.stopAfterCurrent)
        elapse(p, 100_300)
        assertEquals("s2", p.state.value.current?.song?.id)
        assertFalse(p.state.value.playing)
        assertFalse(p.state.value.stopAfterCurrent)
    }

    @Test
    fun autoplayIsAskedWhenTheLastSongStarts() {
        val p = newPlayer() as EnginePlayer
        val asked = java.util.concurrent.atomic.AtomicReference<String?>()
        p.autoplay = AutoplayHook { last, _, add ->
            asked.set(last.id)
            add(listOf(songs[0].copy(id = "more")))
        }
        p.play(songs, 3)
        elapse(p, 100_300)
        // The hook is called on the engine's thread, just after the news.
        val until = System.currentTimeMillis() + 2_000
        while (p.state.value.upcoming.isEmpty() && System.currentTimeMillis() < until) Thread.sleep(5)
        assertEquals("s5", asked.get())
        assertEquals("more", p.state.value.upcoming.lastOrNull()?.song?.id)
    }

    companion object {
        // What plays out for real at the end of a wait, in milliseconds.
        private const val PLAY_OUT_MS = 1_500L
        private lateinit var folder: File
        private lateinit var tone: File

        @BeforeClass
        @JvmStatic
        fun makeTone() {
            folder = Files.createTempDirectory("octo-engine-test").toFile()
            tone = File(folder, "tone.wav").also { writeSine(it, seconds = 100) }
        }

        @AfterClass
        @JvmStatic
        fun dropTone() {
            folder.deleteRecursively()
        }
    }
}
