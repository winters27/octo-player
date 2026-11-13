package app.winters.octo.sound

import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder

// The real output's life on the mechanism fake: every fresh output passes
// the duck gate, a paused output is never played again, a resume plays a
// fresh one from the settled head with the onset ramp, a dry output is
// replaced, a dead one rebuilt, and builds follow Media3's creation rules.
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RealOutputLifecycleTest {
    // A deck whose every frame carries its own song frame, so a written
    // frame says which one it is.
    private fun counting(rig: MixRig, slot: Int = 0): TestDeck {
        val d = rig.deck(slot)
        d.signal = { f -> (f % 400_000 + 1) / 1_000_000f }
        d.play()
        d.feed()
        return d
    }

    private fun carried(sample: Float): Long = Math.round(sample * 1_000_000.0) - 1

    private fun pauseAll(rig: MixRig, d: TestDeck) {
        rig.mixer.pause(hold = false)
        d.pause()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun resume(rig: MixRig, d: TestDeck) {
        rig.mixer.wantPlaying(true)
        d.play()
    }

    @Test
    fun aResumePlaysAFreshOutputFromTheSettledHeadWithTheOnsetRamp() {
        val rig = MixRig()
        val d = counting(rig)
        rig.runFor(1_000.0)
        val p = rig.track(0)
        pauseAll(rig, d)
        rig.runFor(400.0)
        // Paused at once, and a replacement built while it settled.
        assertEquals(1, p.pauses)
        assertEquals(2, rig.builder.built.size)
        val h = p.head
        assertTrue("the paused output went once its replacement was built", p.released)
        resume(rig, d)
        rig.runFor(800.0)
        // The paused output was never played again.
        assertEquals(1, p.plays)
        val f = rig.track(1)
        assertEquals(1, f.plays)
        assertTrue(f.written > 2_000)
        // Its first frames ramp in from the settled head, then carry the
        // frames from h on exactly: nothing repeated or skipped.
        val ramp = 960
        for (j in 0 until ramp) {
            val want = (h + j + 1) / 1_000_000f * (j.toFloat() / ramp)
            assertEquals("ramp frame $j", want, f.left(j.toLong()), 1e-6f)
        }
        for (j in ramp until f.written.toInt()) assertEquals("frame $j", h + j, carried(f.left(j.toLong())))
    }

    @Test
    fun noMusicIsWrittenBeforeTheSignalPlusTheSlack() {
        val rig = MixRig()
        // Octo's track shows up in the list only 60 ms after its play().
        rig.list.hidden = true
        counting(rig)
        rig.runFor(5.0)
        val t = rig.track(0)
        assertEquals(1, t.plays)
        rig.sched.at(rig.sched.now + 60_000_000) { rig.list.hidden = false }
        rig.runFor(70.0)
        assertTrue("nothing before the signal", t.writes.isEmpty())
        rig.runFor(30.0)
        assertTrue(t.writes.isNotEmpty())
        val signalAt = 65_000_000L
        assertTrue("first music at ${t.writes.first() / 1e6} ms", t.writes.first() >= signalAt + 20_000_000 - 2_000_000)
    }

    @Test
    fun aPauseWhileTheGateWaitsStartsItOverOnAFreshOutput() {
        val rig = MixRig()
        rig.list.hidden = true
        val d = counting(rig)
        rig.runFor(5.0)
        val first = rig.track(0)
        pauseAll(rig, d)
        rig.runFor(50.0)
        assertEquals(1, first.pauses)
        assertTrue(first.writes.isEmpty())
        rig.list.hidden = false
        resume(rig, d)
        rig.runFor(200.0)
        assertFalse("the paused gate track never plays again", first.plays > 1)
        val fresh = rig.lastTrack
        assertTrue(fresh !== first)
        assertTrue(fresh.writes.isNotEmpty())
        // Its first frame is the song's first: nothing was heard before.
        assertEquals(0L, carried(fresh.left(0)))
    }

    @Test
    fun aSignalThatNeverComesEndsAtTheBoundWithTheOnsetRampAndIsClassified() {
        val rig = MixRig()
        rig.list.hidden = true
        counting(rig)
        rig.runFor(250.0)
        val t = rig.track(0)
        assertTrue(t.writes.isEmpty())
        rig.runFor(100.0)
        assertTrue(t.writes.isNotEmpty())
        assertTrue(t.writes.first() >= 300_000_000)
        assertEquals(0f, t.left(0), 0f)
        rig.runFor(1_200.0)
        assertEquals(1, rig.gateBounds.size)
        assertEquals(GateBound.OTHER_DEPARTURE, rig.gateBounds[0].cause)
    }

    @Test
    fun anOutputLeftDryIsPausedAndTheNextFramesGoToAFreshOne() {
        val rig = MixRig()
        val d = rig.deck(0)
        d.signal = { 0.25f }
        d.play()
        // Half a second of song, then nothing.
        d.write(24_000)
        rig.runFor(1_500.0)
        val t = rig.track(0)
        assertTrue("written ${t.written}", t.written >= 24_000)
        assertEquals("paused once dry", 1, t.pauses)
        d.feed()
        rig.runFor(500.0)
        val fresh = rig.lastTrack
        assertTrue(fresh !== t)
        assertTrue(fresh.writes.isNotEmpty())
        assertEquals(1, t.plays)
    }

    @Test
    fun aDeadOutputIsRebuiltAndMixedAgainFromItsConsumedPoint() {
        val rig = MixRig()
        counting(rig)
        rig.runFor(1_000.0)
        val dead = rig.track(0)
        dead.dead = true
        val consumed = dead.head
        rig.runFor(600.0)
        val f = rig.lastTrack
        assertTrue(f !== dead)
        assertTrue(f.written > 2_000)
        val ramp = 960
        for (j in ramp until f.written.toInt()) assertEquals("frame $j", consumed + j, carried(f.left(j.toLong())))
        assertTrue(f.left(0) == 0f)
    }

    @Test
    fun aHeadStuckForASecondIsRebuiltAndAStuckRebuildFailsTheDecks() {
        val rig = MixRig()
        val d = counting(rig)
        rig.runFor(1_000.0)
        val stuck = rig.track(0)
        // The head stops while the output plays with data.
        stuck.stuck = true
        val stoppedAt = rig.sched.now
        val consumed = stuck.head
        // Its last step was seen at most one fill wake (125 ms) before.
        rig.runFor(850.0)
        assertEquals("not before a second", 1, rig.builder.built.size)
        rig.runFor(450.0)
        val f = rig.lastTrack
        assertTrue(f !== stuck)
        assertTrue(stuck.released)
        assertTrue("rebuilt ${(f.builtAt - stoppedAt) / 1e6} ms after", f.builtAt - stoppedAt in 875_000_000L..1_200_000_000L)
        // Mixed again from its consumed point, with the onset ramp.
        for (j in 960 until 4_800) assertEquals("frame $j", consumed + j, carried(f.left(j.toLong())))
        assertEquals(0, d.output.failure)

        // A rebuilt output stuck from its start: the decks fail through
        // their writes, as 1.12.0's sink would.
        val rig2 = MixRig()
        val e = counting(rig2)
        rig2.runFor(1_000.0)
        rig2.builder.nextStuck = true
        rig2.track(0).stuck = true
        rig2.runFor(1_200.0)
        assertEquals(2, rig2.builder.built.size)
        assertEquals(0, e.output.failure)
        rig2.stalled[0] = rig2.sched.now
        rig2.runFor(1_300.0)
        assertTrue("the deck failed", e.output.failure != 0)
    }

    @Test
    fun aFailedBuildWaitsOutAPendingReleaseThenRetriesAndOnlyThenFails() {
        val rig = MixRig()
        rig.builder.failing = 1
        rig.builder.pendingReleaseUntil = 400_000_000
        counting(rig)
        rig.runFor(300.0)
        assertTrue("no output while a release is pending", rig.builder.built.isEmpty())
        rig.runFor(400.0)
        assertEquals(1, rig.builder.built.size)

        // Builds that keep failing for 200 ms with no release pending fail
        // the playing deck through its write.
        val rig2 = MixRig()
        rig2.builder.failing = 1_000
        val d = rig2.deck(0)
        d.play()
        d.write(4_800)
        rig2.runFor(500.0)
        assertTrue(rig2.builder.built.isEmpty())
        val buffer = ByteBuffer.allocateDirect(8 * 100).order(ByteOrder.nativeOrder())
        assertThrows(AudioOutput.WriteException::class.java) { d.output.write(buffer, 1, 0) }
    }

    @Test
    fun aPlayAndPauseQueuedBackToBackLeaveTheDeckPlaying() {
        val rig = MixRig()
        val d = counting(rig)
        rig.runFor(500.0)
        // A headset double tap: pause then play in one turn of the deck's
        // thread; the posted pause is not the latest call, so it does nothing.
        d.pause()
        d.play()
        shadowOf(Looper.getMainLooper()).idle()
        rig.runFor(300.0)
        assertEquals(0, rig.track(0).pauses)
        assertTrue(d.output.playing)
    }
}
