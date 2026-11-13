package app.winters.octo.playback

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

// Songs shorter than this get the plain crossfade, never a planned one.
const val SHORTEST_AUTOMIX_SONG_MS = 35_000L

// The shortest planned blend, and the longest that still counts as a cut.
const val SHORTEST_BLEND_MS = 1_800L
const val CUT_BLEND_MS = 2_400L

// The latest a blend may end: this long before the outgoing song's end.
const val END_MARGIN_MS = 250L

// The earliest a blend may start: this far into the song, this share of
// it, once the song has played long enough to count as played (half of it
// or COUNTS_AS_PLAYED_MS, whichever is less), and no more than
// LONGEST_EARLY_EXIT_MS before the latest end.
const val EARLIEST_START_MS = 30_000L
const val EARLIEST_START_SHARE = 0.5
const val COUNTS_AS_PLAYED_MS = 240_000L
const val LONGEST_EARLY_EXIT_MS = 45_000L

// The incoming song needs this long to get ready before the blend starts.
const val PRE_ROLL_MS = 3_000L

// Without a beat grid the start is tried every this many milliseconds.
const val START_STEP_MS = 250L

// Both songs play this much quieter through the overlap, so their sum
// does not clip.
const val OVERLAP_HEADROOM_DB = 1.0

// Automix's longest blend, and its longest intro played under the end of
// the song before, both in song time.
const val AUTOMIX_LONGEST_BLEND_MS = 8_000L
const val AUTOMIX_LONGEST_INTRO_MS = 16_000L

// An intro plays under the outgoing song only when its quiet stretch lasts
// at least this long.
const val INTRO_SHORTEST_MS = 4_000L

// The outgoing song fades out under an intro over this many bars when the
// blend is locked to the bar, else over INTRO_FADE_MS.
const val INTRO_FADE_BARS = 2
const val INTRO_FADE_MS = 3_000L

// The incoming song rises to its own level over this long at the start of
// an intro (one bar when locked).
const val INTRO_RISE_MS = 1_000L

// Through an intro's hold the incoming song, with its rise, is lowered
// (the plan's introLevel) until it stays at least INTRO_UNDER_OUTGOING_DB
// under the outgoing song held at its own level, at the same moments, for
// INTRO_UNDER_OUTGOING_SHARE of the hold where it sounds: a short break in
// the outgoing song, where the incoming one plays alone, does not count.
// Then, over the whole overlap with the planned gains and the overlap's
// headroom, the two together must at every step stay under the louder of
// the two songs alone, by -INTRO_EXCESS_LIMIT_DB at least (the flat power
// sum's excess), and it is lowered further until they do. The levels are
// flat ones of the channels' mean, each song's as its deck plays it
// (TransitionContext.incomingGainDb); heard with the ear's weighting and in
// stereo the incoming song can sit a few dB nearer the outgoing one, which
// the margins leave room for. An intro that would need it lowered by over
// INTRO_TRIM_CAP_DB is not planned.
const val INTRO_UNDER_OUTGOING_DB = 8.0
const val INTRO_UNDER_OUTGOING_SHARE = 0.95
const val INTRO_EXCESS_LIMIT_DB = -0.5
const val INTRO_TRIM_CAP_DB = 30.0

// How many times the trim the overlap needs is halved between the hold's
// and INTRO_TRIM_CAP_DB.
private const val INTRO_TRIM_STEPS = 14

// Music at full level the outgoing song would lose after a blend's end:
// above KEPT_MUSIC_LIMIT_MS the start is left out, and each second below it
// costs KEPT_MUSIC_WEIGHT. Full level is within KEPT_MUSIC_BELOW_BODY_DB of
// the body.
const val KEPT_MUSIC_LIMIT_MS = 1_000L
const val KEPT_MUSIC_WEIGHT = 0.5
const val KEPT_MUSIC_BELOW_BODY_DB = 6.0

// Match tempo reaches tempos this far apart, as a share.
const val BEAT_MATCH_LARGEST_GAP = 0.06

// Wherever both songs are clearly heard together, their beats drift apart
// by at most this much; clearly heard is within BOTH_HEARD_BELOW_BODY_DB of
// a song's body.
const val DRIFT_LIMIT_MS = 40.0
const val BOTH_HEARD_BELOW_BODY_DB = 10.0

// How finely the planner walks a blend to find where both songs are heard.
private const val HEARD_STEP_MS = 10.0

// A song's grid point this close before its start counts as at its start.
private const val GRID_AT_START_MS = 1.0

// Songs in these genres get the plain crossfade, never a planned one.
val PLAIN_CROSSFADE_GENRES = Regex("classical|opera|spoken|audiobook|podcast|comedy", RegexOption.IGNORE_CASE)

// How the two songs meet.
enum class TransitionKind {
    // One song straight after the other, no overlap.
    GAPLESS,

    // The fixed crossfade at the end of the song.
    CROSSFADE,

    // A short planned swap.
    CUT,

    // A planned blend into a louder song.
    LIFT,

    // A planned blend.
    BLEND,

    // The next song's quiet start played under the end of this one: the
    // outgoing song holds its level, then fades out around the moment the
    // incoming one reaches its full level.
    INTRO,
}

// What the player sets for planned transitions.
data class AutomixSettings(
    // The longest blend: Crossfade's length, or AUTOMIX_LONGEST_BLEND_MS
    // for Automix; 0 plays every song gapless.
    val maxOverlapMs: Long,
    // The longest intro played under the end of the song before.
    val longestIntroMs: Long = AUTOMIX_LONGEST_INTRO_MS,
    // Off plays the fixed crossfade at the end of each song.
    val smart: Boolean = true,
    val filterSweeps: Boolean = true,
    // Nudges the incoming song's speed to the outgoing song's tempo.
    val beatMatch: Boolean = false,
    // The shape of the fixed crossfade, with smart transitions off. Planned
    // blends keep their own curves.
    val fadeCurve: FadeCurve = FadeCurve.EqualPower,
)

// Where the player is when it plans, and what it knows besides the scouted
// sections. `nowMs` is the outgoing song's position and `playedMs` how
// much of it has actually been heard (less than nowMs after a seek
// forward). `bodyLevelDb` and `tempoPrior` come from a LiveTap of the
// outgoing song. Skip-silence moves media time away from the beat, and so
// does a pace other than 1 unless `lockAtPace` says the player changes the
// speed of both songs together after they are mixed: either turns off bar
// lock and tempo matching. `pace` is also the speed the beats are heard at.
// `incomingGainDb` is the incoming song's deck gain less the outgoing
// song's, in dB, as the decks apply them (ReplayGain, preamp, the untagged
// fallback): an intro's trim compares the two songs as they are heard.
data class TransitionContext(
    val nowMs: Long = 0,
    val playedMs: Long = nowMs,
    val repeatOne: Boolean = false,
    val stopAtEndOfSong: Boolean = false,
    val pace: Double = 1.0,
    val skipSilence: Boolean = false,
    val currentGenre: String? = null,
    val nextGenre: String? = null,
    val bodyLevelDb: Double? = null,
    val tempoPrior: Double? = null,
    val lockAtPace: Boolean = false,
    val incomingGainDb: Double = 0.0,
)

// How to move from one song to the next. At startMs into the outgoing song
// the incoming song starts playing from entryMs, and over overlapMs the
// volumes follow automixOutGain / automixInGain with curve weight k (or,
// for a curve other than equal power, fadeOutGain / fadeInGain, and for an
// intro introOutGain / introInGain), both lowered by headroomDb. With a filterStrength above 0 the filter sweeps
// run through the overlap and are bypassed once it ends. beatMatchRate,
// when set, is the incoming song's playback rate through the blend.
// beatMs and beatAnchorMs, when set, are the outgoing song's beat length
// and one of its beats in song time: the blend is locked to the bar and the
// low-pass steps on the beat. `late` marks a blend placed at the end
// because there was no time or data to choose a better place. `reason`
// says why this plan was chosen.
//
// An intro's shape is in shares of the overlap: the outgoing song holds
// its level until holdShare, then fades out; the incoming one rises over
// riseShare (never past holdShare) to introLevel (1, or lower so it stays
// under the outgoing song), holds there, and comes up to its own level as
// the outgoing song fades. Any other kind whose incoming song enters before
// its first sound holds too: the outgoing song at its own level and the
// incoming one silent until holdShare, where that sound comes, then the
// curve over the rest. Both are 0 otherwise. `latestEndMs` is
// the latest the blend may end in the outgoing song's time: where its
// sound ends (at most END_MARGIN_MS before its end) for a planned blend,
// the end a late blend was placed against, the song's length for a fixed
// crossfade and for a gapless join. `lateMoveBars` is how many whole bars
// of the outgoing song a player that starts the plan late may move it
// later as it is, LATE_MOVE_UNBOUNDED when any number may (a plan with no
// bar grid, a matched rate or two equal tempos, never an intro, whose
// trim must hold where it moves to: with no bar grid it is 1 when any move
// to the latest end may be made, else 0); past that the player plans
// again from where it is. Every plan sets it.
//
// Counted from startMs in the outgoing song's time, both songs are
// clearly heard together from `bothHeardStartMs` to `bothHeardEndMs` (both
// 0 when they never are), and their beats meet at `lockMs`. `driftGap` is
// how fast the beats part from there, the share their tempos are apart, 0
// when they cannot drift (a matched rate, equal tempos) or are not lined
// up; `driftAtEndMs` is how far apart they get in song time while both are
// heard. Heard at a speed s, the beats are that far apart over s:
// dropCutAtMs says where a speed lowered to s puts them too far apart.
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
    val late: Boolean,
    val headroomDb: Double,
    val reason: String,
    val curve: FadeCurve = FadeCurve.EqualPower,
    val holdShare: Double = 0.0,
    val riseShare: Double = 0.0,
    val introLevel: Double = 1.0,
    val latestEndMs: Long = startMs + overlapMs,
    val lateMoveBars: Int,
    val bothHeardStartMs: Long = 0,
    val bothHeardEndMs: Long = 0,
    val lockMs: Double = 0.0,
    val driftGap: Double = 0.0,
    val driftAtEndMs: Double = 0.0,
) {
    val barLocked: Boolean get() = beatMs != null

    fun progressAt(outgoingMs: Double): Double =
        if (overlapMs <= 0) 1.0 else ((outgoingMs - startMs) / overlapMs).coerceIn(0.0, 1.0)

    // Each song's volume at `t` through the blend: an intro's hold and
    // rise, else the curve after any held start.
    fun outGain(t: Double): Double = when {
        kind == TransitionKind.INTRO -> introOutGain(t, holdShare)
        curve == FadeCurve.EqualPower -> automixOutGain(pastHold(t, holdShare), k)
        else -> fadeOutGain(curve, pastHold(t, holdShare), overlapMs * (1 - holdShare))
    }

    fun inGain(t: Double): Double = when {
        kind == TransitionKind.INTRO -> introInGain(t, riseShare, holdShare, introLevel)
        curve == FadeCurve.EqualPower -> automixInGain(pastHold(t, holdShare), k)
        else -> fadeInGain(curve, pastHold(t, holdShare), overlapMs * (1 - holdShare))
    }

    // The outgoing song's beats inside the blend as places through it (0 to
    // 1). A beat within a twentieth of a beat of either end counts as the
    // end itself and is left out.
    fun beatProgress(): DoubleArray {
        val beat = beatMs ?: return DoubleArray(0)
        val anchor = beatAnchorMs ?: return DoubleArray(0)
        if (overlapMs <= 0 || beat <= 0) return DoubleArray(0)
        val grid = Tempo(60_000.0 / beat, TEMPO_CONFIDENT, 1.0, beat, anchor, anchor)
        val margin = beat / 20
        return grid.beatsBetween(startMs + margin, startMs + overlapMs - margin)
            .map { (it - startMs) / overlapMs }
            .toDoubleArray()
    }

    // The outgoing low-pass: stepped on the beat when the blend is locked
    // to the bar, gliding over an eighth of a bar.
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

// Where, counted from the plan's start, a speed lowered to `speed` while
// the blend is `fromMs` along first puts its two songs' beats heard over
// DRIFT_LIMIT_MS apart while both are still clearly heard: the first
// moment at or after `fromMs` in [bothHeardStartMs, bothHeardEndMs] where
// driftGap * |t - lockMs| passes DRIFT_LIMIT_MS * speed (`fromMs` itself
// when they are already that far apart there, else the moment after the
// lock point where the offset reaches the limit, when the stretch goes on
// past it). The outgoing song keeps its own level through a hold (an
// intro's, or the incoming song's silent lead-in), so the moment is never
// before the hold's end. Null when no such moment comes, and the blend
// plays on as planned.
fun dropCutAtMs(plan: TransitionPlan, speed: Double, fromMs: Double): Double? {
    val gap = plan.driftGap
    if (gap <= 0 || plan.bothHeardEndMs <= 0) return null
    val from = maxOf(fromMs, plan.bothHeardStartMs.toDouble(), plan.holdShare * plan.overlapMs)
    if (from > plan.bothHeardEndMs) return null
    val reach = DRIFT_LIMIT_MS * speed / gap
    if (from < plan.lockMs - reach || from > plan.lockMs + reach) return from
    val at = plan.lockMs + reach
    return at.takeIf { it < plan.bothHeardEndMs }
}

// Plans the move from the playing song to the next one. `tail` is the
// analysis of the end of the playing song (analyzeTail) and `head` of the
// start of the next one (analyzeHead), either null when it is not ready.
// Every rule of crossfadeLength still holds: when it gives no crossfade the
// songs play gaplessly. With smart transitions off the plan is the
// equal-power crossfade (fixedCrossfadePlan); for a song under 35 s or in a
// genre that should not be mixed, the fixed crossfade with the plain curve.
// Only the crossfade with smart transitions off takes the settings'
// fadeCurve. Without the tail, or when no start is left at least PRE_ROLL_MS ahead,
// the plan is a late one: the blend at the end.
//
// When both songs' tempos are trusted (each confident, the outgoing one
// agreeing with the whole song's, and the beats able to stay locked at this
// pace) every plan is locked to the outgoing song's bars, the incoming song
// entering on its own grid, and a rate matches tempos up to
// BEAT_MATCH_LARGEST_GAP apart. Without a rate, wherever both songs are
// clearly heard their beats stay within DRIFT_LIMIT_MS times the pace of
// each other: a blend starts later or becomes a short cut until they do, or
// the songs join gaplessly. A place that would cut over KEPT_MUSIC_LIMIT_MS
// of the outgoing song's full-level music is never planned; with none left
// the songs join gaplessly too. With both tempos confident but no lock, a
// blend where both songs are clearly heard cannot be kept on the beat at
// all, so only one where they never are is planned. A plan with a rate
// whose next song is too short to ease back to its own speed is planned
// again without the rate.
fun planTransition(
    current: FadeSong,
    next: FadeSong?,
    tail: SectionAnalysis?,
    head: SectionAnalysis?,
    settings: AutomixSettings,
    context: TransitionContext = TransitionContext(),
): TransitionPlan {
    val plain = crossfadeLength(current, next, settings.maxOverlapMs, context.repeatOne, context.stopAtEndOfSong)
    if (plain == 0L || next == null) {
        return gaplessPlan(current.durationMs, gaplessReason(current, next, settings.maxOverlapMs, context.repeatOne, context.stopAtEndOfSong))
    }
    val lenA = current.durationMs
    val lenB = next.durationMs
    if (!settings.smart) return fixedCrossfadePlan(lenA, plain, "smart transitions off", settings.fadeCurve)
    listOfNotNull(context.currentGenre, context.nextGenre).firstOrNull { PLAIN_CROSSFADE_GENRES.containsMatchIn(it) }?.let {
        return crossfadePlan(lenA, plain, "genre $it")
    }
    if (lenA < SHORTEST_AUTOMIX_SONG_MS || lenB < SHORTEST_AUTOMIX_SONG_MS) {
        return crossfadePlan(lenA, plain, "a song under ${SHORTEST_AUTOMIX_SONG_MS / 1000} s")
    }
    val matched = planPass(lenA, lenB, tail, head, settings, context, withRate = true)
    val rate = matched.beatMatchRate ?: return matched
    // The incoming song spends overlap * rate of its own time in the blend
    // and needs the settle after it to ease back to its own speed.
    if (lenB - matched.entryMs > matched.overlapMs * rate + BEAT_MATCH_SETTLE_MS + 1_000) return matched
    return planPass(lenA, lenB, tail, head, settings, context, withRate = false)
}

// One pass of the planner, with a rate when `withRate` allows one.
private fun planPass(
    lenA: Long,
    lenB: Long,
    tail: SectionAnalysis?,
    head: SectionAnalysis?,
    settings: AutomixSettings,
    context: TransitionContext,
    withRate: Boolean,
): TransitionPlan {
    val limit = min(settings.maxOverlapMs.toDouble(), min(lenA, lenB) / 2.0)
    val sweeps = if (settings.filterSweeps) FILTER_STRENGTH else 0.0
    val headFeatures = head?.features?.takeIf { it.soundStartMs != null }
    val headEntry = headFeatures?.soundStartMs ?: 0L
    val fixedEnd = (lenA - END_MARGIN_MS).toDouble()

    if (tail == null) {
        return loosePlan(lenA, fixedEnd, limit, context.nowMs.toDouble(), headEntry, null, head, sweeps, "no analysis of this song's end")
    }
    val measured = context.bodyLevelDb?.let(tail::withBodyLevel) ?: tail
    val a = measured.features
    val soundEndA = a.soundEndMs
    val outroA = a.outroStartMs
    if (soundEndA == null || outroA == null) {
        return loosePlan(lenA, fixedEnd, limit, context.nowMs.toDouble(), headEntry, null, head, sweeps, "this song's end is silent")
    }

    val latestEnd = min(soundEndA, lenA - END_MARGIN_MS).toDouble()
    val outroStart = min(outroA.toDouble(), latestEnd)
    var tempoA = a.tempo?.takeIf { it.confident }
    var dropped = false
    val prior = context.tempoPrior
    if (tempoA != null && prior != null && prior > 0) {
        // The end must keep the tempo the whole song has.
        val moved = tempoA.inOctaveOf(prior)
        if (abs(moved.bpm / prior - 1) <= 0.04) {
            tempoA = moved
        } else {
            tempoA = null
            dropped = true
        }
    }
    val tempoB = headFeatures?.tempo?.takeIf { it.confident }
    val canLock = (context.pace == 1.0 || context.lockAtPace) && !context.skipSilence
    val confident = tempoA != null && tempoB != null
    val lockA = tempoA?.takeIf { confident && canLock }
    val lockB = tempoB?.takeIf { lockA != null }
    val ratio = if (lockA != null && lockB != null) foldTempoRatio(lockA.bpm / lockB.bpm) else null
    val gap = ratio?.let { abs(it - 1) } ?: 0.0
    val rate = ratio?.takeIf { withRate && settings.beatMatch && gap > 0 && gap <= BEAT_MATCH_LARGEST_GAP }?.coerceIn(0.94, 1.06)

    // The entry of a locked plan is on the incoming song's own grid, at or
    // before its first sound.
    val soundStartB = headEntry.toDouble()
    val (entry, leadIn) = if (lockB != null) gridEntry(lockB, soundStartB, rate != null) else soundStartB to 0.0
    return PlanPass(
        lenA = lenA,
        limit = limit,
        sweeps = sweeps,
        measured = measured,
        head = head,
        headFeatures = headFeatures,
        settings = settings,
        context = context,
        latestEnd = latestEnd,
        outroStart = outroStart,
        dropped = dropped,
        lockA = lockA,
        ratio = ratio,
        gap = gap,
        rate = rate,
        entry = entry,
        lead = leadIn / (rate ?: 1.0),
        leadHold = if (leadIn > 0) soundStartB / (rate ?: 1.0) else 0.0,
        blind = confident && !canLock,
    ).plan()
}

// The incoming song's entry on its own grid: its last downbeat (with a
// rate) or beat at or before its first sound, so none of its sound is cut.
// When that falls before the song's start the song enters at 0, and the
// second value is how far into the song its first grid point lies: the
// outgoing song's start moves that much earlier, so that point lands on
// the outgoing song's line.
private fun gridEntry(b: Tempo, soundStartMs: Double, downbeats: Boolean): Pair<Double, Double> {
    val step = if (downbeats) b.barMs else b.beatMs
    val anchor = if (downbeats) b.downbeatMs else b.firstBeatMs
    val last = anchor + floor((soundStartMs - anchor) / step + 1e-9) * step
    return if (last >= -GRID_AT_START_MS) max(0.0, last) to 0.0 else 0.0 to (last + step)
}

// A place a blend or intro may go: from startMs over overlapMs in the
// outgoing song's time, its beats meeting the incoming song's at lockMs
// (the start, or later by the incoming song's lead-in). An intro holds and
// rises over its shares, the incoming song at `level` through the hold;
// any other shape holds until holdShare, then follows curve weight `k`, or
// when that is null the curve its own length takes.
private data class Shape(
    val startMs: Double,
    val lockMs: Double,
    val overlapMs: Double,
    val onBar: Boolean,
    val intro: Boolean = false,
    val holdShare: Double = 0.0,
    val riseShare: Double = 0.0,
    val k: Double? = null,
    val level: Double = 1.0,
) {
    val endMs: Double get() = startMs + overlapMs

    fun shifted(ms: Double) = copy(startMs = startMs + ms, lockMs = lockMs + ms)
}

private class PlanPass(
    val lenA: Long,
    val limit: Double,
    val sweeps: Double,
    val measured: SectionAnalysis,
    val head: SectionAnalysis?,
    val headFeatures: SectionFeatures?,
    val settings: AutomixSettings,
    val context: TransitionContext,
    val latestEnd: Double,
    val outroStart: Double,
    // The outgoing song's tempo was not the whole song's.
    val dropped: Boolean,
    // The outgoing song's grid when the plan is locked to it.
    val lockA: Tempo?,
    val ratio: Double?,
    val gap: Double,
    val rate: Double?,
    val entry: Double,
    // From a locked shape's start to where the two grids meet, in the
    // outgoing song's time.
    val lead: Double,
    // With a lead, from a shape's start to the incoming song's first sound,
    // in the outgoing song's time: the blend holds until then.
    val leadHold: Double,
    // Both tempos confident with no lock: only a shape where both songs are
    // never clearly heard together is planned.
    val blind: Boolean,
) {
    val a = measured.features

    // A blend from `start` over `overlap`, its beats meeting at `lock`,
    // holding through the incoming song's silent lead-in.
    private fun blend(start: Double, lock: Double, overlap: Double, onBar: Boolean) =
        Shape(start, lock, overlap, onBar, holdShare = if (leadHold > 0) min(1.0, leadHold / overlap) else 0.0)

    // How fast the incoming song plays through the blend.
    val speedB = rate ?: 1.0

    // The beats drift apart through the blend and must be kept within the
    // limit wherever both songs are clearly heard.
    val bounded = lockA != null && rate == null && gap > 0

    val driftLimit = DRIFT_LIMIT_MS * context.pace

    // Where the song will count as played, at the current pace of listening.
    val lo: Double = run {
        val countsAsPlayed = context.nowMs + max(0L, min(lenA / 2, COUNTS_AS_PLAYED_MS) - context.playedMs)
        maxOf(
            maxOf(EARLIEST_START_SHARE * lenA, EARLIEST_START_MS.toDouble(), countsAsPlayed.toDouble()),
            latestEnd - LONGEST_EARLY_EXIT_MS,
            measured.envelope.startMs.toDouble(),
            (context.nowMs + PRE_ROLL_MS).toDouble(),
        )
    }

    val phraseAnchor = lockA?.let { it.downbeatMs + roundHalfUp((outroStart - it.downbeatMs) / it.barMs) * it.barMs }

    fun plan(): TransitionPlan {
        var bars = 0
        var overlap = if (lockA != null && ratio != null) {
            bars = if (gap > 0.08) 2 else 4
            bars * lockA.barMs
        } else {
            latestEnd - outroStart
        }
        // The next song's quiet start, in the outgoing song's time.
        val quiet = headFeatures?.introEndMs?.let { max(0.0, it - entry) / speedB } ?: 0.0
        if (quiet > fitted(overlap, bars) && quiet >= INTRO_SHORTEST_MS && !dropped) {
            planIntro(quiet)?.let { return it }
        }
        // Without an intro the blend lasts at least until the next song is
        // near its full level.
        val lengthened = quiet > overlap
        if (lengthened) {
            if (lockA != null && bars > 0) {
                bars = ceil(quiet / lockA.barMs - 1e-9).toInt()
                overlap = bars * lockA.barMs
            } else {
                overlap = quiet
            }
        }
        overlap = fitted(overlap, bars)

        val hi = latestEnd - overlap
        if (hi < lo) return latePlan(context.nowMs.toDouble(), overlap, "no start left before ${seconds(hi.toLong())}")

        // Bar lines when the blend is locked to the bar, otherwise a fixed
        // step back from the latest start.
        val shapes = if (lockA != null) {
            lockA.barsBetween(lo + lead, hi + lead).map { blend(it - lead, it, overlap, onBar = true) }
        } else {
            steps(lo, hi).map { blend(it, it, overlap, onBar = false) }
        }
        if (shapes.isEmpty()) return latePlan(lo, overlap, "no bar line left before ${seconds(hi.toLong())}")
        val all = shapes.toMutableList()
        if (lockA != null && shapes.none { kept(it) <= KEPT_MUSIC_LIMIT_MS }) stretched(shapes.last())?.let { all += it }
        val fading = all.filter { fades(it) }
        if (fading.isEmpty()) return gaplessPlan(lenA, "the next song's silent start leaves no time to fade")
        val allowed = fading.mapNotNull { allow(it, lo) }
        if (allowed.isEmpty()) return gaplessPlan(lenA, unheardReason())
        val best = best(allowed) { blendScore(it) } ?: return gaplessPlan(lenA, "every blend left would cut the song short")
        return blendPlan(best, lengthened)
    }

    // A blend's overlap kept between SHORTEST_BLEND_MS and the limit, in
    // whole bars when it was planned in `bars` of them and whole bars fit.
    private fun fitted(overlap: Double, bars: Int): Double {
        var length = min(max(overlap, SHORTEST_BLEND_MS.toDouble()), limit)
        if (lockA != null && bars > 0 && length < bars * lockA.barMs) {
            // Cut back to whole bars that fit, when that is still long enough.
            val fit = floor(limit / lockA.barMs + 1e-9).toInt()
            val whole = fit * lockA.barMs
            if (fit >= 1 && whole >= SHORTEST_BLEND_MS) length = whole
        }
        return floor(length + 0.5)
    }

    private fun steps(from: Double, to: Double): List<Double> =
        generateSequence(to) { it - START_STEP_MS }.takeWhile { it >= from - 1e-6 }.toList().asReversed()

    // Full-level music the outgoing song loses after the shape ends.
    private fun kept(s: Shape): Double = measured.msAtLeast(s.endMs, latestEnd, a.bodyDb - KEPT_MUSIC_BELOW_BODY_DB)

    // Whether a blend fades the outgoing song out over at least
    // SHORTEST_FADE_MS after any hold through the incoming song's silent
    // lead-in, rather than holding it to the end and dropping it there.
    private fun fades(s: Shape): Boolean = s.intro || (1 - s.holdShare) * s.overlapMs >= SHORTEST_FADE_MS - 1e-6

    // The last bar-line blend stretched to end exactly at the latest end,
    // from its bar line or, over the limit, from the next one.
    private fun stretched(last: Shape): Shape? {
        val bar = lockA?.barMs ?: return null
        var shape = blend(last.startMs, last.lockMs, latestEnd - last.startMs, onBar = true)
        if (shape.overlapMs > limit) {
            val lock = last.lockMs + bar
            shape = blend(lock - lead, lock, latestEnd - (lock - lead), onBar = true)
        }
        return shape.takeIf { it.overlapMs >= SHORTEST_BLEND_MS }
    }

    // A blend candidate as it may be planned: capped to the drift bound,
    // only with both songs never heard together when the beats cannot be
    // lined up, or as it is.
    private fun allow(s: Shape, earliest: Double): Shape? = when {
        bounded -> cap(s, earliest)
        blind -> s.takeIf { stretch(it) == null }
        else -> s
    }

    // The candidate's end kept and its start moved later by whole bars
    // until the beats stay within the bound; once that would leave under
    // SHORTEST_BLEND_MS, a cut from the earliest beat of A that keeps them
    // within it. Every one fades A out (`fades`). Null when no cut does.
    private fun cap(s: Shape, earliest: Double): Shape? {
        val bar = lockA?.barMs ?: return s
        var shape = s
        while (true) {
            if (meets(shape) && fades(shape)) return shape
            val lock = shape.lockMs + bar
            val start = lock - lead
            if (shape.endMs - start < SHORTEST_BLEND_MS) break
            shape = blend(start, lock, s.endMs - start, onBar = shape.onBar)
        }
        return cut(s.endMs, earliest)
    }

    // A cut ending at `end`, starting on a beat of A no earlier than
    // `earliest`, from SHORTEST_BLEND_MS down to SHORTEST_FADE_MS long:
    // the longest whose beats stay within the bound.
    private fun cut(end: Double, earliest: Double): Shape? {
        val grid = lockA ?: return null
        val from = max(earliest, end - SHORTEST_BLEND_MS)
        val to = end - SHORTEST_FADE_MS
        for (beat in grid.beatsBetween(from + lead, to + lead)) {
            val shape = blend(beat - lead, beat, end - (beat - lead), onBar = onBarLine(beat))
            if (meets(shape) && fades(shape)) return shape
        }
        return null
    }

    private fun onBarLine(ms: Double): Boolean {
        val grid = lockA ?: return false
        val bars = (ms - grid.downbeatMs) / grid.barMs
        return abs(bars - roundHalfUp(bars)) < 1e-6
    }

    private fun meets(s: Shape): Boolean = when {
        bounded -> offset(s) <= driftLimit
        blind -> stretch(s) == null
        else -> true
    }

    // How far apart the beats are, in song time, where they are furthest
    // apart while both songs are clearly heard: they meet at the lock point
    // and drift apart by the tempo gap either side of it.
    private fun offset(s: Shape): Double {
        val (first, last) = stretch(s) ?: return 0.0
        return gap * max(0.0, max(last - s.lockMs, s.lockMs - first))
    }

    // The first and last moments, in the outgoing song's time, where both
    // songs are within BOTH_HEARD_BELOW_BODY_DB of their bodies through the
    // shape, with its gains and the overlap's headroom; null when they
    // never are.
    private fun stretch(s: Shape): Pair<Double, Double>? {
        val b = head ?: return null
        if (headFeatures == null || s.overlapMs <= 0) return null
        val k = s.k ?: curveOf(kindOf(s.overlapMs, measured, head, s.startMs, entry))
        val room = 10.0.pow(-OVERLAP_HEADROOM_DB / 20)
        val aFloor = a.bodyDb - BOTH_HEARD_BELOW_BODY_DB
        val bFloor = b.features.bodyDb - BOTH_HEARD_BELOW_BODY_DB
        var first = Double.NaN
        var last = Double.NaN
        val count = floor(s.overlapMs / HEARD_STEP_MS).toInt()
        for (i in 0..count) {
            val since = i * HEARD_STEP_MS
            val t = since / s.overlapMs
            val out = if (s.intro) introOutGain(t, s.holdShare) else automixOutGain(pastHold(t, s.holdShare), k)
            val into = if (s.intro) introInGain(t, s.riseShare, s.holdShare, s.level) else automixInGain(pastHold(t, s.holdShare), k)
            if (out <= 0 || into <= 0) continue
            val at = s.startMs + since
            if (measured.levelAt(at) + 20 * log10(out * room) < aFloor) continue
            if (b.levelAt(entry + since * speedB) + 20 * log10(into * room) < bFloor) continue
            if (first.isNaN()) first = at
            last = at
        }
        return if (first.isNaN()) null else first to last
    }

    // The level an intro's incoming song plays at through the hold: the
    // hold's own (holdLevel), lowered further while the mix's excess over the
    // louder song alone passes INTRO_EXCESS_LIMIT_DB somewhere in the
    // overlap (excess), to the highest level that keeps it within that,
    // found by halving the trim between the hold's and INTRO_TRIM_CAP_DB
    // INTRO_TRIM_STEPS times. Null when that takes over INTRO_TRIM_CAP_DB.
    private fun underOutgoing(s: Shape): Double? {
        val held = holdLevel(s) ?: return null
        if (excess(s, held) <= INTRO_EXCESS_LIMIT_DB) return held
        var lo = -20 * log10(held)
        var hi = INTRO_TRIM_CAP_DB
        if (excess(s, 10.0.pow(-hi / 20)) > INTRO_EXCESS_LIMIT_DB) return null
        repeat(INTRO_TRIM_STEPS) {
            val mid = (lo + hi) / 2
            if (excess(s, 10.0.pow(-mid / 20)) > INTRO_EXCESS_LIMIT_DB) lo = mid else hi = mid
        }
        return 10.0.pow(-hi / 20)
    }

    // The level that keeps an intro's incoming song INTRO_UNDER_OUTGOING_DB
    // under the outgoing song through the hold, walked as the both-heard
    // stretch is: at each step where the incoming song, at its own level
    // with its rise, sounds above its silence gate, its 400 ms level as its
    // deck plays it less the outgoing song's at the same moment; the
    // INTRO_UNDER_OUTGOING_SHARE percentile of those (the lower of two
    // neighbors) sets how far it is lowered, 1 when it is already that far
    // under or never sounds there. Null when that takes over
    // INTRO_TRIM_CAP_DB.
    private fun holdLevel(s: Shape): Double? {
        val b = head ?: return 1.0
        val hold = s.holdShare * s.overlapMs
        val gate = b.features.gateDb
        val apart = ArrayList<Double>()
        val count = floor(hold / HEARD_STEP_MS).toInt()
        for (i in 0 until count) {
            val since = i * HEARD_STEP_MS
            val into = introInGain(since / s.overlapMs, s.riseShare, s.holdShare, 1.0)
            if (into <= 0) continue
            val level = b.levelAt(entry + since * speedB) + 20 * log10(into)
            if (level <= gate) continue
            apart += level + context.incomingGainDb - measured.levelAt(s.startMs + since)
        }
        if (apart.isEmpty()) return 1.0
        apart.sort()
        val trim = apart[floor(INTRO_UNDER_OUTGOING_SHARE * (apart.size - 1)).toInt()] + INTRO_UNDER_OUTGOING_DB
        if (trim <= 0) return 1.0
        return if (trim > INTRO_TRIM_CAP_DB) null else 10.0.pow(-trim / 20)
    }

    // How far an intro's mix stands over the louder of its two songs alone,
    // the incoming one at `level` through the hold: at each step of the
    // overlap where either song sounds above its silence gate, the flat
    // power sum of both with their planned gains and the overlap's headroom,
    // less the louder song's own level there, each as its deck plays it;
    // the loudest step. Negative infinity when no step counts.
    private fun excess(s: Shape, level: Double): Double {
        val b = head ?: return Double.NEGATIVE_INFINITY
        val room = 10.0.pow(-OVERLAP_HEADROOM_DB / 20)
        var most = Double.NEGATIVE_INFINITY
        val count = floor(s.overlapMs / HEARD_STEP_MS).toInt()
        for (i in 0..count) {
            val since = i * HEARD_STEP_MS
            val t = since / s.overlapMs
            val outgoing = measured.levelAt(s.startMs + since)
            val own = b.levelAt(entry + since * speedB)
            if (outgoing <= a.gateDb && own <= b.features.gateDb) continue
            val incoming = own + context.incomingGainDb
            val out = introOutGain(t, s.holdShare) * room
            val into = introInGain(t, s.riseShare, s.holdShare, level) * room
            val sum = 10.0.pow(outgoing / 10) * out * out + 10.0.pow(incoming / 10) * into * into
            most = max(most, 10 * log10(max(sum, 1e-30)) - max(outgoing, incoming))
        }
        return most
    }

    // The best scoring shape; the later one wins a tie. Shapes that cut over
    // KEPT_MUSIC_LIMIT_MS of the outgoing song's full-level music are left
    // out; null when no other is left.
    private fun best(shapes: List<Shape>, score: (Shape) -> Double): Shape? {
        val pool = shapes.filter { kept(it) <= KEPT_MUSIC_LIMIT_MS }
        if (pool.isEmpty()) return null
        var best = pool.last()
        var bestScore = Double.NEGATIVE_INFINITY
        for (s in pool) {
            val value = score(s)
            if (value >= bestScore) {
                bestScore = value
                best = s
            }
        }
        return best
    }

    // The terms every shape shares: ending early, music cut, a section
    // boundary where the beats meet, and the bar and phrase it starts on.
    private fun sharedScore(s: Shape): Double {
        var score = -0.08 * (latestEnd - s.endMs) / 1000
        if (a.boundariesMs.any { abs(it - s.lockMs) <= 250 }) score += 1.0
        val grid = lockA
        if (grid != null && phraseAnchor != null && s.onBar) {
            score += 0.6
            val barsBack = roundHalfUp((phraseAnchor - s.lockMs) / grid.barMs).toLong()
            if (barsBack % 8 == 0L) score += 0.8
        }
        return score - KEPT_MUSIC_WEIGHT * kept(s) / 1000
    }

    private fun blendScore(s: Shape): Double {
        var score = sharedScore(s)
        if (outroStart < latestEnd - 1000) score -= 0.15 * abs(s.lockMs - outroStart) / 1000
        return score - 1.5 * measured.shareAtLeast(s.startMs, s.endMs, a.bodyDb - 3)
    }

    private fun blendPlan(s: Shape, lengthened: Boolean): TransitionPlan {
        val kind = kindOf(s.overlapMs, measured, head, s.startMs, entry)
        val startMs = roundHalfUp(s.startMs).toLong()
        val overlapMs = roundHalfUp(s.endMs).toLong() - startMs
        val entryMs = roundHalfUp(entry).toLong()
        val reason = buildString {
            append(kind.name.lowercase())
            append(" at ").append(seconds(startMs)).append(" over ").append(seconds(overlapMs))
            appendBars(overlapMs)
            append(", next song from ").append(seconds(entryMs))
            if (lengthened) headFeatures?.introEndMs?.let { append(" (full from ").append(seconds(it)).append(")") }
            append(", outro at ").append(seconds(outroStart.toLong()))
            append(", latest end ").append(seconds(latestEnd.toLong()))
            if (head == null) append(", no analysis of the next song's start")
            rate?.let { append(", rate ").append(decimals(it, 3)) }
        }
        return finish(
            TransitionPlan(
                startMs = startMs,
                entryMs = entryMs,
                overlapMs = overlapMs,
                kind = kind,
                k = curveOf(kind),
                filterStrength = sweeps,
                beatMatchRate = rate,
                beatMs = lockA?.beatMs,
                beatAnchorMs = lockA?.firstBeatMs,
                late = false,
                headroomDb = OVERLAP_HEADROOM_DB,
                reason = reason,
                holdShare = leadHoldShare(overlapMs),
                latestEndMs = roundHalfUp(latestEnd).toLong(),
                lateMoveBars = 0,
            ),
            s.lockMs,
        )
    }

    // The share of a blend `overlapMs` long held through the incoming
    // song's silent lead-in.
    private fun leadHoldShare(overlapMs: Long) = if (leadHold > 0) min(1.0, leadHold / overlapMs) else 0.0

    // " (N bars of X BPM)" when the overlap is whole bars of a locked plan.
    private fun StringBuilder.appendBars(overlapMs: Long) {
        val grid = lockA ?: return
        val bars = roundHalfUp(overlapMs / grid.barMs)
        if (bars >= 1 && abs(overlapMs - bars * grid.barMs) <= 1.0) {
            append(" (").append(bars.toLong()).append(" bars of ").append(decimals(grid.displayBpm, 1)).append(" BPM)")
        }
    }

    // The next song's quiet start played under the end of this one: the
    // outgoing song holds, then fades over two bars (or INTRO_FADE_MS
    // unlocked, at most half the quiet start) centered on where the next
    // song is full, a bar line from where the beats meet when locked; the
    // next song rises over a bar (INTRO_RISE_MS unlocked; a quarter of the
    // overlap or the hold when shorter), lowered through
    // the hold as far as each start needs to stay under the outgoing song
    // (underOutgoing). Null when no start is left, the beats or levels allow
    // none or every one left would cut the outgoing song short, and the
    // lengthened blend is planned instead.
    private fun planIntro(quiet: Double): TransitionPlan? {
        val bar = lockA?.barMs
        val rise = bar ?: INTRO_RISE_MS.toDouble()
        val fade = min(if (bar != null) INTRO_FADE_BARS * bar else INTRO_FADE_MS.toDouble(), quiet / 2)
        val longest = settings.longestIntroMs.toDouble()
        val fullAt = if (bar != null) {
            lead + min(ceil((quiet - lead) / bar - 1e-9), floor((longest - fade / 2 - lead) / bar + 1e-9)) * bar
        } else {
            min(quiet, longest - fade / 2)
        }
        val hold = fullAt - fade / 2
        if (hold <= 0) return null
        val capped = fullAt < quiet - 1e-6
        val overlap = floor(fullAt + fade / 2 + 0.5)
        fun intro(start: Double, lock: Double, length: Double) = Shape(
            start, lock, length, onBar = bar != null, intro = true,
            holdShare = hold / length, riseShare = minOf(rise, length / 4, hold) / length,
        )
        val hi = latestEnd - overlap
        if (hi < lo) return null
        val shapes = if (lockA != null) {
            lockA.barsBetween(lo + lead, hi + lead).map { intro(it - lead, it, overlap) }
        } else {
            steps(lo, hi).map { intro(it, it, overlap) }
        }
        if (shapes.isEmpty()) return null
        val all = shapes.toMutableList()
        if (bar != null && shapes.none { kept(it) <= KEPT_MUSIC_LIMIT_MS }) {
            // Only the outgoing song's fade end moves, to the latest end.
            val last = shapes.last()
            val length = latestEnd - last.startMs
            if (length <= longest) {
                all += intro(last.startMs, last.lockMs, length)
            } else {
                // Over the longest intro: one bar later, keeping where the
                // next song is full from the start, when the fade left is
                // still at least half the planned one.
                val lock = last.lockMs + bar
                val start = lock - lead
                if (latestEnd - start - hold >= fade / 2) all += intro(start, lock, latestEnd - start)
            }
        }
        val allowed = all.mapNotNull { s -> underOutgoing(s)?.let { s.copy(level = it) } }.filter { meets(it) }
        if (allowed.isEmpty()) return null
        val best = best(allowed) { s ->
            var score = sharedScore(s)
            if (outroStart < latestEnd - 1000) score -= 0.15 * abs(s.startMs + hold - outroStart) / 1000
            score
        } ?: return null
        val startMs = roundHalfUp(best.startMs).toLong()
        val overlapMs = roundHalfUp(best.endMs).toLong() - startMs
        val entryMs = roundHalfUp(entry).toLong()
        val reason = buildString {
            append("intro at ").append(seconds(startMs)).append(" over ").append(seconds(overlapMs))
            appendBars(overlapMs)
            append(", next song from ").append(seconds(entryMs))
            append(" (full from ").append(seconds(headFeatures?.introEndMs ?: entryMs))
            if (capped) append(", capped")
            append("), outro at ").append(seconds(outroStart.toLong()))
            append(", latest end ").append(seconds(latestEnd.toLong()))
            if (best.level < 1) append(", next song lowered ").append(decimals(-20 * log10(best.level), 1)).append(" dB")
            rate?.let { append(", rate ").append(decimals(it, 3)) }
        }
        return finish(
            TransitionPlan(
                startMs = startMs,
                entryMs = entryMs,
                overlapMs = overlapMs,
                kind = TransitionKind.INTRO,
                k = curveOf(TransitionKind.INTRO),
                filterStrength = 0.0,
                beatMatchRate = rate,
                beatMs = lockA?.beatMs,
                beatAnchorMs = lockA?.firstBeatMs,
                late = false,
                headroomDb = OVERLAP_HEADROOM_DB,
                reason = reason,
                holdShare = hold / overlapMs,
                riseShare = minOf(rise, overlapMs / 4.0, hold) / overlapMs,
                introLevel = best.level,
                latestEndMs = roundHalfUp(latestEnd).toLong(),
                lateMoveBars = 0,
            ),
            best.lockMs,
        )
    }

    // The blend at the end, ending at the latest end, when no planned start
    // is left after `earliest`. Locked: from A's first bar line at or after
    // the later of `earliest` and a full `overlap` before the end, capped
    // to the drift bound like a planned blend. Otherwise as it always was,
    // and with the beats unable to line up only when both songs are never
    // heard together.
    private fun latePlan(earliest: Double, overlap: Double, why: String): TransitionPlan {
        val grid = lockA
        if (grid == null) {
            val plan = loosePlan(lenA, latestEnd, overlap, earliest, roundHalfUp(entry).toLong(), measured, head, sweeps, why)
            if (plan.kind == TransitionKind.GAPLESS) return plan.copy(lateMoveBars = LATE_MOVE_UNBOUNDED)
            if (blind && stretch(shapeOf(plan, plan.startMs.toDouble())) != null) return gaplessPlan(lenA, unheardReason())
            return finish(plan, plan.startMs.toDouble())
        }
        val from = max(earliest, latestEnd - overlap)
        val lock = grid.downbeatFrom(from + lead)
        val start = lock - lead
        val length = latestEnd - start
        val shape = when {
            !bounded -> blend(start, lock, length, onBar = true).takeIf { fades(it) }
            length >= SHORTEST_BLEND_MS -> cap(blend(start, lock, length, onBar = true), from)
            else -> cut(latestEnd, from)
        } ?: return gaplessPlan(lenA, "too late to blend: $why")
        val kind = kindOf(shape.overlapMs, measured, head, shape.startMs, entry)
        val startMs = roundHalfUp(shape.startMs).toLong()
        val overlapMs = roundHalfUp(shape.endMs).toLong() - startMs
        val entryMs = roundHalfUp(entry).toLong()
        val matched = rate?.let { ", rate ${decimals(it, 3)}" } ?: ""
        return finish(
            TransitionPlan(
                startMs = startMs,
                entryMs = entryMs,
                overlapMs = overlapMs,
                kind = kind,
                k = curveOf(kind),
                filterStrength = sweeps,
                beatMatchRate = rate,
                beatMs = grid.beatMs,
                beatAnchorMs = grid.firstBeatMs,
                late = true,
                headroomDb = OVERLAP_HEADROOM_DB,
                reason = "late ${kind.name.lowercase()} at ${seconds(startMs)} over ${seconds(overlapMs)}, next song from ${seconds(entryMs)}$matched: $why",
                holdShare = leadHoldShare(overlapMs),
                latestEndMs = roundHalfUp(latestEnd).toLong(),
                lateMoveBars = 0,
            ),
            shape.lockMs,
        )
    }

    private fun unheardReason() = if (bounded) "the beats would drift apart" else "the beats cannot be lined up"

    private fun shapeOf(plan: TransitionPlan, lock: Double) = Shape(
        plan.startMs.toDouble(), lock, plan.overlapMs.toDouble(), onBar = false,
        intro = plan.kind == TransitionKind.INTRO, holdShare = plan.holdShare, riseShare = plan.riseShare, k = plan.k,
        level = plan.introLevel,
    )

    // The plan with how far a late start may move it, where both songs are
    // heard together, where its beats meet and how far they part.
    private fun finish(plan: TransitionPlan, lock: Double): TransitionPlan {
        val shape = shapeOf(plan, lock)
        val heard = stretch(shape)
        return plan.copy(
            lateMoveBars = lateMoveBars(shape, plan),
            bothHeardStartMs = heard?.let { roundHalfUp(it.first - plan.startMs).toLong() } ?: 0L,
            bothHeardEndMs = heard?.let { roundHalfUp(it.second - plan.startMs).toLong() } ?: 0L,
            lockMs = lock - plan.startMs,
            driftGap = if (bounded) gap else 0.0,
            driftAtEndMs = if (bounded) offset(shape) else 0.0,
        )
    }

    // The most whole bars the plan may move later and still end by the
    // latest end with its beats within the bound there; unbounded when the
    // bound does not depend on where it sits. None for a plan whose beats
    // could not be lined up, kept only where both songs are never heard
    // together, which a move could change. An intro is never unbounded: a
    // move must also leave its incoming song needing no deeper trim than
    // planned (trimHolds). With a bar grid it counts those bars; with none,
    // where the player moves it any amount up to the latest end, it is 1
    // when there is room and every START_STEP_MS step there (and the latest
    // end itself) keeps that, else 0.
    private fun lateMoveBars(shape: Shape, plan: TransitionPlan): Int {
        if (blind) return 0
        val bar = lockA?.barMs
        val end = (plan.startMs + plan.overlapMs).toDouble()
        if (shape.intro) {
            fun keeps(ms: Double): Boolean = shape.shifted(ms).let { meets(it) && trimHolds(it) }
            if (bar == null) {
                val room = plan.latestEndMs - end
                if (room <= 1e-6) return 0
                var ms = START_STEP_MS.toDouble()
                while (ms < room - 1e-6) {
                    if (!keeps(ms)) return 0
                    ms += START_STEP_MS
                }
                return if (keeps(room)) 1 else 0
            }
            var bars = 0
            while (end + (bars + 1) * bar <= plan.latestEndMs + 1e-6 && keeps((bars + 1) * bar)) bars++
            return bars
        }
        if (!bounded || bar == null) return LATE_MOVE_UNBOUNDED
        var bars = 0
        while (end + (bars + 1) * bar <= plan.latestEndMs + 1e-6 && meets(shape.shifted((bars + 1) * bar))) bars++
        return bars
    }

    // Whether an intro's shape, at the level it was planned with, needs no
    // deeper trim where it sits: the hold's level is no lower, and the mix's
    // excess over the louder song alone is within INTRO_EXCESS_LIMIT_DB.
    private fun trimHolds(s: Shape): Boolean {
        val held = holdLevel(s) ?: return false
        return held >= s.level - 1e-9 && excess(s, s.level) <= INTRO_EXCESS_LIMIT_DB
    }
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

// A cut for a short overlap; a lift when the next song's first 8 s are at
// least 3 dB louder than this song through the overlap; else a blend.
private fun kindOf(overlap: Double, tail: SectionAnalysis?, head: SectionAnalysis?, start: Double, entry: Double): TransitionKind = when {
    overlap <= CUT_BLEND_MS -> TransitionKind.CUT
    tail != null && head != null && head.levelDb(entry, entry + 8_000) >= tail.levelDb(start, start + overlap) + 3 -> TransitionKind.LIFT
    else -> TransitionKind.BLEND
}

private fun curveOf(kind: TransitionKind): Double = when (kind) {
    TransitionKind.CUT -> CUT_CURVE
    TransitionKind.LIFT -> LIFT_CURVE
    TransitionKind.BLEND -> BLEND_CURVE
    // An intro's shape has no curve weight.
    TransitionKind.INTRO -> 0.0
    else -> CROSSFADE_CURVE
}

// The blend at the end with no bar lock: ending at latestEnd, over
// `overlap` or whatever is left after `earliest`, with the curve and
// filters. Too little left to blend plays gaplessly.
private fun loosePlan(
    lenA: Long,
    latestEnd: Double,
    overlap: Double,
    earliest: Double,
    entryMs: Long,
    tail: SectionAnalysis?,
    head: SectionAnalysis?,
    sweeps: Double,
    why: String,
): TransitionPlan {
    val start = max(earliest, latestEnd - overlap)
    val length = floor(latestEnd - start + 0.5)
    if (length < SHORTEST_FADE_MS) return gaplessPlan(lenA, "too late to blend: $why")
    val kind = kindOf(length, tail, head, start, entryMs.toDouble())
    val startMs = floor(start + 0.5).toLong()
    return TransitionPlan(
        startMs = startMs,
        entryMs = entryMs,
        overlapMs = length.toLong(),
        kind = kind,
        k = curveOf(kind),
        filterStrength = sweeps,
        beatMatchRate = null,
        beatMs = null,
        beatAnchorMs = null,
        late = true,
        headroomDb = OVERLAP_HEADROOM_DB,
        reason = "late ${kind.name.lowercase()} at ${seconds(startMs)} over ${seconds(length.toLong())}, next song from ${seconds(entryMs)}: $why",
        latestEndMs = floor(latestEnd + 0.5).toLong(),
        lateMoveBars = LATE_MOVE_UNBOUNDED,
    )
}

private fun gaplessPlan(lenA: Long, reason: String) = TransitionPlan(
    startMs = max(0L, lenA),
    entryMs = 0,
    overlapMs = 0,
    kind = TransitionKind.GAPLESS,
    k = CROSSFADE_CURVE,
    filterStrength = 0.0,
    beatMatchRate = null,
    beatMs = null,
    beatAnchorMs = null,
    late = false,
    headroomDb = 0.0,
    reason = "gapless: $reason",
    latestEndMs = max(0L, lenA),
    lateMoveBars = LATE_MOVE_UNBOUNDED,
)

// The crossfade at the end of the song, as it was before planned
// transitions: no curve weight and no filters. Equal power with no headroom
// unless `curve` picks another shape; mix only brings its own headroom.
fun fixedCrossfadePlan(lenA: Long, length: Long, why: String, curve: FadeCurve = FadeCurve.EqualPower): TransitionPlan {
    val start = max(0L, lenA - length)
    return TransitionPlan(
        startMs = start,
        entryMs = 0,
        overlapMs = length,
        kind = TransitionKind.CROSSFADE,
        k = 0.0,
        filterStrength = 0.0,
        beatMatchRate = null,
        beatMs = null,
        beatAnchorMs = null,
        late = false,
        headroomDb = fadeHeadroomDb(curve),
        reason = "crossfade at ${seconds(start)} over ${seconds(length)}: $why",
        curve = curve,
        latestEndMs = lenA,
        lateMoveBars = LATE_MOVE_UNBOUNDED,
    )
}

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
    late = false,
    headroomDb = OVERLAP_HEADROOM_DB,
    reason = "crossfade at ${seconds(lenA - length)} over ${seconds(length)}: $why",
    latestEndMs = lenA,
    lateMoveBars = LATE_MOVE_UNBOUNDED,
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
