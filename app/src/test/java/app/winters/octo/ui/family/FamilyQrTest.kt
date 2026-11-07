package app.winters.octo.ui.family

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.winters.octo.design.OctoColors
import app.winters.octo.design.ProvideOctoTheme
import app.winters.octo.theme.AccentChoice
import app.winters.octo.theme.ThemeChoice
import app.winters.octo.theme.resolvePalette
import java.time.Duration
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// The family QR code stays black on white in every theme, so a phone's
// camera reads it off any page: nothing in it takes the theme's colors.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-mdpi")
class FamilyQrTest {
    private fun drawn(theme: ThemeChoice): Bitmap {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            ProvideOctoTheme(resolvePalette(theme, AccentChoice.Octo, null, false)) {
                Box(Modifier.fillMaxSize().background(OctoColors.Background).padding(40.dp)) {
                    QrTile("https://music.example.com/join/abc123", "Join code", 220.dp, Modifier)
                }
            }
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        // The page alone, without the system's bars round it.
        val view = activity.findViewById<android.view.View>(android.R.id.content)
        return Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
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
                if (picture.getPixel(x, y) == 0xFF000000.toInt()) {
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
            var tint: Pair<Int, Int>? = null
            for (y in top..bottom) for (x in left..right) {
                val c = picture.getPixel(x, y)
                when {
                    c == 0xFF000000.toInt() -> black++
                    c == 0xFFFFFFFF.toInt() -> white++
                    (c ushr 24) != 0xFF || (c shr 16 and 0xFF) != (c and 0xFF) || (c shr 8 and 0xFF) != (c and 0xFF) -> {
                        tinted++
                        if (tint == null) tint = c to x * 10_000 + y
                    }
                }
            }
            var quiet = 0
            var off: Int? = null
            fun edge(x: Int, y: Int) {
                val c = picture.getPixel(x, y)
                if (c != 0xFFFFFFFF.toInt()) {
                    quiet++
                    if (off == null) off = c
                }
            }
            for (x in left - 4..right + 4) for (y in listOf(top - 4, bottom + 4)) edge(x, y)
            for (y in top - 4..bottom + 4) for (x in listOf(left - 4, right + 4)) edge(x, y)
            val total = (right - left + 1) * (bottom - top + 1)
            val side = minOf(right - left, bottom - top) + 1
            val summary = "${theme.label}: box $side px at $left,$top, black $black, white $white, tinted $tinted of $total" +
                " (first ${tint?.let { Integer.toHexString(it.first) + " at " + it.second / 10_000 + "," + it.second % 10_000 }}), quiet edge off white $quiet (first ${off?.let(Integer::toHexString)})"
            println("QR $summary")
            assertTrue(summary, side >= 150 && black >= total / 5 && white >= total / 5 && tinted == 0 && quiet == 0)
        }
    }
}
