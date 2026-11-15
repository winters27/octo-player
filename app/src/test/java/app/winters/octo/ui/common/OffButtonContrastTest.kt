package app.winters.octo.ui.common

import androidx.compose.ui.graphics.toArgb
import app.winters.octo.design.offButtonLook
import app.winters.octo.design.surfacesOf
import app.winters.octo.theme.AccentChoice
import app.winters.octo.theme.FixedAccents
import app.winters.octo.theme.ThemeChoice
import app.winters.octo.theme.contrastOn
import app.winters.octo.theme.overArgb
import app.winters.octo.theme.resolvePalette
import org.junit.Assert.assertTrue
import org.junit.Test

// A filled button that cannot be used yet reads as off in every theme and
// accent, with the glass tinted or not, on each surface it can sit on (the
// page, a card, a raised row and a sheet): its words read 3:1 or more on its
// pill, as a label, and under the 4.5:1 of words that can be used, and its
// pill stands 1.1 to 1.5 off the surface, so it shows as a button, faintly,
// where a ready button's fill stands well off it.
class OffButtonContrastTest {
    @Test
    fun anOffFilledButtonReadsOffOnEverySurface() {
        val problems = mutableListOf<String>()
        for (theme in ThemeChoice.entries) {
            for (choice in FixedAccents + AccentChoice.Octo) {
                for (tint in listOf(false, true)) {
                    val p = resolvePalette(theme, choice, null, tint)
                    val look = offButtonLook(surfacesOf(p))
                    val fill = look.fill.toArgb()
                    val words = look.words.toArgb()
                    val layers = listOf("page" to p.page, "card" to p.card, "raised" to p.raised, "sheet" to overArgb(p.sheet, p.page))
                    for ((name, layer) in layers) {
                        val where = "${theme.label} ${choice.label}${if (tint) " tinted" else ""} on the $name"
                        val read = contrastOn(words, overArgb(fill, layer))
                        val step = contrastOn(fill, layer)
                        if (read < 3.0 || read >= 4.5) problems += "$where: words $read:1"
                        if (step < 1.1 || step > 1.5) problems += "$where: pill $step:1 off it"
                    }
                }
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}
