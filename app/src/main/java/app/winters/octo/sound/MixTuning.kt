package app.winters.octo.sound

import android.media.AudioTrack
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.DefaultAudioSink

// Every number the mixer runs on, each settable so tests run in seconds.
class MixTuning(
    // One mix block.
    val blockMs: Int = 10,
    // A deck whose unmixed frames are not taken this long fails.
    val watchdogMs: Long = 10_000,
    // Main's message lag is probed this often while a deck plays.
    val probeMs: Long = 250,
    // Each lag term: the largest of the last `lagSpanMs` plus `lagExtraMs`,
    // never under `lagFloorMs` until it holds `lagSamples` samples.
    val lagFloorMs: Double = 50.0,
    val lagExtraMs: Double = 10.0,
    val lagSamples: Int = 40,
    val lagSpanMs: Long = 600_000,
    // The handover margin never goes past this (the rings' fixed room).
    val marginCapMs: Double = 1_000.0,
    // The quantum assumed for the margin before a route's is learned.
    val defaultQuantumMs: Double = 40.0,
    // The duck gate: its one bound from play(), the slack after the
    // signal, the back-to-back reads after play(), the reads after, and
    // the log's reads after a bound hit.
    val gateBoundMs: Long = 300,
    val gateSlackMs: Long = 20,
    val gateBurstMs: Long = 10,
    val gateReadMs: Long = 2,
    val gateLogMs: Long = 1_000,
    // Head reads while a paused output settles, and the bound on it.
    val headReadMs: Long = 2,
    val settleBoundMs: Long = 300,
    // The change interval taken before any route has one.
    val defaultIntervalMs: Double = 120.0,
    // A playing output whose head has not moved this long with data is stuck.
    val stuckMs: Long = 1_000,
    // The framework's duck ramp the silent fallback covers (AOSP's duck
    // shape; the device's own is hidden from apps).
    val duckRampMs: Long = 500,
    // Head learning: its start after a first music cycle or a re-route,
    // its least and most length and the spans it needs.
    val learnDelayMs: Long = 300,
    val learnMinMs: Long = 600,
    val learnMaxMs: Long = 3_000,
    val learnSpans: Int = 8,
    // A read later than this against its schedule is not learned from.
    val lateReadMs: Double = 1.0,
    // After a fresh start positions are clamped until a timestamp this long
    // after the first music cycle.
    val clampMs: Long = 100,
    // The head-to-presented latency before a route is measured, by kind:
    // speaker, wired and USB; Bluetooth; Android Auto; and the least taken
    // for a device of no known kind.
    val wiredLatencyMs: Double = 250.0,
    val bluetoothLatencyMs: Double = 400.0,
    val carLatencyMs: Double = 500.0,
    val unknownLatencyMs: Double = 250.0,
    // Media3's creation rules: retries this far apart, failing this long
    // after the first moment with no release pending.
    val retryGapMs: Long = 50,
    val retryForMs: Long = 200,
    // The onset ramp of a fresh output that continues a stream, and the
    // leave, dry-out and rejoin ramps.
    val rampMs: Double = 20.0,
    // A Sonic restart joins over this long, aligned within `alignMs`.
    val joinMs: Double = 5.0,
    val alignMs: Double = 15.0,
    // With no deck output for this long (a stop, the queue's end), the
    // outputs kept for a resume go; a seek's new output comes well inside it.
    val idleReleaseMs: Long = 1_000,
)

// Media3's PCM buffer target for float stereo at `rate`, in frames.
@UnstableApi
fun mediaPcmTargetFrames(rate: Int): Int {
    val min = try {
        AudioTrack.getMinBufferSize(rate, android.media.AudioFormat.CHANNEL_OUT_STEREO, android.media.AudioFormat.ENCODING_PCM_FLOAT)
    } catch (e: RuntimeException) {
        0
    }
    val bytes = DefaultAudioSink.AudioTrackBufferSizeProvider.DEFAULT.getBufferSizeInBytes(
        if (min > 0) min else MIX_FRAME_BYTES,
        C.ENCODING_PCM_FLOAT,
        DefaultAudioSink.OUTPUT_MODE_PCM,
        MIX_FRAME_BYTES,
        rate,
        Format.NO_VALUE,
        1.0,
    )
    return bytes / MIX_FRAME_BYTES
}
