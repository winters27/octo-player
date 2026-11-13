package app.winters.octo.playback

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

// The next song starts playing, parked and silent, this long (in the
// playing song's time) before it comes in, so its sound is waiting in the
// mixer by its start.
const val PRE_ROLL_LEAD_MS = 2_500L

// The next song is loaded at least this long before the blend starts:
// songs from outside the library are slow to start.
const val PLAN_LOAD_LEAD_MS = 15_000L

// A parked spare's sink is taken to give frames this long after it plays.
const val SPARE_READY_LEAD_MS = 500L

// The spare is parked this long before the next song's entry, so Media3's
// 20 ms ramp-up on a new output falls on frames dropped before the entry.
const val SPARE_PARK_LEAD_MS = 100L

// A position that moves this far from where it should be means the plan
// no longer fits.
const val REPLAN_JUMP_MS = 2_000L

// The Crossfader's times, settable so tests run in seconds: how far ahead
// of a blend the spare loads, and how often the song is looked at, far
// from its end and near it.
data class FadeTimes(
    val planLoadLeadMs: Long = PLAN_LOAD_LEAD_MS,
    val farCheckMs: Long = 1_000L,
    val nearCheckMs: Long = 40L,
)

// The settings the planner gets at a playback speed: the longest blend and
// the longest intro are in song time, so at 1.5x speed an 8 s blend covers
// 12 s of the songs and still lasts 8 s, as on the desktop.
fun AutomixSettings.atSpeed(speed: Float): AutomixSettings =
    if (speed == 1f) {
        this
    } else {
        copy(
            maxOverlapMs = (maxOverlapMs * speed.toDouble()).roundToLong(),
            longestIntroMs = (longestIntroMs * speed.toDouble()).roundToLong(),
        )
    }

// The playing song's level and tempo as the planner is told them: with the
// song's end from the server's profile, that end is already measured
// against the whole song, so no level goes with it, and its tempo is the
// whole song's from the profile, else what was heard.
fun heardFor(profile: TransitionProfile?, heardLevelDb: Double?, heardTempo: Double?): Pair<Double?, Double?> =
    if (profile == null) heardLevelDb to heardTempo else null to (profile.tempoPrior ?: heardTempo)

// Whether a tap reading taken at song time `askedAtMs`, having heard
// `heardMs` then (null when the tap was not on the song), is worth taking
// again at `positionMs` (song time, played at `speed`): only when it had
// heard less than `enoughMs` then and would have heard that much by now.
fun tapReadsAgain(heardMs: Long?, askedAtMs: Long, positionMs: Long, speed: Float, enoughMs: Long): Boolean {
    if (heardMs == null || heardMs >= enoughMs || speed <= 0f) return false
    return heardMs + ((positionMs - askedAtMs) / speed).toLong() >= enoughMs
}

// The incoming song's playback rate through the blend.
fun TransitionPlan.incomingRate(): Double = beatMatchRate ?: 1.0

// The outgoing song's bar in ms when the plan is locked to it, else NaN.
fun TransitionPlan.barMs(): Double = beatMs?.takeIf { barLocked && it > 0 }?.let { it * BEATS_PER_BAR } ?: Double.NaN

// What a scouted end of a song and start of the next go through: the
// shared analysis, with the song's tag tempo picking its tempo's octave.
fun tailAnalyzer(tagBpm: Double?): (SectionEnvelope) -> SectionAnalysis = { analyzeTail(it, tagBpm) }

fun headAnalyzer(tagBpm: Double?): (SectionEnvelope) -> SectionAnalysis = { analyzeHead(it, tagBpm) }

// Whether a spare parked at `positionMs` must move to `targetMs`: not when
// it is there already, give or take a frame or two.
fun needsRepark(positionMs: Long, targetMs: Long): Boolean = abs(positionMs - targetMs) > PARK_TOLERANCE_MS

// A spare this close to where it should be parked is left there.
const val PARK_TOLERANCE_MS = 40L

// Where the incoming song is parked: just before its entry (the song's
// start when there is no plan yet).
fun TransitionPlan.parkAtMs(): Long = if (overlapMs <= 0) 0 else max(0L, entryMs - SPARE_PARK_LEAD_MS)

// Where in the playing song the blend is registered and the spare plays:
// the earlier of the run-up's start and the moment the start is the
// mixer's proposal lead plus the spare's ready lead ahead, in output time
// at `speed`, so a short blend at high speed is never armed inside its own
// proposal lead.
fun TransitionPlan.armAtMs(proposalLeadMs: Double, speed: Double): Double =
    blendArmAtMs(startMs.toDouble(), PRE_ROLL_LEAD_MS.toDouble(), proposalLeadMs, SPARE_READY_LEAD_MS.toDouble(), speed)
