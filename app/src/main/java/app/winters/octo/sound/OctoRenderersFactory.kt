package app.winters.octo.sound

import android.content.Context
import android.os.Handler
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

// Builds a deck's renderers with Octo's shaping in the audio path. Every
// audio output it builds gets its own shaping step, reading the shared
// settings. The output always takes 16-bit sound, because the player only
// runs these steps on 16-bit sound; the shaping turns it into floats itself.
// The deck's sound goes into `mixer` on its `slot`: the shaping labels every
// stretch with its song time for it, and the mixer is where both decks meet
// and blends are shaped; speed goes through the shaping steps, never the
// output. `keepActive` keeps the shaping step running with nothing to shape
// (for crossfade), and `sound` is the latest shaping step, for the
// crossfade to read.
@UnstableApi
open class OctoRenderersFactory(
    context: Context,
    private val settings: () -> SoundSettings,
    private val albums: AlbumRun,
    private val keepActive: () -> Boolean = { false },
    private val mixer: DeckMixer? = null,
    private val slot: Int = 0,
) : DefaultRenderersFactory(context) {
    @Volatile var sound: DeckSound? = null
        private set

    // ALAC plays through Octo's own decoder first, so it plays the same on
    // every phone whether or not the phone has one. Then the phone's
    // decoders. Last, a renderer that fails a song whose sound none of them
    // can play, so it is skipped and reported instead of playing silence.
    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        out.add(AlacAudioRenderer(eventHandler, eventListener, audioSink))
        super.buildAudioRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, audioSink, eventHandler, eventListener, out)
        out.add(UnplayableAudioRenderer())
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean,
    ): AudioSink {
        val mixer = mixer
        if (mixer == null) {
            // A deck on its own (a device test of a decoder): its own output,
            // nothing blended.
            val shaping = OctoDspProcessor(keepActive, LiveTapFeed(), settings = settings)
            sound = shaping
            val sink = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(false)
                .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                .setAudioProcessorChain(OctoProcessorChain(shaping))
                .build()
            return OctoAudioSink(sink, shaping, albums)
        }
        val (sink, shaping) = mixedDeckSink(context, settings, albums, keepActive, mixer, slot)
        sound = shaping
        return sink
    }
}

// A deck's audio output into `mixer` on `slot`: Media3's own sink with
// Octo's chain, whose last step labels every stretch with its song time
// and stream for the mixer, and the mixer's provider in place of an audio
// track. Speed goes through the chain, never the output. Also the shaping
// step, for what the song sounds like.
@UnstableApi
private fun mixedDeckSink(
    context: Context,
    settings: () -> SoundSettings,
    albums: AlbumRun,
    keepActive: () -> Boolean,
    mixer: DeckMixer,
    slot: Int,
): Pair<AudioSink, OctoDspProcessor> {
    val labels = DeckTimeQueue()
    val shaping = OctoDspProcessor(keepActive, LiveTapFeed(), labels = labels, settings = settings)
    val sink = DefaultAudioSink.Builder(context)
        .setEnableFloatOutput(false)
        .setEnableAudioOutputPlaybackParameters(false)
        .setAudioProcessorChain(OctoProcessorChain(shaping))
        .setAudioOutputProvider(mixer.provider(slot, labels))
        .build()
    return OctoAudioSink(sink, shaping, albums) to shaping
}
