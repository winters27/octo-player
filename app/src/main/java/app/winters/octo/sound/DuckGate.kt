package app.winters.octo.sound

import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build

// The system's list of playing players, as an app may read it: how many
// entries carry Octo's own music attributes. Entries are anonymized, Octo's
// own included, so a count is all it can say.
fun interface PlaybackList {
    fun musicEntries(): Int
}

// The phone's list: entries whose usage, content type, public flags and
// capture policy equal Octo's real output's.
class SystemPlaybackList(private val audio: AudioManager, private val attributes: AudioAttributes) : PlaybackList {
    override fun musicEntries(): Int {
        var n = 0
        for (c in audio.activePlaybackConfigurations) {
            val a = c.audioAttributes
            if (a.usage != attributes.usage || a.contentType != attributes.contentType || a.flags != attributes.flags) continue
            if (Build.VERSION.SDK_INT >= 29 && a.allowedCapturePolicy != attributes.allowedCapturePolicy) continue
            n++
        }
        return n
    }
}

// Why a start waited for the bound, from Octo's own log of its outputs.
enum class GateBound { NONE, OWN_ENTRY, OTHER_DEPARTURE, LATE_LISTING }

// One gated start, for the log and the debug rig.
class GateRecord(
    val output: Int,
    val baseline: Int,
    val playNanos: Long,
    val signalNanos: Long,
    val signalled: Boolean,
    // Counts read until the signal or the bound, and after a bound hit for
    // the log's second.
    val reads: IntArray,
    val readNanos: LongArray,
    val truncated: Boolean,
    val cause: GateBound,
)

// Polls the list on its own thread after a fresh output's play() until it
// holds one more music entry than the lowest count read since the
// baseline (the read just before play(), taken on the mix thread), or the
// bound passes. Reads run back to back for the first burst, then one every
// `readMs`. After a bound hit it keeps reading for the log until
// `logMs` or the next start.
internal class DuckGate(
    private val list: PlaybackList,
    private val gateLoop: MixLoop,
    private val tuning: MixTuning,
) {
    // Raised by the mix thread at each start and cancel; a poll of an older
    // one stops.
    @Volatile private var generation = 0L

    // A start in progress on the gate thread.
    private inner class Poll(
        val gen: Long,
        val output: Int,
        val baseline: Int,
        val playNanos: Long,
        val answer: (signalled: Boolean, nanos: Long, record: GateRecord?) -> Unit,
        val classify: (playNanos: Long, boundNanos: Long) -> Boolean,
        val otherArrived: (fromNanos: Long, toNanos: Long) -> Boolean,
    ) : Runnable {
        var lowest = baseline
        var reads = IntArray(64)
        var times = LongArray(64)
        var n = 0
        var burst = 0
        var bounded = false
        var boundAt = 0L

        override fun run() {
            if (gen != generation && !bounded) return
            val now = gateLoop.nanoTime()
            val count = list.musicEntries()
            if (n == reads.size) {
                reads = reads.copyOf(n * 2)
                times = times.copyOf(n * 2)
            }
            reads[n] = count
            times[n] = now
            n++
            if (bounded) {
                // The log's extra reads end at the next start or after their span.
                if (gen != generation || now - boundAt >= tuning.gateLogMs * 1_000_000) {
                    finishLog(truncated = gen != generation)
                } else {
                    gateLoop.postAt(now + tuning.gateReadMs * 1_000_000, this)
                }
                return
            }
            if (count >= lowest + 1) {
                answer(true, now, null)
                return
            }
            if (count < lowest) lowest = count
            if (now - playNanos >= tuning.gateBoundMs * 1_000_000) {
                bounded = true
                boundAt = now
                answer(false, now, null)
                gateLoop.postAt(now + tuning.gateReadMs * 1_000_000, this)
                return
            }
            val inBurst = now - playNanos < tuning.gateBurstMs * 1_000_000 && burst < MOST_BURST_READS
            if (inBurst) {
                burst++
                gateLoop.post(this)
            } else {
                gateLoop.postAt(now + tuning.gateReadMs * 1_000_000, this)
            }
        }

        // A rise after the bound is a late listing of Octo's own start, unless
        // another app's music player was logged arriving by then.
        private fun finishLog(truncated: Boolean) {
            var riseAt = -1L
            for (i in 0 until n) if (riseAt < 0 && times[i] > boundAt && reads[i] >= lowest + 1) riseAt = times[i]
            val lateListing = riseAt >= 0 && !otherArrived(playNanos, riseAt)
            val own = classify(playNanos, boundAt)
            val cause = when {
                lateListing -> GateBound.LATE_LISTING
                own -> GateBound.OWN_ENTRY
                else -> GateBound.OTHER_DEPARTURE
            }
            answer(false, boundAt, GateRecord(output, baseline, playNanos, boundAt, false, reads.copyOf(n), times.copyOf(n), truncated, cause))
        }
    }

    // Mix thread: the baseline, read just before play().
    fun baseline(): Int = list.musicEntries()

    // Mix thread: starts polling for `output`, played at `playNanos`.
    // `answer` runs on the gate thread: once with the signal or the bound,
    // and after a bound hit once more with the classified record.
    fun start(
        output: Int,
        baseline: Int,
        playNanos: Long,
        answer: (signalled: Boolean, nanos: Long, record: GateRecord?) -> Unit,
        classify: (playNanos: Long, boundNanos: Long) -> Boolean,
        otherArrived: (fromNanos: Long, toNanos: Long) -> Boolean = { _, _ -> false },
    ): Long {
        val gen = ++generation
        gateLoop.post(Poll(gen, output, baseline, playNanos, answer, classify, otherArrived))
        return gen
    }

    // Mix thread: a start that no longer waits (its track paused or went).
    fun cancel() {
        generation++
    }

    private companion object {
        // Back-to-back reads at most this many, whatever the clock says.
        const val MOST_BURST_READS = 400
    }
}
