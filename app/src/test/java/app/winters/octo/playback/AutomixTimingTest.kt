package app.winters.octo.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AutomixTimingTest {
    private fun plan(start: Long = 180_000, entry: Long = 4_000, overlap: Long = 8_000, rate: Double? = null, beatMs: Double? = 500.0) =
        TransitionPlan(start, entry, overlap, TransitionKind.BLEND, BLEND_CURVE, FILTER_STRENGTH, rate, beatMs, beatMs?.let { 0.0 }, false, 1.0, "test")

    @Test
    fun theRunUpStartsSoTheEntryLandsOnTheStart() {
        val p = plan()
        assertEquals(1_500L, p.preRollFromMs())
        assertEquals(177_500.0, p.preRollAtMs(), 1e-9)
        assertEquals(4_000.0, p.incomingAtMs(180_000.0), 1e-9)
        // A song that comes in at its very start cannot run up.
        val early = plan(entry = 1_000)
        assertEquals(0L, early.preRollFromMs())
        assertEquals(179_000.0, early.preRollAtMs(), 1e-9)
    }

    @Test
    fun aTempoMatchedSongRunsUpAtItsRate() {
        val p = plan(rate = 1.02)
        assertEquals(4_000L - 2_550L, p.preRollFromMs())
        assertEquals(180_000 - 2_550 / 1.02, p.preRollAtMs(), 1e-9)
        // Half a second into the blend the incoming song has moved 510 ms.
        assertEquals(4_510.0, p.incomingAtMs(180_500.0), 1e-9)
        assertEquals(0.0, p.alignmentErrorMs(180_500.0, 4_510.0), 1e-9)
        assertEquals(10.0, p.alignmentErrorMs(180_500.0, 4_520.2), 1e-9)
    }

    // A deck that starts late and stops a while on every move, the way an
    // audio output does.
    private class Decks(val plan: TransitionPlan, val startLag: Long, val seekLag: Long) {
        var now = 0L
        private val runUpAt = plan.preRollAtMs()
        var incoming = plan.preRollFromMs().toDouble()
        private var resumesAt = startLag
        val outgoing: Double get() = runUpAt + now
        val playing: Boolean get() = now >= resumesAt

        fun tick(ms: Long) {
            repeat(ms.toInt()) {
                now++
                if (playing) incoming += plan.incomingRate()
            }
        }

        fun seek(to: Long) {
            incoming = to.toDouble()
            resumesAt = now + seekLag
        }
    }

    private fun align(decks: Decks): Pair<Aligner.Step.Settled, Int> {
        val aligner = Aligner(decks.plan)
        var seeks = 0
        while (true) {
            when (val step = aligner.observe(decks.now, decks.outgoing, decks.incoming, decks.playing)) {
                is Aligner.Step.Settled -> return step to seeks
                is Aligner.Step.Seek -> {
                    seeks++
                    decks.seek(step.toMs)
                }
                Aligner.Step.Wait -> Unit
            }
            decks.tick(40)
        }
    }

    @Test
    fun aLateStartIsMovedIntoLineBeforeTheBlend() {
        val decks = Decks(plan(), startLag = 180, seekLag = 90)
        val (settled, seeks) = align(decks)
        assertTrue("${settled.errorMs}", abs(settled.errorMs!!) < BAR_LOCK_TOLERANCE_MS)
        assertTrue(settled.locked)
        assertTrue(seeks in 1..ALIGN_MOST_SEEKS)
        // Settled while the incoming song was still silent.
        assertTrue(decks.outgoing < plan().startMs)
        // And it really is in line.
        assertTrue(abs(plan().alignmentErrorMs(decks.outgoing, decks.incoming)) < BAR_LOCK_TOLERANCE_MS)
    }

    @Test
    fun aTempoMatchedSongIsLinedUpToo() {
        val p = plan(rate = 0.97)
        val decks = Decks(p, startLag = 250, seekLag = 140)
        val (settled, _) = align(decks)
        assertTrue("${settled.errorMs}", settled.locked)
    }

    @Test
    fun aDeckThatIsAlreadyInLineIsLeftAlone() {
        val decks = Decks(plan(), startLag = 0, seekLag = 90)
        val (settled, seeks) = align(decks)
        assertEquals(0, seeks)
        assertTrue(settled.locked)
    }

    @Test
    fun aDeckThatNeverPlaysInTimeGoesAheadUnmeasured() {
        val decks = Decks(plan(), startLag = 5_000, seekLag = 90)
        val (settled, seeks) = align(decks)
        assertEquals(0, seeks)
        assertNull(settled.errorMs)
        assertFalse(settled.locked)
    }

    @Test
    fun aMoveThatKeepsMissingStopsAndTheBlendGoesSmooth() {
        // Each move lands 40 ms off, first early then late, so it never settles.
        val p = plan()
        val aligner = Aligner(p)
        val misses = listOf(-40.0, 40.0, -40.0, 40.0)
        // It starts 100 ms behind.
        var now = 0L
        var outgoing = p.preRollAtMs()
        var incoming = p.preRollFromMs() - 100.0
        var seeks = 0
        var settled: Aligner.Step.Settled? = null
        while (settled == null) {
            when (val step = aligner.observe(now, outgoing, incoming, true)) {
                is Aligner.Step.Settled -> settled = step
                is Aligner.Step.Seek -> incoming = step.toMs + misses[seeks++]
                Aligner.Step.Wait -> Unit
            }
            now += 20
            outgoing += 20
            incoming += 20
        }
        assertEquals(ALIGN_MOST_SEEKS, seeks)
        assertFalse(settled.locked)
        assertTrue(outgoing <= p.startMs)
    }
}
