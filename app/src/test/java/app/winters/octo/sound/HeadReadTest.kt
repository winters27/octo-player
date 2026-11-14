package app.winters.octo.sound

import android.os.Looper
import androidx.media3.common.util.UnstableApi
import app.winters.octo.playback.BlendEventKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

// What the real output's head reads reach: a read of 0 on Android 10 is
// not taken; a jump to the written count at a route change (a restored
// track dropping what it held) is written again, so a start near it is
// never cancelled at the floor; and an announced release of one of two
// mixing decks while paused leaves the mix as it was.
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HeadReadTest {
    private val ms = 1_000_000L

    private fun counting(fr: Long): Float = (fr % 400_000 + 1) / 1_000_000f

    private fun carried(v: Float): Long = Math.round(v * 1_000_000.0) - 1

    @Test
    @Config(sdk = [29])
    fun onAndroid10AHeadReadOf0IsNotTakenAndEveryFrameIsWrittenOnceInOrder() {
        val rig = MixRig(sdk = 29)
        val d = rig.deck(0)
        d.signal = ::counting
        d.play()
        d.feed()
        rig.runFor(1_000.0)
        val t = rig.track(0)
        t.zeroReads = 3
        rig.runFor(1_000.0)
        assertEquals("one output", 1, rig.builder.built.size)
        assertTrue("written ${t.written}", t.written > 48_000)
        for (k in 1 until t.written) assertEquals("frame $k", carried(t.left(k - 1)) + 1, carried(t.left(k)))
    }

    @Test
    fun aHeadJumpAtARouteChangeIsWrittenAgainAndAStartNearItIsNotCancelledAtTheFloor() {
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
        // Main hears the proposal as the route changes (the restored track
        // drops what it held), and acks 25 ms later.
        rig.onEvent = { e ->
            if (e.kind == BlendEventKind.PROPOSED) {
                rig.builder.built.last().invalidate("usb")
                rig.sched.at(rig.sched.now + 25 * ms) { if (rig.mixer.commitWord.ack(e.generation)) rig.mixer.acked(e.generation) }
            }
        }
        rig.arm(testBlend(1, testPlan(start = 2_000, entry = 0, overlap = 1_000)))
        rig.runFor(4_000.0)
        val told = rig.events.map { "${it.kind}/${it.cause}" }
        assertTrue("no start dropped: $told", rig.events.none { it.kind == BlendEventKind.INCOMING_NOT_READY })
        assertTrue("the blend ran: $told", rig.events.any { it.kind == BlendEventKind.BLEND_END })
        assertTrue("written again: ${rig.tap.notes}", rig.tap.notes.any { it.startsWith("head jump") })
    }

    @Test
    fun anAnnouncedReleaseOfOneOfTwoMixingDecksWhilePausedLeavesTheMixAsItWas() {
        val rig = MixRig()
        val a = rig.deck(0)
        a.signal = { 0.5f }
        a.play()
        a.feed()
        val b = rig.deck(1)
        b.signal = ::counting
        b.play()
        b.feed()
        rig.runFor(1_000.0)
        rig.mixer.pause(hold = false)
        a.pause()
        b.pause()
        shadowOf(Looper.getMainLooper()).idle()
        rig.runFor(400.0)
        // The first deck is stopped while paused: its output goes, announced.
        rig.mixer.announce(0)
        a.output.release()
        rig.runFor(200.0)
        rig.mixer.wantPlaying(true)
        b.play()
        rig.runFor(600.0)
        val t = rig.builder.built.last()
        assertTrue("written ${t.written}", t.written > 4_800)
        // Past the onset ramp, what was mixed before the stop still plays:
        // the stopped deck's frames with the other's.
        for (k in 960L until 1_440L) assertTrue("frame $k: ${t.left(k)}", t.left(k) > 0.45f)
    }
}
