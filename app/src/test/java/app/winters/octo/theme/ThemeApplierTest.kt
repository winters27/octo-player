package app.winters.octo.theme

import android.app.UiModeManager
import app.winters.octo.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ThemeApplierTest {
    @Test
    fun theNightModeFollowsMatchOrTheKindOfTheThemeShown() {
        ThemeChoice.entries.forEach { theme ->
            val expected = if (theme.light) UiModeManager.MODE_NIGHT_NO else UiModeManager.MODE_NIGHT_YES
            assertEquals(theme.name, expected, ThemeApplier.nightModeFor(ThemeSettings(theme = theme)))
            assertEquals(theme.name, UiModeManager.MODE_NIGHT_AUTO, ThemeApplier.nightModeFor(ThemeSettings(theme = theme, match = true)))
        }
    }

    @Test
    fun eachThemeHasItsOwnLaunchStyle() {
        val expected = mapOf(
            ThemeChoice.Octo to R.style.Theme_Octo_Splash_Octo,
            ThemeChoice.Moss to R.style.Theme_Octo_Splash_Moss,
            ThemeChoice.Dusk to R.style.Theme_Octo_Splash_Dusk,
            ThemeChoice.Daylight to R.style.Theme_Octo_Splash_Daylight,
            ThemeChoice.Paper to R.style.Theme_Octo_Splash_Paper,
        )
        ThemeChoice.entries.forEach { theme ->
            assertEquals(theme.name, expected.getValue(theme), ThemeApplier.splashStyleFor(ThemeSettings(theme = theme)))
        }
        assertEquals(ThemeChoice.entries.size, expected.values.toSet().size)
    }

    @Test
    fun eachMatchPairHasItsOwnLaunchStyle() {
        val expected = mapOf(
            (ThemeChoice.Daylight to ThemeChoice.Octo) to R.style.Theme_Octo_Splash_DaylightOcto,
            (ThemeChoice.Daylight to ThemeChoice.Moss) to R.style.Theme_Octo_Splash_DaylightMoss,
            (ThemeChoice.Daylight to ThemeChoice.Dusk) to R.style.Theme_Octo_Splash_DaylightDusk,
            (ThemeChoice.Paper to ThemeChoice.Octo) to R.style.Theme_Octo_Splash_PaperOcto,
            (ThemeChoice.Paper to ThemeChoice.Moss) to R.style.Theme_Octo_Splash_PaperMoss,
            (ThemeChoice.Paper to ThemeChoice.Dusk) to R.style.Theme_Octo_Splash_PaperDusk,
        )
        val lights = ThemeChoice.entries.filter { it.light }
        val darks = ThemeChoice.entries.filter { !it.light }
        assertEquals(lights.size * darks.size, expected.size)
        for (light in lights) {
            for (dark in darks) {
                val settings = ThemeSettings(theme = light, light = light, dark = dark, match = true)
                assertEquals("$light/$dark", expected.getValue(light to dark), ThemeApplier.splashStyleFor(settings))
            }
        }
        assertEquals(expected.size, expected.values.toSet().size)
    }

    @Test
    fun aPairIsNotTheStyleOfEitherOfItsThemes() {
        val pair = ThemeApplier.splashStyleFor(ThemeSettings(match = true))
        assertNotEquals(ThemeApplier.splashStyleFor(ThemeSettings(theme = ThemeChoice.Octo)), pair)
        assertNotEquals(ThemeApplier.splashStyleFor(ThemeSettings(theme = ThemeChoice.Daylight)), pair)
    }
}
