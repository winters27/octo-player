package app.winters.octo.playback

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomixTimingTest {
    private fun plan(start: Long = 180_000, entry: Long = 4_000, overlap: Long = 8_000, rate: Double? = null, beatMs: Double? = 500.0) =
        TransitionPlan(start, entry, overlap, TransitionKind.BLEND, BLEND_CURVE, FILTER_STRENGTH, rate, beatMs, beatMs?.let { 0.0 }, false, 1.0, "test", lateMoveBars = LATE_MOVE_UNBOUNDED)

    @Test
    fun theSpareIsParkedJustBeforeTheEntry() {
        // Media3's 20 ms ramp on a new output falls on frames the mixer drops
        // before the entry.
        assertEquals(3_900L, plan().parkAtMs())
        assertEquals(3_900L, plan(rate = 1.02).parkAtMs())
        // An entry under 100 ms parks at the song's start.
        assertEquals(0L, plan(entry = 60).parkAtMs())
    }

    @Test
    fun aBlendIsArmedAtTheRunUpOrEarlierAtSpeed() {
        val p = plan()
        // At 1x the run-up's 2.5 s of song time comes first.
        assertEquals(177_500.0, p.armAtMs(proposalLeadMs = 600.0, speed = 1.0), 1e-9)
        // At 2x the run-up is only 1.25 s of output, under a 1 s lead plus
        // the spare's ready lead: armed so the start is that far ahead.
        assertEquals(180_000 - (1_000.0 + SPARE_READY_LEAD_MS) * 2, p.armAtMs(proposalLeadMs = 1_000.0, speed = 2.0), 1e-9)
    }

    @Test
    fun aLockedPlanNamesItsBarAndAnUnlockedOneNone() {
        assertEquals(2_000.0, plan(beatMs = 500.0).barMs(), 1e-9)
        assertTrue(plan(beatMs = null).barMs().isNaN())
    }

    // 20 s of a kick every half second (120 BPM) with a quiet tone under it.
    private fun beatEnvelope(): SectionEnvelope {
        val rate = 44_100
        val builder = EnvelopeBuilder(rate, 1, 0)
        val samples = FloatArray(rate * 20) { n ->
            val sinceKick = (n % (rate / 2)).toDouble() / rate
            (0.6 * kotlin.math.exp(-sinceKick * 30) * kotlin.math.sin(2 * kotlin.math.PI * 60 * n / rate) +
                0.05 * kotlin.math.sin(2 * kotlin.math.PI * 880.0 * n / rate)).toFloat()
        }
        builder.push(samples)
        return builder.build()
    }

    @Test
    fun theScoutsHandTheSongsTagTempoToTheAnalysis() {
        val envelope = beatEnvelope()
        assertEquals(120.0, tailAnalyzer(null)(envelope).features.tempo!!.bpm, 1.0)
        assertEquals(60.0, tailAnalyzer(61.0)(envelope).features.tempo!!.bpm, 1.0)
        assertEquals(60.0, headAnalyzer(61.0)(envelope).features.tempo!!.bpm, 1.0)
    }

    @Test
    fun aSpareParkedWhereItShouldBeIsNotMovedAgain() {
        assertFalse(needsRepark(1_500, 1_500))
        assertFalse(needsRepark(1_520, 1_500))
        assertTrue(needsRepark(0, 1_500))
        assertTrue(needsRepark(1_500, 0))
    }

    @Test
    fun theMusicIsStillWantedThroughARebufferButNotAtTheQueuesEnd() {
        assertTrue(wantedMusic(true, Player.STATE_BUFFERING))
        assertTrue(wantedMusic(true, Player.STATE_READY))
        assertFalse(wantedMusic(false, Player.STATE_READY))
        assertFalse(wantedMusic(true, Player.STATE_ENDED))
        assertFalse(wantedMusic(true, Player.STATE_IDLE))
    }

    @Test
    fun theLongestIntroIsInSongTimeTooAtSpeed() {
        val settings = AutomixSettings(maxOverlapMs = AUTOMIX_LONGEST_BLEND_MS)
        assertEquals(8_000L, settings.atSpeed(0.5f).longestIntroMs)
        assertEquals(4_000L, settings.atSpeed(0.5f).maxOverlapMs)
        assertEquals(24_000L, settings.atSpeed(1.5f).longestIntroMs)
        assertEquals(AUTOMIX_LONGEST_INTRO_MS, settings.atSpeed(1f).longestIntroMs)
    }

    @Test
    fun theFixedCrossfadeLastsAsLongAtAnySpeed() {
        val settings = AutomixSettings(maxOverlapMs = 8_000, smart = false)
        val a = FadeSong("a", 1, 200_000)
        val b = FadeSong("b", 1, 200_000)
        val context = TransitionContext(nowMs = 100_000, pace = 1.5)
        val plan = planTransition(a, b, null, null, settings.atSpeed(1.5f), context)
        // 12 s of the song pass in 8 s at 1.5x.
        assertEquals(12_000L, plan.overlapMs)
        assertEquals(188_000L, plan.startMs)
        assertEquals(settings, settings.atSpeed(1f))
        assertEquals(4_000L, settings.atSpeed(0.5f).maxOverlapMs)
    }

    @Test
    fun aTapReadEarlyIsReadAgainOnceItsLevelCounts() {
        // Read 3 s in, having heard 2 s: again once 18 more seconds have played.
        assertFalse(tapReadsAgain(2_000, askedAtMs = 3_000, positionMs = 20_000, speed = 1f, enoughMs = 20_000))
        assertTrue(tapReadsAgain(2_000, askedAtMs = 3_000, positionMs = 21_000, speed = 1f, enoughMs = 20_000))
        // At 2x the song time runs ahead of what is heard.
        assertFalse(tapReadsAgain(2_000, askedAtMs = 3_000, positionMs = 21_000, speed = 2f, enoughMs = 20_000))
        assertTrue(tapReadsAgain(2_000, askedAtMs = 3_000, positionMs = 39_000, speed = 2f, enoughMs = 20_000))
    }

    @Test
    fun aTapReadWithItsLevelHeardOrNotOnTheSongIsNotReadAgain() {
        assertFalse(tapReadsAgain(20_000, askedAtMs = 21_000, positionMs = 200_000, speed = 1f, enoughMs = 20_000))
        assertFalse(tapReadsAgain(null, askedAtMs = 3_000, positionMs = 200_000, speed = 1f, enoughMs = 20_000))
    }
}
