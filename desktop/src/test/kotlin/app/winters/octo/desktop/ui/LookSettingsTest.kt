package app.winters.octo.desktop.ui

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import app.winters.octo.design.ProvideOctoTheme
import app.winters.octo.desktop.player.wash.WashCovers
import app.winters.octo.desktop.settings.AmbienceMotion
import app.winters.octo.desktop.settings.AmbienceStyle
import app.winters.octo.desktop.settings.Appearance
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.desktop.settings.WashPrefs
import app.winters.octo.player.immersive.BaseBpm
import app.winters.octo.player.immersive.WashTuning
import app.winters.octo.theme.AccentChoice
import app.winters.octo.theme.ThemeChoice
import app.winters.octo.theme.resolvePalette
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// A theme supplies colours only: every look setting draws as set in every
// theme, and picking a theme writes none of them.
class LookSettingsTest {
    @get:Rule val folder = TemporaryFolder()

    // The look with every theme field set to `theme`, the rest as given.
    private fun Appearance.inTheme(theme: ThemeChoice) = copy(theme = theme, themeLight = if (theme.light) theme else themeLight, themeDark = if (theme.light) themeDark else theme)

    @Test
    fun glowOffDrawsNoGlowInAnyTheme() {
        for (theme in ThemeChoice.entries) for (style in AmbienceStyle.entries) for (cover in listOf("c", null)) {
            val off = Appearance(ambientGlow = false, ambience = style).inTheme(theme)
            assertEquals("$theme $style $cover", WindowGlow.None, windowGlow(off, signedIn = true, cover))
            val words = pageWords(resolvePalette(theme, AccentChoice.Octo, null, false))
            val made = WashCovers.prepared("off", null, WashTuning())
            assertEquals("$theme $style shade", PageShade.None, shadeFor(off, made, playing = cover != null, words))
        }
    }

    @Test
    fun eachStyleDrawsAsChosenInEveryTheme() {
        for (theme in ThemeChoice.entries) {
            val glow = Appearance(ambience = AmbienceStyle.Glow).inTheme(theme)
            val immersive = Appearance(ambience = AmbienceStyle.Immersive).inTheme(theme)
            assertEquals(WindowGlow.Cover, windowGlow(glow, true, "c"))
            assertEquals(WindowGlow.Quiet, windowGlow(glow, true, null))
            assertEquals(WindowGlow.Immersive, windowGlow(immersive, true, "c"))
            assertEquals(WindowGlow.Immersive, windowGlow(immersive, true, null))
            assertEquals(WindowGlow.SignIn, windowGlow(glow, false, null))
        }
    }

    @Test
    fun theStrengthScalesAsItAlwaysHas() {
        assertEquals(0.31f, glowOpacity(0.5f), 1e-6f)
        assertEquals(0.5f, glowOpacity(1f), 1e-6f)
        assertEquals(0.35f, quietOpacity(0.5f), 1e-6f)
        assertEquals(0.5f, quietOpacity(1f), 1e-6f)
        assertEquals(0.6f, immersiveOpacity(0.5f), 1e-6f)
        assertEquals(0.9f, immersiveOpacity(1f), 1e-6f)
    }

    @Test
    fun theStandInColoursNeverMove() {
        // Nothing plays while they show, so with any motion and with motion
        // reduced or not, they hold still.
        for (motion in AmbienceMotion.entries) for (reduced in listOf(false, true)) {
            assertFalse("$motion reduced $reduced", ambienceMoves(motion, playing = false, shown = true, software = false, fullPlayer = false, reduced = reduced))
        }
        assertFalse(ambienceMoves(AmbienceMotion.Full, playing = true, shown = true, software = false, fullPlayer = false, reduced = true))
        assertNull(ambienceSpeed(AmbienceMotion.Still, WashPrefs()))
    }

    @Test
    fun stillImmersiveDrawsNoFramesInAnyTheme() {
        val surface = Surface.makeRasterN32Premul(300, 300)
        surface.canvas.clear(0xFFFFE000.toInt())
        surface.canvas.drawCircle(150f, 150f, 90f, Paint().apply { color = 0xFF2040FF.toInt() })
        val prepared = WashCovers.prepared("still", surface.makeImageSnapshot(), WashTuning())
        val moving = ambienceMoves(AmbienceMotion.Still, playing = true, shown = true, software = false, fullPlayer = false, reduced = false)
        val speed = ambienceSpeed(AmbienceMotion.Still, WashPrefs()) ?: 0f
        for (theme in ThemeChoice.entries) {
            val palette = resolvePalette(theme, AccentChoice.Octo, null, false)
            val scene = ImageComposeScene(160, 100, Density(1f)) {
                ProvideOctoTheme(palette) {
                    ImmersiveBackdrop(prepared, strength = 1f, bpm = BaseBpm, fps = 30, speed = speed, moving = moving)
                }
            }
            val start = System.nanoTime()
            var asked = 0
            // Two and a half seconds to fade in and settle, then a second
            // watched, at 60 frames a second in real time.
            for (i in 0 until 210) {
                val slot = start + i * 16_666_667L
                while (slot - System.nanoTime() > 2_000_000) Thread.sleep(minOf((slot - System.nanoTime()) / 1_000_000 - 1, 9))
                while (System.nanoTime() < slot) Thread.onSpinWait()
                if (i >= 150 && scene.hasInvalidations()) asked++
                scene.render(System.nanoTime() - start).close()
            }
            scene.close()
            assertEquals("$theme frames asked for while still", 0, asked)
        }
    }

    @Test
    fun pickingAThemeWritesNoLookSetting() {
        val store = SettingsStore(folder.newFile("settings.json"))
        store.update {
            it.copy(
                appearance = it.appearance.copy(
                    ambientGlow = false,
                    glowStrength = 0.8f,
                    calmMotion = true,
                    ambience = AmbienceStyle.Immersive,
                    ambienceMotion = AmbienceMotion.Still,
                    wash = WashPrefs(speed = 30, fps = 60),
                    textSize = 130,
                ),
            )
        }
        fun lookOnly(a: Appearance) = a.copy(theme = ThemeChoice.Octo, themeLight = ThemeChoice.Daylight, themeDark = ThemeChoice.Octo, themeMatch = false)
        val before = lookOnly(store.current.appearance)
        for (theme in ThemeChoice.entries) {
            store.pickTheme(theme)
            assertEquals(theme, store.current.appearance.theme)
            assertEquals("after picking $theme", before, lookOnly(store.current.appearance))
        }
        for (on in listOf(true, false)) for (dark in listOf(true, false)) {
            store.setThemeMatch(on, dark)
            assertEquals("after Match $on", before, lookOnly(store.current.appearance))
        }
    }
}
