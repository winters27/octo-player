package app.winters.octo.sound

import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessorChain
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor

// The steps the player's sound goes through, in order: skipping silence,
// then speed and pitch, then Octo's shaping. The first two only understand
// 16-bit sound, so the shaping, which turns it into floats, comes last.
// Built like the player's own default chain, with the shaping added.
@UnstableApi
class OctoProcessorChain(val shaping: OctoDspProcessor) : AudioProcessorChain {
    private val silence = SilenceSkippingAudioProcessor()
    private val sonic = SonicAudioProcessor()
    private val processors = arrayOf<AudioProcessor>(silence, sonic, shaping)

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
