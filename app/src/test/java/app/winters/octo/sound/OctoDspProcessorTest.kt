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
        val out = output
        while (out.hasRemaining()) into += out.float
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
}
