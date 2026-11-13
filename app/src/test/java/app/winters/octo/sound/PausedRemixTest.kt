package app.winters.octo.sound

import android.os.Looper
import androidx.media3.common.util.UnstableApi
import app.winters.octo.playback.BlendDisarm
import app.winters.octo.playback.BlendEventKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.math.abs

// A pause after the handover re-mixes while paused from the settled head:
// A alone up to S then B from S with a 20 ms ramp when S was not consumed,
// B alone from its consumed point when it was; A's frames up to S outlive
// A's release, a continuation reset and its slot's reload as the spare, and
// a later skip while paused drops them. A hold comes only once the start is
// acked and is lifted by the operation's release or by "hold done",
// restoring what was before it.
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PausedRemixTest {
    private val f = 48

    // A's frames carry +(frame + 1) / 1e6, B's -(frame + 1) / 1e6.
    private fun aValue(fr: Long): Float = (fr % 400_000 + 1) / 1_000_000f

    private fun bValue(fr: Long): Float = -aValue(fr)

    private fun aFrame(v: Float): Long = Math.round(v * 1_000_000.0) - 1

    private fun bFrame(v: Float): Long = Math.round(-v * 1_000_000.0) - 1

    private class Blend(val rig: MixRig, val a: TestDeck, val b: TestDeck) {
        var ackAt = -1L
        val s = 2_000L * 48
    }

    // A playing, B parked, a 2 s blend at 2 s acked as soon as it is proposed.
    // On "blend end" main finishes the fade when `finish` is set: A stopped,
    // its output released and its continuation reset.
    private fun blend(finish: Boolean = false): Blend {
        val rig = MixRig(tuning = MixTuning(watchdogMs = 2_000, lagFloorMs = 5.0))
        val a = rig.deck(0)
        a.signal = ::aValue
        a.play()
        a.feed()
        val b = rig.deck(1)
        b.signal = ::bValue
        rig.mixer.park(1, true)
        b.play()
        b.feed()
        rig.runFor(200.0)
        val x = Blend(rig, a, b)
        rig.onEvent = { e ->
            if (e.kind == BlendEventKind.PROPOSED && rig.mixer.commitWord.ack(e.generation)) {
                rig.mixer.acked(e.generation)
                x.ackAt = rig.sched.now
            }
            if (e.kind == BlendEventKind.BLEND_END && finish) {
                rig.mixer.outgoingStopped(e.generation)
                a.stalled()
                a.output.release()
                rig.mixer.resetContinuation(0)
            }
        }
        rig.arm(testBlend(1, testPlan(start = 2_000, entry = 0, overlap = 2_000)))
        return x
    }

    private fun TestDeck.stalled() {
        rig.stalled[slot] = rig.sched.now
    }

    private fun runUntil(rig: MixRig, done: () -> Boolean) {
        var n = 0
        while (!done()) {
            rig.runFor(1.0)
            check(++n < 20_000) { "never happened" }
        }
    }

    // The person's pause after the handover: the mixer's pause, the
    // interrupt, then the current deck (B) pauses.
    private fun personPause(x: Blend) {
        x.rig.mixer.pause(hold = false)
        x.rig.mixer.disarm(BlendDisarm.PAUSE, 1)
        x.b.pause()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun resume(x: Blend) {
        x.rig.mixer.wantPlaying(true)
        x.b.play()
    }

    private fun heard(rig: MixRig, m: Long): Float = rig.tap.frames[m] ?: Float.NaN

    // After a pause with S not consumed: the fresh output from h carries A
    // alone up to S, then A out and B in over 20 ms, then B alone from its
    // entry at its own level.
    private fun checkAToSThenB(x: Blend, p: FakeTrack, writtenAtPause: Long) {
        val rig = x.rig
        val s = x.s
        val h = p.head
        assertTrue("S not consumed: h $h", h < s)
        assertEquals("nothing written into the paused output", writtenAtPause, p.written)
        val fresh = rig.builder.built.last()
        assertTrue(fresh !== p)
        assertTrue("the fresh output was written: ${rig.builder.built.map { "${it.id}/${it.written}" }}", rig.tap.byOutput.containsKey(fresh.id))
        assertEquals("the fresh output starts at h", h, rig.tap.byOutput.getValue(fresh.id).first())
        for (m in h + 960 until s) assertEquals("A alone at mix frame $m", m, aFrame(heard(rig, m)))
        for (j in 0 until 960) {
            val m = s + j
            val want = aValue(m) * (960 - j) / 960.0 + bValue(j.toLong()) * j / 960.0
            assertEquals("the 20 ms at S, frame $m", want, heard(rig, m).toDouble(), 2e-4)
        }
        for (m in s + 960 until s + 48_000) assertEquals("B alone at mix frame $m", m - s, bFrame(heard(rig, m)))
    }

    @Test
    fun aPauseAfterTheAckWithSNotConsumedPlaysAToSThenBFromS() {
        val x = blend()
        runUntil(x.rig) { x.ackAt >= 0 }
        x.rig.runFor(100.0)
        val p = x.rig.track(0)
        personPause(x)
        val writtenAtPause = p.written
        x.rig.runFor(1_000.0)
        assertTrue(x.rig.events.any { it.kind == BlendEventKind.BLEND_END })
        resume(x)
        x.rig.runFor(2_000.0)
        checkAToSThenB(x, p, writtenAtPause)
    }

    @Test
    fun aPauseAfterSWasConsumedPlaysBOnAloneFromItsConsumedPoint() {
        val x = blend()
        runUntil(x.rig) { x.rig.track(0).head > x.s + 4_800 }
        val p = x.rig.track(0)
        personPause(x)
        val writtenAtPause = p.written
        x.rig.runFor(1_000.0)
        resume(x)
        x.rig.runFor(2_000.0)
        val h = p.head
        assertEquals(writtenAtPause, p.written)
        val fresh = x.rig.builder.built.last()
        assertEquals(h, x.rig.tap.byOutput.getValue(fresh.id).first())
        // Each of B's frames heard once: P played them up to h, the fresh
        // output from h on; A is gone; B at its own level once its 250 ms
        // ramp from its shaped level is done.
        for (k in 0 until fresh.written) {
            val v = fresh.left(k)
            if (v != 0f) assertTrue("fresh frame $k carries no A: $v", v < 0f)
        }
        for (m in h + 12_000 + 960 until h + 48_000) assertEquals("B at mix frame $m", m - x.s, bFrame(heard(x.rig, m)))
        val ramp = (h until h + 12_000).map { abs(heard(x.rig, it)) }
        for (i in 1 until ramp.size) assertTrue("B rises with no step at ${h + i}", ramp[i] >= ramp[i - 1] - 1e-4f)
    }

    @Test
    fun aKeptRangeOutlivesAsReleaseAndItsContinuationReset() {
        val x = blend(finish = true)
        runUntil(x.rig) { x.ackAt >= 0 }
        x.rig.runFor(100.0)
        val p = x.rig.track(0)
        personPause(x)
        val writtenAtPause = p.written
        // Ten seconds paused: main finished the fade meanwhile.
        x.rig.runFor(10_000.0)
        assertTrue("A was released", x.a.output.released)
        resume(x)
        x.rig.runFor(2_000.0)
        checkAToSThenB(x, p, writtenAtPause)
    }

    @Test
    fun aKeptRangeOutlivesASlotReloadedAsTheSpare() {
        val x = blend(finish = true)
        runUntil(x.rig) { x.ackAt >= 0 }
        x.rig.runFor(100.0)
        val p = x.rig.track(0)
        personPause(x)
        val writtenAtPause = p.written
        x.rig.runFor(1_000.0)
        // A's player loaded with the next spare, parked, its frames in.
        x.rig.mixer.park(0, true)
        x.rig.mixer.announce(0)
        val spare = x.rig.deck(0)
        spare.entry = "e9"
        spare.signal = { 0.7f }
        spare.play()
        spare.write(24_000)
        x.rig.runFor(1_000.0)
        resume(x)
        x.rig.runFor(2_000.0)
        checkAToSThenB(x, p, writtenAtPause)
        for ((m, v) in x.rig.tap.frames) assertTrue("the spare unheard at $m", abs(v - 0.7f) > 1e-3f)
    }

    @Test
    fun aSkipWhilePausedDropsTheKeptRangeAndPlaysTheNewSong() {
        // With main's fade finished (A's output released) and before it (A's
        // output still alive).
        for (finish in listOf(true, false)) {
            val x = blend(finish = finish)
            runUntil(x.rig) { x.ackAt >= 0 }
            x.rig.runFor(100.0)
            val p = x.rig.track(0)
            personPause(x)
            // A's ring is full while paused: its source gives nothing more.
            x.a.stalled()
            x.rig.runFor(1_000.0)
            // Next while paused, on B: a hold, the announced release, the new
            // song buffered into a new output, and the hold done.
            val rig = x.rig
            rig.mixer.holdIfHandedOver(1)
            rig.mixer.announce(1)
            x.b.restartAt(0.0)
            x.b.entry = "next"
            x.b.signal = { 0.25f }
            x.b.feed()
            rig.mixer.holdDone()
            rig.runFor(500.0)
            resume(x)
            rig.runFor(1_500.0)
            val h = p.head
            val fresh = rig.builder.built.last()
            assertTrue("finish $finish: the fresh output was written", rig.tap.byOutput.containsKey(fresh.id))
            assertEquals(h, rig.tap.byOutput.getValue(fresh.id).first())
            // From h only the new song: never A's old tail, never B's old frames.
            for (m in h until h + 24_000) {
                val v = heard(rig, m)
                assertTrue("finish $finish, mix frame $m: $v", v >= 0f && v <= 0.25f + 1e-6f)
            }
            for (m in h + 960 until h + 24_000) assertEquals("finish $finish: the new song at $m", 0.25f, heard(rig, m), 1e-6f)
        }
    }

    @Test
    fun aSeekWhilePausedPlaysTheNewPositionFirst() {
        val rig = MixRig()
        val a = rig.deck(0)
        a.signal = ::aValue
        a.play()
        a.feed()
        rig.runFor(1_000.0)
        val p = rig.track(0)
        rig.mixer.pause(hold = false)
        a.pause()
        shadowOf(Looper.getMainLooper()).idle()
        rig.runFor(500.0)
        // The seek while paused: announced, the old output released and the
        // new position's frames in a new one.
        rig.mixer.announce(0)
        a.restartAt(5_000.0)
        a.feed()
        rig.runFor(300.0)
        rig.mixer.wantPlaying(true)
        a.play()
        rig.runFor(1_000.0)
        val h = p.head
        val fresh = rig.builder.built.last()
        assertEquals(h, rig.tap.byOutput.getValue(fresh.id).first())
        // The first frame heard after the pause is the new position's.
        for (j in 960 until 24_000) assertEquals("frame ${h + j}", 5_000L * f + j, aFrame(heard(rig, h + j)))
    }

    @Test
    fun anEditAfterTheAckWithTheWrittenPointBeforeSPlaysAToSThenBWithNoGap() {
        // Main's lag at its cap: the start is proposed well over a buffer
        // ahead, so an edit 10 ms after the ack finds the written point before S.
        val rig = MixRig(tuning = MixTuning(watchdogMs = 2_000, lagFloorMs = 500.0))
        val a = rig.deck(0)
        a.signal = ::aValue
        a.play()
        a.feed()
        val b = rig.deck(1)
        b.signal = ::bValue
        rig.mixer.park(1, true)
        b.play()
        b.feed()
        rig.runFor(200.0)
        var writtenAtEdit = -1L
        rig.onEvent = { e ->
            if (e.kind == BlendEventKind.PROPOSED && rig.mixer.commitWord.ack(e.generation)) {
                rig.mixer.acked(e.generation)
                rig.sched.at(rig.sched.now + 10_000_000) {
                    writtenAtEdit = rig.mixer.written
                    rig.mixer.disarm(BlendDisarm.EDIT, e.generation)
                }
            }
        }
        rig.arm(testBlend(1, testPlan(start = 2_000, entry = 0, overlap = 2_000)))
        rig.runFor(3_500.0)
        val s = 2_000L * f
        assertTrue("the written point before S: $writtenAtEdit", writtenAtEdit in 0 until s)
        assertEquals(1, rig.builder.built.size)
        // A alone at its own level up to S; at S A out and B in over 20 ms,
        // B's shaping disarmed; then B alone. No silent gap.
        val bad = (0 until s).firstOrNull { aFrame(heard(rig, it)) != it }
        if (bad != null) {
            val around = (bad - 6..bad + 6).map { "$it:${aFrame(heard(rig, it))}" }
            val t = rig.track(0)
            throw AssertionError("A alone breaks at $bad (written at the edit $writtenAtEdit): $around; notes ${rig.tap.notes}; track written ${t.written}, writes at ${t.writes.take(3)}")
        }
        for (j in 0 until 960) {
            val m = s + j
            val want = aValue(m) * (960 - j) / 960.0 + bValue(j.toLong()) * j / 960.0
            assertEquals("the 20 ms at S, frame $m", want, heard(rig, m).toDouble(), 2e-4)
        }
        for (m in s + 960 until s + 48_000) assertEquals("B alone at mix frame $m", m - s, bFrame(heard(rig, m)))
    }

    // The hold

    @Test
    fun aHoldComesOnlyOnceTheStartIsAcked() {
        val rig = MixRig(tuning = MixTuning(watchdogMs = 2_000, lagFloorMs = 5.0))
        val a = rig.deck(0)
        a.signal = ::aValue
        a.play()
        a.feed()
        val b = rig.deck(1)
        b.signal = ::bValue
        rig.mixer.park(1, true)
        b.play()
        b.feed()
        rig.runFor(200.0)
        var held = 0
        rig.onEvent = { e ->
            if (e.kind == BlendEventKind.PROPOSED) {
                // Proposed but not yet acked: no hold.
                rig.mixer.holdIfHandedOver(e.generation)
                held++
                rig.sched.at(rig.sched.now + 30_000_000) {
                    if (rig.mixer.commitWord.cancel(e.generation)) rig.mixer.disarm(BlendDisarm.EDIT, e.generation)
                }
            }
        }
        rig.arm(testBlend(1, testPlan(start = 2_000, entry = 0, overlap = 2_000)))
        // Before the proposal (INITIAL): no hold either.
        rig.sched.at(1_000_000_000) { rig.mixer.holdIfHandedOver(1) }
        rig.runFor(4_000.0)
        assertEquals(1, held)
        assertEquals(1, rig.builder.built.size)
        assertEquals(0, rig.track(0).pauses)
        val t = rig.track(0)
        for (i in 0 until t.written) assertEquals("A on with no gap at $i", i, aFrame(t.left(i)))
    }

    // After the blend has ended on an acked generation, B alone.
    private fun afterBlend(): Blend {
        val x = blend(finish = true)
        runUntil(x.rig) { x.rig.events.any { it.kind == BlendEventKind.BLEND_END } }
        x.rig.runFor(500.0)
        return x
    }

    @Test
    fun aHoldLiftedByHoldDoneWithNoReleaseGoesOnFromTheSettledHead() {
        val x = afterBlend()
        val rig = x.rig
        val p = rig.lastTrack
        val tracks = rig.builder.built.size
        rig.mixer.holdIfHandedOver(1)
        rig.runFor(15.0)
        assertEquals("paused within a block", 1, p.pauses)
        val writtenAtHold = p.written
        rig.runFor(300.0)
        assertEquals(writtenAtHold, p.written)
        rig.mixer.holdDone()
        rig.runFor(1_000.0)
        assertEquals("one fresh output", tracks + 1, rig.builder.built.size)
        val fresh = rig.lastTrack
        assertEquals(1, fresh.plays)
        assertEquals(1, p.plays)
        val h = p.head + rig.tap.byOutput.getValue(p.id).first()
        assertEquals(h, rig.tap.byOutput.getValue(fresh.id).first())
        for (m in h + 960 until h + 24_000) assertEquals("B on at $m", m - x.s, bFrame(heard(rig, m)))
    }

    @Test
    fun aHoldIsLiftedByTheOperationsOwnRelease() {
        val x = afterBlend()
        val rig = x.rig
        val p = rig.lastTrack
        rig.mixer.holdIfHandedOver(1)
        rig.runFor(100.0)
        // A real seek on B: announced, released, the new position playing.
        rig.mixer.announce(1)
        x.b.restartAt(20_000.0)
        x.b.play()
        x.b.feed()
        rig.runFor(1_000.0)
        val fresh = rig.lastTrack
        assertTrue(fresh !== p)
        assertEquals(1, fresh.plays)
        val first = rig.tap.byOutput.getValue(fresh.id).first()
        for (j in 960 until 24_000) assertEquals("the new position at ${first + j}", (20_000L * f + j) % 400_000, bFrame(heard(rig, first + j)))
        // A late "hold done" changes nothing.
        val built = rig.builder.built.size
        rig.mixer.holdDone()
        rig.runFor(500.0)
        assertEquals(built, rig.builder.built.size)
        assertEquals(1, fresh.plays)
        assertEquals(0, fresh.pauses)
    }

    @Test
    fun liftingAHoldWhilePausedLeavesItPaused() {
        val x = afterBlend()
        val rig = x.rig
        rig.mixer.pause(hold = false)
        x.b.pause()
        shadowOf(Looper.getMainLooper()).idle()
        rig.runFor(500.0)
        rig.mixer.holdIfHandedOver(1)
        rig.runFor(100.0)
        rig.mixer.holdDone()
        rig.runFor(1_000.0)
        for (t in rig.builder.built) assertTrue("no track playing", !t.playing)
        val plays = rig.builder.built.sumOf { it.plays }
        // The person's play brings it back, once.
        x.rig.mixer.wantPlaying(true)
        x.b.play()
        rig.runFor(500.0)
        assertEquals(plays + 1, rig.builder.built.sumOf { it.plays })
    }

    @Test
    fun aSeekMidBlendAfterTheAckHoldsAndPlaysOnlyTheNewPosition() {
        val x = blend()
        val rig = x.rig
        runUntil(rig) { rig.track(0).head > x.s + 24_000 }
        val p = rig.track(0)
        // Main's order: the hold, the interrupt, then the seek on B.
        rig.mixer.holdIfHandedOver(1)
        rig.mixer.disarm(BlendDisarm.EDIT, 1)
        rig.mixer.announce(1)
        x.b.restartAt(30_000.0)
        x.b.signal = { 0.25f }
        x.b.play()
        x.b.feed()
        rig.runFor(1_500.0)
        val h = p.head
        val fresh = rig.lastTrack
        assertTrue(fresh !== p)
        val first = rig.tap.byOutput.getValue(fresh.id).first()
        assertEquals(h, first)
        // No frame of the old mix after h: only the new position.
        for (m in h until h + 24_000) {
            val v = heard(rig, m)
            assertTrue("mix frame $m: $v", v >= 0f && v <= 0.25f + 1e-6f)
        }
        for (m in h + 960 until h + 24_000) assertEquals(0.25f, heard(rig, m), 1e-6f)
        assertFalse(rig.events.any { it.kind == BlendEventKind.INCOMING_NOT_READY })
    }
}
