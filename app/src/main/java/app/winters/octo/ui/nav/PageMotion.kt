package app.winters.octo.ui.nav

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.IntOffset
import app.winters.octo.design.MotionScale
import app.winters.octo.design.OctoEasing

// How pages move. Going deeper slides the new page in from the right as the
// old one drifts left; going back runs it the other way, so where a page
// came from says where Back leads. A tab is beside the others, not under
// them, so changing tab settles the new one in place instead.

// How far across the screen the arriving page starts, and how far the
// leaving one drifts.
private const val ARRIVE = 0.32f
private const val LEAVE = 0.12f

private const val SLIDE_MS = 380
private const val FADE_OUT_MS = 140
private const val FADE_IN_MS = 180
private const val FADE_IN_DELAY_MS = 40

// Opening a page.
fun pushMotion(scale: MotionScale): ContentTransform = pageMotion(scale, forward = true, OctoEasing.Spring)

// Going back, by the button or a finished back gesture.
fun popMotion(scale: MotionScale): ContentTransform = pageMotion(scale, forward = false, OctoEasing.Spring)

// Going back while the back gesture is held: the same move, run evenly so
// the page sits where the finger has taken it.
fun gestureMotion(scale: MotionScale): ContentTransform = pageMotion(scale, forward = false, LinearEasing)

// Changing tab: the new tab grows a touch into place as the old one goes.
fun tabMotion(scale: MotionScale): ContentTransform =
    (fadeIn(tween(scale.ms(220), delayMillis = scale.ms(40))) + scaleIn(tween(scale.ms(260), easing = OctoEasing.Spring), initialScale = scale.scale(0.97f)))
        .togetherWith(fadeOut(tween(scale.ms(100))))

private fun pageMotion(scale: MotionScale, forward: Boolean, easing: Easing): ContentTransform {
    // Reduced motion: nothing travels, the pages only cross.
    if (scale.still) return fadeIn(tween(scale.ms(FADE_IN_MS))).togetherWith(fadeOut(tween(scale.ms(FADE_OUT_MS))))
    val side = if (forward) 1 else -1
    val slide = tween<IntOffset>(scale.ms(SLIDE_MS), easing = easing)
    val enter = slideInHorizontally(slide) { width -> (width * ARRIVE * side).toInt() } +
        fadeIn(tween(scale.ms(FADE_IN_MS), delayMillis = scale.ms(FADE_IN_DELAY_MS), easing = easing))
    val exit = slideOutHorizontally(slide) { width -> (-width * LEAVE * side).toInt() } +
        fadeOut(tween(scale.ms(FADE_OUT_MS), easing = easing))
    return enter togetherWith exit
}
