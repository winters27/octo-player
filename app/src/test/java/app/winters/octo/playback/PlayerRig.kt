package app.winters.octo.playback

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.decoder.CryptoConfig
import androidx.media3.decoder.Decoder
import androidx.media3.decoder.DecoderException
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.SimpleDecoderOutputBuffer
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.PlaybackLooperProvider
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DecoderAudioRenderer
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import androidx.media3.test.utils.FakeMediaSource
import androidx.media3.test.utils.FakeSampleStream.FakeSampleStreamItem
import androidx.media3.test.utils.FakeTimeline
import androidx.test.core.app.ApplicationProvider
import app.winters.octo.sound.AlbumRun
import app.winters.octo.sound.FakeSystemMixer
import app.winters.octo.sound.MixEvent
import app.winters.octo.sound.MixRig
import app.winters.octo.sound.MixTuning
import app.winters.octo.sound.OctoRenderersFactory
import app.winters.octo.sound.SoundSettings
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import kotlin.math.roundToInt

// The rig's songs carry their own frame number in their samples: the left
// channel a sawtooth of SAW frames from 1 up, the right a constant REF
// (positive for one song, negative for the other). Any gain moves both
// channels alike, so their ratio says which frame of which song is heard
// whatever its level (a ramp, a fade, a duck), and a frame where two songs
// are mixed decodes to no whole frame.
const val SAW = 8_191
const val REF = 9_000
const val MONO = 2

fun sawAt(frame: Long): Short = ((frame % SAW) + 1).toShort()

// Which frame of its song's saw a heard frame is (mod SAW), or -1 for
// silence or a frame that is no song's alone.
fun sawFrame(left: Float, right: Float): Int {
    if (right == 0f || left == 0f) return -1
    val r = left / kotlin.math.abs(right) * REF
    val whole = r.roundToInt()
    if (kotlin.math.abs(r - whole) > 0.02f || whole < 1 || whole > SAW) return -1
    return whole - 1
}

// A song the rig plays: 16-bit PCM at `rate`, `sign` telling it from the
// other song on the right channel. A mono song is its saw alone.
class PcmSong(
    val id: String,
    val durationMs: Long,
    val rate: Int = 48_000,
    val channels: Int = 2,
    val sign: Int = 1,
    val albumId: String? = null,
    val albumOrder: Int? = null,
    // Its decoder fails from this song time on (-1: never).
    val failAtMs: Long = -1,
    // How long its sound really is, when its listed length is wrong.
    val realMs: Long = durationMs,
    // Its saw runs below zero, so it is told from a song with the same
    // sign at any level.
    val flip: Boolean = false,
    // Its decoder fails (at failAtMs) only the first time it opens.
    val failOnce: Boolean = false,
    // The track gain it is queued with, in dB, when it has one.
    val trackGainDb: Float? = null,
    // The track gain its own sound is tagged with, in dB, when it has one.
    val streamGainDb: Float? = null,
    // Its album gain as queued.
    val albumGainDb: Float? = null,
) {
    val frames: Long get() = realMs * rate / 1_000

    fun item(entry: String = "q:$id"): MediaItem {
        val extras = Bundle().apply {
            putString(EXTRA_QUEUE_ENTRY, entry)
            albumId?.let { putString(EXTRA_ALBUM_ID, it) }
            albumOrder?.let { putInt(EXTRA_ALBUM_ORDER, it) }
            trackGainDb?.let { putFloat(EXTRA_TRACK_GAIN, it) }
            albumGainDb?.let { putFloat(EXTRA_ALBUM_GAIN, it) }
        }
        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(MediaMetadata.Builder().setDurationMs(durationMs).setExtras(extras).build())
            .build()
    }
}

// Each song as a fake source whose samples are all there at once, in 10 ms
// pieces, every one a key frame, so a seek lands within 10 ms. A song in
// `failing` never opens: its source reports an error.
@UnstableApi
class PcmSources(songs: List<PcmSong>, private val failing: Set<String> = emptySet(), private val stalled: Set<String> = emptySet()) : MediaSource.Factory {
    private val byId = songs.associateBy { it.id }

    // How many times each song's source was made.
    val made = HashMap<String, Int>()

    // The most times a song's source may be made: one more fails the test
    // where it is made (a load that repeats inside one turn never lets the
    // test look).
    val most = HashMap<String, Int>()

    override fun setDrmSessionManagerProvider(drmSessionManagerProvider: DrmSessionManagerProvider): MediaSource.Factory = this

    override fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: LoadErrorHandlingPolicy): MediaSource.Factory = this

    override fun getSupportedTypes(): IntArray = intArrayOf(C.CONTENT_TYPE_OTHER)

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val song = byId.getValue(mediaItem.mediaId)
        val format = Format.Builder()
            .setId(song.id)
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .setSampleRate(song.rate)
            .setChannelCount(song.channels)
            .setMetadata(song.streamGainDb?.let { Metadata(VorbisComment("REPLAYGAIN_TRACK_GAIN", "$it dB")) })
            .build()
        val window = FakeTimeline.TimelineWindowDefinition.Builder()
            .setUid(mediaItem.entryId ?: mediaItem.mediaId)
            .setDurationUs(song.durationMs * 1_000)
            .setWindowPositionInFirstPeriodUs(0)
            .setSeekable(true)
            .setMediaItem(mediaItem)
            .build()
        val times = (made[song.id] ?: 0) + 1
        made[song.id] = times
        most[song.id]?.let { if (times > it) throw AssertionError("${song.id} made $times times, at most $it") }
        if (song.id in failing) {
            return object : FakeMediaSource(FakeTimeline(window), format) {
                override fun prepareSourceInternal(mediaTransferListener: androidx.media3.datasource.TransferListener?) {}

                override fun maybeThrowSourceInfoRefreshError() = throw java.io.IOException("the rig's song does not open")
            }
        }
        return FakeMediaSource(FakeTimeline(window), DrmSessionManager.DRM_UNSUPPORTED, { _, _ -> samples(song) }, format)
            .also { if (song.id in stalled) it.setAllowPreparation(false) }
    }

    private fun samples(song: PcmSong): List<FakeSampleStreamItem> {
        val piece = song.rate / 100
        val out = ArrayList<FakeSampleStreamItem>()
        var f = 0L
        while (f < song.frames) {
            val n = minOf(piece.toLong(), song.frames - f).toInt()
            val bytes = ByteArray(n * song.channels * 2)
            for (i in 0 until n) {
                val v = if (song.flip) (-sawAt(f + i)).toShort() else sawAt(f + i)
                for (c in 0 until song.channels) {
                    val s: Short = if (c == 0) v else (REF * song.sign).toShort()
                    val at = (i * song.channels + c) * 2
                    bytes[at] = (s.toInt() and 0xFF).toByte()
                    bytes[at + 1] = (s.toInt() shr 8).toByte()
                }
            }
            out += FakeSampleStreamItem.sample(f * 1_000_000 / song.rate, C.BUFFER_FLAG_KEY_FRAME, bytes)
            f += n
        }
        out += FakeSampleStreamItem.END_OF_STREAM_ITEM
        return out
    }
}

// PCM in, the same PCM out, on the calling thread (Media3's own decoders
// run a thread of their own, which would race the rig's clock).
@UnstableApi
class PcmDecoder(format: Format, private val failAtUs: Long = -1) : Decoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, DecoderException> {
    val outputFormat: Format = Util.getPcmFormat(C.ENCODING_PCM_16BIT, format.channelCount, format.sampleRate).buildUpon().setMetadata(format.metadata).build()
    private val input = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT)
    private var inputOut = false
    private val ready = ArrayDeque<SimpleDecoderOutputBuffer>()
    private val free = ArrayDeque<SimpleDecoderOutputBuffer>()
    private var startUs = C.TIME_UNSET
    private var firstUs = Long.MIN_VALUE
    private var failed: DecoderException? = null

    override fun getName(): String = "RigPcm"

    override fun setOutputStartTimeUs(outputStartTimeUs: Long) {
        startUs = outputStartTimeUs
    }

    // At most this many decoded buffers (10 ms each) wait: enough that the
    // deck runs ahead of the mixer as a phone's decoder does.
    private val most = 64

    override fun dequeueInputBuffer(): DecoderInputBuffer? {
        failed?.let { throw it }
        if (inputOut || ready.size >= most) return null
        inputOut = true
        input.clear()
        return input
    }

    override fun queueInputBuffer(inputBuffer: DecoderInputBuffer) {
        inputOut = false
        val out = free.removeFirstOrNull() ?: SimpleDecoderOutputBuffer { b ->
            b.clear()
            free.addLast(b)
        }
        if (inputBuffer.isEndOfStream) {
            out.addFlag(C.BUFFER_FLAG_END_OF_STREAM)
            ready.addLast(out)
            return
        }
        // Renderer times carry the stream's offset: counted from the first.
        if (firstUs == Long.MIN_VALUE) firstUs = inputBuffer.timeUs
        if (failAtUs >= 0 && inputBuffer.timeUs - firstUs >= failAtUs) {
            failed = DecoderException("the rig's song fails here")
            return
        }
        val data = inputBuffer.data ?: return
        val bytes = out.init(inputBuffer.timeUs, data.remaining())
        bytes.put(data)
        bytes.flip()
        if (startUs != C.TIME_UNSET && inputBuffer.timeUs < startUs) out.shouldBeSkipped = true
        ready.addLast(out)
    }

    override fun dequeueOutputBuffer(): SimpleDecoderOutputBuffer? {
        failed?.let { throw it }
        return ready.removeFirstOrNull()
    }

    override fun flush() {
        while (ready.isNotEmpty()) ready.removeFirst().release()
        inputOut = false
    }

    override fun release() {
        flush()
    }
}

@UnstableApi
class PcmRenderer(handler: Handler?, listener: AudioRendererEventListener?, sink: AudioSink, private val failAtUs: (String?) -> Long = { -1 }) :
    DecoderAudioRenderer<PcmDecoder>(handler, listener, sink) {
    override fun getName(): String = "RigPcmRenderer"

    override fun supportsFormatInternal(format: Format): Int =
        if (format.sampleMimeType == MimeTypes.AUDIO_RAW) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_TYPE

    override fun createDecoder(format: Format, cryptoConfig: CryptoConfig?): PcmDecoder = PcmDecoder(format, failAtUs(format.id))

    override fun getOutputFormat(decoder: PcmDecoder): Format = decoder.outputFormat
}

// A deck's renderers as the app builds them, into the rig's mixer, with
// the rig's PCM renderer in place of the phone's decoders.
@UnstableApi
class PcmRenderers(
    context: Context,
    rig: MixRig,
    slot: Int,
    private val failAtUs: (String?) -> Long = { -1 },
    sound: () -> SoundSettings = { SoundSettings() },
) : OctoRenderersFactory(context, sound, AlbumRun(), { true }, rig.mixer, slot) {
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
        out.add(PcmRenderer(eventHandler, eventListener, audioSink, failAtUs))
    }
}

// The phone's player as the service builds it (two real ExoPlayers into
// one DeckMixer through the app's own sink), run with the mixer's fake
// system mixer on one virtual clock: every deck's playback thread is the
// main looper, so the decks, the mixer, main and the fake run in time
// order on the test's thread, a millisecond at a time.
@UnstableApi
class PlayerRig(
    val songs: List<PcmSong>,
    latencyCycles: Int = 3,
    tuning: MixTuning = MixTuning(watchdogMs = 2_000),
    times: FadeTimes = FadeTimes(planLoadLeadMs = 4_000, farCheckMs = 200, nearCheckMs = 20),
    failing: Set<String> = emptySet(),
    planner: Planner = ::planTransition,
    // Songs whose source never opens, so a deck playing one waits buffering.
    stalled: Set<String> = emptySet(),
    // The sound settings both decks shape with.
    sound: () -> SoundSettings = { SoundSettings() },
) {
    val context: Context = ApplicationProvider.getApplicationContext()
    val mix = MixRig(latencyCycles = latencyCycles, tuning = tuning)
    val sources = PcmSources(songs, failing, stalled)
    private val failedOnce = HashSet<String>()
    private val failAtUs: (String?) -> Long = { id ->
        val song = songs.firstOrNull { it.id == id }
        if (song == null || song.failAtMs < 0 || (song.failOnce && !failedOnce.add(song.id))) -1 else song.failAtMs * 1_000
    }
    val renderers = listOf(PcmRenderers(context, mix, 0, failAtUs, sound), PcmRenderers(context, mix, 1, failAtUs, sound))
    val decks: List<ExoPlayer> = renderers.map { r ->
        buildDeck(context, sources, r, 7, OctoLoadControl(), PlaybackLooperProvider(Looper.getMainLooper()), releaseTimeoutMs = 0)
    }
    val player = OctoPlayer(context, decks[0], decks[1], soundOf = { d -> renderers[decks.indexOf(d)].sound }, mixer = mix.mixer, times = times, planner = planner)

    // Every blend event main heard, in order.
    val events: List<MixEvent> get() = mix.events

    // Every media item transition the player reported, by entry.
    val transitions = ArrayList<String?>()
    val states = ArrayList<Int>()

    // What the mixer did, for a failing test's message; nothing of the
    // frames, so long runs stay small.
    val notes = ArrayList<String>()

    init {
        mix.mixer.probe = object : app.winters.octo.sound.MixProbe {
            override fun note(what: String, frame: Long) {
                notes += "$what@$frame t=${mix.sched.now / 1_000_000}"
            }
        }
        mix.onEvent = { e -> player.mixListener.onBlendEvent(e) }
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                transitions += mediaItem?.entryId
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                states += playbackState
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                playReason = reason
            }
        })
    }

    // Why the player last started or stopped wanting to play.
    var playReason = -1
        private set

    val system: FakeSystemMixer get() = mix.system

    fun song(id: String): PcmSong = songs.first { it.id == id }

    // Puts `ids` in the queue and plays from the first.
    fun play(vararg ids: String, settings: AutomixSettings = AutomixSettings(maxOverlapMs = 0)) {
        player.automix = settings
        player.setMediaItems(ids.map { song(it).item() })
        player.prepare()
        player.play()
    }

    // Runs everything for `ms` of the clock, a millisecond at a time.
    fun runFor(ms: Long) {
        repeat(ms.toInt()) {
            mix.sched.runUntil(mix.sched.now + 1_000_000L)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
            drain()
        }
    }

    // What the fake system mixer played, kept as two floats a frame.
    private var lefts = FloatArray(1 shl 16)
    private var rights = FloatArray(1 shl 16)
    var heardCount = 0
        private set

    private fun drain() {
        val played = system.heard
        if (played.isEmpty()) return
        if (heardCount + played.size > lefts.size) {
            var n = lefts.size
            while (n < heardCount + played.size) n *= 2
            lefts = lefts.copyOf(n)
            rights = rights.copyOf(n)
        }
        for (p in played) {
            lefts[heardCount] = p.left
            rights[heardCount] = p.right
            heardCount++
        }
        played.clear()
    }

    // Runs until `done` or `maxMs`; true when it was done.
    fun runUntil(maxMs: Long, done: () -> Boolean): Boolean {
        var left = maxMs
        while (left > 0) {
            if (done()) return true
            runFor(1)
            left--
        }
        return done()
    }

    // Each heard frame as (kind, saw frame), in the order heard: kind 1 or
    // -1 a song's frame alone (its sign), MONO a mono song's frame (its saw
    // on both channels, read at full level), 0 silence (a ramp's frame at
    // no level) and MIXED two songs at once.
    fun heardSongs(): List<Pair<Int, Int>> = List(heardCount) { i ->
        val l = lefts[i]
        val r = rights[i]
        if (l == 0f && r == 0f) return@List 0 to -1
        if (l == r && l > 0f) return@List MONO to (l * 32_768f).roundToInt() - 1
        // A flipped song's saw is below zero, its constant above.
        if (l < 0f && r > 0f) return@List sawFrame(-l, r).let { if (it < 0) MIXED to -1 else FLIP to it }
        val f = sawFrame(l, r)
        if (f < 0) MIXED to -1 else (if (r > 0) 1 else -1) to f
    }

    // The heard frames of the song with `sign`, in order, between heard
    // frames `from` and `to`.
    fun framesOf(sign: Int, from: Int = 0, to: Int = heardCount): List<Int> =
        heardSongs().subList(from, to).filter { it.first == sign }.map { it.second }

    // Where the song with `sign` repeats or skips a frame, between heard
    // frames `from` and `to`: from one of its frames to the next with only
    // silence between (a ramp's first frame at no level is still that
    // frame), it must move on as many frames as were heard. Another song or
    // a mix between them sets no rule.
    fun breaksOf(sign: Int, from: Int = 0, to: Int = heardCount): List<String> {
        val s = heardSongs()
        val out = ArrayList<String>()
        var lastAt = -1
        var lastFrame = -1
        for (i in from until to) {
            val (kind, f) = s[i]
            when (kind) {
                sign -> {
                    if (lastAt >= 0 && f != (lastFrame + (i - lastAt)) % SAW) {
                        val around = (maxOf(0, i - 6) until minOf(heardCount, i + 4)).map { "${s[it].first}/${s[it].second}@${"%.3f".format(level(it))}" }
                        out += "heard $i: $lastFrame then $f, ${i - lastAt} on, around $around"
                    }
                    lastAt = i
                    lastFrame = f
                }
                0 -> Unit
                else -> lastAt = -1
            }
        }
        return out
    }

    // Each break of breaksOf as the frame before it and the frame after it.
    fun breakFrames(sign: Int, from: Int = 0, to: Int = heardCount): List<Pair<Int, Int>> {
        val s = heardSongs()
        val out = ArrayList<Pair<Int, Int>>()
        var lastAt = -1
        var lastFrame = -1
        for (i in from until to) {
            val (kind, f) = s[i]
            when (kind) {
                sign -> {
                    if (lastAt >= 0 && f != (lastFrame + (i - lastAt)) % SAW) out += lastFrame to f
                    lastAt = i
                    lastFrame = f
                }
                0 -> Unit
                else -> lastAt = -1
            }
        }
        return out
    }

    // What the mixer told main, for a failing test's message.
    fun told(): String = events.joinToString { "${it.kind}/${it.generation}" }

    // Heard frames where two songs were mixed, and the first and last of them.
    fun mixedCount(): Int = heardSongs().count { it.first == MIXED }

    fun firstMixed(): Int = heardSongs().indexOfFirst { it.first == MIXED }

    fun lastMixed(): Int = heardSongs().indexOfLast { it.first == MIXED }

    // Heard frames of the song with `sign` at all.
    fun anyOf(sign: Int): Boolean = heardSongs().any { it.first == sign }

    // The level heard frame `i` was played at, 1 for a song's own level (a
    // stereo song's: its right channel against the constant it carries).
    fun level(i: Int): Float = kotlin.math.abs(rights[i]) * 32_768f / REF

    // The first heard frame of the kind `kind` from heard frame `from`, or -1.
    fun firstOf(kind: Int, from: Int = 0): Int {
        val s = heardSongs()
        for (i in from until heardCount) if (s[i].first == kind) return i
        return -1
    }

    // Lets go of what the rig holds, so the next test has the room: the
    // decks are released (their taps' workers end), the mixer lets its
    // outputs go.
    fun close() {
        // Released, the decks let go of what they registered with the test's
        // app, which outlives the test and would keep them, and everything
        // they reach, to the end of the run. Their release finishes on the
        // next step, since their playback thread is this one.
        player.release()
        runFor(5)
        mix.mixLoop.post { mix.mixer.release() }
        mix.sched.runFor(1.0)
        system.stop()
        mix.builder.built.clear()
        lefts = FloatArray(0)
        rights = FloatArray(0)
        heardCount = 0
    }

    companion object {
        private val open = ArrayList<PlayerRig>()

        // Every rig made since the last call, closed.
        fun closeAll() {
            open.forEach { it.close() }
            open.clear()
        }
    }

    init {
        open += this
    }
}

const val MIXED = 3

// A flipped song's frames (PcmSong.flip with sign 1).
const val FLIP = 4

// Every place in `frames` (one song's heard saw positions, in order) where
// a frame repeats or is skipped.
fun sawBreaks(frames: List<Int>): List<Int> {
    val breaks = ArrayList<Int>()
    for (i in 1 until frames.size) if (frames[i] != (frames[i - 1] + 1) % SAW) breaks += i
    return breaks
}
