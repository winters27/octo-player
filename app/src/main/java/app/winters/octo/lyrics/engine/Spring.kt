package app.winters.octo.lyrics.engine

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

// Near enough to its target, and slow enough, to count as stopped.
const val SPRING_REST_DISTANCE = 0.01
const val SPRING_REST_SPEED = 0.01

// The window the speed is sampled over when a spring changes course.
private const val VELOCITY_WINDOW_S = 1.0 / 240.0

// A spring worked out in closed form instead of stepped: each time its
// target changes, the curve from where it is, how fast it is going and
// where it is headed is solved once, and every frame just reads that curve
// at the time gone by. So it lands the same at 60 or 120 frames a second.
// A target can wait a moment before it takes over, for the ripple between
// lines. `speed` makes it faster or slower without making it bouncier.
class Spring(
    private val mass: Double,
    private val stiffness: Double,
    private val damping: Double,
    initial: Double = 0.0,
) {
    // Where it is headed, and the curve toward it: how far off it started
    // (`x0`), how fast it was moving then (`v0`), and the time since.
    var target: Double = initial
        private set
    private var x0 = 0.0
    private var v0 = 0.0
    private var time = 0.0

    // The strength and friction in use, scaled by the speed.
    private var k = stiffness
    private var c = damping

    // A target still waiting its turn, and how long it has left.
    private var queued: Double? = null
    private var wait = 0.0

    var atRest: Boolean = true
        private set

    var speed: Double = 1.0
        set(value) {
            if (value == field || value <= 0.0) return
            val now = value()
            val moving = velocity()
            field = value
            k = stiffness * value * value
            c = damping * value
            restart(now, moving, target)
        }

    // How strongly it is damped: at 1 or more it never overshoots.
    val dampingRatio: Double get() = c / (2.0 * sqrt(k * mass))

    fun value(): Double = if (atRest) target else target + offsetAt(time)

    // How fast it is moving now, from a tiny window either side.
    fun velocity(): Double {
        if (atRest) return 0.0
        val half = VELOCITY_WINDOW_S / 2
        return (offsetAt(time + half) - offsetAt(time - half)) / VELOCITY_WINDOW_S
    }

    // Heads for a new target, now or once `delay` seconds have passed. A
    // new target replaces one still waiting.
    fun setTarget(to: Double, delay: Double = 0.0) {
        if (delay > 0.0) {
            queued = to
            wait = delay
            return
        }
        queued = null
        retarget(to)
    }

    // Moves the target without restarting a wait: a target still waiting
    // just changes, one already in use changes course now.
    fun moveTarget(to: Double) {
        if (queued != null) {
            queued = to
        } else if (to != target) {
            retarget(to)
        }
    }

    // Puts it straight at a place, still.
    fun jump(to: Double) {
        queued = null
        target = to
        x0 = 0.0
        v0 = 0.0
        time = 0.0
        atRest = true
    }

    // Lets `dt` seconds go by. A waiting target takes over at the exact
    // moment its wait runs out, even inside a frame.
    fun update(dt: Double) {
        val waiting = queued
        if (waiting != null) {
            if (wait <= dt) {
                val before = wait
                advance(before)
                queued = null
                retarget(waiting)
                advance(dt - before)
                return
            }
            wait -= dt
        }
        advance(dt)
    }

    // Whether a target is still waiting its turn.
    val hasQueued: Boolean get() = queued != null

    private fun retarget(to: Double) {
        restart(value(), velocity(), to)
    }

    private fun restart(from: Double, moving: Double, to: Double) {
        target = to
        x0 = from - to
        v0 = moving
        time = 0.0
        atRest = abs(x0) < SPRING_REST_DISTANCE && abs(v0) < SPRING_REST_SPEED
        if (atRest) x0 = 0.0
    }

    private fun advance(dt: Double) {
        if (atRest || dt <= 0.0) return
        time += dt
        if (abs(offsetAt(time)) < SPRING_REST_DISTANCE && abs(velocity()) < SPRING_REST_SPEED) {
            atRest = true
            x0 = 0.0
            v0 = 0.0
            time = 0.0
        }
    }

    // How far from the target it is `t` seconds into the current curve.
    private fun offsetAt(t: Double): Double = springOffset(x0, v0, t, mass, k, c)
}

// The closed-form spring: how far from its target a spring is `t` seconds
// after starting `x0` away at speed `v0`. Damped enough (a ratio of 1 or
// more) it creeps in; less, it swings in and settles.
fun springOffset(x0: Double, v0: Double, t: Double, mass: Double, stiffness: Double, damping: Double): Double {
    val w0 = sqrt(stiffness / mass)
    val zeta = damping / (2.0 * sqrt(stiffness * mass))
    return when {
        zeta < 1.0 -> {
            val wd = w0 * sqrt(1.0 - zeta * zeta)
            exp(-zeta * w0 * t) * (x0 * cos(wd * t) + (v0 + zeta * w0 * x0) / wd * sin(wd * t))
        }
        zeta == 1.0 -> exp(-w0 * t) * (x0 + (v0 + w0 * x0) * t)
        else -> {
            val root = sqrt(zeta * zeta - 1.0)
            val r1 = -w0 * (zeta - root)
            val r2 = -w0 * (zeta + root)
            val a = (v0 - r2 * x0) / (r1 - r2)
            val b = x0 - a
            a * exp(r1 * t) + b * exp(r2 * t)
        }
    }
}

// The springs the lyrics use (mass, stiffness, damping).
fun linePositionSpring(initial: Double = 0.0) = Spring(1.0, 200.0, 28.0, initial)
fun lineScaleSpring(initial: Double = 0.0) = Spring(1.0, 260.0, 32.0, initial)
fun scrollOffsetSpring(initial: Double = 0.0) = Spring(1.0, 240.0, 28.0, initial)
