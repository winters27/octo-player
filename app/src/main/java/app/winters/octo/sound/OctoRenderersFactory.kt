package app.winters.octo.sound

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

// Builds a deck's renderers with Octo's shaping in the audio path. Every
// audio output it builds gets its own shaping step, reading the shared
// settings. The output always takes 16-bit sound, because the player only
// runs these steps on 16-bit sound; the shaping turns it into floats itself.
@UnstableApi
class OctoRenderersFactory(
    context: Context,
    private val settings: () -> SoundSettings,
    private val albums: AlbumRun,
) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean,
    ): AudioSink {
        val shaping = OctoDspProcessor(settings)
        val sink = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(false)
            .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
            .setAudioProcessorChain(OctoProcessorChain(shaping))
            .build()
        return OctoAudioSink(sink, shaping, albums)
    }
}
