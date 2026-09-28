package app.winters.octo.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.PI
import kotlin.math.sin

// A small gesture an icon makes when its action happens, matched to what
// the action does: a heart pulses when liked, next slides forward, a bell
// rings. Each lasts 0.3 s and is strongest 45% of the way through.
enum class IconAccent {
    // Up a little and back, for sending something up or away.
    Lift,

    // Down a little and back, for a download or a drop into a list.
    Drop,

    // Up and a touch larger, for raising something to the top.
    Raise,

    // Forward 4 dp and back, for next.
    SlideForward,

    // Back 4 dp and return, for previous.
    SlideBack,

    // Larger and back, for opening something out.
    Expand,

    // Smaller and back, for folding something away.
    Collapse,

    // A beat larger and back, for liking.
    Pulse,

    // A bell's swing, dying away.
    Ring,

    // One full turn, for refreshing.
    SpinStep,
}

// Where an icon is part-way through its gesture: moved by `dx`, `dy` (in
// dp), grown by `scale`, turned by `rotation` degrees.
@Immutable
data class AccentPose(val dx: Float = 0f, val dy: Float = 0f, val scale: Float = 1f, val rotation: Float = 0f) {
    companion object {
        val Rest = AccentPose()
    }
}

// How far into its gesture an icon is at `progress` (0 to 1): nothing at
// either end, all of it at the peak, 45% of the way through. It rises on
// the default curve and settles on it too.
fun accentStrength(progress: Float): Float {
    if (progress <= 0f || progress >= 1f) return 0f
    return if (progress < AccentPeak) {
        OctoEasing.Spring.transform(progress / AccentPeak)
    } else {
        1f - OctoEasing.Spring.transform((progress - AccentPeak) / (1f - AccentPeak))
    }
}

// Where the peak of every gesture falls.
const val AccentPeak = 0.45f

// The pose of `accent` at `progress`, with travel and growth multiplied by
// `distance` (the motion scale's), so reduced motion keeps icons still.
fun accentPose(accent: IconAccent, progress: Float, distance: Float = 1f): AccentPose {
    if (distance == 0f || progress <= 0f || progress >= 1f) return AccentPose.Rest
    val s = accentStrength(progress) * distance
    return when (accent) {
        IconAccent.Lift -> AccentPose(dy = -3f * s)
        IconAccent.Drop -> AccentPose(dy = 3f * s)
        IconAccent.Raise -> AccentPose(dy = -2f * s, scale = 1f + 0.08f * s)
        IconAccent.SlideForward -> AccentPose(dx = 4f * s)
        IconAccent.SlideBack -> AccentPose(dx = -4f * s)
        IconAccent.Expand -> AccentPose(scale = 1f + 0.15f * s)
        IconAccent.Collapse -> AccentPose(scale = 1f - 0.15f * s)
        IconAccent.Pulse -> AccentPose(scale = 1f + 0.2f * s)
        // Three swings, each smaller than the last.
        IconAccent.Ring -> AccentPose(rotation = 16f * distance * sin(progress * 6f * PI.toFloat()) * (1f - progress))
        IconAccent.SpinStep -> AccentPose(rotation = 360f * distance * OctoEasing.Spring.transform(progress))
    }
}

// An icon's gesture, ready to play. Call `play` when the action happens.
@Stable
class IconAccentState(val accent: IconAccent) {
    internal val progress = Animatable(1f)
    internal var plays by mutableIntStateOf(0)
    internal var distance = 1f

    fun play() {
        plays++
    }

    // The pose right now.
    val pose: AccentPose get() = accentPose(accent, progress.value, distance)
}

// Remembers an icon gesture that follows the motion scale.
@Composable
fun rememberIconAccent(accent: IconAccent): IconAccentState {
    val state = remember(accent) { IconAccentState(accent) }
    val motion = motionScale()
    state.distance = motion.distance
    LaunchedEffect(state, state.plays) {
        if (state.plays == 0) return@LaunchedEffect
        state.progress.snapTo(0f)
        state.progress.animateTo(1f, tween(motion.ms(OctoDuration.Neutral).coerceAtLeast(1), easing = LinearEasing))
    }
    return state
}

// Moves an icon through its gesture. Read while drawing, so it does not
// recompose.
fun Modifier.iconAccent(state: IconAccentState?): Modifier =
    if (state == null) {
        this
    } else {
        graphicsLayer {
            val pose = state.pose
            translationX = pose.dx * density
            translationY = pose.dy * density
            scaleX = pose.scale
            scaleY = pose.scale
            rotationZ = pose.rotation
        }
    }
