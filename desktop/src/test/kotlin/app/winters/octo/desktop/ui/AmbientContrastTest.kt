package app.winters.octo.desktop.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import app.winters.octo.desktop.player.wash.WashCover
import app.winters.octo.desktop.player.wash.WashCovers
import app.winters.octo.desktop.settings.AmbienceStyle
import app.winters.octo.desktop.settings.Appearance
import app.winters.octo.player.immersive.WashTuning
import app.winters.octo.player.immersive.contrastRatio
import app.winters.octo.player.immersive.overlay
import app.winters.octo.theme.AccentChoice
import app.winters.octo.theme.ThemeChoice
import app.winters.octo.theme.contrastOn
import app.winters.octo.theme.resolvePalette
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Surface
import org.junit.Assert.assertTrue
import org.junit.Test

// Every word on a page reads over the window's colors as they are drawn
// behind it, in every theme: the cover's glow at the usual and the
// strongest strength, the immersive wash at its strongest (0.9), and the
// stand-in colors while nothing plays, each with the light themes' veil
// and the page's shade. Main and secondary words at 7:1, muted words and
// the accent, complement and third at 4.5:1, over both the brightest and
// the darkest color of each.
class AmbientContrastTest {
    private fun cover(background: Int, disc: Int = background): Image {
        val surface = Surface.makeRasterN32Premul(300, 300)
        surface.canvas.clear(background)
        surface.canvas.drawCircle(150f, 150f, 90f, Paint().apply { color = disc })
        return surface.makeImageSnapshot()
    }

    private val covers: Map<String, WashCover> by lazy {
        mapOf(
            "black" to cover(0xFF000000.toInt()),
            "white" to cover(0xFFFFFFFF.toInt()),
            "red" to cover(0xFFFF0000.toInt()),
            "green" to cover(0xFF00FF00.toInt()),
            "blue" to cover(0xFF0000FF.toInt()),
            "yellow" to cover(0xFFFFFF00.toInt()),
            "yellow disc" to cover(0xFFFFE000.toInt(), 0xFFFF8A00.toInt()),
            "white disc" to cover(0xFFFFFFFF.toInt(), 0xFFF2EAD8.toInt()),
            "green disc" to cover(0xFF00FF40.toInt(), 0xFFB0FF00.toInt()),
            "dark disc" to cover(0xFF101830.toInt(), 0xFF8A1C3C.toInt()),
        ).mapValues { (name, image) -> WashCovers.prepared(name, image, WashTuning()) }
    }

    private val strengths = listOf(0.5f, 1f)

    // What falls short over each of `patches` shown at `opacity` on the
    // page, under `veil` of the page color and then `shade`.
    private fun shortfalls(words: PageWords, what: String, patches: List<Int>, opacity: Float, veil: Float, shade: PageShade): List<String> =
        patches.flatMap { patch ->
            val washed = overlay(words.page, veil, overlay(patch, opacity, words.page))
            val under = overlay(shade.color.toArgb(), shade.most, washed)
            val roles = listOf(
                Triple("main", contrastOn(words.text, under), 7.0),
                Triple("secondary", contrastOn(words.secondary, under), 7.0),
                Triple("muted", contrastOn(words.muted, under), 4.5),
                Triple("accent", contrastRatio(words.accents[0], under), 4.5),
                Triple("complement", contrastRatio(words.accents[1], under), 4.5),
                Triple("third", contrastRatio(words.accents[2], under), 4.5),
            )
            roles.filter { it.second < it.third }.map { (role, ratio, need) -> "$what over %08X: $role %.2f < $need".format(patch, ratio) }
        }

    private fun everyTheme(check: (ThemeChoice, PageWords) -> List<String>) {
        val low = ThemeChoice.entries.flatMap { theme -> check(theme, pageWords(resolvePalette(theme, AccentChoice.Octo, null, false))) }
        assertTrue(low.joinToString("\n"), low.isEmpty())
    }

    @Test
    fun everyWordReadsOverTheImmersiveWashAtItsStrongest() = everyTheme { theme, words ->
        val look = Appearance(ambience = AmbienceStyle.Immersive, glowStrength = 1f)
        covers.flatMap { (name, cover) ->
            val opacity = cover.pageOpacity(1f, Color(words.page), Color(words.secondary))
            val veil = words.veil(cover.washPatches, immersiveOpacity(1f))
            shortfalls(words, "$theme $name immersive", listOf(cover.peak, cover.darkest), opacity, veil, shadeFor(look, cover, playing = true, words))
        }
    }

    @Test
    fun everyWordReadsOverACoversGlow() = everyTheme { theme, words ->
        strengths.flatMap { strength ->
            val look = Appearance(ambience = AmbienceStyle.Glow, glowStrength = strength)
            covers.flatMap { (name, cover) ->
                val veil = words.veil(cover.glowPatches, glowOpacity(1f))
                shortfalls(words, "$theme $name glow $strength", listOf(cover.glowPeak, cover.darkest), glowOpacity(strength), veil, shadeFor(look, cover, playing = true, words))
            }
        }
    }

    @Test
    fun everyWordReadsOverTheStandInColours() = everyTheme { theme, words ->
        val glow = resolvePalette(theme, AccentChoice.Octo, null, false).glow
        strengths.flatMap { strength ->
            val cover = quietCover(theme, glow, strength, WashTuning())
            AmbienceStyle.entries.flatMap { style ->
                val look = Appearance(ambience = style, glowStrength = strength)
                val opacity = if (style == AmbienceStyle.Immersive) {
                    minOf(cover.pageOpacity(strength, Color(words.page), Color(words.secondary)), quietOpacity(strength))
                } else {
                    quietOpacity(strength)
                }
                val veil = words.veil(cover.washPatches, if (style == AmbienceStyle.Immersive) immersiveOpacity(1f) else glowOpacity(1f))
                shortfalls(words, "$theme at rest $style $strength", listOf(cover.peak, cover.darkest), opacity, veil, shadeFor(look, cover, playing = false, words))
            }
        }
    }
}
