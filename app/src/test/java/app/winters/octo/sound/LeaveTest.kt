package app.winters.octo.sound

import androidx.media3.common.util.UnstableApi
import app.winters.octo.playback.BlendDisarm
import app.winters.octo.playback.BlendEventKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.min

// A deck's leave: one told to leave with nothing left to mix stops at once
// and never carries over into the slot's next part, and a leaving deck
// whose song ends under the ramp has nothing of its next song mixed there.
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LeaveTest {
    private val start = 2_000L * 48

    // Slot 0 plays its song's frames up to `upTo`, then the frames of `next`
    // (if any) at `nextLevel`, as a playback thread fills its sink.
    private fun outgoing(rig: MixRig, level: Float, upTo: Long, next: String? = null, nextLevel: Float = 0f): TestDeck {
        val a = rig.deck(0)
        a.signal = { if (a.entry == "e0") level else nextLevel }
        a.play()
        fun step() {
            while (true) {
                val n = if (a.entry == "e0") min(1_024L, upTo - a.nextFrame).toInt() else 1_024
                if (n <= 0) {
                    if (next == null) break
                    a.nextSong(next)
                    continue
                }
                if (a.write(n) < n) break
            }
            rig.sched.at(rig.sched.now + 10_000_000) { step() }
        }
        step()
        return a
    }

    private fun spare(rig: MixRig, slot: Int, level: Float): TestDeck {
        val b = rig.deck(slot)
        b.signal = { level }
        rig.mixer.park(slot, true)
        b.play()
        b.feed()
        return b
    }

    // Main acks the start and makes an edit 10 ms later, with the written
    // point still before S, so the old song leaves at the start by the cut.
    private fun editAtTheHandover(rig: MixRig) {
        rig.onEvent = { e ->
            if (e.kind == BlendEventKind.PROPOSED && rig.mixer.commitWord.ack(e.generation)) {
                rig.mixer.acked(e.generation)
                rig.sched.at(rig.sched.now + 10_000_000) { rig.mixer.disarm(BlendDisarm.EDIT, e.generation) }
            }
        }
    }

    @Test
    fun aLeaveOnADeckWithNothingLeftToMixNeverRampsTheSlotsNextStartOut() {
        val rig = MixRig(tuning = MixTuning(watchdogMs = 2_000, lagFloorMs = 5.0))
        val a = rig.deck(0)
        a.signal = { 0.1f }
        a.play()
        a.feed()
        // The incoming song's source gives 200 ms, then nothing.
        val b = rig.deck(1)
        b.signal = { -0.3f }
        rig.mixer.park(1, true)
        b.play()
        b.write(9_600)
        rig.runFor(200.0)
        // A start committed at the floor with no ACK: B plays its 200 ms and
        // runs dry, then main's edit undoes the start, fading out a B that
        // has nothing left to mix.
        val first = testBlend(1, testPlan(start = 2_000, entry = 0, overlap = 1_000))
        rig.arm(MixBlend(first.arm.copy(commitBeforeAck = true), 0, 1, first.plan, first.outgoing, first.incoming))
        rig.runFor(2_400.0)
        assertTrue(rig.mixer.commitWord.cancel(1))
        rig.mixer.disarm(BlendDisarm.EDIT, 1)
        rig.runFor(600.0)
        // Slot 1 loads the next song as the spare: its own level, 0.5.
        rig.mixer.announce(1)
        b.restartAt(0.0)
        b.entry = "e2"
        b.signal = { 0.5f }
        rig.mixer.park(1, true)
        b.play()
        b.feed()
        rig.runFor(200.0)
        // Slot 0 is at 3.4 s: the next blend starts at its 4.5 s.
        rig.ackProposals()
        rig.arm(testBlend(2, testPlan(start = 4_500, entry = 0, overlap = 1_000)))
        rig.runFor(3_000.0)
        assertTrue("${rig.events.map { "${it.kind}/${it.generation}" }}", rig.events.any { it.kind == BlendEventKind.BLEND_END && it.generation == 2L })
        // After its blend the new song plays alone at its own level.
        val from = 6_000L * 48
        val off = (from until from + 4_800).filter { m -> rig.tap.frames[m]?.let { kotlin.math.abs(it - 0.5f) > 1e-4f } ?: true }
        assertEquals("not the new song at its level: ${off.take(3).map { it to rig.tap.frames[it] }}, ${rig.tap.notes}", 0, off.size)
    }

    @Test
    fun aLeavingDecksSongThatEndsUnderTheRampHasNothingOfItsNextSongMixedThere() {
        // Main's lag at its cap: the start is proposed well over a buffer
        // ahead.
        val rig = MixRig(tuning = MixTuning(watchdogMs = 2_000, lagFloorMs = 500.0))
        // The old song ends 5 ms after the start and reads on into its next
        // song, at 0.7; the new song is -0.3.
        val end = start + 240
        outgoing(rig, 0.2f, upTo = end, next = "e1", nextLevel = 0.7f)
        spare(rig, 1, -0.3f)
        rig.runFor(200.0)
        editAtTheHandover(rig)
        rig.arm(testBlend(1, testPlan(start = 2_000, entry = 0, overlap = 1_000), endMs = 2_005.0))
        rig.runFor(1_500.0)
        assertTrue("cut at the start: ${rig.tap.notes}", rig.tap.notes.any { it.startsWith("leave 0 over 960@$start") })
        // The old song leaves over 20 ms from the start; from its end on, only
        // the new song (at or under zero) is heard.
        for (m in end until start + 960 + 480) {
            val v = rig.tap.frames[m] ?: Float.NaN
            assertTrue("mix $m: $v, ${rig.tap.notes}", v <= 1e-6f)
        }
        assertTrue((end until start + 1_440).any { (rig.tap.frames[it] ?: 0f) < 0f })
    }
}
