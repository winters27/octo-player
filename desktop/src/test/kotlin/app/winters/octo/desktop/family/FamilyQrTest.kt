package app.winters.octo.desktop.family

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.winters.octo.design.OctoColors
import app.winters.octo.design.ProvideOctoTheme
import app.winters.octo.theme.AccentChoice
import app.winters.octo.theme.ThemeChoice
import app.winters.octo.theme.resolvePalette
import org.jetbrains.skia.Bitmap
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.swing.SwingUtilities

// The family QR code stays black on white in every theme, so a phone's
// camera reads it off any page: nothing in it takes the theme's colors.
class FamilyQrTest {
    private fun drawn(theme: ThemeChoice): Bitmap {
        lateinit var bitmap: Bitmap
        SwingUtilities.invokeAndWait {
            val scene = ImageComposeScene(320, 320, Density(1f)) {
                ProvideOctoTheme(resolvePalette(theme, AccentChoice.Octo, null, false)) {
                    Box(Modifier.fillMaxSize().background(OctoColors.Background).padding(40.dp)) {
                        QrImage("https://music.example.com/join/abc123", side = 220.dp)
                    }
                }
            }
            try {
                bitmap = Bitmap.makeFromImage(scene.render(0L))
            } finally {
                scene.close()
            }
        }
        return bitmap
    }

    @Test
    fun theCodeIsBlackOnWhiteInEveryTheme() {
        for (theme in ThemeChoice.entries) {
            val picture = drawn(theme)
            // The box round every pure black pixel, and how much of it is
            // pure black and pure white.
            var left = picture.width
            var top = picture.height
            var right = -1
            var bottom = -1
            for (y in 0 until picture.height) for (x in 0 until picture.width) {
                if (picture.getColor(x, y) == 0xFF000000.toInt()) {
                    left = minOf(left, x)
                    right = maxOf(right, x)
                    top = minOf(top, y)
                    bottom = maxOf(bottom, y)
                }
            }
            assertTrue("${theme.label}: no pure black modules", right >= 0)
            // Inside, only black, white and the grays where a module's edge
            // falls between pixels; round it, a quiet edge of pure white.
            var black = 0
            var white = 0
            var tinted = 0
            for (y in top..bottom) for (x in left..right) {
                val c = picture.getColor(x, y)
                when {
                    c == 0xFF000000.toInt() -> black++
                    c == 0xFFFFFFFF.toInt() -> white++
                    (c ushr 24) != 0xFF || (c shr 16 and 0xFF) != (c and 0xFF) || (c shr 8 and 0xFF) != (c and 0xFF) -> tinted++
                }
            }
            var quiet = 0
            for (x in left - 4..right + 4) for (y in listOf(top - 4, bottom + 4)) if (picture.getColor(x, y) != 0xFFFFFFFF.toInt()) quiet++
            for (y in top - 4..bottom + 4) for (x in listOf(left - 4, right + 4)) if (picture.getColor(x, y) != 0xFFFFFFFF.toInt()) quiet++
            val total = (right - left + 1) * (bottom - top + 1)
            val side = minOf(right - left, bottom - top) + 1
            val summary = "${theme.label}: box $side px, black $black, white $white, tinted $tinted of $total, quiet edge off white $quiet"
            println("QR $summary")
            assertTrue(summary, side >= 150 && black >= total / 5 && white >= total / 5 && tinted == 0 && quiet == 0)
        }
    }
}
