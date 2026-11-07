package app.winters.octo.ambient

import app.winters.octo.player.immersive.contrastRatio
import app.winters.octo.theme.AccentChoice
import app.winters.octo.theme.ThemeChoice
import app.winters.octo.theme.alphaArgb
import app.winters.octo.theme.contrastOn
import app.winters.octo.theme.mixArgb
import app.winters.octo.theme.overArgb
import app.winters.octo.theme.resolvePalette
import org.junit.Assert.assertTrue
import org.junit.Test

// Every word on a page reads over the glow behind it, in every theme, with
// every accent, at both strengths: over the brightest and darkest covers,
// the desktop's riskiest four (each color of a cover with a disc), and the
// theme's own colors at rest, and where two colors of a glow blend half and
// half. Main and secondary words at 7:1, muted words, the theme's accent,
// complement and third, and the accent drawn at 4.5:1.
class AmbientContrastTest {
    private val covers = mapOf(
        "black" to 0xFF000000.toInt(),
        "white" to 0xFFFFFFFF.toInt(),
        "red" to 0xFFFF0000.toInt(),
        "green" to 0xFF00FF00.toInt(),
        "blue" to 0xFF0000FF.toInt(),
        "yellow" to 0xFFFFFF00.toInt(),
        "yellow cover" to 0xFFFFE000.toInt(),
        "yellow cover disc" to 0xFFFF8A00.toInt(),
        "white cover" to 0xFFFFFFFF.toInt(),
        "white cover disc" to 0xFFF2EAD8.toInt(),
        "green cover" to 0xFF00FF40.toInt(),
        "green cover disc" to 0xFFB0FF00.toInt(),
        "dark cover" to 0xFF101830.toInt(),
        "dark cover disc" to 0xFF8A1C3C.toInt(),
    )

    // Covers' four mesh colors, the brightest and busiest mixes.
    private val meshes = mapOf(
        "white and black" to listOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt()),
        "yellow and blue" to listOf(0xFFFFE000.toInt(), 0xFF0000FF.toInt(), 0xFFFF8A00.toInt(), 0xFFF2EAD8.toInt()),
        "green" to listOf(0xFF00FF40.toInt(), 0xFFB0FF00.toInt(), 0xFF101830.toInt(), 0xFF8A1C3C.toInt()),
    )

    private val strengths = listOf(AmbientStrength.Subtle, AmbientStrength.Rich)

    // The accent each choice draws in `theme`, with `art` as the cover.
    private fun accentOf(theme: ThemeChoice, choice: AccentChoice, art: Int): Int = resolvePalette(theme, choice, art, false).accents.accent

    // What falls short over `glow` drawn at `alpha` on `theme`'s page.
    private fun shortfalls(theme: ThemeChoice, glow: Int, alpha: Float, accent: Int, what: String): List<String> {
        val p = resolvePalette(theme, AccentChoice.Octo, null, false)
        val under = overArgb(alphaArgb(glow, perMille(alpha)), p.page)
        val roles = listOf(
            Triple("main", contrastRatio(p.text, under), 7.0),
            Triple("secondary", contrastRatio(p.secondary, under), 7.0),
            Triple("muted", contrastOn(p.muted, under), 4.5),
            Triple("theme accent", contrastRatio(p.accents.accent, under), 4.5),
            Triple("complement", contrastRatio(p.complement, under), 4.5),
            Triple("third", contrastRatio(p.third, under), 4.5),
            Triple("accent drawn", contrastRatio(accent, under), 4.5),
        )
        return roles.filter { it.second < it.third }.map { (role, ratio, need) -> "$theme $what: $role %.2f < $need".format(ratio) }
    }

    // What falls short over each color of `mesh` and each half and half
    // blend of two of them.
    private fun meshShortfalls(theme: ThemeChoice, mesh: List<Int>, alpha: Float, accent: Int, what: String): List<String> =
        mesh.indices.flatMap { i ->
            mesh.indices.filter { it >= i }.flatMap { j -> shortfalls(theme, mixArgb(mesh[i], mesh[j], 500), alpha, accent, "$what, $i-$j") }
        }

    @Test
    fun everyWordReadsOverACoversGlow() {
        val low = ThemeChoice.entries.flatMap { theme ->
            AccentChoice.entries.flatMap { choice ->
                strengths.flatMap { strength ->
                    val alpha = ambientAlpha(strength)
                    covers.flatMap { (name, cover) ->
                        val accent = accentOf(theme, choice, cover)
                        shortfalls(theme, coverGlow(theme, cover, alpha, accent), alpha, accent, "${choice.label}, $name at $strength")
                    }
                }
            }
        }
        assertTrue(low.joinToString("\n"), low.isEmpty())
    }

    @Test
    fun everyWordReadsWhereACoversColorsBlend() {
        val low = ThemeChoice.entries.flatMap { theme ->
            AccentChoice.entries.flatMap { choice ->
                strengths.flatMap { strength ->
                    val alpha = ambientAlpha(strength)
                    meshes.flatMap { (name, colors) ->
                        val accent = accentOf(theme, choice, colors[0])
                        meshShortfalls(theme, coverMesh(theme, colors, alpha, accent), alpha, accent, "${choice.label}, $name at $strength")
                    }
                }
            }
        }
        assertTrue(low.joinToString("\n"), low.isEmpty())
    }

    @Test
    fun everyWordReadsOverTheThemesGlowAtRest() {
        val low = ThemeChoice.entries.flatMap { theme ->
            val glow = resolvePalette(theme, AccentChoice.Octo, null, false).glow
            AccentChoice.entries.flatMap { choice ->
                val accent = accentOf(theme, choice, 0xFF3A8FB7.toInt())
                strengths.flatMap { strength ->
                    val alpha = ambientAlpha(strength)
                    meshShortfalls(theme, restingGlow(theme, glow, alpha, accent), alpha, accent, "${choice.label}, rest at $strength")
                }
            }
        }
        assertTrue(low.joinToString("\n"), low.isEmpty())
    }
}
