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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

// A session effect that controls volume (an equalizer app on Octo's
// session) mixes every track of the session at unity under one volume, the
// last one set. With the write cap a blend's undo before the ACK never needs
// two tracks: what is heard is A exactly as with no blend, with no frame of
// B, whichever way the effect smooths.
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionEffectTest {
    private fun heard(rig: MixRig): List<Pair<Long, Float>> = rig.system.heard.map { it.frame to it.left }

    // A a sine, B (when armed) loud; main undoes the start at `undoAtMs`,
    // before and after the proposal.
    private fun run(effect: FakeSystemMixer.Effect, blend: Boolean, undoAtMs: Double, ackNever: Boolean): MixRig {
        val rig = MixRig(tuning = MixTuning(watchdogMs = 2_000, lagFloorMs = 5.0))
        rig.system.effect = effect
        val a = rig.deck(0)
        a.signal = { f -> (0.5 * sin(2 * PI * 440 * f / 48_000)).toFloat() }
        a.play()
        a.feed()
        val b = rig.deck(1)
        b.signal = { 0.9f }
        rig.mixer.park(1, true)
        b.play()
        b.feed()
        rig.runFor(200.0)
        if (blend) {
            rig.arm(testBlend(1, testPlan(start = 2_000, entry = 0, overlap = 2_000)))
            rig.sched.at((undoAtMs * 1_000_000).toLong()) {
                if (rig.mixer.commitWord.cancel(1)) rig.mixer.disarm(BlendDisarm.EDIT, 1)
            }
        }
        rig.runFor(4_000.0)
        return rig
    }

    @Test
    fun anUndoBeforeTheAckIsHeardAsANoBlendRunWithAVolumeEffectOn() {
        for (effect in listOf(FakeSystemMixer.Effect.SMOOTHED, FakeSystemMixer.Effect.STEPPED)) {
            val reference = heard(run(effect, blend = false, undoAtMs = -1.0, ackNever = true))
            // In the run-up, and with the start mixed and proposed but not acked.
            for (undoAt in listOf(1_000.0, 1_650.0)) {
                val rig = run(effect, blend = true, undoAtMs = undoAt, ackNever = true)
                val undone = heard(rig)
                assertEquals("$effect at $undoAt: one track", 1, rig.builder.built.size)
                val n = minOf(reference.size, undone.size)
                assertTrue(n > 100_000)
                for (i in 0 until n) {
                    assertEquals("$effect at $undoAt: heard frame $i", reference[i].first, undone[i].first)
                    assertEquals("$effect at $undoAt: heard frame $i", reference[i].second, undone[i].second, 0f)
                }
                assertTrue("no frame of B", undone.none { abs(it.second) > 0.5f + 1e-6f })
            }
        }
    }

    @Test
    fun theFakesEffectMixesTwoTracksAtUnityUnderTheLastVolumeSet() {
        // Why two tracks of one session cannot be crossfaded by their own
        // volumes: under the effect the sum swells to both at full level.
        val sched = Sched()
        val system = FakeSystemMixer(sched)
        system.effect = FakeSystemMixer.Effect.STEPPED
        val builder = FakeBuilder(system)
        val one = builder.build(24_000) as FakeTrack
        val two = builder.build(24_000) as FakeTrack
        val frames = FloatArray(9_600 * 2) { 0.4f }
        for (t in listOf(one, two)) {
            t.setStartThreshold(1)
            t.write(frames, 0, 9_600)
            t.play()
        }
        // A crossfade's middle by track volumes: 0.25 and 0.75 sum to the
        // music's own level, 0.4, with no effect.
        one.setVolume(0.25f)
        two.setVolume(0.75f)
        sched.runFor(100.0)
        fun lastSum(): Double {
            val cycle = system.heard.groupBy { it.nanos }.values.last { it.size == 2 * system.quantum }
            return cycle.groupBy { it.frame }.values.first().sumOf { it.left.toDouble() }
        }
        // Under the effect both play at the last volume set: a swell...
        assertEquals(0.6, lastSum(), 1e-6)
        // ...or, set the other way round, a dip.
        one.setVolume(0.25f)
        sched.runFor(80.0)
        assertEquals(0.2, lastSum(), 1e-6)
    }
}
