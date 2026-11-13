package app.winters.octo.sound

import androidx.media3.common.util.UnstableApi
import app.winters.octo.playback.BlendDisarm
import app.winters.octo.playback.BlendEventKind
import app.winters.octo.playback.blendPassOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.min

// A start undone after the outgoing song had read past its end: its next
// pass is held until main names what plays after the end. A held frame
// plays only when the named pass is the one it holds; any other answer
// leaves the held frames unmixed and unheard.
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HeldPassTest {
    // A's frames carry +(frame + 1) / 1e6; its next song, held, is 0.7.
    private fun aValue(fr: Long): Float = (fr % 400_000 + 1) / 1_000_000f

    private val heldLevel = 0.7f

    // A ends 5 ms after the planned start (frame 96,240) and reads on into
    // its next song "e1"; B parked. Main never acks: at the proposal it
    // undoes the start and answers with `answer`.
    private fun undoneAtItsEnd(answer: Long): MixRig {
        val rig = MixRig(tuning = MixTuning(watchdogMs = 2_000, lagFloorMs = 5.0))
        val endFrame = 2_005L * 48
        val a = rig.deck(0)
        a.signal = { f -> if (a.entry == "e0") aValue(f) else heldLevel }
        a.play()
        fun step() {
            while (true) {
                val n = if (a.entry == "e0") min(1_024L, endFrame - a.nextFrame).toInt() else 1_024
                if (n <= 0) {
                    a.nextSong("e1")
                    continue
                }
                if (a.write(n) < n) break
            }
            rig.sched.at(rig.sched.now + 10_000_000) { step() }
        }
        step()
        val b = rig.deck(1)
        b.signal = { -0.3f }
        rig.mixer.park(1, true)
        b.play()
        b.feed()
        rig.runFor(200.0)
        rig.onEvent = { e ->
            if (e.kind == BlendEventKind.PROPOSED && rig.mixer.commitWord.cancel(e.generation)) {
                rig.mixer.disarm(BlendDisarm.EDIT, e.generation)
                rig.mixer.followUp(e.generation, answer)
            }
        }
        rig.arm(testBlend(1, testPlan(start = 2_000, entry = 0, overlap = 2_000), endMs = 2_005.0))
        rig.runFor(4_000.0)
        return rig
    }

    private fun heard(rig: MixRig): List<Pair<Long, Float>> = rig.tap.frames.entries.sortedBy { it.key }.map { it.key to it.value }

    @Test
    fun anAnswerNamingAnotherPassLeavesTheHeldFramesUnheard() {
        val rig = undoneAtItsEnd(blendPassOf("x"))
        val frames = heard(rig)
        val end = 2_005L * 48
        // A whole up to its end, alone at its own level.
        for (m in 0 until end) assertEquals("A at $m", aValue(m), rig.tap.frames[m] ?: Float.NaN, 0f)
        // Nothing of the held song, nothing of B.
        val stray = frames.filter { (_, v) -> v == heldLevel || v < 0f }
        assertEquals("held or incoming frames heard", emptyList<Pair<Long, Float>>(), stray.take(3))
        assertTrue(rig.events.none { it.kind == BlendEventKind.BLEND_END })
    }

    @Test
    fun anAnswerNamingTheHeldPassPlaysItOnWithNoGap() {
        val rig = undoneAtItsEnd(blendPassOf("e1"))
        val end = 2_005L * 48
        for (m in 0 until end) assertEquals("A at $m", aValue(m), rig.tap.frames[m] ?: Float.NaN, 0f)
        // The held song right after A's last frame, whole.
        for (m in end until end + 48_000) assertEquals("the held song at $m", heldLevel, rig.tap.frames[m] ?: Float.NaN, 0f)
    }
}
