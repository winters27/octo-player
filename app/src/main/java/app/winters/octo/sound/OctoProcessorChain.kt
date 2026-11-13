package app.winters.octo.sound

import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessorChain
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import java.nio.ByteBuffer

// The steps the player's sound goes through, in order: skipping silence,
// then speed and pitch, then Octo's shaping. The first two only understand
// 16-bit sound, so the shaping, which turns it into floats, comes last.
// Built like the player's own default chain, with the shaping added.
//
// The silence skipper is watched (it cannot be extended) so the shaping
// knows how much silence was skipped before each stretch of the song.
@UnstableApi
class OctoProcessorChain(val shaping: OctoDspProcessor) : AudioProcessorChain {
    private val times = ChainTimes()
    private val silence = SilenceSkippingAudioProcessor()
    private val watched = SkipWatcher(silence, times)
    private val sonic = SonicAudioProcessor()
    private val processors = arrayOf<AudioProcessor>(watched, sonic, shaping)

    init {
        shaping.useTimes(times)
    }

    override fun getAudioProcessors(): Array<AudioProcessor> = processors

    override fun applyPlaybackParameters(playbackParameters: PlaybackParameters): PlaybackParameters {
        sonic.setSpeed(playbackParameters.speed)
        sonic.setPitch(playbackParameters.pitch)
        shaping.setSpeed(playbackParameters.speed)
        return playbackParameters
    }

    override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean): Boolean {
        silence.setEnabled(skipSilenceEnabled)
        return skipSilenceEnabled
    }

    override fun getMediaDuration(playoutDuration: Long): Long =
        if (sonic.isActive) sonic.getMediaDuration(playoutDuration) else playoutDuration

    override fun getSkippedOutputFrameCount(): Long = silence.skippedFrames
}

// Passes everything to the silence skipper and notes, for each chunk it
// hands on, the chunk's first output frame and the frames skipped before
// it. While skipping is off it is not in the player's pipeline at all.
@UnstableApi
internal class SkipWatcher(
    private val inner: SilenceSkippingAudioProcessor,
    private val times: ChainTimes,
) : AudioProcessor by inner {
    private var bytesPerFrame = 0
    private var outputFrames = 0L

    override fun getDurationAfterProcessorApplied(durationUs: Long): Long = inner.getDurationAfterProcessorApplied(durationUs)

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        bytesPerFrame = inputAudioFormat.bytesPerFrame
        return inner.configure(inputAudioFormat)
    }

    override fun getOutput(): ByteBuffer {
        val out = inner.output
        val frames = if (bytesPerFrame > 0) out.remaining() / bytesPerFrame else 0
        if (frames > 0) {
            times.chunk(outputFrames, inner.skippedFrames)
            outputFrames += frames
        }
        return out
    }

    override fun flush(streamMetadata: AudioProcessor.StreamMetadata) {
        inner.flush(streamMetadata)
        outputFrames = 0
        times.flushed()
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun flush() {
        inner.flush()
        outputFrames = 0
        times.flushed()
    }

    override fun reset() {
        inner.reset()
        outputFrames = 0
        times.flushed()
    }
}
