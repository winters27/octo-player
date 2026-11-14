package app.winters.octo.sound

import androidx.media3.common.util.UnstableApi
import app.winters.octo.playback.BlendCause
import app.winters.octo.playback.BlendEventKind
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// A blend the session ends on its own before the handover is let go, so a
// lone deck's move clears what was mixed for its old place at once.
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RetireTest {
    @Test
    fun aSpeedDropCutBeforeTheHandoverLeavesALoneDeckWhoseSeekClearsAtOnce() {
        val rig = MixRig(tuning = MixTuning(watchdogMs = 2_000, lagFloorMs = 5.0))
        val a = rig.deck(0)
        a.signal = { 0.1f }
        a.play()
        a.feed()
        val b = rig.deck(1)
        b.signal = { -0.3f }
        rig.mixer.park(1, true)
        b.play()
        b.feed()
        rig.runFor(200.0)
        // Committed at its floor with no ACK (main never answers), the songs
        // held in step from the start with a 10% drift gap.
        val first = testBlend(1, testPlan(start = 2_000, entry = 0, overlap = 3_000))
        val arm = first.arm.copy(commitBeforeAck = true, lockMs = 0.0, driftGap = 0.1, bothHeardStartMs = 0.0, bothHeardEndMs = 3_000.0)
        rig.arm(MixBlend(arm, 0, 1, first.plan, first.outgoing, first.incoming))
        rig.runFor(1_600.0)
        // The listener's speed halves: the drift passes its limit at once and
        // the start, not handed over, is undone.
        a.speed = 0.5
        rig.mixer.paceChanged(1, 0.5)
        val told = { rig.events.map { "${it.kind}/${it.cause}" } }
        var waited = 0
        while (rig.events.none { it.kind == BlendEventKind.CANCELLED && it.cause == BlendCause.PACE } && waited++ < 3_000) rig.runFor(1.0)
        assertTrue("cut: ${told()} ${rig.tap.notes}", rig.events.any { it.kind == BlendEventKind.CANCELLED && it.cause == BlendCause.PACE })
        rig.runFor(400.0)
        // A seek on the playing deck: the mixed frames of its old place go at once.
        val before = rig.tap.notes.size
        rig.mixer.announce(0)
        a.restartAt(4_000.0)
        a.feed()
        rig.runFor(20.0)
        assertTrue("cleared: ${rig.tap.notes.drop(before)}", rig.tap.notes.drop(before).any { it.startsWith("cleared") })
    }
}
