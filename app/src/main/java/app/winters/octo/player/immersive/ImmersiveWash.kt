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

// Whether a frame has been drawn yet.
private class FirstFrame {
    var drawn = false
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun WashCanvas(cover: WashCover?, bpm: Float, fpsLimit: Int, speed: Float, moving: Boolean, dolly: () -> Float) {
    val renderer = remember { WashRenderer() }
    DisposableEffect(renderer) { onDispose { renderer.release() } }
    val covers = remember { CoverSwap<Bitmap>() }
    val first = remember { FirstFrame() }
    // One more for every frame drawn. Only the drawing reads it, so a new
    // frame redraws this layer and nothing is recomposed.
    val frames = remember { mutableIntStateOf(0) }
    val shown = remember { Animatable(0f) }
    // Changes when a new cover arrives, to wake the clock for its fade.
    var arrivals by remember { mutableIntStateOf(0) }

    val focused = LocalWindowInfo.current.isWindowFocused
    val powerSave = rememberPowerSave()
    val target by rememberUpdatedState(
        targetFps(if (focused) WashVisibility.Focused else WashVisibility.Unfocused, fpsLimit, powerSave),
    )
    val tempo by rememberUpdatedState(bpm)
    val pace by rememberUpdatedState(speed)
    val clock = remember { WashClock(target) }

    LaunchedEffect(cover) {
        val next = cover?.square ?: return@LaunchedEffect
        val (from, to) = covers.arrive(next, clock.fade, SystemClock.uptimeMillis())
        renderer.setCovers(from, to)
        arrivals++
    }

    // Fades in on the first frame drawn, or after 1.5 s if that is slow.
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        var now = start
        while (!first.drawn && now - start < FirstFrameWaitNanos) now = withFrameNanos { it }
        shown.animateTo(1f, tween(FadeInMs))
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // The one clock. It runs while the player is in view (hidden or with
    // the screen off it stops, as the app is no longer started) and while
    // there is something to move: the motion, or a cover fading in.
    LaunchedEffect(lifecycle, moving, arrivals) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            clock.start(withFrameNanos { it })
            frames.intValue++
            while (clock.busy(moving)) {
                withFrameNanos { now ->
                    if (clock.tick(now, target, tempo, pace, moving)) frames.intValue++
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
                        clock.motion,
                        clock.fade.mix,
                        zoom,
                        shown.value,
                    )
                    if (drew) first.drawn = true
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
