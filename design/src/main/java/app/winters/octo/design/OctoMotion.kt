package app.winters.octo.design

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

// Shared springs so every surface moves the same way.
object OctoMotion {
    fun <T> soft() = spring<T>(dampingRatio = 0.75f, stiffness = 400f)
    fun <T> snappy() = spring<T>(dampingRatio = 0.65f, stiffness = 600f)
    fun <T> bouncy() = spring<T>(dampingRatio = 0.5f, stiffness = 400f)
    fun <T> jelly() = spring<T>(dampingRatio = 0.55f, stiffness = 300f)
    fun <T> smooth() = spring<T>(dampingRatio = 0.75f, stiffness = 1500f)

    // Settles without a bounce: for a card changing size, like a menu
    // turning to its next page.
    fun <T> calm() = spring<T>(dampingRatio = 0.9f, stiffness = 500f)
}

// How long each kind of change takes, in milliseconds, before the motion
// scale is applied.
object OctoDuration {
    // A press going down.
    const val Press = 80

    // The pointer arriving or leaving.
    const val Hover = 140

    // One icon or pop-up swapping for another.
    const val Swap = 160

    // A fill changing colour.
    const val Fill = 180

    // A card or row changing, or a ripple of light.
    const val Card = 200

    // Anything without a more particular speed, and a focus ring.
    const val Neutral = 300

    // A row squeezed by a long press, down and back.
    const val Squeeze = 340

    // The scrubber growing, and its played part sweeping away at the end.
    const val Scrub = 400
}

// The curves every tween uses.
object OctoEasing {
    // The default: quick off the mark, a long soft landing.
    val Spring: Easing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

    // Goes a little past and comes back, for something growing.
    val Overshoot: Easing = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)

    // Dips back a touch before it starts and passes a touch at the end.
    val Bounce: Easing = CubicBezierEasing(0.45f, -0.05f, 0.15f, 1.05f)

    // Even in and out, for a pill sliding between places.
    val Smooth: Easing = CubicBezierEasing(0.65f, 0.05f, 0.36f, 1f)

    // All at once, then settling.
    val Snappy: Easing = CubicBezierEasing(0f, 0f, 0f, 1f)

    // Gathers speed as it goes, for something leaving.
    val EaseIn: Easing = CubicBezierEasing(0.4f, 0f, 1f, 1f)

    // The scrubber's played part sweeping off the end.
    val Sweep: Easing = CubicBezierEasing(0.5f, 0f, 0.75f, 0f)
}

// How far a press, a pop and a lift go.
object OctoPress {
    // A control pressed down.
    const val Scale = 0.96f

    // A large surface pressed, like a row or a card.
    const val LargeScale = 0.985f

    // A chrome button popping out as it is pressed.
    const val Pop = 1.05f

    // A small control rising under the pointer.
    val LiftSmall: Dp = 1.dp

    // A medium one.
    val LiftMedium: Dp = 2.dp
}

// One number for how long motion takes and one for how far it goes, so
// reduced motion calms everything at once. Every token duration is
// multiplied by `duration` and every travel, lift or growth by `distance`.
@Immutable
data class MotionScale(val duration: Float = 1f, val distance: Float = 1f) {
    // A token duration after the scale.
    fun ms(base: Int): Int = (base * duration).roundToInt()

    // A travel after the scale.
    fun travel(base: Dp): Dp = base * distance

    // A scale factor after the scale: 1.05 becomes 1.0 when nothing moves.
    fun scale(base: Float): Float = 1f + (base - 1f) * distance

    // True when nothing moves at all.
    val still: Boolean get() = distance == 0f

    companion object {
        val Full = MotionScale()

        // Reduced motion: nothing travels, grows or shrinks, and what still
        // changes (a fade, a colour) does so in half the time.
        val Reduced = MotionScale(duration = 0.5f, distance = 0f)

        // The scale for a platform. `systemAnimatorScale` is the phone's
        // animation speed setting; `reduce` is Octo's own Reduce motion.
        // Compose already stretches every animation by a non-zero animator
        // scale itself, so passing it on here would count it twice. Only
        // "off" is taken, as reduced motion, since Compose cannot know that
        // travel should stop as well.
        fun of(systemAnimatorScale: Float, reduce: Boolean): MotionScale =
            if (reduce || systemAnimatorScale == 0f) Reduced else Full
    }
}

// The motion scale for everything inside. The phone app gives it from
// Reduce motion and the animator setting, the desktop from its own setting.
val LocalMotionScale = compositionLocalOf { MotionScale.Full }

// Whether motion is reduced, by Octo's own setting or the phone's. Surfaces
// here then fade rather than move.
val LocalReduceMotion = compositionLocalOf { false }

// The motion scale in force: reduced whenever either local says so.
@Composable
@ReadOnlyComposable
fun motionScale(): MotionScale = if (LocalReduceMotion.current) MotionScale.Reduced else LocalMotionScale.current

// A tween of a token duration on a token curve, after the motion scale.
fun <T> octoTween(scale: MotionScale, duration: Int, easing: Easing = OctoEasing.Spring): TweenSpec<T> =
    tween(scale.ms(duration), easing = easing)
