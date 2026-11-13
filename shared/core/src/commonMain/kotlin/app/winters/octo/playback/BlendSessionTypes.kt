package app.winters.octo.playback

import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

// The two decks of a blend: A plays out, B comes in.
enum class BlendDeck { A, B }

// What a deck's audio thread found, posted with the output frame where it
// happened (the mix frame on Android, the bus frame on iPhone).
enum class BlendInput {
    // The deck can give frames: its sink plays and its ring holds frames,
    // or it has reached its end. Also a dry deck that has frames again.
    READY,

    // A playing sink with an empty ring, its end not reached.
    DRY,

    // A sink pause while unmixed frames remain (never a rebuffer). The
    // value is the settled head: the output frame the hardware stopped at.
    PAUSED,

    // A's stream ended with nothing after it.
    ENDED,

    // A's first frame of a new pass (another entry, or the same entry from
    // its start right after its end) is mixed at this frame.
    MOVED_ON,

    // A's frame at the plan's start is known: the value is S, the output
    // frame it is mixed at. Posted at the frame where it became known, which
    // is before S.
    START_AT,

    // A's frame at the blend end (extensions included) is known: the value
    // is its output frame. Posted at the frame where it became known.
    END_REACHED,

    // An engine restart re-placed a decided start that has not played yet:
    // the value is the new S.
    RESTARTED,

    // A's decoded end is known (its stream's last frame of the song is in
    // hand, its next pass or its stream's end after it): the value is the
    // output frame just past A's last frame of the song. Posted at the frame
    // where it became known.
    DECODED_END,
}

// Idle; armed (the run-up); mixing; held (B stopped inside A's hold,
// waiting to be placed on a bar); stalled (B stopped, waiting for the clean
// cut); ended (the blend's end is decided, A not yet stopped).
enum class BlendState { IDLE, ARMED, MIXING, HELD, STALLED, ENDED }

// Why the main thread disarms: a pause takes its own rule after the
// handover, every other interrupt is an edit.
enum class BlendDisarm { EDIT, PAUSE }

// What the audio thread does, at the action's frame (or at once when the
// frame is already behind it).
enum class BlendActionKind {
    // B's first frame is mixed at the frame, B's shaping armed by the plan.
    START_B,

    // B's start is final: its frames may be written (Android) or rendered
    // (iPhone) from the frame on.
    COMMIT_B,

    // B's start at the frame is undone: none of B's frames is written, B is
    // parked again and A goes on alone at its own level.
    UNDO_START,

    // B's frames already rendered fade out over `value` frames.
    FADE_OUT_B,

    // A's blend shaping goes to its own level over `value` frames (0 when
    // nothing it shaped has been heard).
    A_DISARM,

    // A leaves with a ramp of `value` frames; what of it was mixed plays out.
    A_LEAVE,

    // A's frames of its own song from the frame on are dropped and counted
    // presented; frames of its next song are never mixed.
    A_END,

    // A's frames past its song's last frame (its next pass, from the frame
    // on) are held, neither mixed nor counted presented.
    A_HOLD_NEXT,

    // What A plays after a held end: the pass named by `value`
    // (BLEND_PASS_NONE: nothing, A stops at its end; BLEND_PASS_HELD: the
    // pass it holds, as its own advance). Held frames of that pass are mixed,
    // any others dropped.
    A_FOLLOW,

    // B's shaping goes to its own level over `value` frames, and any hold
    // on B is lifted: B plays from where it stopped as soon as it has frames.
    B_DISARM,

    // B consumes nothing from the frame on.
    HOLD_B,

    // B's next frame is mixed at the frame (shaping on).
    PLACE_B,

    // The clean cut at the frame: A leaves with a ramp of `value` frames,
    // and B comes in from where it stopped with the same ramp, its shaping
    // disarmed.
    CUT,

    // A's blend time stops at A's frame mixed at the frame.
    FREEZE_A,

    // A's blend time runs again from the frame, shifted by `ms` in all.
    UNFREEZE_A,

    // The plan's start moves to `ms` of A's song time.
    MOVE_START,

    // The mix limiter limits the sum from the frame on, whatever the
    // Limiter setting.
    LIMITER_ON,

    // The mix limiter is off from the frame on (by a need of 1 and its
    // release).
    LIMITER_OFF,

    // While paused, the output is cleared and mixed again from the frame,
    // the consumed point, by the actions that follow.
    REMIX,
}

// What the main thread is told.
enum class BlendEventKind {
    // B's start at the frame is decided: main acks and hands over.
    PROPOSED,

    // B did not start (cause NOT_READY, LATE or FLOOR): re-plan the rest of A.
    INCOMING_NOT_READY,

    // The start moved (`ms`, A's song time) or a hold extended the blend
    // (`ms2`, in all).
    MOVED,

    // The blend ended at the frame: finish the fade. `ms` is where easing
    // starts, in B's song time since entry over the rate.
    BLEND_END,

    // The session cancelled the plan itself (cause A_MOVED_ON, A_ENDED,
    // PAUSED, or PACE for a speed drop's cut before the handover).
    CANCELLED,

    // An undone start needs to hear what A plays next: answer with followUp.
    FOLLOW_UP_NEEDED,
}

enum class BlendCause { NONE, NOT_READY, LATE, FLOOR, A_MOVED_ON, A_ENDED, PAUSED, PACE }

// Follow-up answers: no next pass (A stops at its end), the pass A already
// holds, and a failure that carries no answer.
const val BLEND_PASS_NONE = -1L
const val BLEND_PASS_HELD = -2L
const val BLEND_NO_ANSWER = -3L

// A leave, a cut's two ramps and a rejoin take this long; a disarm to a
// song's own level takes the longer one.
const val BLEND_LEAVE_RAMP_MS = 20.0
const val BLEND_DISARM_RAMP_MS = 250.0

// A hold may move the meeting point only while A has more than its ring's
// lead plus the sink buffer plus this much left, in output time.
const val BLEND_ROOM_SPARE_MS = 1_000.0

// A plan whose drift bound holds wherever it sits: its start may move any
// number of bars.
const val LATE_MOVE_UNBOUNDED = Int.MAX_VALUE

// The numbers that differ between phones and routes, in output frames
// unless named otherwise. The caller may change them between calls.
class BlendTiming(
    var sampleRate: Int,
    // How far ahead an action that lands on a later frame (a cut, a
    // placement) must land.
    var placementHorizonFrames: Long,
    // How far ahead of the head a start must be decided; a start closer
    // than this moves (the late start).
    var proposalLeadFrames: Long,
    // The start is proposed once it is this close to the head.
    var proposeAtFrames: Long,
    // The start commits, or is cancelled when main has not acked, once it
    // is this close to the head (the cap floor on Android, the commit
    // deadline on iPhone).
    var commitAtFrames: Long,
    // The deck ring's lead and the sink's buffer, for the hold's room.
    var ringLeadMs: Double,
    var sinkBufferMs: Double,
)

// One plan as the session needs it. Times are A's song time in ms; a bar
// length of NaN (or 0) means A's tempo is not locked. A's end is its length
// as known, for the hold's room. Generations start at 1 and stay under
// 2^61.
data class BlendArm(
    val generation: Long,
    val startMs: Double,
    val overlapMs: Double,
    val latestEndMs: Double,
    val outgoingEndMs: Double,
    // The most whole bars of A the blend may move later in all (a late
    // start, a hold, a restart inside the commit deadline) and still meet its
    // drift bound: LATE_MOVE_UNBOUNDED for no limit, 0 for no move. A move
    // past it does not happen. Without a bar grid only 0 counts (no move and
    // no hold extension); any other budget moves within the latest end.
    val lateMoveBars: Int,
    val barMs: Double = Double.NaN,
    // A holds its level (and B does not yet sound) for this share of the
    // overlap, whatever the kind: an intro's hold, a silent lead-in. B going
    // dry before it may move the meeting point.
    val holdShare: Double = 0.0,
    val speed: Double = 1.0,
    // Where both songs are heard together, and where their beats meet, in
    // the blend's time (ms of A from the start); the beats part by
    // driftGap ms a ms away from the lock. A gap of 0 has no bound.
    val bothHeardStartMs: Double = 0.0,
    val bothHeardEndMs: Double = 0.0,
    val lockMs: Double = 0.0,
    val driftGap: Double = 0.0,
    // A start too quiet to be heard before the acknowledgement can arrive
    // commits at its deadline without waiting for it.
    val commitBeforeAck: Boolean = false,
)

// One action, filled in by BlendSession.takeAction.
class BlendAction {
    var kind: BlendActionKind = BlendActionKind.START_B
    var frame: Long = 0
    var value: Long = 0
    var ms: Double = 0.0
}

// One event, filled in by BlendSession.takeEvent.
class BlendEvent {
    var kind: BlendEventKind = BlendEventKind.PROPOSED
    var generation: Long = 0
    var frame: Long = 0
    var ms: Double = 0.0
    var ms2: Double = 0.0
    var cause: BlendCause = BlendCause.NONE
}

// Where a generation's start stands between the main thread and the audio
// thread: the arm opens it INITIAL, the audio thread proposes, main acks or
// cancels, and the audio thread cancels at its floor.
enum class CommitState { INITIAL, PROPOSED, ACKED, CANCELLED }

private val COMMIT_STATES = CommitState.entries.toTypedArray()

// A generation and its state packed into one Long: the generation above the
// two low bits that hold the state, so one compare-and-set checks both and
// a stale generation never matches.
fun commitWordOf(generation: Long, state: CommitState): Long = (generation shl 2) or state.ordinal.toLong()

fun commitGenerationOf(word: Long): Long = word ushr 2

fun commitStateOf(word: Long): CommitState = COMMIT_STATES[(word and 3L).toInt()]

// The one word main and the audio thread share. Before any arm it holds
// generation 0, cancelled.
class CommitWord {
    private val word = AtomicLong(commitWordOf(0, CommitState.CANCELLED))

    fun get(): Long = word.get()

    // A new generation, INITIAL. The registering thread opens it before it
    // sends the arm.
    fun open(generation: Long) = word.set(commitWordOf(generation, CommitState.INITIAL))

    fun compareAndSet(expect: Long, update: Long): Boolean = word.compareAndSet(expect, update)

    // The generation's state, or null once the word holds another one.
    fun stateOf(generation: Long): CommitState? {
        val now = word.get()
        return if (commitGenerationOf(now) == generation) commitStateOf(now) else null
    }

    fun propose(generation: Long): Boolean =
        compareAndSet(commitWordOf(generation, CommitState.INITIAL), commitWordOf(generation, CommitState.PROPOSED))

    fun ack(generation: Long): Boolean =
        compareAndSet(commitWordOf(generation, CommitState.PROPOSED), commitWordOf(generation, CommitState.ACKED))

    // INITIAL or PROPOSED to CANCELLED; false once acked, cancelled or stale.
    fun cancel(generation: Long): Boolean =
        compareAndSet(commitWordOf(generation, CommitState.INITIAL), commitWordOf(generation, CommitState.CANCELLED)) ||
            compareAndSet(commitWordOf(generation, CommitState.PROPOSED), commitWordOf(generation, CommitState.CANCELLED))
}

// When to arm a blend, in A's song time: the earlier of the run-up start
// and the moment the start is the proposal lead plus the spare's ready lead
// ahead in output time. At high speed the run-up alone can leave less
// output time than the lead.
fun blendArmAtMs(startMs: Double, preRollLeadMs: Double, proposalLeadMs: Double, readyLeadMs: Double, speed: Double): Double =
    min(startMs - preRollLeadMs, startMs - (proposalLeadMs + readyLeadMs) * speed)

// After a drop of the pace to `pace`: the first moment at or after
// `fromMs`, inside the both-heard stretch and never before `holdEndMs` (the
// outgoing song keeps its own level through a hold), where the beats would
// be more than DRIFT_LIMIT_MS times the pace apart (their offset is driftGap
// times the distance from the lock); NaN when there is none. All in the
// blend's time.
fun blendDropCutAtMs(
    bothHeardStartMs: Double,
    bothHeardEndMs: Double,
    holdEndMs: Double,
    lockMs: Double,
    driftGap: Double,
    pace: Double,
    fromMs: Double,
): Double {
    if (driftGap <= 0) return Double.NaN
    val from = maxOf(bothHeardStartMs, holdEndMs, fromMs)
    if (from > bothHeardEndMs) return Double.NaN
    val within = DRIFT_LIMIT_MS * pace / driftGap
    return when {
        from < lockMs - within || from > lockMs + within -> from
        lockMs + within < bothHeardEndMs -> lockMs + within
        else -> Double.NaN
    }
}

// What a re-plan takes as now: A's song time at the output's head plus the
// proposal lead, never the mixed point or the heard position.
fun blendReplanNowMs(outgoingAtHeadMs: Double, proposalLeadFrames: Long, sampleRate: Int, speed: Double): Double =
    outgoingAtHeadMs + proposalLeadFrames * 1_000.0 / sampleRate * speed

// B's rate at its song time `incomingMs`: the matched rate until its time
// since entry over the rate passes `easeFromMs` (from the blend end event),
// then eased back to its own. Song time stands still while B is held, so a
// stall never moves the easing.
fun blendIncomingRate(rate: Double, incomingMs: Double, entryMs: Double, easeFromMs: Double): Double =
    if (rate == 1.0) 1.0 else beatMatchRateAt(rate, (incomingMs - entryMs) / rate, easeFromMs)

// The spare song that failed for this transition. It is not loaded into
// the spare again until the song changes, so the playing deck reaches it
// once by its own advance instead of the spare reloading it on every check.
class BlendFailedSpare {
    var failedFor: String? = null
        private set

    fun failed(entryId: String) {
        failedFor = entryId
    }

    fun mayLoad(entryId: String): Boolean = entryId != failedFor

    fun songChanged() {
        failedFor = null
    }
}
