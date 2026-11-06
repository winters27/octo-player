package app.winters.octo.theme

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.os.Build
import androidx.annotation.StyleRes
import app.winters.octo.R

// Hands the platform what only the platform draws: the night mode the
// system parts follow, and the launch screen.
object ThemeApplier {
    // The night mode the app asks the platform for: automatic while Match is
    // on, else the kind of the theme shown.
    fun nightModeFor(settings: ThemeSettings): Int = when {
        settings.match -> UiModeManager.MODE_NIGHT_AUTO
        settings.theme.light -> UiModeManager.MODE_NIGHT_NO
        else -> UiModeManager.MODE_NIGHT_YES
    }

    // Asked every time: the platform has no getter for the app's own mode.
    fun applyNightMode(context: Context, settings: ThemeSettings) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        context.getSystemService(UiModeManager::class.java)?.setApplicationNightMode(nightModeFor(settings))
    }

    // The launch screen style: the theme's own, or with Match on the pair of
    // its light and dark themes, whose page follows the night mode.
    @StyleRes
    fun splashStyleFor(settings: ThemeSettings): Int =
        if (settings.match) splashPair(settings.light, settings.dark) else splashOf(settings.theme)

    fun applySplash(activity: Activity, settings: ThemeSettings) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        activity.splashScreen.setSplashScreenTheme(splashStyleFor(settings))
    }

    @StyleRes
    private fun splashOf(theme: ThemeChoice): Int = when (theme) {
        ThemeChoice.Octo -> R.style.Theme_Octo_Splash_Octo
        ThemeChoice.Moss -> R.style.Theme_Octo_Splash_Moss
        ThemeChoice.Dusk -> R.style.Theme_Octo_Splash_Dusk
        ThemeChoice.Daylight -> R.style.Theme_Octo_Splash_Daylight
        ThemeChoice.Paper -> R.style.Theme_Octo_Splash_Paper
    }

    @StyleRes
    private fun splashPair(light: ThemeChoice, dark: ThemeChoice): Int = when (light) {
        ThemeChoice.Paper -> when (dark) {
            ThemeChoice.Moss -> R.style.Theme_Octo_Splash_PaperMoss
            ThemeChoice.Dusk -> R.style.Theme_Octo_Splash_PaperDusk
            else -> R.style.Theme_Octo_Splash_PaperOcto
        }
        else -> when (dark) {
            ThemeChoice.Moss -> R.style.Theme_Octo_Splash_DaylightMoss
            ThemeChoice.Dusk -> R.style.Theme_Octo_Splash_DaylightDusk
            else -> R.style.Theme_Octo_Splash_DaylightOcto
        }
    }
}
