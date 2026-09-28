package app.winters.octo.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import app.winters.octo.desktop.player.wash.WashCovers
import app.winters.octo.design.OctoColors
import app.winters.octo.player.immersive.BaseBpm
import app.winters.octo.player.immersive.WashTuning
import app.winters.octo.player.immersive.contrastRatio
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.junit.Assert.assertTrue
import org.junit.Test

// The full player's words read over whatever its background draws: each
// cover's wash is rendered as the full player draws it, moving, and the
// words' colour is checked against every pixel of it, since the title and
// the lyrics can land on any part as the wash drifts.
class PlayerInkTest {
    // A cover of one colour with a disc and a band of others, as the
    // screenshots' made-up covers are.
    private fun cover(ground: Int, disc: Int, band: Int = 0xFF202020.toInt()): Image {
        val surface = Surface.makeRasterN32Premul(300, 300)
        surface.canvas.clear(ground)
        surface.canvas.drawCircle(190f, 170f, 110f, Paint().apply { color = disc })
        surface.canvas.drawRect(Rect.makeXYWH(40f, 200f, 120f, 60f), Paint().apply { color = band })
        return surface.makeImageSnapshot()
    }

    private val covers = mapOf(
        "dark" to cover(0xFF101830.toInt(), 0xFF8A1C3C.toInt()),
        // The screenshots' Karma Police: teal with a navy disc and a red band.
        "teal" to cover(0xFF3AA6A0.toInt(), 0xFF1B2A4A.toInt(), 0xFFC0463F.toInt()),
        "yellow" to cover(0xFFFFE000.toInt(), 0xFFFF8A00.toInt()),
        "white" to cover(0xFFFFFFFF.toInt(), 0xFFF2EAD8.toInt(), 0xFFF2EAD8.toInt()),
        "pale" to cover(0xFFF4EEDC.toInt(), 0xFFEFE4C8.toInt(), 0xFFF8F2E4.toInt()),
        "green" to cover(0xFF00FF40.toInt(), 0xFFB0FF00.toInt()),
    )

    // The lowest contrast between the words and any pixel of the full
    // player's background, over several moments of its drift.
    private fun worstContrast(image: Image, tuning: WashTuning): Pair<Double, Boolean> {
        val prepared = WashCovers.prepared("ink", image, tuning)
        val ink = prepared.playerInk()
        val words = ink.color().toArgb()
        val scene = ImageComposeScene(360, 225, Density(1f)) {
            Box(Modifier.fillMaxSize().background(OctoColors.Background)) {
                PlayerBackdrop(prepared, BaseBpm, fps = 60, speed = 1f, moving = true, dolly = { 1f })
            }
        }
        var worst = Double.MAX_VALUE
        var t = 0L
        repeat(40) { i ->
            val frame = scene.render(t)
            // Past the fade in, then every half second of drift.
            if (i >= 16 && i % 4 == 0) {
                val bitmap = Bitmap.makeFromImage(frame)
                for (y in 0 until bitmap.height step 3) for (x in 0 until bitmap.width step 3) {
                    worst = minOf(worst, contrastRatio(words, bitmap.getColor(x, y)))
                }
            }
            frame.close()
            t += 125_000_000L
        }
        scene.close()
        return worst to ink.dark
    }

    @Test
    fun theWordsReadOverEveryCoversWash() {
        for (tuning in listOf(WashTuning(), WashTuning(contrast = 1f, saturation = 1f, brightnessCap = 1f))) {
            for ((name, image) in covers) {
                val (worst, dark) = worstContrast(image, tuning)
                // The grain moves a pixel by a level either way, so a hair
                // under 4.5 is the same.
                assertTrue("${if (dark) "dark" else "white"} words over the $name wash ($tuning): $worst to 1", worst >= 4.4)
            }
        }
    }

    @Test
    fun aLightAllOverWashGetsDarkWordsAndADarkOneWhite() {
        val uncapped = WashTuning(contrast = 1f, saturation = 1f, brightnessCap = 1f)
        assertTrue(WashCovers.prepared("pale", covers.getValue("pale"), uncapped).playerInk().dark)
        assertTrue(!WashCovers.prepared("dark", covers.getValue("dark"), WashTuning()).playerInk().dark)
        // Teal with a navy disc is light in places and dark in others: white
        // words, and the wash dimmed for them.
        val teal = WashCovers.prepared("teal", covers.getValue("teal"), WashTuning()).playerInk()
        assertTrue(!teal.dark && teal.show < 1f)
    }
}
