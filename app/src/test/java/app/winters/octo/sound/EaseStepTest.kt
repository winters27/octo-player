package app.winters.octo.sound

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.MediaSource
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// A tempo-matched song easing back to its own speed after its blend: the
// Crossfader raises the deck's speed a step at a time, and each step makes
// Media3's sink drain Sonic and start it again. What reaches the mix keeps
// its song time running on and its sound whole at every step: no jump and
// no gap.
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EaseStepTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    // The steps the Crossfader eases by.
    private val step = 0.0025

    @Test
    fun easingTheRateBackInStepsKeepsTheSongTimeRunningAndTheSoundWhole() {
        val rig = MixRig()
        val labels = DeckTimeQueue()
        val shaping = OctoDspProcessor(entryOf = { it.periodUid as? String }, labels = labels) { SoundSettings() }
        val sink = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(false)
            .setAudioProcessorChain(OctoProcessorChain(shaping))
            .setEnableAudioOutputPlaybackParameters(false)
            .setAudioOutputProvider(rig.mixer.provider(0, labels))
            .build()
        var rate = 0.97
        sink.playbackParameters = PlaybackParameters(rate.toFloat())
        val format = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setPcmEncoding(C.ENCODING_PCM_16BIT)
            .setSampleRate(48_000).setChannelCount(1).build()
        sink.configure(AudioSink.AudioSinkConfig.Builder(format).setMediaPeriodId(MediaSource.MediaPeriodId("b")).build())
        sink.play()
        // A 220 Hz tone at half scale.
        val tone = { f: Long -> (16_384 * sin(2 * PI * 220 * f / 48_000)).toInt().toShort() }
        val chunk = 4_800
        var at = 0L
        val jumps = ArrayList<String>()
        var checkedMark = 0L
        val view = MarkView()
        var output: MixAudioOutput? = null
        fun checkMarks() {
            val o = output ?: return
            o.ring.marks.view(view)
            var m = maxOf(checkedMark, view.floor + 1)
            while (m < view.count) {
                val predicted = view.songMs(m - 1) + (view.ringAt(m) - view.ringAt(m - 1)) * view.msPerFrame(m - 1)
                val jump = view.songMs(m) - predicted
                if (abs(jump) > 1.0) jumps += "mark $m at ring ${view.ringAt(m)}: ${view.songMs(m)} ms for $predicted"
                m++
            }
            checkedMark = m
        }
        // 200 ms at each rate from 0.97 up to 1, then 500 ms more at 1.
        val total = 48_000L * 3
        while (at < total) {
            val buffer = ByteBuffer.allocateDirect(chunk * 2).order(ByteOrder.nativeOrder())
            for (i in 0 until chunk) buffer.putShort(tone(at + i))
            buffer.flip()
            var tries = 0
            while (!sink.handleBuffer(buffer, at * 1_000_000 / 48_000, 1)) {
                rig.runFor(10.0)
                check(++tries < 2_000) { "the sink never took the buffer" }
            }
            if (output == null) output = currentOutput(sink)
            checkMarks()
            at += chunk
            if (at % 9_600 == 0L && rate < 1.0) {
                rate = min(1.0, rate + step)
                sink.playbackParameters = PlaybackParameters(rate.toFloat())
            }
        }
        rig.runFor(200.0)
        checkMarks()
        assertTrue("one output for the whole ease", output === currentOutput(sink))
        assertTrue("song time jumps: $jumps", jumps.isEmpty())
        // The sound at the mix: past Media3's 20 ms ramp-up and short of the
        // end, every 10 ms holds the tone's own peak.
        val frames = rig.tap.frames
        val last = frames.keys.maxOrNull() ?: -1L
        assertTrue("written $last", last > 96_000)
        var lowest = Double.MAX_VALUE
        var lowestAt = -1L
        var w = 2_000L
        while (w + 480 < last - 4_800) {
            var peak = 0.0
            for (f in w until w + 480) peak = max(peak, abs((frames[f] ?: 0f).toDouble()))
            if (peak < lowest) {
                lowest = peak
                lowestAt = w
            }
            w += 240
        }
        assertTrue("the tone dips to $lowest at mix frame $lowestAt", lowest >= 0.5 * 0.97)
    }

    private fun currentOutput(sink: DefaultAudioSink): MixAudioOutput {
        val f = DefaultAudioSink::class.java.getDeclaredField("audioOutput")
        f.isAccessible = true
        return f.get(sink) as MixAudioOutput
    }
}
