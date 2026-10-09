package app.winters.octo.playback

import android.media.MediaCodec
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.inspector.MediaExtractorCompat
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.max

// The scout gives up on a stretch after this long and the plan does without it.
const val SCOUT_BUDGET_MS = 20_000L

// How much of a song the scout listens to: the end of the playing song and
// the start of the next.
const val TAIL_SCOUT_MS = 60_000L
const val HEAD_SCOUT_MS = 30_000L

// How long one wait on the decoder may take.
private const val CODEC_WAIT_US = 10_000L

// Decodes a stretch of a song into its envelope, the way the player would
// read it, on a thread of its own. One stretch at a time; each can be
// cancelled, and each gives up after SCOUT_BUDGET_MS.
@OptIn(UnstableApi::class)
class Scout(private val sources: DataSource.Factory, private val extractors: ExtractorsFactory) {
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "octo-scout").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }
    private val main = Handler(Looper.getMainLooper())

    // A stretch being read; cancel() stops it and keeps its answer from coming.
    class Job internal constructor() {
        @Volatile var cancelled = false
            private set
        internal var future: Future<*>? = null

        fun cancel() {
            cancelled = true
            future?.cancel(false)
        }
    }

    // Reads `uri` from `fromMs` to `toMs` and hands the envelope (or null,
    // when it could not) to `done` on the main thread.
    fun read(uri: Uri, fromMs: Long, toMs: Long, done: (SectionEnvelope?) -> Unit): Job {
        val job = Job()
        job.future = worker.submit {
            if (job.cancelled) return@submit
            val started = SystemClock.elapsedRealtime()
            val envelope = try {
                decodeSection(uri, fromMs, toMs) { job.cancelled || SystemClock.elapsedRealtime() - started > SCOUT_BUDGET_MS }
            } catch (e: NotSeekable) {
                Log.i("Octo", "automix: not scouting ${uri.lastPathSegment}: ${e.message}")
                null
            } catch (e: Exception) {
                Log.i("Octo", "automix: scouting ${uri.lastPathSegment} failed: $e")
                null
            }
            if (!job.cancelled) main.post { if (!job.cancelled) done(envelope) }
        }
        return job
    }

    fun release() {
        worker.shutdownNow()
    }

    private fun decodeSection(uri: Uri, fromMs: Long, toMs: Long, stop: () -> Boolean): SectionEnvelope? {
        val extractor = MediaExtractorCompat(extractors, sources)
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(uri, 0)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            if (fromMs > 0) extractor.seekTo(fromMs * 1_000, MediaExtractorCompat.SEEK_TO_PREVIOUS_SYNC)
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()

            val info = MediaCodec.BufferInfo()
            var builder: EnvelopeBuilder? = null
            var rate = 0
            var channels = 0
            var floats = false
            var inputDone = false
            var shorts = ShortArray(0)
            var samples = FloatArray(0)
            while (!stop()) {
                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(CODEC_WAIT_US)
                    if (index >= 0) {
                        val buffer = decoder.getInputBuffer(index)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val index = decoder.dequeueOutputBuffer(info, CODEC_WAIT_US)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val out = decoder.outputFormat
                    val newRate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    val newChannels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    // A stretch that changes its format halfway is not worth the trouble.
                    if (builder != null && (newRate != rate || newChannels != channels)) break
                    rate = newRate
                    channels = newChannels
                    floats = out.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        out.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                    continue
                }
                if (index < 0) continue
                val buffer = decoder.getOutputBuffer(index)!!.order(ByteOrder.nativeOrder())
                buffer.position(info.offset)
                buffer.limit(info.offset + info.size)
                if (rate == 0) {
                    val out = decoder.outputFormat
                    rate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
                val count = if (floats) info.size / 4 else info.size / 2
                if (samples.size < count) samples = FloatArray(count)
                if (floats) {
                    buffer.asFloatBuffer().get(samples, 0, count)
                } else {
                    if (shorts.size < count) shorts = ShortArray(count)
                    buffer.asShortBuffer().get(shorts, 0, count)
                    for (i in 0 until count) samples[i] = shorts[i] / 32_768f
                }
                val startMs = info.presentationTimeUs / 1_000.0
                decoder.releaseOutputBuffer(index, false)
                val frames = count / max(1, channels)
                // Frames before the stretch (a seek lands on the frame before) are left out.
                val skip = if (startMs < fromMs) ((fromMs - startMs) * rate / 1_000).toInt().coerceAtMost(frames) else 0
                if (frames > skip) {
                    val b = builder ?: EnvelopeBuilder(rate, channels, max(fromMs.toDouble(), startMs).toLong()).also { builder = it }
                    b.push(samples, skip * channels, (frames - skip) * channels)
                }
                val endMs = startMs + frames * 1_000.0 / rate
                if (endMs >= toMs || info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
            if (stop()) return null
            return builder?.build()?.takeIf { it.size > 0 }
        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            codec?.release()
            extractor.release()
        }
    }
}
