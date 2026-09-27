package app.winters.octo.lyrics.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.math.sqrt

class SpringTest {
    // Runs a spring for `seconds` in steps of `hz` a second.
    private fun Spring.run(seconds: Double, hz: Int) {
        val steps = Math.round(seconds * hz).toInt()
        repeat(steps) { update(1.0 / hz) }
    }

    @Test
    fun underDampedSwingsPastItsTargetAndMatchesTheClosedForm() {
        val spring = Spring(1.0, 100.0, 10.0)
        spring.setTarget(1.0)
        assertEquals(0.5, spring.dampingRatio, 1e-12)
        spring.update(0.2)
        // From 1 away at rest: x = e^(-zeta w t) (cos(wd t) + zeta w / wd sin(wd t)).
        val w = 10.0
        val zeta = 0.5
        val wd = w * sqrt(1 - zeta * zeta)
        val expected = 1 - exp(-zeta * w * 0.2) * (Math.cos(wd * 0.2) + zeta * w / wd * Math.sin(wd * 0.2))
        assertEquals(expected, spring.value(), 1e-9)
        // It goes past the target at some point.
        var most = 0.0
        repeat(120) {
            spring.update(1.0 / 120)
            most = maxOf(most, spring.value())
        }
        assertTrue(most > 1.0)
    }

    @Test
    fun criticallyDampedCreepsInWithoutPassingTheTarget() {
        val spring = Spring(1.0, 100.0, 20.0)
        assertEquals(1.0, spring.dampingRatio, 1e-12)
        spring.setTarget(1.0)
        spring.update(0.1)
        assertEquals(1 - exp(-1.0) * (1 + 1.0), spring.value(), 1e-9)
        repeat(240) {
            spring.update(1.0 / 120)
            assertTrue(spring.value() <= 1.0 + 1e-12)
        }
    }

    @Test
    fun overDampedNeverPassesTheTarget() {
        val spring = Spring(1.0, 100.0, 40.0)
        assertTrue(spring.dampingRatio > 1)
        spring.setTarget(1.0)
        var last = 0.0
        repeat(600) {
            spring.update(1.0 / 120)
            assertTrue(spring.value() >= last - 1e-12)
            assertTrue(spring.value() <= 1.0 + 1e-12)
            last = spring.value()
        }
    }

    @Test
    fun comesToRestExactlyOnItsTarget() {
        val spring = linePositionSpring()
        spring.setTarget(300.0)
        assertFalse(spring.atRest)
        spring.run(3.0, 60)
        assertTrue(spring.atRest)
        assertEquals(300.0, spring.value(), 0.0)
        assertEquals(0.0, spring.velocity(), 0.0)
    }

    @Test
    fun aRestingSpringCountsAsStillWhenCloseAndSlow() {
        val spring = Spring(1.0, 200.0, 28.0, 100.0)
        // Already within 0.01 and still: at rest straight away.
        spring.setTarget(100.005)
        assertTrue(spring.atRest)
        // Further than 0.01: moving.
        spring.setTarget(100.5)
        assertFalse(spring.atRest)
    }

    @Test
    fun landsTheSameAt60And120FramesASecond() {
        val sixty = linePositionSpring()
        val oneTwenty = linePositionSpring()
        listOf(sixty to 60, oneTwenty to 120).forEach { (spring, hz) ->
            spring.setTarget(500.0)
            spring.run(0.25, hz)
            // A change of course halfway, while it is moving fast.
            spring.setTarget(-200.0)
            spring.run(0.25, hz)
        }
        assertEquals(sixty.value(), oneTwenty.value(), 1e-6)
        assertEquals(sixty.velocity(), oneTwenty.velocity(), 1e-4)
    }

    @Test
    fun aWaitingTargetTakesOverAtTheSameMomentAtAnyFrameRate() {
        val sixty = linePositionSpring()
        val oneTwenty = linePositionSpring()
        listOf(sixty to 60, oneTwenty to 120).forEach { (spring, hz) ->
            // A wait that falls between frames at 60 a second.
            spring.setTarget(100.0, delay = 0.123)
            spring.run(0.1, hz)
            assertEquals(0.0, spring.value(), 0.0)
            assertTrue(spring.hasQueued)
            spring.run(0.4, hz)
        }
        assertFalse(sixty.hasQueued)
        assertEquals(sixty.value(), oneTwenty.value(), 1e-6)
    }

    @Test
    fun movingAWaitingTargetKeepsItsWait() {
        val spring = linePositionSpring()
        spring.setTarget(100.0, delay = 0.1)
        spring.update(0.05)
        spring.moveTarget(150.0)
        spring.update(0.04)
        assertEquals(0.0, spring.value(), 0.0)
        spring.update(0.02)
        assertEquals(150.0, spring.target, 0.0)
    }

    @Test
    fun speedKeepsTheDampingRatioSoFasterIsNotBouncier() {
        val normal = linePositionSpring()
        val fast = linePositionSpring()
        fast.speed = 2.0
        val slow = linePositionSpring()
        slow.speed = 0.5
        assertEquals(normal.dampingRatio, fast.dampingRatio, 1e-12)
        assertEquals(normal.dampingRatio, slow.dampingRatio, 1e-12)
        // Twice the speed covers in t what normal covers in 2t.
        normal.setTarget(100.0)
        fast.setTarget(100.0)
        normal.update(0.2)
        fast.update(0.1)
        assertEquals(normal.value(), fast.value(), 1e-9)
    }

    @Test
    fun theLyricsSpringsAreJustUnderCriticalDamping() {
        assertEquals(28 / (2 * sqrt(200.0)), linePositionSpring().dampingRatio, 1e-12)
        assertEquals(32 / (2 * sqrt(260.0)), lineScaleSpring().dampingRatio, 1e-12)
        assertEquals(28 / (2 * sqrt(240.0)), scrollOffsetSpring().dampingRatio, 1e-12)
    }

    @Test
    fun aJumpPutsItStraightThere() {
        val spring = linePositionSpring()
        spring.setTarget(100.0, delay = 1.0)
        spring.jump(40.0)
        assertTrue(spring.atRest)
        assertFalse(spring.hasQueued)
        assertEquals(40.0, spring.value(), 0.0)
    }
}
