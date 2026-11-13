package app.winters.octo.sound

import androidx.media3.common.util.UnstableApi
import app.winters.octo.playback.BlendCause
import app.winters.octo.playback.BlendDisarm
import app.winters.octo.playback.BlendEventKind
import app.winters.octo.playback.CommitState
import app.winters.octo.playback.LATE_MOVE_UNBOUNDED
import app.winters.octo.playback.TransitionKind
import app.winters.octo.playback.TransitionPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeckMixerTest {
    private val ceiling = 10.0.pow(-1.0 / 20).toFloat()

    // A deck playing from the start, fed as a playback thread would.
    private fun playing(rig: MixRig, slot: Int, signal: (Long) -> Float): TestDeck {
        val d = rig.deck(slot)
        d.signal = signal
        d.play()
        d.feed()
        return d
    }

    @Test
    fun anUntouchedSongPassesBitForBit() {
        val rig = MixRig()
        val noise = Random(3)
        val samples = FloatArray(200_000) { (noise.nextFloat() * 2 - 1) * 0.999f }
        playing(rig, 0) { f -> samples[(f % samples.size).toInt()] }
        rig.runFor(2_000.0)
        val t = rig.track(0)
        assertTrue("written ${t.written}", t.written > 48_000)
        // The mix limiter stays in the path with its fixed delay and passes
        // at unity: frame i of the track is the song's frame i.
        for (i in 0 until t.written) assertEquals("frame $i", samples[(i % samples.size).toInt()], t.left(i), 0f)
    }

    @Test
    fun aFreshOutputPlaysWithNothingWrittenAndMusicComesTheSlackAfterTheSignal() {
        val rig = MixRig()
        playing(rig, 0) { 0.25f }
        rig.runFor(500.0)
        val t = rig.track(0)
        assertEquals(1, t.plays)
        // The first frame was written no earlier than the slack after play().
        assertTrue(t.writes.isNotEmpty())
        val firstWrite = t.writes.first()
        assertTrue("first music ${firstWrite / 1e6} ms", firstWrite >= 20_000_000)
        assertEquals(t.bufferFrames, t.threshold)
    }

    @Test
    fun twoFullScaleDecksStayUnderTheCeilingFromTheStartAndAddUpBeforeIt() {
        val rig = MixRig(tuning = MixTuning(watchdogMs = 2_000, lagFloorMs = 5.0))
        val na = Random(1)
        val nb = Random(2)
        val aSamples = FloatArray(400_000) { (na.nextFloat() * 2 - 1) }
        val bSamples = FloatArray(400_000) { (nb.nextFloat() * 2 - 1) }
        val a = playing(rig, 0) { f -> aSamples[(f % aSamples.size).toInt()] }
        val b = rig.deck(1)
        b.signal = { f -> bSamples[(f % bSamples.size).toInt()] }
        rig.mixer.park(1, true)
        b.play()
        b.feed()
        rig.runFor(300.0)
        val plan = testPlan(start = 2_000, entry = 0, overlap = 2_000)
        rig.mixer.commitWord.open(1)
        rig.mixer.arm(testBlend(1, plan))
        rig.onEvent = { e -> if (e.kind == BlendEventKind.PROPOSED) rig.mixer.commitWord.ack(e.generation).also { rig.mixer.acked(e.generation) } }
        rig.runFor(6_000.0)
        val t = rig.track(0)
        val s = 2_000 * 48
        val l = 72
        assertTrue("written ${t.written}", t.written > s + 96_000)
        var under = 0
        for (i in s.toLong() + l until s + 96_000L) assertTrue("frame $i at ${t.left(i)}", abs(t.left(i)) <= ceiling + 1e-6f)
        for (i in 0L until s) {
            if (abs(t.left(i) - aSamples[i.toInt()]) > 0f) under++
        }
        assertEquals("A alone before the start", 0, under)
        assertTrue(rig.events.any { it.kind == BlendEventKind.BLEND_END })
        a.feed(forMs = 0.0)
    }

    // The loudest point between frames k and k + 1 of `x`, read at eight
    // points a frame by a long windowed sinc (Blackman, 48 frames each side).
    private fun peakBetween(x: (Long) -> Float, k: Long): Double {
        val half = 48
        var peak = 0.0
        for (step in 1 until 8) {
            val along = step / 8.0
            var y = 0.0
            var norm = 0.0
            for (j in -half + 1..half) {
                val d = along - j
                val w = 0.42 + 0.5 * kotlin.math.cos(PI * d / half) + 0.08 * kotlin.math.cos(2 * PI * d / half)
                val c = sin(PI * d) / (PI * d) * w
                y += x(k + j) * c
                norm += c
            }
            peak = max(peak, abs(y / norm))
        }
        return peak
    }

    @Test
    fun theMixHoldsTheSumsTruePeakUnderTheCeilingBetweenItsSamples() {
        // Two loud sines at a quarter of the rate, a quarter cycle apart from
        // their samples: each sample reads 0.71 of the wave's top, so a limit
        // on samples alone lets the sound between them 3 dB past the ceiling.
        val wave = { f: Long -> (0.95 * sin(PI / 2 * f + PI / 4)).toFloat() }
        val rig = blended(testPlan(start = 1_500, entry = 0, overlap = 1_000), wave, wave, 4_000.0)
        val t = rig.track(0)
        val s = 1_500L * 48
        val end = rig.events.first { it.kind == BlendEventKind.BLEND_END }.frame
        assertTrue("written ${t.written} past $end", t.written > end + 4_800)
        val left = { f: Long -> t.left(f) }
        var worst = 0.0
        var worstAt = -1L
        for (k in s + 72 + 48 until end - 48) {
            val p = peakBetween(left, k)
            if (p > worst) {
                worst = p
                worstAt = k
            }
        }
        assertTrue("the sum's true peak ${20 * kotlin.math.log10(worst)} dB at $worstAt, past the ceiling", worst <= ceiling * 1.01)
    }

    @Test
    fun theIncomingSongStartsOnTheExactFrame() {
        // B parked 100 ms before its entry, and a seek that lands 7 ms late.
        for (landMs in listOf(400.0, 507.0)) {
            val rig = MixRig(tuning = MixTuning(watchdogMs = 2_000, lagFloorMs = 5.0))
            // A silent; B a click at its frame entry + 2,400 and one before
            // its entry, which must never be heard.
            playing(rig, 0) { 0f }
            val entryFrame = 500L * 48
            val b = rig.deck(1)
            b.from(landMs)
            b.signal = { f -> if (f == entryFrame + 2_400 || f == entryFrame - 480) 0.5f else 0f }
            rig.mixer.park(1, true)
            b.play()
            b.feed()
            rig.runFor(200.0)
            val plan = testPlan(start = 1_500, entry = 500, overlap = 1_000)
            rig.mixer.commitWord.open(1)
            rig.mixer.arm(testBlend(1, plan))
            rig.onEvent = { e -> if (e.kind == BlendEventKind.PROPOSED) rig.mixer.commitWord.ack(e.generation).also { rig.mixer.acked(e.generation) } }
            rig.runFor(4_000.0)
            val t = rig.track(0)
            // A's frame at the start is mix frame 72,000; B's entry + 2,400
            // lands 2,400 later whatever B's first frame was.
            val at = 1_500L * 48 + 2_400
            assertTrue("written ${t.written}", t.written > at + 10)
            val heard = (0 until t.written).filter { t.left(it) != 0f }
            assertEquals("landing $landMs: frames heard ${heard.take(5)}, events ${rig.events.map { "${it.kind}@${it.frame}" }}", listOf(at), heard)
        }
    }

    @Test
    fun anUndoBeforeTheAckWritesNoFrameOfBAndLeavesAAsIfNoBlend() {
        val reference = runA(blend = false, cancelAtMs = -1.0)
        val undone = runA(blend = true, cancelAtMs = 1_700.0)
        assertEquals(reference.size, undone.size)
        for (i in reference.indices) assertEquals("frame $i", reference[i], undone[i], 0f)
    }

    // A is a sine; B (if armed) is loud noise. Returns what was written,
    // left channel, for 4 s. Main cancels the start at `cancelAtMs`.
    private fun runA(blend: Boolean, cancelAtMs: Double): FloatArray {
        val rig = MixRig(tuning = MixTuning(watchdogMs = 2_000, lagFloorMs = 5.0))
        playing(rig, 0) { f -> (0.5 * sin(2 * PI * 440 * f / 48_000)).toFloat() }
        val b = rig.deck(1)
        b.signal = { 0.9f }
        rig.mixer.park(1, true)
        b.play()
        b.feed()
        rig.runFor(200.0)
        if (blend) {
            val plan = testPlan(start = 2_000, entry = 0, overlap = 2_000)
            rig.mixer.commitWord.open(1)
            rig.mixer.arm(testBlend(1, plan))
            rig.sched.at((cancelAtMs * 1_000_000).toLong()) {
                if (rig.mixer.commitWord.cancel(1)) rig.mixer.disarm(BlendDisarm.EDIT, 1)
            }
        }
        rig.runFor(4_000.0)
        val t = rig.track(0)
        return FloatArray(150_000) { t.left(it.toLong()) }
    }

    @Test
    fun theStartIsProposedAheadOfTheFloorAndBIsWrittenOnlyAfterTheAck() {
        val rig = MixRig(tuning = MixTuning(watchdogMs = 2_000, lagFloorMs = 5.0))
        playing(rig, 0) { 0.1f }
        val b = rig.deck(1)
        b.signal = { 0.2f }
        rig.mixer.park(1, true)
        b.play()
        b.feed()
        rig.runFor(200.0)
        val plan = testPlan(start = 2_000, entry = 0, overlap = 1_000)
        rig.mixer.commitWord.open(1)
        rig.mixer.arm(testBlend(1, plan))
        var proposedAt = -1L
        var writtenAtAck = -1L
        rig.onEvent = { e ->
            if (e.kind == BlendEventKind.PROPOSED) {
                proposedAt = rig.sched.now
                writtenAtAck = rig.track(0).written
                assertTrue(rig.mixer.commitWord.ack(e.generation))
                rig.mixer.acked(e.generation)
            }
        }
        rig.runFor(5_000.0)
        val s = 2_000L * 48
        assertTrue("proposed", proposedAt > 0)
        // Up to the ack the track held nothing at or past S.
        assertTrue("written at ack $writtenAtAck", writtenAtAck <= s)
        val t = rig.track(0)
        assertEquals(0.1f, t.left(s - 1), 1e-6f)
        assertTrue("B in after the ack", t.left(s + 40_000) > 0.1f)
        assertTrue(rig.events.any { it.kind == BlendEventKind.BLEND_END })
    }

    @Test
    fun aStartMainNeverAcksIsCancelledAtTheFloorAndAPlaysOn() {
        val rig = MixRig(tuning = MixTuning(watchdogMs = 2_000, lagFloorMs = 5.0))
        playing(rig, 0) { 0.1f }
        val b = rig.deck(1)
        b.signal = { 0.7f }
        rig.mixer.park(1, true)
        b.play()
        b.feed()
        rig.runFor(200.0)
        val plan = testPlan(start = 2_000, entry = 0, overlap = 1_000)
        rig.mixer.commitWord.open(1)
        rig.mixer.arm(testBlend(1, plan))
        rig.runFor(5_000.0)
        val t = rig.track(0)
        for (i in 0 until t.written) assertEquals("frame $i", 0.1f, t.left(i), 1e-6f)
        assertEquals(CommitState.CANCELLED, rig.mixer.commitWord.stateOf(1))
        assertTrue(rig.events.any { it.kind == BlendEventKind.INCOMING_NOT_READY && it.cause == BlendCause.FLOOR })
        // Main's late ack fails.
        assertFalse(rig.mixer.commitWord.ack(1))
    }

    // A blend acked as soon as it is proposed; returns the rig.
    private fun blended(plan: app.winters.octo.playback.TransitionPlan, a: (Long) -> Float, b: (Long) -> Float, runMs: Double, also: (MixRig, TestDeck, TestDeck) -> Unit = { _, _, _ -> }): MixRig {
        val rig = MixRig(tuning = MixTuning(watchdogMs = 2_000, lagFloorMs = 5.0))
        val da = playing(rig, 0, a)
        val db = rig.deck(1)
        db.signal = b
        rig.mixer.park(1, true)
        db.play()
        db.feed()
        rig.runFor(200.0)
        rig.mixer.commitWord.open(1)
        rig.mixer.arm(testBlend(1, plan))
        rig.onEvent = { e -> if (e.kind == BlendEventKind.PROPOSED) rig.mixer.commitWord.ack(e.generation).also { rig.mixer.acked(e.generation) } }
        also(rig, da, db)
        rig.runFor(runMs)
        return rig
    }

    @Test
    fun aftertheBlendTheMixLimiterReturnsToExactlyUnity() {
        // Loud enough together to be limited, each alone under the ceiling.
        val rig = blended(testPlan(start = 1_500, entry = 0, overlap = 1_000), { 0.8f }, { 0.8f }, 5_000.0)
        val t = rig.track(0)
        val end = rig.events.first { it.kind == BlendEventKind.BLEND_END }.frame
        // Once the release has run its course after the end, B alone passes
        // bit for bit: the gain lands on exactly 1.
        val from = end + 48 * 1_000
        assertTrue("written ${t.written} past $from", t.written > from + 4_800)
        for (i in from until from + 4_800) assertEquals("frame $i", 0.8f, t.left(i), 0f)
    }

    @Test
    fun anIntroTrimmedToHalfPlaysTheIncomingSongAtHalfThroughTheHoldAndWholeByTheEnd() {
        // A 4 s intro: B rises over its first second to 0.5, holds there to
        // the hold's end at 2 s, then comes up to its own level by 4 s. A is
        // silent, so what is heard is B's share alone.
        val plan = TransitionPlan(
            1_500, 0, 4_000, TransitionKind.INTRO, 0.4, 0.0, null, null, null, false, 0.0, "test",
            holdShare = 0.5, riseShare = 0.25, introLevel = 0.5, latestEndMs = 9_500, lateMoveBars = LATE_MOVE_UNBOUNDED,
        )
        val rig = blended(plan, { 0f }, { 0.4f }, 7_000.0)
        val s = 1_500L * 48
        val t = rig.track(0)
        assertTrue("written ${t.written}", t.written > s + 5_000L * 48)
        for (m in s + 1_050L * 48 until s + 2_000L * 48) assertEquals("the hold at mix frame $m", 0.2f, t.left(m), 2e-3f)
        // Up from the hold's end to its own level by the overlap's end.
        val end = s + 4_000L * 48
        assertTrue("past the hold ${t.left(s + 2_500L * 48)}", t.left(s + 2_500L * 48) > 0.2f + 1e-2f)
        assertEquals("at the overlap's last frame", 0.4f, t.left(end - 1), 1e-3f)
        for (m in end until end + 4_800) assertEquals("after the end at $m", 0.4f, t.left(m), 1e-3f)
    }

    @Test
    fun aNewOutputOnTheSparesSlotKeepsTheRegistration() {
        // The spare re-seeked (a re-plan) before the start: a new output on
        // the slot, the blend still registered and it still starts.
        val rig = blended(testPlan(start = 2_000, entry = 1_000, overlap = 1_000), { 0.1f }, { 0.2f }, 300.0) { r, _, b ->
            r.sched.at(r.sched.now + 300_000_000) {
                b.restartAt(900.0)
                b.play()
                b.feed()
            }
        }
        rig.runFor(4_000.0)
        assertTrue(rig.events.any { it.kind == BlendEventKind.BLEND_END })
        assertFalse(rig.events.any { it.kind == BlendEventKind.INCOMING_NOT_READY })
    }

    @Test
    fun aMainActionAfterTheAckLandsAtTheWrittenPointNotAfterTheMixAhead() {
        // The edit comes one block after the ack: A leaves with its 20 ms
        // ramp from the first block not yet written, B plays on disarmed.
        var editAt = -1L
        val rig = blended(testPlan(start = 2_000, entry = 0, overlap = 4_000), { 0.3f }, { 0.3f }, 6_000.0) { r, _, _ ->
            r.onEvent = { e ->
                if (e.kind == BlendEventKind.PROPOSED) {
                    r.mixer.commitWord.ack(e.generation)
                    r.mixer.acked(e.generation)
                    r.sched.at(r.sched.now + 10_000_000) {
                        editAt = r.track(0).written
                        r.mixer.disarm(BlendDisarm.EDIT, e.generation)
                    }
                }
            }
        }
        val t = rig.track(0)
        assertTrue(editAt > 0)
        // Before S A alone at its level; B from S on, A gone soon after.
        val s = 2_000L * 48
        assertEquals(0.3f, t.left(s - 100), 1e-6f)
        val late = s + 48 * 200
        assertEquals("B alone at its own level", 0.3f, t.left(late + 48 * 300), 1e-3f)
    }

    @Test
    fun thePlayerLevelReachesTheRealOutputAtOnceAndAFadeToZeroComesBack() {
        val rig = MixRig()
        playing(rig, 0) { 0.5f }
        rig.runFor(500.0)
        val t = rig.track(0)
        rig.mixer.setVolume(0.3f)
        rig.runFor(1.0)
        assertEquals(0.3f, t.volume, 0f)
        rig.mixer.setVolume(0f)
        rig.runFor(1.0)
        assertEquals(0f, t.volume, 0f)
        rig.mixer.setVolume(Float.NaN)
        rig.runFor(1.0)
        assertEquals("a level that is not a number is never applied", 0f, t.volume, 0f)
        rig.mixer.setVolume(1f)
        rig.runFor(1.0)
        assertEquals(1f, t.volume, 0f)
    }

    @Test
    fun anOutgoingSongThatStallsMidBlendEndsItAndTheIncomingOneGoesToItsOwnLevel() {
        val rig = blended(testPlan(start = 1_500, entry = 0, overlap = 3_000), { 0.3f }, { 0.3f }, 0.0)
        // A's sink stops being fed 2 s in, half way through the blend.
        rig.decksStop(0, 2_000.0)
        rig.runFor(6_000.0)
        assertTrue(rig.events.any { it.kind == BlendEventKind.BLEND_END })
        val t = rig.track(0)
        // Well after the stall and B's 250 ms ramp, B alone at its own level.
        val late = t.written - 4_800
        assertEquals(0.3f, t.left(late), 1e-6f)
    }

    @Test
    fun anInterruptInTheRunUpLeavesNoFrameOfTheSpareHeard() {
        val rig = blended(testPlan(start = 2_000, entry = 0, overlap = 1_000), { 0.1f }, { 0.9f }, 0.0) { r, _, _ ->
            r.sched.at(1_000_000_000) {
                r.mixer.commitWord.cancel(1)
                r.mixer.disarm(BlendDisarm.EDIT, 1)
            }
        }
        rig.runFor(4_000.0)
        val t = rig.track(0)
        for (i in 0 until t.written) assertEquals("frame $i", 0.1f, t.left(i), 1e-6f)
        assertFalse(rig.events.any { it.kind == BlendEventKind.PROPOSED })
    }

    @Test
    fun anIncomingSongNotReadyAtTheStartNeverStartsAndAPlaysOn() {
        val rig = MixRig(tuning = MixTuning(watchdogMs = 2_000, lagFloorMs = 5.0))
        playing(rig, 0) { 0.1f }
        // B loaded and parked, but its sink never plays.
        val b = rig.deck(1)
        b.signal = { 0.9f }
        rig.mixer.park(1, true)
        b.write(9_600)
        rig.runFor(200.0)
        rig.mixer.commitWord.open(1)
        rig.mixer.arm(testBlend(1, testPlan(start = 2_000, entry = 0, overlap = 1_000)))
        rig.runFor(4_000.0)
        val t = rig.track(0)
        for (i in 0 until t.written) assertEquals("frame $i", 0.1f, t.left(i), 1e-6f)
        assertTrue(rig.events.any { it.kind == BlendEventKind.INCOMING_NOT_READY && it.cause == BlendCause.NOT_READY })
    }

    @Suppress("unused")
    private fun peak(x: FloatArray): Float = x.fold(0f) { m, v -> max(m, abs(v)) }
}

// Stops feeding slot `slot`'s deck from `atMs` on the rig's clock (a
// stalled source).
@UnstableApi
private fun MixRig.decksStop(slot: Int, atMs: Double) {
    stalled[slot] = (atMs * 1_000_000).toLong()
}
