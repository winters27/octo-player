package app.winters.octo.design

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur

// The frame's material: the title bar, sidebar, side panel and player are
// one sheet of dark glass over the window's colours, meeting at hairlines.
// Unlike a glaze it has no lit rim of its own, so the pieces read as one
// frame rather than four floating panels. Dark enough that bright artwork
// behind never washes out the words on it; clear enough that the colour
// still shows.
val ChromeFilm: Color @Composable @ReadOnlyComposable get() = glassFilm(0.46f)

// The hairline where the frame meets the page.
val ChromeEdge: Color @Composable @ReadOnlyComposable get() = glassRim(0.07f)

@Composable
private fun ChromeBlur(): HazeBlurStyle {
    val page = OctoColors.Background
    val card = OctoColors.BackgroundTertiary
    return remember(page, card) {
        HazeBlurStyle {
            backgroundColor(page)
            blurRadius(backdropBlur(20f))
            noiseFactor(0f)
            colorEffects(listOf(HazeColorEffect.colorFilter(ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1.2f) }))))
            fallbackColorEffect(HazeColorEffect.tint(card))
        }
    }
}

// Frosts what is behind (the window's colours) and lays the dark film on
// it. With no backdrop, the film alone.
@Composable
fun Modifier.chromeFilm(backdrop: HazeState?): Modifier = this
    .then(if (backdrop != null) Modifier.hazeBlur(input = HazeInput.Backdrop(backdrop), style = ChromeBlur()) else Modifier)
    .background(ChromeFilm)
