package app.winters.octo.ambient

import app.winters.octo.player.immersive.contrastRatio
import app.winters.octo.theme.AccentChoice
import app.winters.octo.theme.ThemeChoice
import app.winters.octo.theme.alphaArgb
import app.winters.octo.theme.contrastOn
import app.winters.octo.theme.overArgb
import app.winters.octo.theme.resolvePalette
import org.junit.Assert.assertTrue
import org.junit.Test

// Every word on a page reads over the glow behind it, in every theme, at
// both strengths: over the brightest and darkest covers, the desktop's
// riskiest four (each colour of a cover with a disc), and the theme's own
// colours at rest. Main and secondary words at 7:1, muted words and the
// accent, complement and third at 4.5:1.
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

    private val strengths = listOf(AmbientStrength.Subtle, AmbientStrength.Rich)

    // What falls short over `glow` drawn at `alpha` on `theme`'s page.
    private fun shortfalls(theme: ThemeChoice, glow: Int, alpha: Float, what: String): List<String> {
        val p = resolvePalette(theme, AccentChoice.Octo, null, false)
        val under = overArgb(alphaArgb(glow, perMille(alpha)), p.page)
        val roles = listOf(
            Triple("main", contrastRatio(p.text, under), 7.0),
            Triple("secondary", contrastRatio(p.secondary, under), 7.0),
            Triple("muted", contrastOn(p.muted, under), 4.5),
            Triple("accent", contrastRatio(p.accents.accent, under), 4.5),
            Triple("complement", contrastRatio(p.complement, under), 4.5),
            Triple("third", contrastRatio(p.third, under), 4.5),
        )
        return roles.filter { it.second < it.third }.map { (role, ratio, need) -> "$theme $what: $role %.2f < $need".format(ratio) }
    }

    @Test
    fun everyWordReadsOverACoversGlow() {
        val low = ThemeChoice.entries.flatMap { theme ->
            strengths.flatMap { strength ->
                val alpha = ambientAlpha(strength)
                covers.flatMap { (name, cover) -> shortfalls(theme, coverGlow(theme, cover, alpha), alpha, "$name at $strength") }
            }
        }
        assertTrue(low.joinToString("\n"), low.isEmpty())
    }

    @Test
    fun everyWordReadsOverTheThemesGlowAtRest() {
        val low = ThemeChoice.entries.flatMap { theme ->
            val glow = resolvePalette(theme, AccentChoice.Octo, null, false).glow
            strengths.flatMap { strength ->
                val alpha = ambientAlpha(strength)
                restingGlow(theme, glow, alpha).flatMapIndexed { i, color -> shortfalls(theme, color, alpha, "rest $i at $strength") }
            }
        }
        assertTrue(low.joinToString("\n"), low.isEmpty())
    }
}
