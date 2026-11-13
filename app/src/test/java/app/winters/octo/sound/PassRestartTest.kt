package app.winters.octo.sound

import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

// A player's own restart of its stream: through Sonic it joins the old
// ring's frames over 5 ms, aligned by their cross-correlation, so the
// heard sound runs on unbroken and the position stays within the
// alignment of the heard frame; in plain playback, removing a later pass
// already written takes the restart: the old output pauses and settles,
// a fresh one is built meanwhile and plays from the settled head through
// the gate, and the old one goes no later than the fresh one starts.
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PassRestartTest {
    private val f = 48

    // One render of the song after Sonic, by its output frame.
    private fun noise(seed: Int): (Long) -> Float {
        val r = Random(seed)
        val n = FloatArray(800_000) { r.nextFloat() * 2 - 1 }
        return { k -> n[(k % n.size).toInt()] }
    }

    private val chord: (Long) -> Float = { k ->
        val t = k / 48_000.0
        ((sin(2 * PI * 220.0 * t) + sin(2 * PI * 277.18 * t) + sin(2 * PI * 329.63 * t)) / 3.2).toFloat()
    }

    @Test
    fun aRestartThroughSonicJoinsTheTwoRendersAlignedAndTheSoundRunsOn() {
        val speed = 1.25
        val per = speed * 1_000.0 / 48_000
        val coin = noise(9)
        for ((name, content) in listOf("noise" to noise(5), "chord" to chord, "full scale" to { k: Long -> if (coin(k) > 0) 1f else -1f })) {
            for (offset in listOf(96L, -144L)) {
                val rig = MixRig()
                val d = rig.deck(0)
                d.speed = speed
                d.signal = content
                d.play()
                d.feed()
                rig.runFor(2_000.0)
                val old = d.output
                // Media3 restarts the stream from an earlier song time; the
                // second render of the same music sits `offset` frames off
                // the first, as two renders through Sonic can.
                val k1 = 48_000L
                val fromMs = k1 * per
                d.restartAt(fromMs)
                val firstIndex = d.nextFrame
                d.signal = { idx -> content(idx - firstIndex + k1 + offset) }
                d.play()
                d.feed()
                rig.runFor(2_000.0)
                assertTrue(old.released)
                // One real output throughout: the join opens no gap.
                assertEquals("$name $offset: outputs ${rig.tap.notes}", 1, rig.builder.built.size)
                val t = rig.track(0)
                // The heard sound is the music itself throughout, frame for
                // frame: no step, no dip, no peak and no repeat at the join.
                for (m in 0 until t.written) assertEquals("$name $offset: mix frame $m", content(m), t.left(m), 1e-6f)
                // The new output's position, in song time, stays within the
                // alignment of the song time heard.
                val stamp = RealTimestamp()
                assertTrue(t.timestamp(stamp))
                val heardFrame = stamp.frame + (rig.sched.now - stamp.nanos) * 48_000 / 1_000_000_000
                val s = d.output.snap
                val heardAtSnap = heardFrame - (rig.sched.now - s.nanos) * 48_000 / 1_000_000_000
                val positionMs = fromMs + s.presentedIn * per
                val o = d.output
                val join = rig.tap.notes.firstOrNull { it.startsWith("continued@") }?.substringAfter('@')?.toLong() ?: -1L
                assertEquals(
                    "$name $offset: position (presented ${s.presentedIn}, heard mix frame $heardAtSnap, at ${s.nanos / 1e6} ms of ${rig.sched.now / 1e6}, " +
                        "join $join, ring at join ${o.ringAt(join)}/${o.ringAt(join + 1)}/${o.ringAt(join + 480)}, at heard ${o.ringAt(heardAtSnap)}, last run end ${o.lastRunEnd()}, written ${o.ring.written})",
                    heardAtSnap * per, positionMs, abs(offset) * per + 2 * per + 1e-6,
                )
            }
        }
    }

    // A deck playing e0 (2 s, frames +(k + 1) / 1e6) and reading on into
    // x (0.9); after `restartAt` its player restarts e0 from `fromMs` with y
    // (-(k + 1) / 1e6) after it.
    private class Songs(val rig: MixRig, val d: TestDeck) {
        var restarted = false
        var running = true

        fun feed() {
            fun step() {
                if (!running) return
                while (true) {
                    val inFirst = d.entry == "e0"
                    val n = if (inFirst) minOf(960L, 96_000 - d.nextFrame).toInt() else 960
                    if (n <= 0 || d.write(n) < n) break
                    if (inFirst && d.nextFrame >= 96_000) {
                        val next = if (restarted) "y" else "x"
                        d.nextSong(next)
                        d.signal = if (next == "x") { _ -> 0.9f } else { k -> -((k + 1) / 1_000_000f) }
                    }
                }
                rig.sched.at(rig.sched.now + 10_000_000) { step() }
            }
            step()
        }
    }

    private fun songs(): Songs {
        val rig = MixRig()
        val d = rig.deck(0)
        d.entry = "e0"
        d.songEndMs = 2_000.0
        d.signal = { k -> (k + 1) / 1_000_000f }
        d.play()
        val s = Songs(rig, d)
        s.feed()
        rig.runFor(1.0)
        return s
    }

    // The edit removing x: the player's own restart of e0 from its presented
    // position, with y next now.
    private fun restart(s: Songs) {
        val stamp = RealTimestamp()
        s.rig.lastTrack.timestamp(stamp)
        val fromMs = stamp.frame / 48.0
        s.restarted = true
        s.d.restartAt(fromMs)
        s.d.entry = "e0"
        s.d.songEndMs = 2_000.0
        s.d.signal = { k -> (k + 1) / 1_000_000f }
        s.d.play()
    }

    private fun runUntil(rig: MixRig, done: () -> Boolean) {
        var n = 0
        while (!done()) {
            rig.runFor(1.0)
            check(++n < 20_000)
        }
    }

    private fun checkRestart(s: Songs, p: FakeTrack, restartAt: Long, writtenAtRestart: Long, built: Int) {
        val rig = s.rig
        assertEquals("one fresh output: ${rig.tap.notes}", built + 1, rig.builder.built.size)
        val fresh = rig.lastTrack
        // The old output paused as soon as the restart's first frames came
        // (its tail plays on until then), and took nothing more.
        assertEquals(1, p.pauses)
        assertTrue("paused ${(p.pauseTimes[0] - restartAt) / 1e6} ms after", p.pauseTimes[0] - restartAt < 15_000_000)
        assertTrue(p.writtenAtPauses[0] >= writtenAtRestart)
        assertEquals("nothing written after the pause", p.writtenAtPauses[0], p.written)
        // Built at once, in parallel with the settle.
        val settledAt = p.steps.last()[0]
        assertTrue("built ${fresh.builtAt / 1e6} ms, settled ${settledAt / 1e6} ms", fresh.builtAt <= p.pauseTimes[0] + 1_000_000)
        // From the settled head: e0 on with nothing repeated or skipped,
        // then y; x never heard.
        val h = p.head
        assertTrue("the fresh output was written: ${rig.builder.built.map { "${it.id}/${it.written}" }}", rig.tap.byOutput.containsKey(fresh.id))
        assertEquals(h, rig.tap.byOutput.getValue(fresh.id).first())
        for (m in h + 960 until 96_000) assertEquals("e0 at $m", (m + 1) / 1_000_000f, rig.tap.frames.getValue(m), 1e-7f)
        for (m in 96_000L until 96_000 + 24_000) assertEquals("y at $m (h $h, notes ${rig.tap.notes})", -((m - 96_000 + 1) / 1_000_000f), rig.tap.frames.getValue(m), 1e-7f)
        assertTrue("x never heard", rig.system.heard.none { abs(it.left - 0.9f) < 1e-3f })
        // The gap: the old output's last cycle to the fresh one's first, no
        // longer than 1.12.0's restart.
        val lastOld = rig.system.heard.last { it.track == p.id && it.left != 0f }.nanos
        val firstNew = rig.system.heard.first { it.track == fresh.id }.nanos
        assertTrue("gap ${(firstNew - lastOld) / 1e6} ms", firstNew - lastOld <= 150_000_000)
        // The old output goes no later than the fresh one starts, and only
        // after it settled.
        assertTrue("released ${p.releasedAt / 1e6} ms, fresh plays ${fresh.playTimes[0] / 1e6} ms", p.releasedAt in 0..fresh.playTimes[0])
    }

    @Test
    fun removingAWrittenLaterPassRestartsFromTheSettledHead() {
        val s = songs()
        val rig = s.rig
        runUntil(rig) { rig.track(0).head > 79_200 }
        val p = rig.track(0)
        assertTrue("x's frames are written: ${p.written}, the mixer's written point ${rig.mixer.written}", p.written > 96_000 && rig.mixer.written > 96_000)
        val restartAt = rig.sched.now
        restart(s)
        val writtenAtRestart = p.written
        var alive = 0
        repeat(2_500) {
            rig.runFor(1.0)
            alive = maxOf(alive, rig.builder.built.count { !it.released })
        }
        assertTrue("at most two real outputs alive: $alive", alive <= 2)
        checkRestart(s, p, restartAt, writtenAtRestart, 1)
        // Positions never fall back and never run ahead of what is heard.
        assertTrue(s.d.output.snap.presentedIn > 0)
    }

    @Test
    fun aSecondRestartWhileTheFirstWaitsFoldsIntoIt() {
        val s = songs()
        val rig = s.rig
        runUntil(rig) { rig.track(0).head > 79_200 }
        val p = rig.track(0)
        val restartAt = rig.sched.now
        restart(s)
        val writtenAtRestart = p.written
        rig.runFor(10.0)
        restart(s)
        rig.runFor(2_500.0)
        checkRestart(s, p, restartAt, writtenAtRestart, 1)
    }

    @Test
    fun aRestartAfterASongsLastFrameWasMixedGoesOnWithTheNextSongFromItsStart() {
        val rig = MixRig()
        val d = rig.deck(0)
        d.entry = "e0"
        d.songEndMs = 2_000.0
        d.signal = { k -> (k + 1) / 1_000_000f }
        d.play()
        // All of e0 and, until the player's restart, nothing after it.
        var restarted = false
        fun feed() {
            while (true) {
                val inFirst = d.entry == "e0"
                if (inFirst && d.nextFrame >= 96_000) {
                    if (!restarted) break
                    d.nextSong("y")
                    d.signal = { k -> -((k + 1) / 1_000_000f) }
                    continue
                }
                val n = if (inFirst) minOf(960L, 96_000 - d.nextFrame).toInt() else 960
                if (d.write(n) < n) break
            }
            rig.sched.at(rig.sched.now + 10_000_000) { feed() }
        }
        feed()
        rig.runFor(1.0)
        runUntil(rig) { rig.track(0).head > 74_000 }
        // The player restarts its reading period from the presented position
        // (an edit put y next): e0's rest, then y.
        val stamp = RealTimestamp()
        rig.track(0).timestamp(stamp)
        restarted = true
        d.restartAt(stamp.frame / 48.0)
        d.entry = "e0"
        d.songEndMs = 2_000.0
        d.signal = { k -> (k + 1) / 1_000_000f }
        d.play()
        rig.runFor(1_500.0)
        assertEquals("one output: ${rig.tap.notes}", 1, rig.builder.built.size)
        // y from its first frame right after e0's last.
        for (m in 96_000L until 96_000 + 24_000) assertEquals("y at $m", -((m - 96_000 + 1) / 1_000_000f), rig.tap.frames[m] ?: Float.NaN, 1e-7f)
    }

    @Test
    fun anOutputInvalidatedWhileItSettlesTakesTheReadBeforeTheJump() {
        val s = songs()
        val rig = s.rig
        runUntil(rig) { rig.track(0).head > 79_200 }
        val p = rig.track(0)
        restart(s)
        runUntil(rig) { p.pauses > 0 }
        rig.runFor(6.0)
        // While it settles the route goes away: the next client call restores
        // the track and its head jumps to its written count.
        val heardHead = p.head
        p.invalidate("usb")
        rig.runFor(2_500.0)
        val fresh = rig.lastTrack
        val first = rig.tap.byOutput.getValue(fresh.id).first()
        assertTrue("h $first from the read before the jump (head $heardHead, written ${p.written})", first in heardHead - 1_920..heardHead)
        for (m in first + 960 until 96_000) assertEquals("e0 at $m", (m + 1) / 1_000_000f, rig.tap.frames.getValue(m), 1e-7f)
        assertTrue(p.released)
    }
}
