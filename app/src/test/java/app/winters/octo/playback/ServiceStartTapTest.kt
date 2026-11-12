package app.winters.octo.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import app.winters.octo.player.MemoryPrefs
import app.winters.octo.player.PlayerSettings
import app.winters.octo.sound.LiveTapFeed
import app.winters.octo.sound.OctoDspProcessor
import app.winters.octo.sound.SoundSettings
import app.winters.octo.sound.TAP_LEVEL_HEARD_MS
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

// The first song after the service starts on a phone that never touched
// Between songs (Automix) feeds its deck's live tap, so its level and tempo
// reach the planner for the first blend.
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class ServiceStartTapTest {
    private val rate = 44_100
    private val worker = Executors.newSingleThreadScheduledExecutor()

    @After
    fun stop() {
        worker.shutdownNow()
    }

    // 10 ms of 16-bit stereo from `from`: a kick every half second (120
    // BPM) with a bass note under it.
    private fun beat(from: Int, frames: Int): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
        repeat(frames) {
            val n = from + it
            val sinceKick = (n % (rate / 2)).toDouble() / rate
            val value = (0.6 * exp(-sinceKick * 30) * sin(2 * PI * 60 * n / rate) + 0.05 * sin(2 * PI * 880 * n / rate)) * 32_000
            buffer.putShort(value.toInt().toShort())
            buffer.putShort(value.toInt().toShort())
        }
        buffer.flip()
        return buffer
    }

    @Test
    fun anUntouchedInstallsFirstSongFeedsTheLiveTap() = runBlocking {
        val prefs = PlayerSettings(MemoryPrefs()).prefs.first()
        val blends = blendsAtStart(prefs)
        val feed = LiveTapFeed(worker)
        // The deck's sound path as the service sets it up at start, with no
        // shaping of its own asked for.
        val deck = OctoDspProcessor(keepActive = { blends }, tap = feed, entryOf = { "q:1" }) { SoundSettings(limiter = false) }
        deck.configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
        deck.flush(AudioProcessor.StreamMetadata(0))
        // The player passes sound only through a step that is active.
        val step = rate / 100
        var at = 0
        while (deck.isActive && at < (TAP_LEVEL_HEARD_MS + 1_000).toInt() * rate / 1_000) {
            deck.queueInput(beat(at, step))
            deck.output
            at += step
            if (at % (step * 50) == 0) feed.drain()
        }
        val reading = deck.reading("q:1", tagBpm = null)
        assertNotNull("the first song fed no tap", reading)
        assertTrue("${reading!!.heardMs} ms heard", reading.heardMs >= TAP_LEVEL_HEARD_MS)
        assertNotNull("its body level", reading.bodyLevelDb)
        assertEquals(120.0, reading.tempoPrior!!, 1.0)
    }
}
