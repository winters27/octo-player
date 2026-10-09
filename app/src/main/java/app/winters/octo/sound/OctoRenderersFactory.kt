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
// `keepActive` keeps the shaping step running with nothing to shape (for
// crossfade), and `sound` is the latest shaping step, for the crossfade to
// drive.
@UnstableApi
class OctoRenderersFactory(
    context: Context,
    private val settings: () -> SoundSettings,
    private val albums: AlbumRun,
    private val keepActive: () -> Boolean = { false },
) : DefaultRenderersFactory(context) {
    @Volatile var sound: DeckSound? = null
        private set

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean,
    ): AudioSink {
        val shaping = OctoDspProcessor(keepActive, LiveTapFeed(), settings)
        sound = shaping
        val sink = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(false)
            .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
            .setAudioProcessorChain(OctoProcessorChain(shaping))
            .build()
        return OctoAudioSink(sink, shaping, albums)
    }
}
