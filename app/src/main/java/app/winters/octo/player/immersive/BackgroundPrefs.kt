package app.winters.octo.player.immersive

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import app.winters.octo.player.PlayerColors
import kotlinx.serialization.Serializable

// What fills the full player behind the lyrics and controls.
enum class BackgroundMode {
    // The artwork torn into drifting copies, warped and blurred into a
    // slow wash of its colours.
    Default,

    // The artwork, blurred and still.
    Artwork,

    // A flat fill of the artwork's main colour.
    Colour,

    // The earlier moving mesh of four colours.
    Classic,
}

// The background's settings. The brightness cap, saturation and drift speed
// are percentages, the contrast a factor, and the frame rate a limit.
@Serializable
data class BackgroundPrefs(
    val mode: BackgroundMode = BackgroundMode.Default,
    val brightnessCap: Int = 50,
    val saturation: Int = 180,
    val contrast: Float = 1.3f,
    val useBpm: Boolean = true,
    val fps: Int = 60,
    // How fast it drifts, as a share of the full pace. Low by default, so it
    // reads as a slow ambience rather than an animation.
    val speed: Int = 25,
) {
    // Held to the ranges the settings offer, for values read back.
    fun sane(): BackgroundPrefs = copy(
        brightnessCap = brightnessCap.coerceIn(BrightnessCapRange),
        saturation = saturation.coerceIn(SaturationRange),
        contrast = contrast.coerceIn(ContrastRange),
        fps = fps.takeIf { it in FpsChoices } ?: 60,
        speed = speed.coerceIn(SpeedRange),
    )

    val tuning: WashTuning get() = WashTuning(contrast, saturation / 100f, brightnessCap / 100f)

    // Whether the background is drawn from the prepared cover, so the
    // adjustments and the light or dark words apply.
    val prepared: Boolean get() = mode != BackgroundMode.Classic
}

val BrightnessCapRange = 20..100
val SaturationRange = 0..300
val ContrastRange = 0.5f..2f
val FpsChoices = listOf(30, 60, 90, 120)
val SpeedRange = 5..100

// The player's colours with the words' colour decided for this background:
// the light or dark test on the adjusted main colour, or white over the
// classic mesh.
fun PlayerColors.over(prefs: BackgroundPrefs): PlayerColors =
    copy(content = if (prefs.prepared) washContent(dominant?.toArgb(), prefs.tuning) else Color.White)
