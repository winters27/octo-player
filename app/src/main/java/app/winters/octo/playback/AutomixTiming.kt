package app.winters.octo.playback

import kotlin.math.abs
import kotlin.math.max

// The next song starts playing silently this long (in the playing song's
// time) before it comes in, so the two can be lined up while nobody hears it.
const val PRE_ROLL_LEAD_MS = 2_500L

// The next song is loaded at least this long before the blend starts:
// songs from outside the library are slow to start.
const val PLAN_LOAD_LEAD_MS = 15_000L

// The decks are measured this long after the next one starts playing, and
// this long after it plays again following a correction.
const val ALIGN_FIRST_MS = 1_000L
const val ALIGN_AGAIN_MS = 400L

// An offset this small is left alone.
const val ALIGN_TOLERANCE_MS = 10.0

// The filters step on the beat only when the decks are lined up this closely.
const val BAR_LOCK_TOLERANCE_MS = 15.0

// Corrections tried before the blend goes ahead as it is.
const val ALIGN_MOST_SEEKS = 3

// A correction needs the next song to play for a while before the blend;
// with less than this left it is not tried.
const val ALIGN_LAST_CHANCE_MS = 400L

// A position that moves this far from where it should be means the plan
// no longer fits.
const val REPLAN_JUMP_MS = 2_000L

// The incoming song's playback rate through the blend.
fun TransitionPlan.incomingRate(): Double = beatMatchRate ?: 1.0

// Where the incoming song starts its silent run-up.
fun TransitionPlan.preRollFromMs(): Long =
    if (overlapMs <= 0) 0 else max(0L, entryMs - (PRE_ROLL_LEAD_MS * incomingRate()).toLong())

// Where in the playing song the incoming song starts its run-up.
fun TransitionPlan.preRollAtMs(): Double = startMs - (entryMs - preRollFromMs()) / incomingRate()

// Where the incoming song should be when the playing song is at `outgoingMs`.
fun TransitionPlan.incomingAtMs(outgoingMs: Double): Double = entryMs + (outgoingMs - startMs) * incomingRate()

// How far the incoming song runs ahead of where it should be, in the
// playing song's time: negative when it is behind.
fun TransitionPlan.alignmentErrorMs(outgoingMs: Double, incomingMs: Double): Double =
    (incomingMs - entryMs) / incomingRate() - (outgoingMs - startMs)

// Lines the incoming song up with the playing one during its silent
// run-up. It is told the two positions as they come and answers what to
// do: wait, move the incoming song to a position (it is still silent), or
// go ahead with the offset it ended on. A deck that is moved stops and
// starts again, which itself costs a little time; the lag seen after each
// move is added to the next.
class Aligner(private val plan: TransitionPlan) {
    sealed interface Step {
        data object Wait : Step

        data class Seek(val toMs: Long) : Step

        // `errorMs` is the offset at the last measure, null if none was
        // made; `locked` says whether it is close enough to step on the beat.
        data class Settled(val errorMs: Double?, val locked: Boolean) : Step
    }

    private var playingSince: Long? = null
    private var seeks = 0
    private var lag = 0.0
    private var lastError: Double? = null
    private var settled: Step.Settled? = null

    // `nowMs` is a clock in real milliseconds; `outgoingMs` and
    // `incomingMs` are the two songs' positions read at that moment.
    fun observe(nowMs: Long, outgoingMs: Double, incomingMs: Double, incomingPlaying: Boolean): Step {
        settled?.let { return it }
        // Time left before the blend, in the playing song's time.
        val left = plan.startMs - outgoingMs
        if (!incomingPlaying) {
            playingSince = null
            return if (left <= 0) settle() else Step.Wait
        }
        val since = playingSince ?: nowMs.also { playingSince = it }
        val wait = if (seeks == 0) ALIGN_FIRST_MS else ALIGN_AGAIN_MS
        if (nowMs - since < wait) {
            return if (left <= 0) settle() else Step.Wait
        }
        val error = plan.alignmentErrorMs(outgoingMs, incomingMs)
        if (seeks > 0) lag -= error
        lastError = error
        if (abs(error) <= ALIGN_TOLERANCE_MS || seeks >= ALIGN_MOST_SEEKS || left < ALIGN_LAST_CHANCE_MS) return settle()
        seeks++
        playingSince = null
        // Where it should be now, plus the time a move is seen to cost.
        val target = plan.incomingAtMs(outgoingMs + lag)
        return Step.Seek(target.toLong().coerceAtLeast(0))
    }

    // Goes ahead with whatever was measured last; the blend is starting.
    fun finish(): Step.Settled = settled ?: settle()

    private fun settle(): Step.Settled {
        val error = lastError
        return Step.Settled(error, error != null && abs(error) < BAR_LOCK_TOLERANCE_MS).also { settled = it }
    }
}
