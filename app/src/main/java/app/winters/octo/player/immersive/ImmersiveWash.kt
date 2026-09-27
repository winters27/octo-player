package app.winters.octo.player.immersive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle

// How long the wash takes to fade in once its first frame is drawn, and
// how long it waits for that frame before fading in regardless.
private const val FadeInMs = 500
private const val FirstFrameWaitNanos = 1_500_000_000L

// The moving wash, on phones that can draw it; the still artwork on
// others. `moving` off freezes the motion (a still wash) while covers
// still fade into each other.
@Composable
fun ImmersiveWash(cover: WashCover?, bpm: Float, fpsLimit: Int, speed: Float, moving: Boolean, dolly: () -> Float) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        WashCanvas(cover, bpm, fpsLimit, speed, moving, dolly)
    } else {
        StillArtwork(cover, dolly)
    }
}

// The two covers being faded between, and whether a frame has been drawn.
private class WashCovers {
    var old: Bitmap? = null
    var new: Bitmap? = null
    var drawn = false
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun WashCanvas(cover: WashCover?, bpm: Float, fpsLimit: Int, speed: Float, moving: Boolean, dolly: () -> Float) {
    val renderer = remember { WashRenderer() }
    DisposableEffect(renderer) { onDispose { renderer.release() } }
    val motion = remember { WashMotion() }
    val fade = remember { CoverFade() }
    val covers = remember { WashCovers() }
    // One more for every frame drawn. Only the drawing reads it, so a new
    // frame redraws this layer and nothing is recomposed.
    val frames = remember { mutableIntStateOf(0) }
    val shown = remember { Animatable(0f) }
    // Changes when a new cover arrives, to wake the clock for its fade.
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
        fade.start(SystemClock.uptimeMillis())
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
    val powerSave = rememberPowerSave()
    val target by rememberUpdatedState(
        targetFps(if (focused) WashVisibility.Focused else WashVisibility.Unfocused, fpsLimit, powerSave),
    )
    val tempo by rememberUpdatedState(bpm)
    val pace by rememberUpdatedState(speed)
    val rate = remember { FrameRate(target) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // The one clock. It runs while the player is in view (hidden or with
    // the screen off it stops, as the app is no longer started) and while
    // there is something to move: the motion, or a cover fading in.
    LaunchedEffect(lifecycle, moving, arrivals) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
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
    }

    Spacer(
        Modifier
            .fillMaxSize()
            .drawBehind {
                frames.intValue
                val zoom = dollyScale(dolly())
                drawIntoCanvas { canvas ->
                    val drew = renderer.draw(
                        canvas.nativeCanvas,
                        size.width.toInt(),
                        size.height.toInt(),
                        motion,
                        fade.mix,
                        zoom,
                        shown.value,
                    )
                    if (drew) covers.drawn = true
                }
            },
    )
}

// Whether battery saver is on, kept up to date while shown.
@Composable
private fun rememberPowerSave(): Boolean {
    val context = LocalContext.current
    val power = remember(context) { context.getSystemService(PowerManager::class.java) }
    var on by remember { mutableStateOf(power?.isPowerSaveMode == true) }
    DisposableEffect(context, power) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                on = power?.isPowerSaveMode == true
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { context.unregisterReceiver(receiver) }
    }
    return on
}
