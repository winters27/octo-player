package app.winters.octo.playback

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

// The quietest a silence gate can be, in dBFS.
const val SILENCE_FLOOR_DB = -60.0

// How far below a song's body level its silence gate sits.
const val SILENCE_BELOW_BODY_DB = 45.0

// How far below the body level the song has to stay for its outro to begin.
const val OUTRO_BELOW_BODY_DB = 6.0

// How much the level must change between the two seconds before and after a
// moment for it to count as a section boundary.
const val BOUNDARY_STEP_DB = 4.0

// The body level is this percentile of the 400 ms level. A song's start, or
// the whole song heard as it plays, uses HEAD_BODY_PERCENTILE; the end of a
// song alone uses TAIL_BODY_PERCENTILE, since a tail that is mostly outro
// would otherwise read too quiet.
const val HEAD_BODY_PERCENTILE = 0.75
const val TAIL_BODY_PERCENTILE = 0.9

// The tempo range the beat finder looks in.
const val SLOWEST_BPM = 70.0
const val FASTEST_BPM = 180.0

// A tempo is trusted when its autocorrelation peak stands this far above
// the mean of the range and this share of its beats land on an onset peak.
const val TEMPO_CONFIDENT = 1.5
const val BEAT_CONSISTENT = 0.55

// How near an onset peak a beat has to fall to count as landing on it.
const val BEAT_HIT_MS = 35.0

// Beats in a bar.
const val BEATS_PER_BAR = 4

// Below this autocorrelation peak the onsets are too faint to carry a beat,
// so no tempo is reported.
private const val FAINTEST_BEAT = 1e-4

// An onset peak must reach at least this, and one standard deviation above
// the mean onset of the sounding part.
private const val FAINTEST_PEAK = 0.05

private const val BODY_WINDOW_MS = 400
private const val SOUND_WINDOW_MS = 50
private const val OUTRO_WINDOW_MS = 1000
private const val BOUNDARY_SIDE_MS = 2000

// A song's beat grid. Beat times are song times in milliseconds: beats fall
// on firstBeatMs + n * beatMs and bars start on downbeatMs + n * barMs.
// `consistency` is the share of the grid's beats that land on an onset peak.
data class Tempo(
    val bpm: Double,
    val confidence: Double,
    val consistency: Double,
    val beatMs: Double,
    val firstBeatMs: Double,
    val downbeatMs: Double,
) {
    val confident: Boolean get() = confidence >= TEMPO_CONFIDENT && consistency >= BEAT_CONSISTENT

    val barMs: Double get() = beatMs * BEATS_PER_BAR

    // The beat nearest a time, extending the grid either way.
    fun nearestBeat(ms: Double): Double = firstBeatMs + roundHalfUp((ms - firstBeatMs) / beatMs) * beatMs

    // Every bar line from fromMs to toMs, both included.
    fun barsBetween(fromMs: Double, toMs: Double): List<Double> = gridBetween(downbeatMs, barMs, fromMs, toMs)

    // Every beat from fromMs to toMs, both included.
    fun beatsBetween(fromMs: Double, toMs: Double): List<Double> = gridBetween(firstBeatMs, beatMs, fromMs, toMs)

    // The first bar line at or after a time.
    fun downbeatFrom(ms: Double): Double = downbeatMs + ceil((ms - downbeatMs) / barMs - 1e-9) * barMs

    // The same grid at double or half the tempo, as often as it takes to
    // come within a factor of sqrt 2 of `bpm`.
    fun inOctaveOf(bpm: Double): Tempo {
        if (bpm <= 0) return this
        var factor = 1.0
        while (this.bpm * factor > bpm * SQRT_2) factor /= 2
        while (this.bpm * factor < bpm / SQRT_2) factor *= 2
        if (factor == 1.0) return this
        return copy(bpm = this.bpm * factor, beatMs = beatMs / factor)
    }
}

private const val SQRT_2 = 1.4142135623730951

private fun gridBetween(anchor: Double, step: Double, fromMs: Double, toMs: Double): List<Double> {
    val points = ArrayList<Double>()
    var n = ceil((fromMs - anchor) / step - 1e-9)
    while (true) {
        val at = anchor + n * step
        if (at > toMs + 1e-6) break
        points += at
        n += 1
    }
    return points
}

// What the planner needs to know about one stretch of a song. Times are
// song times in milliseconds. The sound times are null when the stretch is
// silent throughout; the tempo is null when no beat was found.
data class SectionFeatures(
    // The level of the song's body, in dBFS.
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
class SectionAnalysis private constructor(
    val envelope: SectionEnvelope,
    val features: SectionFeatures,
    private val prefix: DoubleArray,
    private val body: DoubleArray,
    private val sound: DoubleArray,
    private val outro: DoubleArray,
) {
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

    // The same section measured against a body level known from elsewhere,
    // such as the whole song heard as it played: the gate, sound times and
    // outro move with it; the boundaries and tempo stay.
    fun withBodyLevel(bodyDb: Double): SectionAnalysis {
        val levels = levelsFor(envelope, bodyDb, sound, outro)
        return SectionAnalysis(
            envelope,
            features.copy(
                bodyDb = bodyDb,
                gateDb = levels.gate,
                soundStartMs = levels.soundStart,
                soundEndMs = levels.soundEnd,
                outroStartMs = levels.outroStart,
            ),
            prefix, body, sound, outro,
        )
    }

    companion object {
        // Finds the features of an envelope. `tagBpm`, when the song's tags
        // carry a tempo, picks between double and half time. `bodyLevelDb`,
        // when known from elsewhere, replaces the body level measured here
        // at `bodyPercentile`.
        fun of(
            envelope: SectionEnvelope,
            tagBpm: Double? = null,
            bodyLevelDb: Double? = null,
            bodyPercentile: Double = HEAD_BODY_PERCENTILE,
        ): SectionAnalysis {
            val hop = envelope.hopMs
            val prefix = prefixOf(powerOf(envelope.db))
            val body = smoothed(prefix, framesOf(BODY_WINDOW_MS, hop))
            val sound = smoothed(prefix, framesOf(SOUND_WINDOW_MS, hop))
            val outro = smoothed(prefix, framesOf(OUTRO_WINDOW_MS, hop))
            val bodyDb = bodyLevelDb ?: bodyLevelOf(body, bodyPercentile)
            val levels = levelsFor(envelope, bodyDb, sound, outro)
            val features = SectionFeatures(
                bodyDb = bodyDb,
                gateDb = levels.gate,
                soundStartMs = levels.soundStart,
                soundEndMs = levels.soundEnd,
                outroStartMs = levels.outroStart,
                boundariesMs = boundaries(envelope, body),
                tempo = if (levels.soundStart == null) null else tempoOf(envelope, levels.first, levels.last, tagBpm),
            )
            return SectionAnalysis(envelope, features, prefix, body, sound, outro)
        }
    }
}

// The features of the start of a song.
fun analyzeHead(envelope: SectionEnvelope, tagBpm: Double? = null): SectionAnalysis =
    SectionAnalysis.of(envelope, tagBpm, bodyPercentile = HEAD_BODY_PERCENTILE)

// The features of the end of a song. Without a body level from the whole
// song, the tail's own upper level stands in for it.
fun analyzeTail(envelope: SectionEnvelope, tagBpm: Double? = null, bodyLevelDb: Double? = null): SectionAnalysis =
    SectionAnalysis.of(envelope, tagBpm, bodyLevelDb, TAIL_BODY_PERCENTILE)

// The body level of a whole envelope: the given percentile of its 400 ms
// level over the hops above the silence gate, or null when it is silent.
fun bodyLevelOf(envelope: SectionEnvelope, percentile: Double = HEAD_BODY_PERCENTILE): Double? {
    val body = smoothed(prefixOf(powerOf(envelope.db)), framesOf(BODY_WINDOW_MS, envelope.hopMs))
    return bodyLevelOf(body, percentile).takeIf { it > SILENT_DB }
}

// The gate depends on the body level and the body level on the gate, so the
// body is measured above the floor first, then above its own gate.
private fun bodyLevelOf(body: DoubleArray, percentile: Double): Double {
    var gate = SILENCE_FLOOR_DB
    var bodyDb = SILENT_DB.toDouble()
    repeat(2) {
        val level = percentileAbove(body, gate, percentile) ?: return@repeat
        bodyDb = level
        gate = max(SILENCE_FLOOR_DB, level - SILENCE_BELOW_BODY_DB)
    }
    return bodyDb
}

private class Levels(val gate: Double, val first: Int, val last: Int, val soundStart: Long?, val soundEnd: Long?, val outroStart: Long?)

private fun levelsFor(envelope: SectionEnvelope, bodyDb: Double, sound: DoubleArray, outro: DoubleArray): Levels {
    val gate = max(SILENCE_FLOOR_DB, bodyDb - SILENCE_BELOW_BODY_DB)
    var first = -1
    var last = -1
    for (i in sound.indices) {
        if (sound[i] > gate) {
            if (first < 0) first = i
            last = i
        }
    }
    val soundStart = if (first < 0) null else envelope.timeOf(first)
    val soundEnd = if (last < 0) null else envelope.timeOf(last + 1)
    val outroStart = soundEnd?.let { end ->
        var lastLoud = -1
        for (i in outro.indices) if (outro[i] >= bodyDb - OUTRO_BELOW_BODY_DB) lastLoud = i
        min(envelope.timeOf(lastLoud + 1), end)
    }
    return Levels(gate, first, last, soundStart, soundEnd, outroStart)
}

private fun framesOf(ms: Int, hopMs: Int): Int = max(1, ms / hopMs)

private fun powerOf(db: FloatArray): DoubleArray = DoubleArray(db.size) { 10.0.pow(db[it] / 10.0) }

private fun prefixOf(values: DoubleArray): DoubleArray {
    val prefix = DoubleArray(values.size + 1)
    for (i in values.indices) prefix[i + 1] = prefix[i] + values[i]
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

// A percentile of the levels above a gate, the lower of the two neighbors
// when it falls between two, or null when none is above it.
private fun percentileAbove(levels: DoubleArray, gate: Double, percentile: Double): Double? {
    val above = levels.filter { it > gate }.sorted()
    if (above.isEmpty()) return null
    return above[floor(percentile * (above.size - 1)).toInt()]
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
// neighbors and moved to the tag's octave when there is a tag; the beat
// phase with the largest onset sum along the grid; and of the four beats in
// a bar, the one whose beats carry the most bass onset. The consistency is
// measured over the sounding hops first to last.
private fun tempoOf(envelope: SectionEnvelope, first: Int, last: Int, tagBpm: Double?): Tempo? {
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
    var period = peak + shift
    if (tagBpm != null && tagBpm > 0) {
        while (perMinute / period > tagBpm * SQRT_2) period *= 2
        while (perMinute / period < tagBpm / SQRT_2) period /= 2
    }

    var bestPhase = 0
    var best = -1.0
    for (p in 0 until ceil(period).toInt()) {
        val sum = combMean(onset, p.toDouble(), period, 0, 1)
        if (sum > best) {
            best = sum
            bestPhase = p
        }
    }
    var phase = bestPhase.toDouble()

    // Fit the grid to the onset peaks the beats land on, which corrects a
    // period read off whole hops: first to the peaks within a quarter beat,
    // then twice to those within BEAT_HIT_MS.
    val peaks = onsetPeaks(envelope, first, last)
    for (pass in 0 until 3) {
        val reach = if (pass == 0) period / 4 else BEAT_HIT_MS / hop
        val fit = fitGrid(peaks, phase, period, size, reach) ?: continue
        period = fit.second
        phase = fit.first - floor(fit.first / period) * period
    }

    var bar = 0
    var bestBass = -1.0
    for (q in 0 until BEATS_PER_BAR) {
        val sum = combMean(envelope.lowOnset, phase, period, q, BEATS_PER_BAR)
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
        consistency = consistencyOf(envelope, peaks, first, last, firstBeat, beatMs),
        beatMs = beatMs,
        firstBeatMs = firstBeat,
        downbeatMs = firstBeat + bar * beatMs,
    )
}

// The onset peaks from hop `first` to hop `last`, as hop indexes: a hop
// above the hop before and at least the hop after, reaching FAINTEST_PEAK
// and one standard deviation above the mean onset of those hops.
private fun onsetPeaks(envelope: SectionEnvelope, first: Int, last: Int): IntArray {
    val onset = envelope.onset
    if (last <= first) return IntArray(0)
    var sum = 0.0
    var squares = 0.0
    for (i in first..last) {
        sum += onset[i]
        squares += onset[i].toDouble() * onset[i]
    }
    val n = last - first + 1
    val mean = sum / n
    val spread = sqrt(max(0.0, squares / n - mean * mean))
    val threshold = max(FAINTEST_PEAK, mean + spread)
    val peaks = ArrayList<Int>()
    for (i in max(first, 1)..min(last, onset.size - 2)) {
        val x = onset[i]
        if (x >= threshold && x > onset[i - 1] && x >= onset[i + 1]) peaks += i
    }
    return peaks.toIntArray()
}

// A least-squares line through the peak nearest each beat of the grid
// (within `reach` hops; the earlier of two equally near): the fitted phase
// and period in hops, or null when fewer than 8 beats, or under half of
// them, find a peak.
private fun fitGrid(peaks: IntArray, phase: Double, period: Double, size: Int, reach: Double): Pair<Double, Double>? {
    var count = 0
    var beats = 0
    var sn = 0.0
    var st = 0.0
    var snn = 0.0
    var snt = 0.0
    var at = 0
    var n = 0
    while (true) {
        val beat = phase + n * period
        if (beat >= size) break
        beats++
        while (at < peaks.size && peaks[at] < beat - reach) at++
        var nearest = -1
        var j = at
        while (j < peaks.size && peaks[j] <= beat + reach) {
            if (nearest < 0 || abs(peaks[j] - beat) < abs(peaks[nearest] - beat)) nearest = j
            j++
        }
        if (nearest >= 0) {
            val t = peaks[nearest].toDouble()
            count++
            sn += n
            st += t
            snn += n.toDouble() * n
            snt += n * t
        }
        n++
    }
    if (count < 8 || count * 2 < beats) return null
    val d = count * snn - sn * sn
    if (d <= 0) return null
    val slope = (count * snt - sn * st) / d
    if (slope <= 0) return null
    return (st - slope * sn) / count to slope
}

// The share of the grid's beats from hop `first` to hop `last` that fall
// within BEAT_HIT_MS of an onset peak.
private fun consistencyOf(envelope: SectionEnvelope, peaks: IntArray, first: Int, last: Int, firstBeat: Double, beatMs: Double): Double {
    val beats = gridBetween(firstBeat, beatMs, envelope.timeOf(first).toDouble(), envelope.timeOf(last).toDouble())
    if (beats.isEmpty()) return 0.0
    var hits = 0
    var at = 0
    for (beat in beats) {
        while (at < peaks.size && envelope.timeOf(peaks[at]) < beat - BEAT_HIT_MS) at++
        if (at < peaks.size && abs(envelope.timeOf(peaks[at]) - beat) <= BEAT_HIT_MS) hits++
    }
    return hits.toDouble() / beats.size
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

// Listens to a song as it plays and keeps its envelope, so the whole song's
// body level and tempo are known when the next transition is planned. Feed
// it the same samples the listener hears; start it again for each song.
class LiveTap(sampleRate: Int, channels: Int) {
    private val builder = EnvelopeBuilder(sampleRate, channels, 0)

    fun push(samples: FloatArray, offset: Int = 0, length: Int = samples.size - offset) =
        builder.push(samples, offset, length)

    fun push(samples: ShortArray, offset: Int = 0, length: Int = samples.size - offset) =
        builder.push(samples, offset, length)

    // How much has been heard, in milliseconds.
    fun heardMs(): Long = builder.heardMs

    // The body level of everything heard, or null while it is all silence.
    fun bodyLevelDb(): Double? = bodyLevelOf(builder.build())

    // The tempo of everything heard when it is trusted, else null.
    fun tempoPrior(tagBpm: Double? = null): Double? =
        SectionAnalysis.of(builder.build(), tagBpm).features.tempo?.takeIf { it.confident }?.bpm
}
