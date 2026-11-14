package app.winters.octo.sound

import android.os.Looper
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

// The one release rule for an output a pause or a restart leaves behind:
// it goes once its replacement is built and its head has settled, at once
// when it is invalidated meanwhile, whatever its timestamps say, so never
// more than two real outputs are alive.
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReleaseRuleTest {
    private val ms = 1_000_000L

    private fun alive(rig: MixRig): Int = rig.builder.built.count { !it.released }

    private fun pause(rig: MixRig, d: TestDeck) {
        rig.mixer.pause(hold = false)
        d.pause()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun resume(rig: MixRig, d: TestDeck) {
        rig.mixer.wantPlaying(true)
        d.play()
    }

    @Test
    fun aPausedOutputGoesOnceItsReplacementIsBuiltAndItsHeadSettledThoughItsTimestampsFroze() {
        val rig = MixRig()
        // Its timestamps stop short of where its head settles.
        rig.builder.nextStampsUpTo = 4_800
        val d = rig.deck(0)
        d.signal = { 0.3f }
        d.play()
        d.feed()
        rig.runFor(1_000.0)
        val p = rig.track(0)
        pause(rig, d)
        val pausedAt = rig.sched.now
        var most = 0
        while (rig.sched.now < pausedAt + 400 * ms) {
            rig.runFor(1.0)
            most = maxOf(most, alive(rig))
        }
        assertTrue("released ${(p.releasedAt - pausedAt) / 1e6} ms after the pause", p.released && p.releasedAt - pausedAt <= 300 * ms)
        assertTrue("at most two alive: $most", most <= 2)
    }

    @Test
    fun aStopAfterAPauseLeavesNoOutputAlive() {
        val rig = MixRig()
        val d = rig.deck(0)
        d.signal = { 0.3f }
        d.play()
        d.feed()
        rig.runFor(1_000.0)
        pause(rig, d)
        rig.runFor(400.0)
        // The player stops, as OctoPlayer does it: nothing continues on the
        // slot, the mixer hears the stop, the move is announced, the deck
        // output goes and nothing comes after it.
        rig.mixer.resetContinuation(0)
        rig.mixer.stop()
        rig.mixer.announce(0)
        d.output.release()
        rig.runFor(1_500.0)
        assertEquals("alive: ${rig.builder.built.filter { !it.released }.map { it.id }}", 0, alive(rig))
    }

    @Test
    fun theQueuesEndLeavesNoOutputAlive() {
        val rig = MixRig()
        val d = rig.deck(0)
        d.signal = { 0.3f }
        d.play()
        d.feed(forMs = 1_000.0)
        rig.runFor(1_100.0)
        // The last song's stream ends, and Media3 stops its renderers there:
        // the deck output pauses, ended, and is never let go.
        d.end()
        rig.runFor(600.0)
        d.pause()
        rig.runFor(1_500.0)
        assertEquals("alive: ${rig.builder.built.filter { !it.released }.map { it.id }}", 0, alive(rig))
    }

    @Test
    fun aStationsPauseKeepsAnOutputForTheResumeAndAStopLetsItGo() {
        val rig = MixRig()
        val d = rig.deck(0)
        d.signal = { 0.3f }
        d.play()
        d.feed()
        rig.runFor(1_000.0)
        pause(rig, d)
        rig.runFor(400.0)
        // A station's pause lets its stream go: announced, nothing continues.
        rig.mixer.resetContinuation(0)
        rig.mixer.announce(0)
        d.output.release()
        rig.runFor(1_500.0)
        assertEquals("kept for the resume: ${rig.builder.built.map { "${it.id} ${it.released}" }}", 1, alive(rig))
        rig.mixer.stop()
        rig.runFor(10.0)
        assertEquals("let go at the stop", 0, alive(rig))
    }

    @Test
    fun anOutputInvalidatedWhileItSettlesGoesAtOnce() {
        val rig = MixRig(routes = hashMapOf("speaker" to RouteMeasure().apply { quantum = 1_920; largestStep = 1_920; intervalNanos = 200 * ms }))
        val d = rig.deck(0)
        d.signal = { 0.3f }
        d.play()
        d.feed()
        rig.runFor(1_000.0)
        val p = rig.track(0)
        pause(rig, d)
        // A long change interval: it is still settling 20 ms in, when its
        // device goes away.
        rig.runFor(20.0)
        assertTrue("still settling", !p.released)
        val unplugged = rig.sched.now
        p.invalidate("usb")
        rig.runFor(10.0)
        assertTrue("released ${(p.releasedAt - unplugged) / 1e6} ms after the unplug", p.released && p.releasedAt - unplugged <= 4 * ms)
    }

    @Test
    fun pausesResumesAndUnplugsNeverLeaveMoreThanTwoOutputsAlive() {
        val rig = MixRig()
        val d = rig.deck(0)
        d.signal = { 0.3f }
        d.play()
        d.feed()
        rig.runFor(800.0)
        var most = 0
        fun run(msLong: Int) = repeat(msLong) {
            rig.runFor(1.0)
            most = maxOf(most, alive(rig))
        }
        for (i in 0 until 10) {
            pause(rig, d)
            run(5)
            // Every other pause, the device goes during the drain.
            if (i % 2 == 0) rig.builder.built.lastOrNull { it.pauses > 0 && !it.released }?.invalidate("usb$i")
            run(200)
            resume(rig, d)
            run(300)
        }
        assertTrue("at most two alive: $most", most <= 2)
        assertTrue("built ${rig.builder.built.size}", rig.builder.built.size >= 10)
        assertTrue("one alive at the end", alive(rig) <= 2)
    }
}
