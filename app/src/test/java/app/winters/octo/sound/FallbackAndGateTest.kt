package app.winters.octo.sound

import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

// When no fresh output can be built through a pause and at the resume, the
// paused output plays again silent for a fixed span: its track volume 0,
// its held frames played out unheard, silence with no deck frames after
// them, the volume back once its head is past them and well before the
// music, and the music written again from h with the onset ramp, positions
// held at h until it is heard. And the gate's bound hits, classed from
// Octo's own log.
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FallbackAndGateTest {
    private val ms = 1_000_000L

    private fun counting(fr: Long): Float = (fr % 400_000 + 1) / 1_000_000f

    private fun carried(v: Float): Long = Math.round(v * 1_000_000.0) - 1

    // Feeds `d` up to song frame `last`, then ends its stream.
    private fun feedTo(rig: MixRig, d: TestDeck, last: Long) {
        fun step() {
            while (d.nextFrame < last) {
                val n = minOf(960L, last - d.nextFrame).toInt()
                if (d.write(n) < n) break
            }
            if (d.nextFrame >= last) d.end() else rig.sched.at(rig.sched.now + 10 * ms) { step() }
        }
        step()
    }

    @Test
    fun aVolumeChangeWhileTheFallbackPlaysItsHeldFramesWaitsUntilTheyHavePlayed() {
        val rig = MixRig()
        val d = rig.deck(0)
        d.signal = ::counting
        d.play()
        feedTo(rig, d, 48_000L * 6)
        rig.runFor(1_000.0)
        val p = rig.track(0)
        rig.builder.failing = Int.MAX_VALUE
        rig.mixer.pause(hold = false)
        d.pause()
        shadowOf(Looper.getMainLooper()).idle()
        rig.runFor(600.0)
        val heldEnd = p.written
        rig.mixer.wantPlaying(true)
        d.play()
        var waited = 0
        while (p.plays < 2 && waited++ < 1_000) rig.runFor(1.0)
        assertEquals("the paused output played again", 2, p.plays)
        // A duck lifts while its held frames still play at 0.
        rig.mixer.setVolume(0.6f)
        rig.runFor(2_000.0)
        val replay = p.playTimes[1]
        val pastHeld = p.steps.first { it[0] > replay && it[1] >= heldEnd }[0]
        val early = p.volumeCalls.filter { it.first in replay until pastHeld && it.second > 0f }
        assertEquals("up before the held frames had played", emptyList<Pair<Long, Float>>(), early)
        assertEquals("back at the level set", 0.6f, p.volumeCalls.last().second, 0f)
    }

    @Test
    fun aFallbackOutputThatDiesGoesToAFreshOneWithTheMusicFromWhereItStopped() {
        val rig = MixRig()
        val d = rig.deck(0)
        d.signal = ::counting
        d.play()
        feedTo(rig, d, 48_000L * 6)
        rig.runFor(1_000.0)
        val p = rig.track(0)
        rig.builder.failing = Int.MAX_VALUE
        rig.mixer.pause(hold = false)
        d.pause()
        shadowOf(Looper.getMainLooper()).idle()
        rig.runFor(600.0)
        val h = p.head
        rig.mixer.wantPlaying(true)
        d.play()
        var waited = 0
        while (p.plays < 2 && waited++ < 1_000) rig.runFor(1.0)
        assertEquals("the fallback played", 2, p.plays)
        // Builds work again, and the fallback's output dies in its silence.
        rig.builder.failing = 0
        p.dead = true
        rig.runFor(1_500.0)
        val t = rig.builder.built.last()
        assertTrue("a fresh output after the fallback's: ${rig.tap.notes}", t !== p && t.written > 4_800)
        // The music from h, nothing lost (past the onset ramp).
        for (j in 960 until 2_000) assertEquals("frame $j", h + j, carried(t.left(j.toLong())))
    }

    @Test
    fun withEveryBuildFailingTheResumePlaysThePausedOutputSilentForItsSpanAndLosesNothing() {
        val rig = MixRig()
        val d = rig.deck(0)
        d.signal = ::counting
        d.play()
        val songFrames = 48_000L * 6
        feedTo(rig, d, songFrames)
        rig.runFor(1_000.0)
        val p = rig.track(0)
        rig.builder.failing = Int.MAX_VALUE
        rig.mixer.pause(hold = false)
        d.pause()
        shadowOf(Looper.getMainLooper()).idle()
        rig.runFor(600.0)
        val h = p.head
        val heldEnd = p.written
        assertEquals("no other output was built", 1, rig.builder.built.size)
        // Android 14: a track at volume 0 leaves the public list; the
        // fallback takes no signal from it.
        rig.list.hidden = true
        rig.mixer.wantPlaying(true)
        d.play()
        // Positions never run ahead of what is heard while it plays out.
        var last = d.output.snap.presentedIn
        var music = -1L
        repeat(3_000) {
            rig.runFor(1.0)
            val s = d.output.snap.presentedIn
            assertTrue("positions never fall back", s >= last)
            last = s
            // The onset ramp's first frame is 0: the music starts one before
            // the first frame that is not.
            if (music < 0 && p.written > heldEnd) {
                val musicAt = (heldEnd until p.written).firstOrNull { p.left(it) != 0f } ?: -1L
                if (musicAt >= 0) music = musicAt - 1
            }
            if (music < 0 || p.head < music) assertTrue("held at h: $s against $h", s <= h)
        }
        assertEquals("the paused output played again, the one exception", 2, p.plays)
        val replay = p.playTimes[1]
        // Its volume to 0 before it played, never the player's level.
        val zero = p.volumeCalls.last { it.first <= replay }
        assertEquals(0f, zero.second, 0f)
        // The held frames, h to its written count, played unheard.
        val afterReplay = rig.system.heard.filter { it.track == p.id && it.nanos > replay }
        val firstMix = afterReplay.first().nanos
        for (x in afterReplay) if (x.frame in h until heldEnd) assertEquals("held frame ${x.frame}", 0f, x.left, 0f)
        // Silence after them with no deck frame in it, until the music.
        assertTrue("music written: $music", music >= 0)
        for (k in heldEnd until music) assertEquals("silence at $k", 0f, p.left(k), 0f)
        // The span from its first mix covers the gate's bound and the duck ramp.
        val musicHeard = afterReplay.first { it.frame >= music && it.left != 0f }.nanos
        assertTrue("span ${(musicHeard - firstMix) / 1e6} ms", musicHeard - firstMix >= 800 * ms)
        // Its volume back once its head is past the held frames, at least
        // 100 ms before the music is heard.
        val back = p.volumeCalls.first { it.first > replay && it.second > 0f }
        val pastHeld = p.steps.first { it[0] > replay && it[1] >= heldEnd }[0]
        assertTrue("back ${back.first / 1e6} ms, past the held frames ${pastHeld / 1e6} ms", back.first >= pastHeld)
        assertTrue("back ${(musicHeard - back.first) / 1e6} ms before the music", musicHeard - back.first >= 100 * ms)
        // The music again from h, with the onset ramp: nothing lost.
        for (j in 0 until 960) assertTrue("ramp $j", p.left(music + j) <= counting(h + j) + 1e-7f)
        for (j in 960 until 24_000) assertEquals("frame ${music + j}", h + j, carried(p.left(music + j)))
        // And the song ends at its real length.
        rig.runFor(6_000.0)
        assertEquals(Util.sampleCountToDurationUs(songFrames, 48_000), d.output.positionUs)
    }

    // The bound and its classes

    @Test
    fun aBoundHitWithOctosOwnOldEntryLeavingInTheSameReadIsClassedAsItsOwn() {
        val rig = MixRig()
        // system_server lists a start and takes a pause off 50 ms late, so a
        // seek's old entry leaves in the read the new one arrives in.
        rig.list.arriveLagNanos = 50 * ms
        rig.list.departLagNanos = 50 * ms
        val d = rig.deck(0)
        d.signal = ::counting
        d.play()
        d.feed()
        rig.runFor(1_000.0)
        assertTrue(rig.gateBounds.isEmpty())
        rig.mixer.announce(0)
        d.restartAt(10_000.0)
        d.play()
        d.feed()
        rig.runFor(2_000.0)
        assertEquals(1, rig.gateBounds.size)
        assertEquals(GateBound.OWN_ENTRY, rig.gateBounds[0].cause)
        // Music waited the bound and came with the onset ramp.
        val fresh = rig.lastTrack
        assertTrue(fresh.writes.first() - fresh.playTimes[0] >= 300 * ms)
    }

    @Test
    fun aListingThatComesAfterTheBoundIsALateListing() {
        val rig = MixRig()
        rig.list.arriveLagNanos = 400 * ms
        val d = rig.deck(0)
        d.signal = ::counting
        d.play()
        d.feed()
        rig.runFor(2_000.0)
        assertEquals(1, rig.gateBounds.size)
        assertEquals(GateBound.LATE_LISTING, rig.gateBounds[0].cause)
    }

    @Test
    fun anotherAppsLoggedArrivalAfterTheBoundIsNeverALateListing() {
        val rig = MixRig()
        // Octo's entry never listed (an app-ops mute); the debug rig's own
        // music player starts 400 ms after the bound and is logged.
        rig.list.hidden = true
        val d = rig.deck(0)
        d.signal = ::counting
        d.play()
        d.feed()
        rig.sched.at(700 * ms) {
            rig.list.others++
            rig.mixer.otherMusicArrived(700 * ms)
        }
        rig.runFor(2_000.0)
        assertEquals(1, rig.gateBounds.size)
        assertEquals(GateBound.OTHER_DEPARTURE, rig.gateBounds[0].cause)
    }

    @Test
    fun theNextGateEndsABoundHitsTraceAndPollsOnSchedule() {
        val rig = MixRig()
        rig.list.hidden = true
        val d = rig.deck(0)
        d.signal = ::counting
        d.play()
        d.feed()
        rig.runFor(500.0)
        // 200 ms after the bound, a seek starts the next gate, now listed.
        rig.list.hidden = false
        rig.mixer.announce(0)
        d.restartAt(10_000.0)
        d.play()
        d.feed()
        rig.runFor(1_500.0)
        assertEquals(1, rig.gateBounds.size)
        val r = rig.gateBounds[0]
        assertTrue("truncated", r.truncated)
        val next = rig.lastTrack
        val nextPlay = next.playTimes[0]
        assertTrue("trace ended by the next play: ${(r.readNanos.last() - nextPlay) / 1e6} ms", r.readNanos.last() <= nextPlay + 2 * ms)
        // The next gate signalled at once and wrote its music the slack later.
        assertTrue("next music ${(next.writes.first() - nextPlay) / 1e6} ms after play", next.writes.first() - nextPlay in 20 * ms..25 * ms)
    }

    @Test
    fun everyBoundHitInARunOfSeeksIsCounted() {
        val rig = MixRig()
        rig.list.hidden = true
        val d = rig.deck(0)
        d.signal = ::counting
        d.play()
        d.feed()
        rig.runFor(800.0)
        repeat(3) { i ->
            rig.mixer.announce(0)
            d.restartAt(10_000.0 * (i + 1))
            d.play()
            d.feed()
            rig.runFor(800.0)
        }
        rig.runFor(1_500.0)
        assertEquals("the first play and three seeks", 4, rig.gateBounds.size)
    }
}
