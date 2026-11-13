package app.winters.octo.sound

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

@UnstableApi
class OctoDspProcessorTest {
    private val format = AudioProcessor.AudioFormat(48_000, 1, C.ENCODING_PCM_16BIT)

    // Half of full scale, as 16-bit samples.
    private fun half(frames: Int): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(frames * 2).order(ByteOrder.nativeOrder())
        repeat(frames) { buffer.putShort(16_384) }
        buffer.flip()
        return buffer
    }

    private fun AudioProcessor.collect(into: MutableList<Float>) {
        var out = output
        while (out.hasRemaining()) {
            while (out.hasRemaining()) into += out.float
            out = output
        }
    }

    private fun gain(db: Float) = SongLoudness(ReplayGainInfo(trackGain = db), followsSameAlbum = false)

    @Test
    fun aSongsGainStartsAtItsFirstSample() {
        val settings = SoundSettings(replayGain = ReplayGainMode.Track, limiter = false, preventClipping = false)
        val processor = OctoDspProcessor { settings }
        processor.setNextSong(gain(-6.0206f))
        assertEquals(C.ENCODING_PCM_FLOAT, processor.configure(format).encoding)
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)

        val first = mutableListOf<Float>()
        processor.queueInput(half(1_000))
        processor.collect(first)
        // The next song is set up while this one still plays...
        processor.setNextSong(gain(0f))
        processor.configure(format)
        processor.queueInput(half(1_000))
        processor.collect(first)
        // ...and this one plays out at its own level.
        processor.queueEndOfStream()
        processor.collect(first)
        assertEquals(2_000, first.size)
        assertTrue(first.all { kotlin.math.abs(it - 0.25f) < 1e-4f })

        // After the hand-over the new level holds from the very first sample.
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val second = mutableListOf<Float>()
        processor.queueInput(half(1_000))
        processor.collect(second)
        processor.queueEndOfStream()
        processor.collect(second)
        assertEquals(1_000, second.size)
        assertTrue(second.all { it == 0.5f })
    }

    @Test
    fun turningEverythingOffLeavesTheSoundAlone() {
        val off = SoundSettings(limiter = false)
        val processor = OctoDspProcessor { off }
        assertEquals(AudioProcessor.AudioFormat.NOT_SET, processor.configure(format))
    }

    // The player asks for the end of a stream while it may still hold the
    // last output unwritten (its track full): the held sound comes after
    // it, never over it.
    @Test
    fun theTailAtTheEndOfAStreamNeverOverwritesOutputNotYetTaken() {
        val processor = OctoDspProcessor { SoundSettings(replayGain = ReplayGainMode.Track, limiter = false, preventClipping = false) }
        processor.setNextSong(gain(-6.0206f))
        processor.configure(format)
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        processor.queueInput(half(1_000))
        val held = processor.output
        val before = held.remaining()
        assertTrue(before > 0)
        processor.queueEndOfStream()
        assertEquals(before, held.remaining())
        val first = held.duplicate().order(ByteOrder.nativeOrder()).float
        assertEquals(0.25f, first, 1e-4f)
        val all = mutableListOf<Float>()
        while (held.hasRemaining()) all += held.float
        processor.collect(all)
        assertEquals(1_000, all.size)
        assertTrue(processor.isEnded)
    }

    // A stream's song time is the start the speed step received: at 1.25 it
    // hands later steps a 10 s start as 8 s.
    @Test
    fun theMixPathLabelsEachStretchFromTheStartBeforeTheSpeedStep() {
        val labels = DeckTimeQueue()
        val processor = OctoDspProcessor(entryOf = { "e1" }, labels = labels) { SoundSettings() }
        val out = processor.configure(format)
        // Always float stereo for the mix, whatever the settings shape.
        assertEquals(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_FLOAT), out)
        processor.setSpeed(1.25f)
        processor.flush(AudioProcessor.StreamMetadata(8_000_000))
        processor.queueInput(half(1_000))
        val first = labels.peek()!!
        assertEquals(10_000.0, first.songMs, 1e-9)
        assertEquals(1.25 * 1_000.0 / 48_000, first.msPerFrame, 1e-12)
        assertEquals("e1", first.entryId)
        assertTrue(first.streamStart)
        // Mono goes out on both sides.
        val stereo = processor.output.order(ByteOrder.nativeOrder()).asFloatBuffer()
        assertEquals(stereo.get(0), stereo.get(1), 0f)
        assertEquals(0.5f, stereo.get(0), 1e-6f)
    }
}
