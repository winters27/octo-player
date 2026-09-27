package app.winters.octo.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PaceTest {
    @Test
    fun speedsNearADetentSettleOnIt() {
        assertEquals(1f, snapSpeed(0.97f))
        assertEquals(1f, snapSpeed(1.03f))
        assertEquals(0.75f, snapSpeed(0.72f))
        assertEquals(1.25f, snapSpeed(1.28f))
        assertEquals(1.5f, snapSpeed(1.46f))
    }

    @Test
    fun betweenDetentsTheSpeedMovesInSmallSteps() {
        assertEquals(1.1f, snapSpeed(1.09f))
        assertEquals(1.15f, snapSpeed(1.16f))
        assertEquals(1.85f, snapSpeed(1.86f))
        assertEquals(0.6f, snapSpeed(0.61f))
    }

    @Test
    fun speedStaysInsideItsRange() {
        assertEquals(0.5f, snapSpeed(0.1f))
        assertEquals(2f, snapSpeed(3f))
        assertEquals(0.5f, speedAt(-1f))
        assertEquals(2f, speedAt(2f))
    }

    @Test
    fun theSliderEndsAreTheSlowestAndFastest() {
        assertEquals(0.5f, speedAt(0f))
        assertEquals(2f, speedAt(1f))
        // Normal speed sits a third of the way along.
        assertEquals(1f, speedAt(speedFraction(1f)))
        assertEquals(1f / 3f, speedFraction(1f), 0.0001f)
        SpeedDetents.forEach { assertEquals(it, speedAt(speedFraction(it))) }
    }

    @Test
    fun keepingPitchLeavesItWhereItWas() {
        assertEquals(Pace(1.5f, 1f), paceOf(1.5f, keepPitch = true, semitones = 0))
    }

    @Test
    fun withoutKeepPitchItFollowsTheSpeed() {
        assertEquals(Pace(1.25f, 1.25f), paceOf(1.25f, keepPitch = false, semitones = 0))
    }

    @Test
    fun semitonesShiftThePitchEitherWay() {
        assertEquals(1.4142f, paceOf(1f, true, 6).pitch, 0.001f)
        assertEquals(0.7071f, paceOf(1f, true, -6).pitch, 0.001f)
        // Beyond the range is held at its end.
        assertEquals(paceOf(1f, true, 6), paceOf(1f, true, 9))
        // Without keep pitch the shift goes on top of the speed's.
        assertEquals(1.5f * 1.4142f, paceOf(1.5f, false, 6).pitch, 0.001f)
    }

    @Test
    fun semitoneSliderSnapsToWholeSteps() {
        assertEquals(-6, semitonesAt(0f))
        assertEquals(0, semitonesAt(0.5f))
        assertEquals(6, semitonesAt(1f))
        assertEquals(2, semitonesAt(semitonesFraction(2)))
        assertEquals(1, semitonesAt(0.55f))
    }

    @Test
    fun labelsDropTrailingZeros() {
        assertEquals("1x", speedLabel(1f))
        assertEquals("2x", speedLabel(2f))
        assertEquals("1.5x", speedLabel(1.5f))
        assertEquals("1.25x", speedLabel(1.25f))
        assertEquals("0.75x", speedLabel(0.75f))
        assertEquals("1.05x", speedLabel(1.05f))
        assertEquals("+2 st", semitonesLabel(2))
        assertEquals("-3 st", semitonesLabel(-3))
        assertEquals("0 st", semitonesLabel(0))
    }
}
