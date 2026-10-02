package app.winters.octo.desktop.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import app.winters.octo.design.FrameSize
import app.winters.octo.design.OctoColors
import app.winters.octo.design.Space

// How Octo's octopus comes in: it rises a little and grows to its size as
// it fades in, settling on a soft spring rather than stopping dead.
private const val GROW_FROM = 0.88f
private val Rise = spring<Float>(dampingRatio = 0.8f, stiffness = 160f)

// With calm motion it only fades in, quickly.
private val CalmFade = tween<Float>(durationMillis = 180, easing = LinearOutSlowInEasing)

// Far enough in to call it there: the app may be made from here on.
const val OPENING_ENTERED_AT = 0.95f

// How it leaves once the app has drawn: the whole opening fades away over
// the app, the octopus growing a touch as it goes.
const val OPENING_LEAVE_MS = 240
private const val GROW_TO = 1.04f

// The window's first frames, while the app behind it gets ready: the page
// colour the app then draws over, so nothing flashes, and Octo's octopus
// coming in. `onEntered` says when it is far enough in that the app may be
// made (making it keeps the window's thread busy, which would freeze the
// octopus part way). Once `leaving`, it waits for the app to draw, fades
// away over it and says so with `onGone`.
@Composable
fun Opening(
    mark: Painter?,
    calm: Boolean,
    leaving: Boolean = false,
    onEntered: () -> Unit = {},
    onGone: () -> Unit = {},
) {
    val entered by rememberUpdatedState(onEntered)
    val gone by rememberUpdatedState(onGone)
    val shown = remember { Animatable(0f) }
    val left = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        var told = false
        shown.animateTo(1f, if (calm) CalmFade else Rise) {
            if (!told && value >= OPENING_ENTERED_AT) {
                told = true
                entered()
            }
        }
        if (!told) entered()
    }
    LaunchedEffect(leaving) {
        if (!leaving) return@LaunchedEffect
        // The app's first frames are slow to draw; fading before them would
        // only show the page colour.
        withFrameNanos { }
        withFrameNanos { }
        left.animateTo(1f, tween(if (calm) OPENING_LEAVE_MS / 2 else OPENING_LEAVE_MS, easing = FastOutLinearInEasing))
        gone()
    }
    val rise = with(LocalDensity.current) { Space.M.toPx() }
    Box(
        Modifier.fillMaxSize().graphicsLayer { alpha = 1f - left.value }.background(OctoColors.Background),
        contentAlignment = Alignment.Center,
    ) {
        if (mark != null) {
            Image(
                mark,
                contentDescription = "Octo is opening",
                modifier = Modifier.size(FrameSize.OpeningMark).graphicsLayer {
                    val p = shown.value
                    alpha = (p / 0.6f).coerceIn(0f, 1f)
                    val grow = if (calm) 1f else GROW_FROM + (1f - GROW_FROM) * p
                    val out = 1f + (GROW_TO - 1f) * left.value
                    scaleX = grow * out
                    scaleY = grow * out
                    translationY = if (calm) 0f else (1f - p) * rise
                },
            )
        }
    }
}
