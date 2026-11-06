package app.winters.octo.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import app.winters.octo.design.OctoDefaultPalette
import app.winters.octo.design.ProvideOctoTheme
import app.winters.octo.design.surfacesOf
import app.winters.octo.theme.AccentChoice
import app.winters.octo.theme.ResolvedPalette
import app.winters.octo.theme.ThemeChoice
import app.winters.octo.theme.resolvePalette
import javax.swing.SwingUtilities

// Octo's own colors as plain values, for a check made outside composition,
// where the design tokens (read from the theme being drawn) cannot be read.
object OctoLook {
    private val surfaces = surfacesOf(OctoDefaultPalette)
    private val accents = OctoDefaultPalette.accents

    val page = surfaces.page
    val card = surfaces.card
    val text = surfaces.text
    val secondary = surfaces.secondary
    val muted = surfaces.muted
    val accent = Color(accents.accent)
    val accentSelected = Color(accents.selected)
    val onAccent = Color(accents.onAccent)
}

// What `read` gives when composed in `palette`: for a check of the tokens as
// a theme draws them.
fun <T> readIn(palette: ResolvedPalette, read: @Composable () -> T): T {
    var out: Any? = null
    var done = false
    SwingUtilities.invokeAndWait {
        val scene = ImageComposeScene(8, 8, Density(1f)) { ProvideOctoTheme(palette) { out = read(); done = true } }
        try {
            scene.render(System.nanoTime())
        } finally {
            scene.close()
        }
    }
    check(done) { "the tokens were not read" }
    @Suppress("UNCHECKED_CAST")
    return out as T
}

fun <T> readIn(theme: ThemeChoice, accent: AccentChoice = AccentChoice.Octo, read: @Composable () -> T): T =
    readIn(resolvePalette(theme, accent, null, false), read)
