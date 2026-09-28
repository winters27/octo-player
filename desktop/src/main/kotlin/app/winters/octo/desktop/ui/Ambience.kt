package app.winters.octo.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.player.wash.ImmersiveWash
import app.winters.octo.desktop.player.wash.WashCover
import app.winters.octo.desktop.player.wash.WashCovers
import app.winters.octo.design.LocalReduceMotion
import app.winters.octo.design.OctoColors
import app.winters.octo.player.immersive.WashTuning
import app.winters.octo.player.immersive.paceBpm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image

// Octo's own colour, the violet of its icon: the key colour while no song
// gives one.
val OctoKey = Color(0xFF9061FA)

// The colour the glass takes a hint of: the playing song's, or Octo's.
val LocalKeyColour = staticCompositionLocalOf { OctoKey }

// A colour made fit to tint with: the same hue, never too dark or grey to
// show on dark glass.
fun keyColour(argb: Int): Color {
    val hsv = FloatArray(3)
    java.awt.Color.RGBtoHSB(argb shr 16 and 0xFF, argb shr 8 and 0xFF, argb and 0xFF, hsv)
    // A cover with hardly any colour gives Octo's own.
    if (hsv[1] < 0.12f) return OctoKey
    val rgb = java.awt.Color.HSBtoRGB(hsv[0], hsv[1].coerceIn(0.35f, 0.8f), hsv[2].coerceIn(0.75f, 1f))
    return Color(rgb or (0xFF shl 24))
}

// The glass's rim with a fifth of the key colour in it.
fun keyRim(key: Color, alpha: Float = 0.12f): Color = lerp(Color.White, key, 0.2f).copy(alpha = alpha)

// Octo's icon made ready for the wash, once for the whole run.
private object OctoArt {
    @Volatile private var made: WashCover? = null

    fun cover(tuning: WashTuning): WashCover {
        made?.takeIf { it.key == keyFor(tuning) }?.let { return it }
        val image = runCatching {
            AppState::class.java.getResourceAsStream("/octo-icon.png")!!.use { Image.makeFromEncoded(it.readBytes()) }
        }.getOrNull()
        return WashCovers.prepared(keyFor(tuning), image, tuning).also { made = it }
    }

    private fun keyFor(tuning: WashTuning) = "octo|${tuning.contrast}|${tuning.saturation}|${tuning.brightnessCap}"
}

// Octo's own colours as a slow wash, for when there is no cover to show:
// behind the sign-in, and very dim behind the pages while nothing plays.
// It is the full player's background seeded with the app's icon. With
// motion reduced, or `moving` off, it holds still. `veil` is how much of
// the page colour lies over it.
@Composable
fun OctoAmbience(app: AppState, moving: Boolean, veil: Float, modifier: Modifier = Modifier) {
    val settings by app.settings.state.collectAsState()
    val wash = settings.appearance.wash
    val tuning = WashTuning(wash.contrast, wash.saturation / 100f, wash.brightnessCap / 100f)
    val cover by produceState<WashCover?>(null, tuning) { value = withContext(Dispatchers.Default) { OctoArt.cover(tuning) } }
    Box(modifier.fillMaxSize().background(OctoColors.Background)) {
        ImmersiveWash(
            cover,
            paceBpm(null, false),
            // Calm and cheap: a gentle pace at a modest frame rate.
            fpsLimit = minOf(wash.fps, 30),
            speed = 0.12f,
            moving = moving && !LocalReduceMotion.current,
            dolly = { 1f },
        )
        Box(Modifier.fillMaxSize().alpha(veil).background(OctoColors.Background))
    }
}

// The key colour for a prepared cover.
fun WashCover.keyColour(): Color = if (main == 0) OctoKey else keyColour(main)
