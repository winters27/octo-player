package app.winters.octo.desktop.player.wash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.platform.LocalWindowInfo
import app.winters.octo.player.immersive.CoverFade
import app.winters.octo.player.immersive.FrameRate
import app.winters.octo.player.immersive.WashMotion
import app.winters.octo.player.immersive.WashVisibility
import app.winters.octo.player.immersive.frameDue
import app.winters.octo.player.immersive.frameFactor
import app.winters.octo.player.immersive.framesElapsed
import app.winters.octo.player.immersive.targetFps
import org.jetbrains.skia.Image

// How long the wash takes to fade in once its first frame is drawn, and
// how long it waits for that frame before fading in regardless.
private const val FadeInMs = 500
private const val FirstFrameWaitNanos = 1_500_000_000L

// How far out the background starts on the way in, as a share of its size.
const val DollyStart = 0.9f

fun dollyScale(dolly: Float): Float = DollyStart + (1f - DollyStart) * dolly

// The two covers being faded between, and whether a frame has been drawn.
private class Covers {
    var old: Image? = null
    var new: Image? = null
    var drawn = false
}

// The full player's moving background: the cover torn into eight drifting
// copies, warped and blurred into a slow wash of its colours, as on the
// phone. `moving` off holds it still while covers still fade into each
// other. `speed` is the share of the full pace, `bpm` the song's tempo (or
// the base), `fpsLimit` the frame rate it is held to; behind another window
// it drops to 10 frames a second. `dolly` is read while drawing, for the
// way in.
@Composable
fun ImmersiveWash(cover: WashCover?, bpm: Float, fpsLimit: Int, speed: Float, moving: Boolean, dolly: () -> Float, modifier: Modifier = Modifier) {
    val renderer = remember { WashRenderer() }
    val motion = remember { WashMotion() }
    val fade = remember { CoverFade() }
    val covers = remember { Covers() }
    // One more for every frame drawn; only the drawing reads it.
    val frames = remember { mutableIntStateOf(0) }
    val shown = remember { Animatable(0f) }
    var arrivals by remember { mutableIntStateOf(0) }

    LaunchedEffect(cover) {
        val next = cover?.square ?: return@LaunchedEffect
        val current = covers.new
        // Mid-fade, the new fade starts from whichever cover shows most.
        val from = when {
            current == null -> next
            fade.running && fade.mix < 0.5f -> covers.old ?: current
            else -> current
        }
        covers.old = from
        covers.new = next
        renderer.setCovers(from, next)
        fade.start(System.nanoTime() / 1_000_000)
        arrivals++
    }

    // Fades in on the first frame drawn, or after 1.5 s if that is slow.
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        var now = start
        while (!covers.drawn && now - start < FirstFrameWaitNanos) now = withFrameNanos { it }
        shown.animateTo(1f, tween(FadeInMs))
    }

    val focused = LocalWindowInfo.current.isWindowFocused
    val target by rememberUpdatedState(targetFps(if (focused) WashVisibility.Focused else WashVisibility.Unfocused, fpsLimit, powerSave = false))
    val tempo by rememberUpdatedState(bpm)
    val pace by rememberUpdatedState(speed)
    val rate = remember { FrameRate(target) }

    // The one clock, while there is something to move: the motion, or a
    // cover fading in.
    LaunchedEffect(moving, arrivals) {
        var last = withFrameNanos { it }
        frames.intValue++
        while (moving || fade.running) {
            withFrameNanos { now ->
                val elapsed = now - last
                if (frameDue(elapsed, rate.limit)) {
                    last = now
                    val limit = rate.ease(target)
                    if (moving) motion.step(frameFactor(tempo, limit) * framesElapsed(elapsed, limit) * pace, tempo, elapsed / 1e9f)
                    fade.advance(elapsed / 1e6f)
                    frames.intValue++
                }
            }
        }
    }

    Spacer(
        modifier
            .fillMaxSize()
            .drawBehind {
                frames.intValue
                val zoom = dollyScale(dolly())
                drawIntoCanvas { canvas ->
                    if (renderer.draw(canvas.skiaCanvas, size.width, size.height, motion, fade.mix, zoom, shown.value)) covers.drawn = true
                }
            },
    )
}
