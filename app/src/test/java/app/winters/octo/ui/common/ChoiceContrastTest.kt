package app.winters.octo.ui.common

import app.winters.octo.theme.AccentChoice
import app.winters.octo.theme.FixedAccents
import app.winters.octo.theme.ResolvedPalette
import app.winters.octo.theme.ThemeChoice
import app.winters.octo.theme.contrastOn
import app.winters.octo.theme.resolvePalette
import org.junit.Assert.assertTrue
import org.junit.Test

// The chosen state of every choice control reads against what is behind
// it, in every theme and every accent the app offers.
class ChoiceContrastTest {
    private fun eachThemeAndAccent(check: (name: String, p: ResolvedPalette) -> Unit) {
        assertTrue(FixedAccents.isNotEmpty())
        for (theme in ThemeChoice.entries) {
            for (choice in FixedAccents + AccentChoice.Octo) {
                check("${theme.label} ${choice.label}", resolvePalette(theme, choice, null, false))
            }
        }
    }

    @Test
    fun theChosenSegmentStandsOutFromTheTray() = eachThemeAndAccent { name, p ->
        val ratio = contrastOn(p.accents.accent, p.card)
        assertTrue("$name pill on the tray is $ratio", ratio >= 3.0)
    }

    @Test
    fun theChosenSegmentsWordsReadOnTheAccent() = eachThemeAndAccent { name, p ->
        val ratio = contrastOn(p.accents.onAccent, p.accents.accent)
        assertTrue("$name words on the pill are $ratio", ratio >= 4.5)
    }

    @Test
    fun theChosenMenuRowsWordsReadOnItsTint() = eachThemeAndAccent { name, p ->
        val ratio = contrastOn(p.text, p.accents.selected)
        assertTrue("$name words on the row are $ratio", ratio >= 4.5)
        val small = contrastOn(p.accents.selectedWords, p.accents.selected)
        assertTrue("$name small words on the row are $small", small >= 7.0)
    }

    @Test
    fun theWordsOnAFilledButtonReadOnItsFill() = eachThemeAndAccent { name, p ->
        val ratio = contrastOn(p.accents.onFill, p.accents.fill)
        assertTrue("$name words on the button are $ratio", ratio >= 4.5)
    }
}
