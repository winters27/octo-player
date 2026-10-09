package app.winters.octo.playback

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// Songs shorter than this get the plain crossfade, never a planned one.
const val SHORTEST_AUTOMIX_SONG_MS = 35_000L

// The shortest and the hard limits of a planned blend.
const val SHORTEST_BLEND_MS = 1_800L
const val CUT_BLEND_MS = 2_400L

// The latest a blend may end: this long before the outgoing song's end.
const val END_MARGIN_MS = 250L

// The earliest a blend may start: this far into the song, this share of
// it, and no more than LONGEST_EARLY_EXIT_MS before the latest end.
const val EARLIEST_START_MS = 25_000L
const val EARLIEST_START_SHARE = 0.35
const val LONGEST_EARLY_EXIT_MS = 45_000L

// Without a beat grid the start is tried every this many milliseconds.
const val START_STEP_MS = 250L

// The incoming song's entry moves onto a beat this close to its first sound.
const val ENTRY_SNAP_MS = 300.0

// How the two songs meet.
enum class TransitionKind {
    // One song straight after the other, no overlap.
    GAPLESS,

    // The fixed equal-power crossfade at the end of the song.
    CROSSFADE,

    // A short planned swap.
    CUT,

    // A planned blend into a louder song.
    LIFT,

    // A planned blend.
    BLEND,
}

// What the player sets for planned transitions.
data class AutomixSettings(
    // The longest blend, from the crossfade slider; 0 turns crossfade off.
    val maxOverlapMs: Long,
    // Off plays the fixed crossfade at the end of each song.
    val smart: Boolean = true,
    val filterSweeps: Boolean = true,
    // Nudges the incoming song's speed to the outgoing song's tempo.
    val beatMatch: Boolean = false,
)

// How to move from one song to the next. At startMs into the outgoing song
// the incoming song starts playing from entryMs, and over overlapMs the
// volumes follow automixOutGain / automixInGain with curve weight k. With a
// filterStrength above 0 the filter sweeps run through the overlap and are
// bypassed once it ends. beatMatchRate, when set, is the incoming song's
// playback rate through the blend. beatMs and beatAnchorMs, when set, are
// the outgoing song's beat length and one of its beats in song time, for
// the low-pass to step on. `reason` says why this plan was chosen.
data class TransitionPlan(
    val startMs: Long,
    val entryMs: Long,
    val overlapMs: Long,
    val kind: TransitionKind,
    val k: Double,
    val filterStrength: Double,
    val beatMatchRate: Double?,
    val beatMs: Double?,
    val beatAnchorMs: Double?,
    val reason: String,
) {
    fun progressAt(outgoingMs: Double): Double =
        if (overlapMs <= 0) 1.0 else ((outgoingMs - startMs) / overlapMs).coerceIn(0.0, 1.0)

    fun outGain(t: Double): Double = automixOutGain(t, k)

    fun inGain(t: Double): Double = automixInGain(t, k)

    // The outgoing song's beats inside the blend as places through it (0 to
    // 1). A beat within a twentieth of a beat of either end counts as the
    // end itself and is left out.
    fun beatProgress(): DoubleArray {
        val beat = beatMs ?: return DoubleArray(0)
        val anchor = beatAnchorMs ?: return DoubleArray(0)
        if (overlapMs <= 0 || beat <= 0) return DoubleArray(0)
        val grid = Tempo(60_000.0 / beat, TEMPO_CONFIDENT, beat, anchor, anchor)
        val margin = beat / 20
        return grid.beatsBetween(startMs + margin, startMs + overlapMs - margin)
            .map { (it - startMs) / overlapMs }
            .toDoubleArray()
    }

    // The outgoing low-pass: stepped on the beat when the beat is known.
    fun outgoingLowPassAt(t: Double): Double {
        val beat = beatMs
        if (beat == null || overlapMs <= 0) return outgoingLowPassHz(t, filterStrength)
        return steppedOutgoingLowPassHz(t, filterStrength, beatProgress(), beat * BEATS_PER_BAR / 8 / overlapMs)
    }

    fun outgoingHighPassAt(t: Double): Double = outgoingHighPassHz(t, filterStrength)

    fun incomingHighPassAt(t: Double): Double = incomingHighPassHz(t, filterStrength)

    // The incoming song's playback rate `sinceEntryMs` after it came in.
    fun incomingRateAt(sinceEntryMs: Double): Double =
        beatMatchRate?.let { beatMatchRateAt(it, sinceEntryMs, overlapMs.toDouble()) } ?: 1.0
}

// Plans the move from the playing song to the next one. `tail` is the
// analysis of the end of the playing song and `head` of the start of the
// next one, either null when it is not ready. Every rule of crossfadeLength
// still holds: when it gives no crossfade the songs play gaplessly. Without
// both analyses, with smart transitions off, or for a song under 35 s, the
// plan is the fixed crossfade at the end of the song.
fun planTransition(
    current: FadeSong,
    next: FadeSong?,
    tail: SectionAnalysis?,
    head: SectionAnalysis?,
    settings: AutomixSettings,
    repeatOne: Boolean,
    stopAtEndOfSong: Boolean,
): TransitionPlan {
    val plain = crossfadeLength(current, next, settings.maxOverlapMs, repeatOne, stopAtEndOfSong)
    if (plain == 0L || next == null) {
        return gaplessPlan(current, gaplessReason(current, next, settings.maxOverlapMs, repeatOne, stopAtEndOfSong))
    }
    val lenA = current.durationMs
    val lenB = next.durationMs
    if (!settings.smart) return crossfadePlan(lenA, plain, "smart transitions off")
    if (lenA < SHORTEST_AUTOMIX_SONG_MS || lenB < SHORTEST_AUTOMIX_SONG_MS) {
        return crossfadePlan(lenA, plain, "a song under ${SHORTEST_AUTOMIX_SONG_MS / 1000} s")
    }
    if (tail == null || head == null) {
        val missing = listOfNotNull("this song's end".takeIf { tail == null }, "the next song's start".takeIf { head == null })
        return crossfadePlan(lenA, plain, "no analysis of ${missing.joinToString(" or ")}")
    }
    val a = tail.features
    val b = head.features
    val soundEndA = a.soundEndMs
    val outroA = a.outroStartMs
    val soundStartB = b.soundStartMs
    if (soundEndA == null || outroA == null) return crossfadePlan(lenA, plain, "this song's end is silent")
    if (soundStartB == null) return crossfadePlan(lenA, plain, "the next song's start is silent")

    val latestEnd = min(soundEndA, lenA - END_MARGIN_MS).toDouble()
    val outroStart = min(outroA.toDouble(), latestEnd)
    val tempoA = a.tempo?.takeIf { it.confident }
    val tempoB = b.tempo?.takeIf { it.confident }
    val ratio = if (tempoA != null && tempoB != null) foldTempoRatio(tempoA.bpm / tempoB.bpm) else null

    var entry = soundStartB.toDouble()
    if (tempoB != null) {
        val beat = tempoB.nearestBeat(entry)
        if (abs(beat - entry) <= ENTRY_SNAP_MS) entry = max(0.0, beat)
    }

    val limit = min(settings.maxOverlapMs.toDouble(), min(lenA, lenB) / 2.0)
    var bars = 0
    var overlap = if (tempoA != null && ratio != null) {
        bars = if (abs(ratio - 1) > 0.08) 2 else 4
        bars * tempoA.barMs
    } else {
        latestEnd - outroStart
    }
    overlap = min(max(overlap, SHORTEST_BLEND_MS.toDouble()), limit)
    if (tempoA != null && bars > 0 && overlap < bars * tempoA.barMs) {
        // Cut back to whole bars that fit, when that is still long enough.
        bars = floor(limit / tempoA.barMs + 1e-9).toInt()
        val whole = bars * tempoA.barMs
        if (bars >= 1 && whole >= SHORTEST_BLEND_MS) overlap = whole else bars = 0
    }
    overlap = floor(overlap + 0.5)

    val lo = maxOf(EARLIEST_START_SHARE * lenA, EARLIEST_START_MS.toDouble(), latestEnd - LONGEST_EARLY_EXIT_MS, tail.envelope.startMs.toDouble())
    val hi = latestEnd - overlap
    if (hi < lo) return crossfadePlan(lenA, plain, "no room for a blend before the end")

    // Bar lines when the beat is known, otherwise a fixed step back from the latest start.
    val barLines = tempoA?.barsBetween(lo, hi).orEmpty()
    val onBars = barLines.isNotEmpty()
    val candidates = barLines.ifEmpty {
        generateSequence(hi) { it - START_STEP_MS }.takeWhile { it >= lo - 1e-6 }.toList().asReversed()
    }
    val phraseAnchor = tempoA?.let { it.downbeatMs + roundHalfUp((outroStart - it.downbeatMs) / it.barMs) * it.barMs }
    val fullDb = a.bodyDb - 3

    var bestStart = candidates.last()
    var bestScore = Double.NEGATIVE_INFINITY
    for (start in candidates) {
        var score = -0.08 * (latestEnd - (start + overlap)) / 1000
        if (outroStart < latestEnd - 1000) score -= 0.15 * abs(start - outroStart) / 1000
        if (a.boundariesMs.any { abs(it - start) <= 250 }) score += 1.0
        if (tempoA != null && phraseAnchor != null && onBars) {
            score += 0.6
            val barsBack = roundHalfUp((phraseAnchor - start) / tempoA.barMs).toLong()
            if (barsBack % 8 == 0L) score += 0.8
        }
        score -= 1.5 * tail.shareAtLeast(start, start + overlap, fullDb)
        if (score >= bestScore) {
            bestScore = score
            bestStart = start
        }
    }

    var rate: Double? = null
    if (settings.beatMatch && tempoA != null && tempoB != null && ratio != null) {
        val gap = abs(ratio - 1)
        if (gap >= 0.005 && gap <= 0.04) {
            rate = ratio.coerceIn(0.94, 1.06)
            entry = max(0.0, tempoB.downbeatFrom(soundStartB - ENTRY_SNAP_MS))
        }
    }

    val kind = when {
        overlap <= CUT_BLEND_MS -> TransitionKind.CUT
        head.levelDb(entry, entry + 8_000) >= tail.levelDb(bestStart, bestStart + overlap) + 3 -> TransitionKind.LIFT
        else -> TransitionKind.BLEND
    }
    val k = when (kind) {
        TransitionKind.CUT -> CUT_CURVE
        TransitionKind.LIFT -> LIFT_CURVE
        else -> BLEND_CURVE
    }
    val start = floor(bestStart + 0.5).toLong()
    val entryMs = floor(entry + 0.5).toLong()
    val reason = buildString {
        append(kind.name.lowercase())
        append(" at ").append(seconds(start)).append(" over ").append(seconds(overlap.toLong()))
        if (bars > 0 && tempoA != null) append(" (").append(bars).append(" bars of ").append(decimals(tempoA.bpm, 1)).append(" BPM)")
        append(", next song from ").append(seconds(entryMs))
        append(", outro at ").append(seconds(outroStart.toLong()))
        append(", latest end ").append(seconds(latestEnd.toLong()))
        rate?.let { append(", rate ").append(decimals(it, 3)) }
    }
    return TransitionPlan(
        startMs = start,
        entryMs = entryMs,
        overlapMs = overlap.toLong(),
        kind = kind,
        k = k,
        filterStrength = if (settings.filterSweeps) FILTER_STRENGTH else 0.0,
        beatMatchRate = rate,
        beatMs = tempoA?.beatMs,
        beatAnchorMs = tempoA?.firstBeatMs,
        reason = reason,
    )
}

// A tempo ratio folded by halving or doubling into 0.707 to 1.414, so a
// song at half or double the tempo counts as the same tempo.
fun foldTempoRatio(ratio: Double): Double {
    if (ratio <= 0) return ratio
    var r = ratio
    while (r > 1.4142135623730951) r /= 2
    while (r < 0.7071067811865476) r *= 2
    return r
}

private fun gaplessPlan(current: FadeSong, reason: String) = TransitionPlan(
    startMs = max(0L, current.durationMs),
    entryMs = 0,
    overlapMs = 0,
    kind = TransitionKind.GAPLESS,
    k = CROSSFADE_CURVE,
    filterStrength = 0.0,
    beatMatchRate = null,
    beatMs = null,
    beatAnchorMs = null,
    reason = "gapless: $reason",
)

private fun crossfadePlan(lenA: Long, length: Long, why: String) = TransitionPlan(
    startMs = lenA - length,
    entryMs = 0,
    overlapMs = length,
    kind = TransitionKind.CROSSFADE,
    k = CROSSFADE_CURVE,
    filterStrength = 0.0,
    beatMatchRate = null,
    beatMs = null,
    beatAnchorMs = null,
    reason = "crossfade at ${seconds(lenA - length)} over ${seconds(length)}: $why",
)

private fun gaplessReason(current: FadeSong, next: FadeSong?, fadeMs: Long, repeatOne: Boolean, stopAtEndOfSong: Boolean): String = when {
    fadeMs <= 0 -> "crossfade off"
    next == null -> "nothing next"
    repeatOne -> "repeat one"
    stopAtEndOfSong -> "stopping at the end of the song"
    current.durationMs <= 0 || next.durationMs <= 0 -> "a song's length is unknown"
    current.albumId != null && current.albumId == next.albumId -> "the album plays in order"
    else -> "a song too short to blend"
}

// Milliseconds as seconds with two decimals, like "182.50 s".
private fun seconds(ms: Long): String {
    val sign = if (ms < 0) "-" else ""
    val abs = abs(ms)
    val hundredths = (abs % 1000) / 10
    return "$sign${abs / 1000}.${hundredths.toString().padStart(2, '0')} s"
}

// A positive number with a fixed count of decimals, like "120.0".
private fun decimals(x: Double, places: Int): String {
    var scale = 1L
    repeat(places) { scale *= 10 }
    val scaled = floor(x * scale + 0.5).toLong()
    return "${scaled / scale}.${(scaled % scale).toString().padStart(places, '0')}"
}
