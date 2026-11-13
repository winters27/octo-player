package app.winters.octo.sound

import kotlin.math.max

// The largest value seen over the last `spanNanos`, plus `extra`, for one
// term of the handover margin. Until it holds `minSamples` it never says
// less than `floor`. Mix thread only.
internal class LagWindow(
    private val spanNanos: Long,
    private val floor: Double,
    private val extra: Double,
    private val minSamples: Int,
) {
    private var at = LongArray(256)
    private var value = DoubleArray(256)
    private var head = 0
    private var count = 0

    // Samples seen since the start (the window may have dropped some).
    var seen = 0L
        private set

    fun add(nanos: Long, sample: Double) {
        if (sample.isNaN()) return
        drop(nanos)
        if (count == at.size) grow()
        val i = (head + count) % at.size
        at[i] = nanos
        value[i] = sample
        count++
        seen++
    }

    fun value(nanos: Long): Double {
        drop(nanos)
        var most = Double.NEGATIVE_INFINITY
        for (k in 0 until count) most = max(most, value[(head + k) % at.size])
        val v = if (count == 0) Double.NEGATIVE_INFINITY else most + extra
        return if (count < minSamples) max(floor, v) else v
    }

    private fun drop(nanos: Long) {
        while (count > 0 && nanos - at[head] > spanNanos) {
            head = (head + 1) % at.size
            count--
        }
    }

    private fun grow() {
        val a = LongArray(at.size * 2)
        val v = DoubleArray(at.size * 2)
        for (k in 0 until count) {
            a[k] = at[(head + k) % at.size]
            v[k] = value[(head + k) % at.size]
        }
        at = a
        value = v
        head = 0
    }
}

// What the mixer has learned of one route's head: the quantum Q (the most
// common head step) and the change interval (the longest time between
// steps), each published only from a full window.
class RouteMeasure {
    var quantum = 0L
    var largestStep = 0L
    var intervalNanos = 0L
    var latencyNanos = 0L

    val known: Boolean get() = quantum > 0
}

// One learning window of head reads on one real output: 2 ms reads that
// keep only spans taken while the output held at least two cycles of
// unplayed frames (half its buffer before a quantum is known), its
// underruns unchanged and both reads on time. It runs at least `minNanos`
// and until it has `spans` spans, at most `maxNanos`. At each kept step it
// also takes the head's lead over the presented frame: the latency at a
// step, its largest value.
internal class HeadLearning(
    private val startNanos: Long,
    private val minNanos: Long,
    private val maxNanos: Long,
    private val spans: Int,
    private val lateNanos: Long,
    private val rate: Int,
) {
    private val steps = HashMap<Long, Int>()
    private var accepted = 0
    private var longest = 0L
    private var latest = 0L
    private var lastHead = -1L
    private var lastNanos = 0L
    private var lastOnTime = false
    private var lastUnderruns = -1
    var done = false
        private set

    // A read at `nanos` scheduled for `dueNanos`, with the frame presented
    // at `nanos` by the output's timestamp (or -1). Returns true once the
    // window has ended (published into `into` when it had enough spans).
    fun read(head: Long, nanos: Long, dueNanos: Long, unplayed: Long, bufferFrames: Int, underruns: Int, known: RouteMeasure, into: RouteMeasure, presented: Long = -1): Boolean {
        if (done) return true
        if (nanos < startNanos) return false
        val onTime = nanos - dueNanos <= lateNanos
        val held = if (known.known) unplayed >= 2 * known.quantum else unplayed >= bufferFrames / 2
        val steady = underruns == lastUnderruns
        if (lastHead >= 0 && head != lastHead) {
            if (lastNanos > 0 && onTime && lastOnTime && held && steady) {
                val step = head - lastHead
                if (step > 0) {
                    steps[step] = (steps[step] ?: 0) + 1
                    longest = max(longest, nanos - lastNanos)
                    accepted++
                    if (presented >= 0) latest = max(latest, head - presented)
                }
            }
            lastNanos = nanos
        } else if (lastHead < 0) {
            lastNanos = 0
        }
        if (!held || !steady) lastNanos = 0
        lastHead = head
        lastOnTime = onTime
        lastUnderruns = underruns
        val ran = nanos - startNanos
        if ((ran >= minNanos && accepted >= spans) || ran >= maxNanos) {
            done = true
            if (accepted >= spans) publish(into)
            return true
        }
        return false
    }

    private fun publish(into: RouteMeasure) {
        var best = 0L
        var bestCount = 0
        var largest = 0L
        for ((step, n) in steps) {
            if (n > bestCount || (n == bestCount && step > best)) {
                best = step
                bestCount = n
            }
        }
        // Steps near the quantum (an alternating resampled route) count as
        // its jitter; partial cycles and late reads spanning two do not.
        for ((step, n) in steps) if (n * 4 >= bestCount && step <= best * 3 / 2) largest = max(largest, step)
        into.quantum = best
        into.largestStep = max(best, largest)
        into.intervalNanos = longest
        if (latest > 0) into.latencyNanos = latest * 1_000_000_000L / rate
    }
}
