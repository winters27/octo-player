package app.winters.octo.sound

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.MediaSource
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

// A real DefaultAudioSink with Octo's chain and the mix provider: what
// reaches a deck's ring carries each frame's song time.
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SongTimeLabelsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private class Deck(val rig: MixRig, rate: Int) {
        val labels = DeckTimeQueue()
        val shaping = OctoDspProcessor(entryOf = { it.periodUid as? String }, labels = labels) { SoundSettings() }
        val chain = OctoProcessorChain(shaping)
        val outputs = ArrayList<MixAudioOutput>()
        private val provider = rig.mixer.provider(0, labels)
        val sink: DefaultAudioSink = DefaultAudioSink.Builder(ApplicationProvider.getApplicationContext<Context>())
            .setEnableFloatOutput(false)
            .setAudioProcessorChain(chain)
            .setAudioOutputProvider(object : AudioOutputProvider by provider {
                override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput =
                    provider.getAudioOutput(config).also { outputs += it as MixAudioOutput }
            })
            .build()
        val rate = rate

        fun configure(entry: String, channels: Int = 1, rate: Int = this.rate) {
            val format = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setPcmEncoding(C.ENCODING_PCM_16BIT)
                .setSampleRate(rate).setChannelCount(channels).build()
            sink.configure(AudioSink.AudioSinkConfig.Builder(format).setMediaPeriodId(MediaSource.MediaPeriodId(entry)).build())
        }

        // `frames` 16-bit frames whose value is their song frame (mod 30,000)
        // from `from`, at presentation time `ptsUs`, handed until taken.
        fun feed(from: Long, frames: Int, ptsUs: Long, channels: Int = 1, value: (Long) -> Int = { ((it % 30_000) - 15_000).toInt() }) {
            val buffer = ByteBuffer.allocateDirect(frames * 2 * channels).order(ByteOrder.nativeOrder())
            for (i in 0 until frames) repeat(channels) { buffer.putShort(value(from + i).toShort()) }
            buffer.flip()
            var tries = 0
            while (!sink.handleBuffer(buffer, ptsUs, 1)) {
                rig.runFor(10.0)
                check(++tries < 2_000) { "the sink never took the buffer" }
            }
        }
    }

    private fun frameOf(sample: Float): Long = Math.round(sample * 32_768.0) + 15_000

    // Every ring frame's song time, against the frame it carries. A new
    // output's first 20 ms carry Media3's own ramp-up, so they are skipped;
    // frames the mixer has freed are gone.
    private fun checkLabels(o: MixAudioOutput, rate: Int, offsetFrames: Long = 0, from: Long = 1_000) {
        val view = MarkView()
        o.ring.marks.view(view)
        val written = o.ring.written
        assertTrue("ring holds $written", written > 2_000)
        for (r in maxOf(from, o.ring.freed) until written) {
            val song = view.songAt(r)
            val carried = frameOf(o.ring.raw[o.ring.at(r)])
            val labeled = Math.round(song * rate / 1_000.0) + offsetFrames
            assertEquals("ring frame $r labeled $song", carried, ((labeled % 30_000) + 30_000) % 30_000)
        }
    }

    @Test
    fun atSpeedOneEveryFrameCarriesItsOwnSongTimeAcrossASeekAndAGaplessJoin() {
        val rig = MixRig()
        val d = Deck(rig, 48_000)
        d.configure("a")
        d.sink.play()
        d.feed(0, 20_000, 0)
        val first = rig.mixer.let { lastOutput(d) }
        checkLabels(first, 48_000)
        // A seek: Media3 releases the output and starts from the new place.
        d.sink.flush()
        d.configure("a")
        d.feed(96_000, 20_000, 2_000_000)
        val second = lastOutput(d)
        assertTrue(second !== first)
        checkLabels(second, 48_000)
        // A gapless join to the next song: the same output goes on, the
        // next song's frames labeled from its own start.
        d.configure("b")
        d.feed(0, 20_000, 2_000_000 + 20_000 * 1_000_000L / 48_000)
        val third = lastOutput(d)
        assertTrue(third === second)
        val view = MarkView()
        third.ring.marks.view(view)
        val mark = view.covering(third.ring.written - 1)
        assertEquals("b", view.entry(mark))
        assertTrue(view.flags(mark) and MARK_STREAM_START != 0)
    }

    private fun lastOutput(d: Deck): MixAudioOutput {
        val f = DefaultAudioSink::class.java.getDeclaredField("audioOutput")
        f.isAccessible = true
        return f.get(d.sink) as MixAudioOutput
    }

    @Test
    fun aTempoMatchedRateKeepsClicksOnTheirSongTime() {
        // At 0.94 through Sonic, a click every 0.5 s of song lands in the
        // ring labeled within the rate over 65 ms of where it is.
        val rig = MixRig()
        val d = Deck(rig, 48_000)
        d.sink.playbackParameters = PlaybackParameters(0.94f)
        d.configure("a")
        d.sink.play()
        // Each click 2 ms long, so Sonic's stretching keeps its onset.
        val click = { f: Long -> if (f % 24_000 < 96) 30_000 else 0 }
        var at = 0L
        while (at < 80_000) {
            d.feed(at, 4_800, at * 1_000_000 / 48_000, value = click)
            at += 4_800
        }
        d.sink.playToEndOfStream()
        val o = lastOutput(d)
        val view = MarkView()
        o.ring.marks.view(view)
        var found = 0
        for (r in maxOf(1_000L, o.ring.freed) until o.ring.written) {
            val v = abs(o.ring.raw[o.ring.at(r)])
            if (v > 0.5f && (r == 0L || abs(o.ring.raw[o.ring.at(r - 1)]) <= 0.5f)) {
                val song = view.songAt(r)
                val nearest = Math.round(song / 500.0) * 500.0
                assertTrue("a click at ring $r labeled $song", abs(song - nearest) <= 0.94 / 65 * 1_000)
                found++
            }
        }
        assertTrue("clicks $found", found >= 2)
    }

    @Test
    fun skippedSilenceAndAPaceKeepTheSongTimeAfterIt() {
        for (pace in listOf(1f, 1.25f)) {
            val rig = MixRig()
            val d = Deck(rig, 48_000)
            d.sink.setSkipSilenceEnabled(true)
            d.sink.playbackParameters = PlaybackParameters(pace)
            d.configure("a")
            d.sink.play()
            // 1 s of tone, 2 s of silence, then tone with a click at 3.5 s.
            val tone = { f: Long ->
                when {
                    f in 48_000 until 144_000 -> 0
                    f in 168_000L until 168_096L -> 32_000
                    else -> if ((f / 24) % 2 == 0L) 6_000 else -6_000
                }
            }
            // Fed until just past the click, so it is still in the ring.
            var at = 0L
            while (at < 172_800) {
                d.feed(at, 4_800, at * 1_000_000 / 48_000, value = tone)
                at += 4_800
            }
            d.sink.playToEndOfStream()
            val o = lastOutput(d)
            val view = MarkView()
            o.ring.marks.view(view)
            var clickAt = -1L
            val from = maxOf(1_000L, o.ring.freed)
            for (r in from until o.ring.written) if (clickAt < 0 && o.ring.raw[o.ring.at(r)] > 0.5f) clickAt = r
            assertTrue("pace $pace: the click is in the ring", clickAt >= 0)
            val song = view.songAt(clickAt)
            assertTrue("pace $pace: the click at 3,500 ms is labeled $song", abs(song - 3_500.0) <= 15.0)
            // Song time never runs back.
            var last = -1.0
            for (r in from until o.ring.written) {
                val s = view.songAt(r)
                assertTrue("pace $pace: ring frame $r at $s after $last", s >= last - 1e-6)
                last = s
            }
        }
    }

    @Test
    fun theOutputsPositionIsItsHeardInputFramesAndASongEndsAtItsLastFrame() {
        // A 44.1 kHz song on the 48 kHz mix.
        val rig = MixRig()
        val d = Deck(rig, 44_100)
        d.configure("a", rate = 44_100)
        d.sink.play()
        val frames = 44_100 * 2
        var at = 0L
        val trace = ArrayList<String>()
        while (at < frames) {
            d.feed(at, 4_410, at * 1_000_000 / 44_100)
            at += 4_410
            trace += "$at:${d.outputs.lastOrNull()?.inWritten}"
        }
        d.sink.playToEndOfStream()
        var ended = false
        repeat(400) {
            if (!ended) {
                rig.runFor(10.0)
                d.sink.playToEndOfStream()
                ended = d.sink.isEnded
            }
        }
        assertTrue("the sink ended (${d.outputs.lastOrNull()?.inWritten}, position ${d.outputs.lastOrNull()?.positionUs}, $trace)", ended)
        assertEquals("one output for the whole song", 1, d.outputs.size)
        val o = lastOutput(d)
        assertEquals("ring ${o.ring.written}, taken ${o.resampler?.inputTaken}, position ${o.positionUs}, $trace", frames.toLong(), o.inWritten)
        // Exactly its frames, in its own units.
        assertEquals(androidx.media3.common.util.Util.sampleCountToDurationUs(frames.toLong(), 44_100), o.positionUs)
    }
}
