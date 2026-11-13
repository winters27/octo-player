package app.winters.octo.sound

import android.os.Looper
import androidx.media3.common.util.UnstableApi
import app.winters.octo.playback.BlendCause
import app.winters.octo.playback.BlendEventKind
import app.winters.octo.playback.LATE_MOVE_UNBOUNDED
import app.winters.octo.playback.TransitionKind
import app.winters.octo.playback.TransitionPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.math.abs

// The incoming song going dry inside the outgoing one's hold, the room
// deadline, the clean cut, and the rules while a blend is armed, on the
// mechanism fake. Every expected value comes from the plan's own curves
// and the hold's piecewise blend time: unchanged before the hold, frozen
// through the extension, shifted by it after.
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HoldPlacementTest {
    private val rate = 48_000
    private val f = rate / 1_000

    private fun rig(watchdogMs: Long = 2_000) = MixRig(tuning = MixTuning(watchdogMs = watchdogMs, lagFloorMs = 5.0))

    // Start 2 s into A, B from its start, on a bar grid of `beatMs` beats.
    private fun heldPlan(
        kind: TransitionKind = TransitionKind.BLEND,
        overlap: Long = 4_000,
        holdShare: Double = 0.5,
        riseShare: Double = 0.0,
        beatMs: Double = 250.0,
        headroomDb: Double = 0.0,
        matched: Double? = null,
    ) = TransitionPlan(
        2_000, 0, overlap, kind, 0.4, 0.0, matched, beatMs, 0.0, false, headroomDb, "test",
        holdShare = holdShare, riseShare = riseShare, latestEndMs = 2_000 + overlap + 4_000, lateMoveBars = LATE_MOVE_UNBOUNDED,
    )

    // A steady at `level`, B parked with `bFrames` frames of `b` written,
    // fed again from `bBackAtMs` on the clock; the blend armed at 200 ms and
    // acked when proposed. B's last frames are mixed, and B found dry, only
    // once the output's lead has run down to its low-water mark, about the
    // time they are heard: B comes back after that.
    private fun hold(
        rig: MixRig,
        plan: TransitionPlan,
        level: Float,
        b: (Long) -> Float,
        bFrames: Int,
        bBackAtMs: Double,
        endMs: Double = plan.startMs + plan.overlapMs + 5_000.0,
        speed: Double = 1.0,
    ): Pair<TestDeck, TestDeck> {
        val a = rig.deck(0)
        a.signal = { level }
        a.play()
        a.feed()
        val bd = rig.deck(1)
        bd.signal = b
        bd.speed = speed
        rig.mixer.park(1, true)
        bd.play()
        bd.write(bFrames)
        rig.sched.at((bBackAtMs * 1_000_000).toLong()) { bd.feed() }
        rig.runFor(200.0)
        rig.ackProposals()
        rig.arm(testBlend(1, plan, endMs = endMs))
        return a to bd
    }

    private fun heard(rig: MixRig, m: Long): Float = rig.tap.frames[m] ?: Float.NaN

    // A's blend time at mix frame m for a start at S, a hold from `from`
    // until `to`, and `extMs` added after it.
    private fun blendAt(m: Long, s: Long, from: Long, to: Long, extMs: Double): Double = when {
        m < from -> (m - s).toDouble() / f
        m < to -> (from - s).toDouble() / f
        else -> (m - s).toDouble() / f - extMs
    }

    private fun checkA(rig: MixRig, plan: TransitionPlan, level: Float, range: LongRange, skip: Set<Long> = emptySet(), blend: (Long) -> Double) {
        val out = DeckTransition("e0", plan, incoming = false)
        for (m in range) {
            if (m in skip) continue
            val want = level * out.gainAt(blend(m) / plan.overlapMs)
            assertEquals("mix frame $m (blend ${blend(m)} ms)", want, heard(rig, m).toDouble(), 2e-3)
        }
    }

    @Test
    fun anIncomingSongDryInsideTheHoldIsPlacedWholeBarsLaterAndConsumesNothingWhileHeld() {
        val rig = rig()
        val plan = heldPlan()
        val click = 120_000L
        // B has half a second, so it runs dry at S + 24,000 (500 ms into a
        // 2 s hold), and is fed again 2.7 s in.
        hold(rig, plan, 0.3f, { fr -> if (fr == click) 0.5f else 0f }, 24_000, 2_700.0)
        rig.runFor(7_400.0)
        val s = 2_000L * f
        val stop = s + 24_000
        val moved = rig.events.filter { it.kind == BlendEventKind.MOVED }
        assertEquals("one moved event: ${rig.events.map { "${it.kind}@${it.frame}" }}", 1, moved.size)
        assertEquals(1L, moved[0].generation)
        // A whole number of A's bars (1,000 ms), here one.
        assertEquals(1_000.0, moved[0].ms2, 1e-6)
        val placed = stop + 1_000L * f
        // B consumed nothing while held: its click lands one bar later than
        // with no stall.
        val clickAt = s + 1_000L * f + click
        val inT = DeckTransition("e1", plan, incoming = true)
        val gB = inT.gainAt(click.toDouble() / f / plan.overlapMs)
        val blend = { m: Long -> blendAt(m, s, stop, placed, 1_000.0) }
        val outT = DeckTransition("e0", plan, incoming = false)
        assertEquals("the click", 0.5 * gB + 0.3 * outT.gainAt(blend(clickAt) / plan.overlapMs), heard(rig, clickAt).toDouble(), 5e-3)
        checkA(rig, plan, 0.3f, (s - 4_800) until (s + 5_800L * f), setOf(clickAt), blend)
        // The extended end: B's song time since entry is the unextended overlap.
        val end = rig.events.single { it.kind == BlendEventKind.BLEND_END }
        assertEquals(s + 5_000L * f, end.frame)
        assertEquals(4_000.0, end.ms, 0.1)
    }

    @Test
    fun anIntroWithHeadroomHoldsAAtItsHoldValueThroughTheMoveAtEitherRate() {
        for (matched in listOf<Double?>(null, 0.94)) {
            val rig = rig()
            val plan = heldPlan(kind = TransitionKind.INTRO, overlap = 6_000, holdShare = 0.5, riseShare = 0.25, headroomDb = 3.0, matched = matched)
            val click = 192_000L
            // B (at the matched rate through Sonic) runs dry 1 s into a 3 s hold.
            hold(rig, plan, 0.3f, { fr -> if (fr == click) 0.5f else 0f }, 48_000, 3_200.0, speed = matched ?: 1.0)
            rig.runFor(7_300.0)
            val s = 2_000L * f
            val stop = s + 48_000
            val moved = rig.events.single { it.kind == BlendEventKind.MOVED }
            assertEquals("rate $matched", 1_000.0, moved.ms2, 1e-6)
            val placed = stop + 1_000L * f
            val blend = { m: Long -> blendAt(m, s, stop, placed, 1_000.0) }
            val clickAt = s + 1_000L * f + click
            checkA(rig, plan, 0.3f, (s - 4_800) until clickAt + 4_800, setOf(clickAt), blend)
            // The hold value, headroom included, with no step at the move.
            val holdValue = 0.3 * DeckTransition("e0", plan, incoming = false).gainAt(1_000.0 / plan.overlapMs)
            for (m in stop - 480 until placed + 480) assertEquals("rate $matched frame $m", holdValue, heard(rig, m).toDouble(), 2e-3)
            assertTrue("rate $matched: the click on the bar", abs(heard(rig, clickAt) - holdValue.toFloat()) > 0.05f)
        }
    }

    @Test
    fun anIncomingSongNotReadyByTheRoomDeadlineLetsAFadeOnAndComesInAfter() {
        val rig = rig()
        val plan = heldPlan()
        // B dry 500 ms into the hold and back only after the blend's end.
        hold(rig, plan, 0.3f, { 0.2f }, 24_000, 10_000.0)
        rig.runFor(10_800.0)
        val s = 2_000L * f
        val stop = s + 24_000
        // The deadline is the last bar line whose placement still ends by the
        // latest end (4 bars of room) with A's 2 s spare left.
        val moved = rig.events.single { it.kind == BlendEventKind.MOVED }
        assertEquals(4_000.0, moved.ms2, 1e-6)
        val deadline = stop + 4_000L * f
        val blend = { m: Long -> blendAt(m, s, stop, deadline, 4_000.0) }
        val endFrame = s + 8_000L * f
        // A's blend time ran again at the deadline: it faded to nothing at
        // the extended end, never playing on at its hold level.
        checkA(rig, plan, 0.3f, (s - 4_800) until endFrame, emptySet(), blend)
        val ends = rig.events.filter { it.kind == BlendEventKind.BLEND_END }
        assertEquals("one blend end: ${rig.events.map { "${it.kind}@${it.frame}" }}", 1, ends.size)
        assertEquals(endFrame, ends[0].frame)
        // Nothing heard after A's end until B is back, then B alone at its own level.
        val firstB = (endFrame until endFrame + 200L * f * 10).firstOrNull { (rig.tap.frames[it] ?: 0f) != 0f }
        assertTrue("B came back: ${rig.tap.frames.size} frames written", firstB != null)
        for (m in endFrame + 480 until firstB!!) assertEquals("frame $m", 0f, rig.tap.frames[m] ?: 0f, 0f)
        for (m in firstB + 300L * f until firstB + 500L * f) assertEquals("B alone at frame $m", 0.2f, heard(rig, m), 1e-6f)
    }

    @Test
    fun withNoRoomTheIncomingSongComesBackByACleanCutFromWhereItStopped() {
        val rig = rig()
        val plan = heldPlan()
        // A's end leaves less than its ring lead, the sink buffer and 1 s
        // after one bar: no room to move, so A fades on.
        hold(rig, plan, 0.3f, { fr -> (fr % 400_000 + 1) / 1_000_000f }, 24_000, 2_700.0, endMs = 5_000.0)
        rig.runFor(4_500.0)
        val s = 2_000L * f
        val stop = s + 24_000
        assertFalse(rig.events.any { it.kind == BlendEventKind.MOVED })
        val blend = { m: Long -> (m - s).toDouble() / f }
        val outT = DeckTransition("e0", plan, incoming = false)
        val end = rig.events.single { it.kind == BlendEventKind.BLEND_END }
        val cut = end.frame
        assertTrue("the cut after B's stop: $cut", cut > stop)
        assertEquals(500.0, end.ms, 0.1)
        // A on along its planned fade (its blend time never frozen) up to the cut.
        checkA(rig, plan, 0.3f, (s - 4_800) until cut, emptySet(), blend)
        // One mix point: A out and B in over the same 20 ms, B from the frame
        // it stopped at, its shaping disarmed, nothing dropped.
        for (j in 0 until 960) {
            val m = cut + j
            val want = 0.3 * outT.gainAt(blend(m) / plan.overlapMs) * (960 - j) / 960 + (24_000 + j + 1) / 1_000_000.0 * j / 960
            assertEquals("frame $m", want, heard(rig, m).toDouble(), 2e-3)
        }
        for (m in cut + 960 until cut + 48_000) {
            assertEquals("frame $m", 24_000 + (m - cut), Math.round(heard(rig, m) * 1_000_000.0) - 1)
        }
    }

    @Test
    fun aHeldIncomingSongNeverTripsTheWatchdogAtSixtyBeatsAMinute() {
        val rig = rig(watchdogMs = 1_000)
        // A 4 s bar: B is held for one whole bar with a 1 s watchdog.
        val plan = heldPlan(overlap = 6_000, holdShare = 0.75, beatMs = 1_000.0)
        val (a, b) = hold(rig, plan, 0.3f, { 0f }, 24_000, 2_700.0)
        rig.runFor(6_800.0)
        val moved = rig.events.single { it.kind == BlendEventKind.MOVED }
        assertEquals(4_000.0, moved.ms2, 1e-6)
        assertEquals(0, a.output.failure)
        assertEquals(0, b.output.failure)
    }

    // Armed-state rules

    private fun counting(f: Long): Float = (f % 400_000 + 1) / 1_000_000f

    private fun carried(sample: Float): Long = Math.round(sample * 1_000_000.0) - 1

    @Test
    fun anOutgoingRebufferInTheRunUpKeepsThePlanAndTheStartWaitsForItsFrame() {
        val rig = rig()
        val a = rig.deck(0)
        a.signal = ::counting
        a.play()
        a.feed()
        // A's source stops for 2 s in the run-up, long enough to run dry.
        rig.stalled[0] = 500_000_000
        rig.sched.at(2_500_000_000) {
            rig.stalled[0] = Long.MAX_VALUE
            a.feed()
        }
        val b = rig.deck(1)
        b.signal = { 0f }
        rig.mixer.park(1, true)
        b.play()
        b.feed()
        rig.runFor(200.0)
        rig.ackProposals()
        val plan = heldPlan(holdShare = 0.0).copy(startMs = 3_000)
        rig.arm(testBlend(1, plan))
        rig.runFor(7_000.0)
        assertFalse("never disarmed: ${rig.events.map { it.kind }}", rig.events.any { it.kind == BlendEventKind.CANCELLED || it.kind == BlendEventKind.INCOMING_NOT_READY })
        val proposed = rig.events.single { it.kind == BlendEventKind.PROPOSED }
        // B starts against A's frame at 3,000 ms, wherever the stall put it.
        val s = proposed.frame
        assertEquals(3_000L * f - 1, carried(heard(rig, s - 1)))
    }

    @Test
    fun aDecksOwnPauseInTheRunUpDisarms() {
        for (slot in 0..1) {
            val rig = rig()
            val a = rig.deck(0)
            a.signal = ::counting
            a.play()
            a.feed()
            val b = rig.deck(1)
            b.signal = { 0.9f }
            rig.mixer.park(1, true)
            b.play()
            b.feed()
            rig.runFor(200.0)
            rig.ackProposals()
            rig.arm(testBlend(1, heldPlan(holdShare = 0.0)))
            rig.runFor(800.0)
            // The deck's own pause (a noisy pause), with frames left.
            val paused = if (slot == 0) a else b
            paused.pause()
            shadowOf(Looper.getMainLooper()).idle()
            rig.runFor(400.0)
            val cancel = rig.events.filter { it.kind == BlendEventKind.CANCELLED }
            assertEquals("slot $slot: ${rig.events.map { "${it.kind}/${it.cause}" }}", 1, cancel.size)
            assertEquals(BlendCause.PAUSED, cancel[0].cause)
            if (slot == 0) {
                // A was the only deck playing: the output paused.
                assertEquals(1, rig.track(0).pauses)
                a.play()
            } else {
                // A plays on through B's pause.
                assertEquals(0, rig.track(0).pauses)
            }
            rig.runFor(3_500.0)
            assertFalse(rig.events.any { it.kind == BlendEventKind.PROPOSED })
            // Only A was ever heard, each of its frames once and in order: mix
            // frame m carries A's frame m, but for the onset ramp of each
            // fresh output that continues it.
            val ramps = rig.tap.byOutput.keys.sorted().drop(1).map { id -> rig.tap.byOutput.getValue(id).first() }
            val heard = rig.tap.frames.keys.sorted()
            for (m in heard) {
                val v = rig.tap.frames.getValue(m)
                if (ramps.any { m in it until it + 960 }) {
                    assertTrue("slot $slot: ramp frame $m", v >= 0f && v <= counting(m) + 1e-7f)
                } else {
                    assertEquals("slot $slot: mix frame $m", m, carried(v))
                }
            }
            assertTrue(heard.last() > 4_000L * f)
        }
    }

    @Test
    fun anIncomingSongDryInTheRunUpWaitsForTheStart() {
        for (readyAtMs in listOf(1_000.0, 2_500.0)) {
            val rig = rig()
            val a = rig.deck(0)
            a.signal = { 0.1f }
            a.play()
            a.feed()
            // B's sink plays from the arm but has nothing until `readyAtMs`.
            val b = rig.deck(1)
            b.signal = { 0.2f }
            rig.mixer.park(1, true)
            b.play()
            rig.sched.at((readyAtMs * 1_000_000).toLong()) { b.feed() }
            rig.runFor(200.0)
            rig.ackProposals()
            rig.arm(testBlend(1, heldPlan(holdShare = 0.0)))
            rig.runFor(4_500.0)
            val kinds = rig.events.map { "${it.kind}/${it.cause}" }
            assertFalse("ready at $readyAtMs: $kinds", rig.events.any { it.kind == BlendEventKind.CANCELLED })
            if (readyAtMs < 1_200) {
                // Ready before the start was decided: the blend starts.
                assertTrue(kinds.toString(), rig.events.any { it.kind == BlendEventKind.PROPOSED })
                assertFalse(rig.events.any { it.kind == BlendEventKind.INCOMING_NOT_READY })
            } else {
                // Still dry at the start: not ready, and nothing of B heard.
                val notReady = rig.events.single { it.kind == BlendEventKind.INCOMING_NOT_READY }
                assertEquals(BlendCause.NOT_READY, notReady.cause)
                for ((m, v) in rig.tap.frames) assertEquals("frame $m", 0.1f, v, 1e-6f)
            }
        }
    }
}
