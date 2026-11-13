package app.winters.octo.sound

import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// A deck whose data comes no faster than it plays: a fresh output's first
// music waits until the deck holds the start threshold plus a fill wake, a
// dry deck comes back only with as much, and a short ring is mixed only
// once the output's lead runs low, so neither fades out and back in.
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RefillTest {
    private val ms = 1_000_000L

    private fun speaker() = RouteMeasure().apply {
        quantum = 1_920
        largestStep = 1_920
        intervalNanos = 40 * ms
    }

    private fun rig() = MixRig(routes = hashMapOf("speaker" to speaker()))

    // Every frame written into every track from mix frame `from` on, by
    // track, that is not the deck's level.
    private fun offLevel(rig: MixRig, level: Float, fromMix: Long): List<String> {
        val out = ArrayList<String>()
        for (t in rig.builder.built) {
            val first = rig.tap.byOutput[t.id]?.firstOrNull() ?: continue
            for (k in 0 until t.written) {
                val m = first + k
                if (m >= fromMix && t.left(k) != level && out.size < 5) out += "track ${t.id} frame $k (mix $m): ${t.left(k)}"
            }
        }
        return out
    }

    @Test
    fun aDeckFedAtItsOwnRateAfterAStallPlaysOnWithNoDryOutAndNoFade() {
        val rig = rig()
        val d = rig.deck(0)
        d.signal = { 0.5f }
        d.play()
        d.feed()
        rig.runFor(1_000.0)
        // The source stops until the ring and the output have run dry.
        rig.stalled[0] = rig.sched.now
        rig.runFor(1_800.0)
        rig.stalled[0] = Long.MAX_VALUE
        val notesAtStall = rig.tap.notes.size
        // Then exactly as fast as it plays: 10 ms every 10 ms.
        d.pace()
        rig.runFor(11_000.0)
        val after = rig.tap.notes.drop(notesAtStall)
        val back = after.indexOfFirst { it.startsWith("back 0@") }
        assertTrue("it came back: $after", back >= 0)
        val backAt = after[back].substringAfter('@').toLong()
        // From there on, no dry-out and no second rejoin in ten seconds.
        val again = after.drop(back + 1).filter { it.startsWith("dry 0@") || it.startsWith("back 0@") }
        assertEquals("dry-outs and rejoins after it", emptyList<String>(), again)
        // And no fade: past the rejoin's 20 ms ramp every frame is the deck's.
        assertTrue("ten seconds written after the rejoin: ${rig.mixer.written}", rig.mixer.written > backAt + 10L * 48_000)
        assertEquals(emptyList<String>(), offLevel(rig, 0.5f, backAt + 960))
        // The output it came back on never ran short.
        val since = rig.builder.built.filter { (rig.tap.byOutput[it.id]?.firstOrNull() ?: -1L) >= backAt }
        assertTrue(since.isNotEmpty())
        for (t in since) assertEquals("track ${t.id} never underran", 0, t.underrunCount)
    }

    @Test
    fun aDeckWhoseSourceStallsBesideAPlayingOneDriesWithItsFadeAndTheOutputNeverRunsLowOnLateWakes() {
        val rig = rig()
        val a = rig.deck(0)
        a.signal = { 0.25f }
        a.play()
        a.feed()
        val b = rig.deck(1)
        b.signal = { 0.25f }
        b.play()
        b.feed()
        rig.runFor(1_000.0)
        // The second deck's source stops; from here every wake runs 45 ms late.
        rig.stalled[1] = rig.sched.now
        rig.mixLoop.lateNanos = 45 * ms
        rig.runFor(3_000.0)
        assertTrue("the stalled deck dried: ${rig.tap.notes}", rig.tap.notes.any { it.startsWith("dry 1@") })
        for (t in rig.builder.built) assertEquals("track ${t.id} underruns", 0, t.underrunCount)
        assertEquals("one output", 1, rig.builder.built.size)
    }

    @Test
    fun aFirstStartFedNoFasterThanItPlaysWaitsInsteadOfDipping() {
        val rig = rig()
        val d = rig.deck(0)
        d.signal = { 0.5f }
        d.play()
        // The deck's first frames come at the rate they play.
        d.pace()
        rig.runFor(3_000.0)
        val t = rig.track(0)
        assertTrue("written ${t.written}", t.written > 96_000)
        // Its first music waited for the start threshold plus a fill wake
        // (8,400 frames, in by the chunk at 170 ms).
        val first = t.writes.first()
        assertTrue("first music at ${first / 1e6} ms", first >= 165 * ms)
        // Every frame the deck's own, nothing faded, nothing gone dry.
        assertEquals(emptyList<String>(), offLevel(rig, 0.5f, 0))
        assertTrue("no dry-out: ${rig.tap.notes}", rig.tap.notes.none { it.startsWith("dry 0@") })
        assertEquals(0, t.underrunCount)
    }
}
