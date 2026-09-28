package app.winters.octo.desktop.ui

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import app.winters.octo.desktop.player.wash.WashCovers
import app.winters.octo.desktop.settings.AmbienceMotion
import app.winters.octo.desktop.settings.WashPrefs
import app.winters.octo.design.OctoColors
import app.winters.octo.player.immersive.BaseBpm
import app.winters.octo.player.immersive.WashTuning
import app.winters.octo.player.immersive.contrastRatio
import app.winters.octo.player.immersive.relativeLuminance
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The immersive wash behind the pages: the words stay readable over the
// brightest covers, it moves only when it should, and between frames the
// window is left alone.
class ImmersiveAmbienceTest {
    private fun cover(background: Int, shape: Int): Image {
        val surface = Surface.makeRasterN32Premul(300, 300)
        surface.canvas.clear(background)
        surface.canvas.drawCircle(150f, 150f, 90f, Paint().apply { color = shape })
        surface.canvas.drawRect(Rect.makeXYWH(20f, 240f, 200f, 40f), Paint().apply { color = 0xFF202020.toInt() })
        return surface.makeImageSnapshot()
    }

    // The drawn wash's pixels once it has faded in, at full strength.
    private fun drawn(image: Image, moving: Boolean = true): IntArray {
        val prepared = WashCovers.prepared("t", image, WashTuning())
        val scene = ImageComposeScene(320, 200, Density(1f)) {
            ImmersiveBackdrop(prepared, strength = 1f, bpm = BaseBpm, fps = 30, speed = 0.25f, moving = moving)
        }
        var last: Image? = null
        var t = 0L
        // A tenth of a second a frame, to 1.6 s: past the fades in.
        repeat(17) {
            last?.close()
            last = scene.render(t)
            t += 100_000_000L
        }
        val bitmap = Bitmap.makeFromImage(last!!)
        scene.close()
        return IntArray(bitmap.width * bitmap.height) { bitmap.getColor(it % bitmap.width, it / bitmap.width) }
    }

    @Test
    fun theQuieterWordsReadOverTheBrightestCovers() {
        val accent = OctoColors.TextSecondary.toArgb()
        val covers = mapOf(
            "yellow" to cover(0xFFFFE000.toInt(), 0xFFFF8A00.toInt()),
            "green" to cover(0xFF00FF40.toInt(), 0xFFB0FF00.toInt()),
            "white" to cover(0xFFFFFFFF.toInt(), 0xFFF2EAD8.toInt()),
            "cyan" to cover(0xFF00FFFF.toInt(), 0xFFFFFFFF.toInt()),
        )
        for ((name, image) in covers) {
            val pixels = drawn(image)
            val brightest = pixels.maxBy { relativeLuminance(it) }
            val ratio = contrastRatio(accent, brightest)
            // The grain adds a level either way, so a hair under 4.5 is the same.
            assertTrue("accent words over the $name wash: $ratio to 1", ratio >= 4.4)
        }
    }

    @Test
    fun aDarkCoverShowsAsAWashNotAPlainPage() {
        val pixels = drawn(cover(0xFF101830.toInt(), 0xFF8A1C3C.toInt()))
        val page = OctoColors.Background.toArgb()
        val lifted = pixels.count { contrastRatio(it, page) > 1.3 }
        assertTrue("the wash shows: $lifted of ${pixels.size} pixels lifted", lifted > pixels.size / 2)
    }

    @Test
    fun betweenFramesTheWindowIsLeftAlone() {
        // Held to 30 frames a second on a 60 Hz beat in real time, the wash
        // asks the window for about 30 frames a second, not all 60. With no
        // cover yet its clock runs just the same, and each frame is quick to
        // draw on the processor, as the graphics card draws a real one.
        fun asked(moving: Boolean): Int {
            val scene = ImageComposeScene(160, 100, Density(1f)) {
                ImmersiveBackdrop(null, strength = 0.5f, bpm = BaseBpm, fps = 30, speed = 0.25f, moving = moving)
            }
            val start = System.nanoTime()
            var count = 0
            for (i in 0 until 300) {
                val slot = start + i * 16_666_667L
                while (slot - System.nanoTime() > 2_000_000) Thread.sleep(minOf((slot - System.nanoTime()) / 1_000_000 - 1, 9))
                while (System.nanoTime() < slot) Thread.onSpinWait()
                // The last two seconds, after the wait for a first frame
                // (1.5 s with no cover) and the fade in.
                if (i >= 180 && scene.hasInvalidations()) count++
                scene.render(System.nanoTime() - start).close()
            }
            scene.close()
            return count
        }
        // Asking for every frame would be 120 in two seconds. A busy machine
        // wakes it late now and then, which only makes it fewer.
        val moving = asked(moving = true)
        assertTrue("asked for $moving frames in two seconds", moving in 20..70)
        assertEquals(0, asked(moving = false))
    }

    @Test
    fun itMovesOnlyWhenItShould() {
        fun moves(
            motion: AmbienceMotion = AmbienceMotion.Gentle,
            playing: Boolean = true,
            shown: Boolean = true,
            software: Boolean = false,
            fullPlayer: Boolean = false,
            reduced: Boolean = false,
        ) = ambienceMoves(motion, playing, shown, software, fullPlayer, reduced)
        assertTrue(moves())
        assertTrue(moves(AmbienceMotion.Full))
        assertFalse(moves(AmbienceMotion.Still))
        assertFalse("paused or nothing playing", moves(playing = false))
        assertFalse("minimised or in the tray", moves(shown = false))
        assertFalse("drawn without the graphics card", moves(software = true))
        assertFalse("under the full player", moves(fullPlayer = true))
        assertFalse("motion reduced", moves(reduced = true))
    }

    @Test
    fun thePaceAndFrameRateFollowTheChoices() {
        val wash = WashPrefs(speed = 30, fps = 120)
        assertNull(ambienceSpeed(AmbienceMotion.Still, wash))
        assertEquals(0.15f, ambienceSpeed(AmbienceMotion.Gentle, wash)!!, 1e-6f)
        assertEquals(0.30f, ambienceSpeed(AmbienceMotion.Full, wash)!!, 1e-6f)
        // At most 30 behind the pages, the phone's lowest choice.
        assertEquals(30, ambienceFps(wash))
        assertEquals(30, ambienceFps(WashPrefs(fps = 30)))
    }

    @Test
    fun strengthRunsFromAThirdToNineTenths() {
        assertEquals(0.3f, immersiveOpacity(0f), 1e-6f)
        assertEquals(0.6f, immersiveOpacity(0.5f), 1e-6f)
        assertEquals(0.9f, immersiveOpacity(1f), 1e-6f)
        // With nothing playing, as dim as the glow keeps Octo's colours.
        assertEquals(0.2f, quietOpacity(0f), 1e-6f)
        assertEquals(0.5f, quietOpacity(1f), 1e-6f)
    }
}
