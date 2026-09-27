package app.winters.octo.player.immersive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WashFadeTest {
    @Test
    fun theFirstCoverShowsAtOnce() {
        assertEquals(0L, coverFadeMs(null, 5_000))
    }

    @Test
    fun skippingFadesQuicker() {
        assertEquals(300L, coverFadeMs(1_000, 1_800))
        assertEquals(300L, coverFadeMs(1_000, 2_499))
        // 1.5 s or more apart: the full fade.
        assertEquals(500L, coverFadeMs(1_000, 2_500))
        assertEquals(500L, coverFadeMs(1_000, 60_000))
    }

    @Test
    fun easeInOutCubicIsSlowAtBothEnds() {
        assertEquals(0f, easeInOutCubic(0f))
        assertEquals(0.0625f, easeInOutCubic(0.25f), 1e-6f)
        assertEquals(0.5f, easeInOutCubic(0.5f), 1e-6f)
        assertEquals(0.9375f, easeInOutCubic(0.75f), 1e-6f)
        assertEquals(1f, easeInOutCubic(1f))
        // Held to its ends, and symmetric about the middle.
        assertEquals(0f, easeInOutCubic(-1f))
        assertEquals(1f, easeInOutCubic(2f))
        for (i in 0..100) {
            val x = i / 100f
            assertEquals(1f - easeInOutCubic(x), easeInOutCubic(1f - x), 1e-5f)
            if (i > 0) assertTrue(easeInOutCubic(x) >= easeInOutCubic(x - 0.01f))
        }
    }

    @Test
    fun aFadeRunsItsLengthOnTheClock() {
        val fade = CoverFade()
        assertEquals(0L, fade.start(1_000))
        assertFalse(fade.running)
        assertEquals(1f, fade.mix)

        assertEquals(500L, fade.start(10_000))
        assertTrue(fade.running)
        assertEquals(0f, fade.mix)
        fade.advance(250f)
        assertEquals(0.5f, fade.mix, 1e-6f)
        fade.advance(250f)
        assertFalse(fade.running)

        // Another within 1.5 s: 300 ms.
        assertEquals(300L, fade.start(10_900))
        fade.advance(75f)
        assertEquals(easeInOutCubic(0.25f), fade.mix, 1e-6f)
        fade.advance(225f)
        assertEquals(1f, fade.mix)
    }
}
