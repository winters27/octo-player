package app.winters.octo.playback

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

// The quietest a silence gate can be, in dBFS.
const val SILENCE_FLOOR_DB = -60.0

// How far below a song's body level its silence gate sits.
const val SILENCE_BELOW_BODY_DB = 45.0

// How far below the body level the song has to stay for its outro to begin.
const val OUTRO_BELOW_BODY_DB = 6.0

// How much the level must change between the two seconds before and after a
// moment for it to count as a section boundary.
const val BOUNDARY_STEP_DB = 4.0

// The tempo range the beat finder looks in.
const val SLOWEST_BPM = 70.0
const val FASTEST_BPM = 180.0

// A tempo is trusted when its autocorrelation peak stands this far above
// the mean of the range.
const val TEMPO_CONFIDENT = 1.5

// Beats in a bar.
const val BEATS_PER_BAR = 4

// Below this autocorrelation peak (dB squared per hop) the onsets are too
// faint to carry a beat, so no tempo is reported.
private const val FAINTEST_BEAT = 0.01

private const val BODY_WINDOW_MS = 400
private const val SOUND_WINDOW_MS = 50
private const val OUTRO_WINDOW_MS = 1000
private const val BOUNDARY_SIDE_MS = 2000

// A song's beat grid. Beat times are song times in milliseconds: beats fall
// on firstBeatMs + n * beatMs and bars start on downbeatMs + n * barMs.
data class Tempo(
    val bpm: Double,
    val confidence: Double,
    val beatMs: Double,
    val firstBeatMs: Double,
    val downbeatMs: Double,
) {
    val confident: Boolean get() = confidence >= TEMPO_CONFIDENT

    val barMs: Double get() = beatMs * BEATS_PER_BAR

    // The beat nearest a time, extending the grid either way.
    fun nearestBeat(ms: Double): Double = firstBeatMs + roundHalfUp((ms - firstBeatMs) / beatMs) * beatMs

    // Every bar line from fromMs to toMs, both included.
    fun barsBetween(fromMs: Double, toMs: Double): List<Double> {
        val first = ceil((fromMs - downbeatMs) / barMs - 1e-9)
        val bars = ArrayList<Double>()
        var n = first
        while (true) {
            val at = downbeatMs + n * barMs
            if (at > toMs + 1e-6) break
            bars += at
            n += 1
        }
        return bars
    }

    // The first bar line at or after a time.
    fun downbeatFrom(ms: Double): Double = downbeatMs + ceil((ms - downbeatMs) / barMs - 1e-9) * barMs

    // Every beat from fromMs to toMs, both included.
    fun beatsBetween(fromMs: Double, toMs: Double): List<Double> {
        val first = ceil((fromMs - firstBeatMs) / beatMs - 1e-9)
        val beats = ArrayList<Double>()
        var n = first
        while (true) {
            val at = firstBeatMs + n * beatMs
            if (at > toMs + 1e-6) break
            beats += at
            n += 1
        }
        return beats
    }
}

// What the planner needs to know about one stretch of a song. Times are
// song times in milliseconds. The sound times are null when the stretch is
// silent throughout; the tempo is null when no beat was found.
data class SectionFeatures(
    // The level of the song's body: the 75th percentile of its 400 ms level
    // over the hops above the silence gate.
    val bodyDb: Double,
    // Below this the song counts as silent.
    val gateDb: Double,
    val soundStartMs: Long?,
    val soundEndMs: Long?,
    // From here on the song never again comes within 6 dB of its body level.
    val outroStartMs: Long?,
    // Where the level steps up or down by 4 dB or more, two seconds either side.
    val boundariesMs: List<Long>,
    val tempo: Tempo?,
)

// An envelope with the features found in it.
class SectionAnalysis(val envelope: SectionEnvelope, val features: SectionFeatures) {
    private val power = powerOf(envelope.db)
    private val prefix = prefixOf(power)
    private val body = smoothed(prefix, framesOf(BODY_WINDOW_MS, envelope.hopMs))

    // The mean level from fromMs to toMs in dBFS, power averaged, over the
    // part of that span the envelope covers; SILENT_DB when it covers none.
    fun levelDb(fromMs: Double, toMs: Double): Double {
        val from = max(0, envelope.indexAt(fromMs))
        val to = min(envelope.size, envelope.indexAt(toMs))
        if (to <= from) return SILENT_DB.toDouble()
        return dbOf((prefix[to] - prefix[from]) / (to - from)).toDouble()
    }

    // The share of the hops from fromMs to toMs whose 400 ms level is at or
    // above thresholdDb; 0 when the envelope covers none of them.
    fun shareAtLeast(fromMs: Double, toMs: Double, thresholdDb: Double): Double {
        val from = max(0, envelope.indexAt(fromMs))
        val to = min(envelope.size, envelope.indexAt(toMs))
        if (to <= from) return 0.0
        var at = 0
        for (i in from until to) if (body[i] >= thresholdDb) at++
        return at.toDouble() / (to - from)
    }
}

// Finds the features of an envelope.
fun analyzeSection(envelope: SectionEnvelope): SectionAnalysis {
    val hop = envelope.hopMs
    val size = envelope.size
    val prefix = prefixOf(powerOf(envelope.db))
    val body = smoothed(prefix, framesOf(BODY_WINDOW_MS, hop))
    val sound = smoothed(prefix, framesOf(SOUND_WINDOW_MS, hop))
    val outro = smoothed(prefix, framesOf(OUTRO_WINDOW_MS, hop))

    // The gate depends on the body level and the body level on the gate, so
    // the body is measured above the floor first, then above its own gate.
    var gate = SILENCE_FLOOR_DB
    var bodyDb = SILENT_DB.toDouble()
    repeat(2) {
        val level = upperQuartile(body, gate) ?: return@repeat
        bodyDb = level
        gate = max(SILENCE_FLOOR_DB, level - SILENCE_BELOW_BODY_DB)
    }

    var first = -1
    var last = -1
    for (i in 0 until size) {
        if (sound[i] > gate) {
            if (first < 0) first = i
            last = i
        }
    }
    val soundStart = if (first < 0) null else envelope.timeOf(first)
    val soundEnd = if (last < 0) null else envelope.timeOf(last + 1)

    val outroStart = soundEnd?.let { end ->
        var lastLoud = -1
        for (i in 0 until size) if (outro[i] >= bodyDb - OUTRO_BELOW_BODY_DB) lastLoud = i
        min(envelope.timeOf(lastLoud + 1), end)
    }

    val features = SectionFeatures(
        bodyDb = bodyDb,
        gateDb = gate,
        soundStartMs = soundStart,
        soundEndMs = soundEnd,
        outroStartMs = outroStart,
        boundariesMs = boundaries(envelope, body),
        tempo = if (soundStart == null) null else tempoOf(envelope),
    )
    return SectionAnalysis(envelope, features)
}

private fun framesOf(ms: Int, hopMs: Int): Int = max(1, ms / hopMs)

private fun powerOf(db: FloatArray): DoubleArray = DoubleArray(db.size) { 10.0.pow(db[it] / 10.0) }

private fun prefixOf(power: DoubleArray): DoubleArray {
    val prefix = DoubleArray(power.size + 1)
    for (i in power.indices) prefix[i + 1] = prefix[i] + power[i]
    return prefix
}

// The level of each hop averaged, as power, with the hops up to half the
// window either side (fewer at the edges).
private fun smoothed(prefix: DoubleArray, window: Int): DoubleArray {
    val size = prefix.size - 1
    val half = window / 2
    return DoubleArray(size) { i ->
        val from = max(0, i - half)
        val to = min(size, i + half + 1)
        dbOf((prefix[to] - prefix[from]) / (to - from)).toDouble()
    }
}

// The 75th percentile of the levels above a gate, the lower of the two
// neighbors when it falls between two, or null when none is above it.
private fun upperQuartile(levels: DoubleArray, gate: Double): Double? {
    val above = levels.filter { it > gate }.sorted()
    if (above.isEmpty()) return null
    return above[floor(0.75 * (above.size - 1)).toInt()]
}

// Hops where the mean of the 400 ms level (in dB) over the two seconds
// after differs from the two seconds before by BOUNDARY_STEP_DB or more,
// keeping only the strongest change within two seconds either way. Earlier
// wins a tie.
private fun boundaries(envelope: SectionEnvelope, body: DoubleArray): List<Long> {
    val side = framesOf(BOUNDARY_SIDE_MS, envelope.hopMs)
    val size = envelope.size
    if (size < 2 * side) return emptyList()
    val prefix = prefixOf(body)
    val change = DoubleArray(size)
    for (i in side until size - side + 1) {
        if (i >= size) break
        val before = (prefix[i] - prefix[i - side]) / side
        val after = (prefix[i + side] - prefix[i]) / side
        change[i] = abs(after - before)
    }
    val found = ArrayList<Int>()
    for (i in side until size) {
        val step = change[i]
        if (step < BOUNDARY_STEP_DB) continue
        var peak = true
        for (j in max(0, i - side)..min(size - 1, i + side)) {
            if (j < i && change[j] >= step) { peak = false; break }
            if (j > i && change[j] > step) { peak = false; break }
        }
        if (peak && found.none { abs(it - i) < side }) found += i
    }
    return found.map(envelope::timeOf)
}

// The beat grid from the onsets: the strongest autocorrelation lag between
// FASTEST_BPM and SLOWEST_BPM, refined with a parabola through its
// neighbors; the beat phase with the largest onset sum along the grid; and
// of the four beats in a bar, the one whose beats carry the most bass onset.
private fun tempoOf(envelope: SectionEnvelope): Tempo? {
    val onset = envelope.onset
    val size = onset.size
    val hop = envelope.hopMs.toDouble()
    val perMinute = 60_000.0 / hop
    val shortest = floor(perMinute / FASTEST_BPM).toInt()
    val longest = ceil(perMinute / SLOWEST_BPM).toInt()
    if (size < 2 * (longest + 1)) return null

    val r = DoubleArray(longest + 2)
    for (lag in shortest - 1..longest + 1) {
        var sum = 0.0
        for (i in 0 until size - lag) sum += onset[i].toDouble() * onset[i + lag]
        r[lag] = sum / (size - lag)
    }
    var peak = shortest
    var mean = 0.0
    for (lag in shortest..longest) {
        mean += r[lag]
        if (r[lag] > r[peak]) peak = lag
    }
    mean /= longest - shortest + 1
    if (mean <= 0 || r[peak] < FAINTEST_BEAT) return null
    val confidence = r[peak] / mean

    val left = r[peak - 1]
    val right = r[peak + 1]
    val curve = left - 2 * r[peak] + right
    val shift = if (curve < 0) (0.5 * (left - right) / curve).coerceIn(-0.5, 0.5) else 0.0
    val period = peak + shift

    var phase = 0
    var best = -1.0
    for (p in 0 until ceil(period).toInt()) {
        val sum = combMean(onset, p.toDouble(), period, 0, 1)
        if (sum > best) {
            best = sum
            phase = p
        }
    }

    val bass = FloatArray(size) { i -> if (i == 0) 0f else max(0f, envelope.lowDb[i] - envelope.lowDb[i - 1]) }
    var bar = 0
    var bestBass = -1.0
    for (q in 0 until BEATS_PER_BAR) {
        val sum = combMean(bass, phase.toDouble(), period, q, BEATS_PER_BAR)
        if (sum > bestBass) {
            bestBass = sum
            bar = q
        }
    }

    val beatMs = period * hop
    val firstBeat = envelope.startMs + phase * hop
    return Tempo(
        bpm = 60_000.0 / beatMs,
        confidence = confidence,
        beatMs = beatMs,
        firstBeatMs = firstBeat,
        downbeatMs = firstBeat + bar * beatMs,
    )
}

// The mean of values at phase + n * period (rounded to a hop), taking every
// `every`th beat from beat `from`.
private fun combMean(values: FloatArray, phase: Double, period: Double, from: Int, every: Int): Double {
    var sum = 0.0
    var count = 0
    var n = from
    while (true) {
        val i = roundHalfUp(phase + n * period).toInt()
        if (i >= values.size) break
        sum += values[i]
        count++
        n += every
    }
    return if (count == 0) 0.0 else sum / count
}

// Rounds halves up, the same in every language.
internal fun roundHalfUp(x: Double): Double = floor(x + 0.5)
