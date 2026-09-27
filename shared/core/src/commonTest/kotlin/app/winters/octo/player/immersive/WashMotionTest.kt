package app.winters.octo.player.immersive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class WashMotionTest {
    private val twoPi = (2 * PI).toFloat()

    @Test
    fun kScalesByTempoAndFrameLimit() {
        assertEquals(1f, frameFactor(120f, 60f), 1e-6f)
        assertEquals(0.5f, frameFactor(60f, 60f), 1e-6f)
        assertEquals(2f, frameFactor(120f, 30f), 1e-6f)
        assertEquals(0.5f, frameFactor(120f, 120f), 1e-6f)
    }

    @Test
    fun aSixtyBpmSongDriftsAtHalfTheSpeed() {
        val slow = WashMotion()
        val quick = WashMotion()
        repeat(60) {
            slow.step(frameFactor(60f, 60f), 60f, 1 / 60f)
            quick.step(frameFactor(120f, 60f), 120f, 1 / 60f)
        }
        // About one unit of t a second at 120 BPM.
        assertEquals(0.96f, quick.time, 1e-4f)
        assertEquals(quick.time / 2f, slow.time, 1e-4f)
    }

    @Test
    fun theSpeedDoesNotDependOnTheFrameRate() {
        val sixty = WashMotion()
        val thirty = WashMotion()
        repeat(60) { sixty.step(frameFactor(120f, 60f), 120f, 1 / 60f) }
        repeat(30) { thirty.step(frameFactor(120f, 30f), 120f, 1 / 30f) }
        assertEquals(sixty.time, thirty.time, 1e-4f)
        assertEquals(sixty.turn, thirty.turn, 1e-4f)
    }

    @Test
    fun theWarpRipplesFasterOnQuickSongsButNeverSlower() {
        fun warpAfterASecond(bpm: Float) = WashMotion().apply { repeat(60) { step(frameFactor(bpm, 60f), bpm, 1 / 60f) } }.warpPhase
        // At 60 BPM, k halves but the warp's own factor stays at 1.
        assertEquals(0.48f, warpAfterASecond(60f), 1e-4f)
        assertEquals(0.96f, warpAfterASecond(120f), 1e-4f)
        // At 240 BPM, k doubles and the factor is capped at 2.
        assertEquals(3.84f, warpAfterASecond(240f), 1e-3f)
    }

    @Test
    fun theRotationTurnsBackPastAFullTurnEitherWay() {
        val motion = WashMotion()
        assertEquals(1f, motion.direction)
        var frames = 0
        while (motion.direction > 0f && frames < 100_000) {
            motion.step(1f, 120f, 1 / 60f)
            frames++
        }
        assertTrue(motion.rotation > twoPi)
        // About every 105 s at 60 fps (a little sooner as the breathing
        // swells past 1).
        val seconds = frames / 60f
        assertTrue("$seconds s", seconds in 90f..110f)
        while (motion.direction < 0f && frames < 100_000) {
            motion.step(1f, 120f, 1 / 60f)
            frames++
        }
        assertTrue(motion.rotation < -twoPi)
        assertEquals(1f, motion.direction)
    }

    @Test
    fun theRotationIsAboutSixHundredthsOfARadianASecond() {
        val motion = WashMotion()
        repeat(60) { motion.step(1f, 120f, 1 / 60f) }
        assertEquals(0.06f, motion.rotation, 0.001f)
    }

    @Test
    fun breathingStaysBetweenPointNineAndOnePointOne() {
        val motion = WashMotion()
        var low = 2f
        var high = 0f
        // A large k sweeps the slow phase quickly.
        repeat(20_000) {
            motion.step(40f, 120f, 0f)
            low = minOf(low, motion.breathing)
            high = maxOf(high, motion.breathing)
        }
        assertEquals(0.9f, low, 0.001f)
        assertEquals(1.1f, high, 0.001f)
    }

    @Test
    fun breathingIsAVerySlowSwell() {
        val motion = WashMotion()
        repeat(60) { motion.step(1f, 120f, 1 / 60f) }
        // 0.016 x k x k / 120 a frame.
        assertEquals(60 * 0.016f / 120f, motion.breathPhase, 1e-6f)
    }

    @Test
    fun theFinalPassTurnsOnceInAboutFiftyTwoSeconds() {
        val motion = WashMotion()
        var frames = 0
        var last = 0f
        while (frames < 10_000) {
            motion.step(frameFactor(120f, 60f), 120f, 1 / 60f)
            frames++
            if (motion.turn < last) break
            last = motion.turn
        }
        val seconds = frames / 60f
        assertEquals(52.36f, seconds, 0.1f)
    }

    @Test
    fun aPulseBoostsTheRotationAndFades() {
        val motion = WashMotion()
        assertEquals(1f, motion.pulseBoost)
        motion.beat()
        assertEquals(3f, motion.pulseBoost)
        motion.step(1f, 120f, 1f)
        assertEquals(2f, motion.pulseBoost, 1e-6f)
        repeat(10) { motion.beat() }
        assertEquals(10f, motion.pulseBoost)
    }

    @Test
    fun theFrameRateFollowsWhereTheBackgroundIs() {
        assertEquals(60f, targetFps(WashVisibility.Focused, 60, powerSave = false))
        assertEquals(30f, targetFps(WashVisibility.Focused, 60, powerSave = true))
        assertEquals(30f, targetFps(WashVisibility.Focused, 30, powerSave = true))
        assertEquals(120f, targetFps(WashVisibility.Focused, 120, powerSave = false))
        assertEquals(10f, targetFps(WashVisibility.Unfocused, 60, powerSave = false))
        assertEquals(0f, targetFps(WashVisibility.Hidden, 60, powerSave = false))
    }

    @Test
    fun rateChangesEaseInByEightPercentAFrame() {
        val rate = FrameRate(60f)
        assertEquals(56f, rate.ease(10f), 1e-4f)
        assertEquals(56f + (10f - 56f) * 0.08f, rate.ease(10f), 1e-4f)
        repeat(200) { rate.ease(10f) }
        assertEquals(10f, rate.limit)
        // Hidden stops the clock rather than easing to nothing.
        assertEquals(10f, rate.ease(0f))
    }

    @Test
    fun framesAreDrawnOnlyWhenDue() {
        val sixtieth = 16_666_667L
        assertTrue(frameDue(sixtieth, 60f))
        // A hair early still counts.
        assertTrue(frameDue(sixtieth - 1_000_000, 60f))
        // A 120 Hz screen draws every other refresh at a 60 limit.
        assertFalse(frameDue(sixtieth / 2, 60f))
        assertFalse(frameDue(sixtieth * 4, 0f))
        assertEquals(1f, framesElapsed(sixtieth, 60f), 0.001f)
        assertEquals(3f, framesElapsed(5_000_000_000L, 60f))
    }

    @Test
    fun theSongsTempoIsUsedWhenWanted() {
        assertEquals(90f, paceBpm(90, useBpm = true))
        assertEquals(120f, paceBpm(90, useBpm = false))
        assertEquals(120f, paceBpm(null, useBpm = true))
        assertEquals(120f, paceBpm(0, useBpm = true))
        assertEquals(240f, paceBpm(999, useBpm = true))
    }
}
