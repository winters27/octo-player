package app.winters.octo.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun OctoTheme(content: @Composable () -> Unit) {
    val s = LocalOctoSurfaces.current
    val a = LocalOctoAccent.current
    val scheme = if (s.page == OctoDarkSurfaces.page && s.card == OctoDarkSurfaces.card) {
        darkColorScheme(
            primary = a.accent,
            background = s.page,
            surface = s.card,
            onBackground = s.text,
            onSurface = s.text,
            error = OctoColors.Error,
        )
    } else {
        themedScheme(s, a)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

// Every Material role set from the theme's surfaces and the accent's colors.
private fun themedScheme(s: OctoSurfaces, a: OctoAccentFamily): ColorScheme =
    if (s.light) {
        lightColorScheme(
            primary = a.accent,
            onPrimary = a.onAccent,
            primaryContainer = a.selected,
            onPrimaryContainer = s.text,
            inversePrimary = a.accent,
            secondary = s.complement,
            onSecondary = a.onAccent,
            secondaryContainer = a.tonal,
            onSecondaryContainer = s.text,
            tertiary = s.third,
            onTertiary = a.onAccent,
            tertiaryContainer = s.raised,
            onTertiaryContainer = s.text,
            background = s.page,
            onBackground = s.text,
            surface = s.card,
            onSurface = s.text,
            surfaceVariant = s.raised,
            onSurfaceVariant = s.secondary,
            surfaceTint = Color.Transparent,
            inverseSurface = s.text,
            inverseOnSurface = s.page,
            error = OctoColors.Error,
            onError = Color.White,
            outline = s.edge,
            outlineVariant = s.inkQuaternary,
            scrim = Color.Black.copy(alpha = 0.45f),
            surfaceBright = s.sheet,
            surfaceDim = s.page,
            surfaceContainerLowest = s.page,
            surfaceContainerLow = s.card,
            surfaceContainer = s.card,
            surfaceContainerHigh = s.raised,
            surfaceContainerHighest = s.sheet,
        )
    } else {
        darkColorScheme(
            primary = a.accent,
            onPrimary = a.onAccent,
            primaryContainer = a.selected,
            onPrimaryContainer = s.text,
            inversePrimary = a.accent,
            secondary = s.complement,
            onSecondary = a.onAccent,
            secondaryContainer = a.tonal,
            onSecondaryContainer = s.text,
            tertiary = s.third,
            onTertiary = a.onAccent,
            tertiaryContainer = s.raised,
            onTertiaryContainer = s.text,
            background = s.page,
            onBackground = s.text,
            surface = s.card,
            onSurface = s.text,
            surfaceVariant = s.raised,
            onSurfaceVariant = s.secondary,
            surfaceTint = Color.Transparent,
            inverseSurface = s.text,
            inverseOnSurface = s.page,
            error = OctoColors.Error,
            onError = Color.White,
            outline = s.edge,
            outlineVariant = s.inkQuaternary,
            scrim = Color.Black.copy(alpha = 0.45f),
            surfaceBright = s.sheet,
            surfaceDim = s.page,
            surfaceContainerLowest = s.page,
            surfaceContainerLow = s.card,
            surfaceContainer = s.card,
            surfaceContainerHigh = s.raised,
            surfaceContainerHighest = s.sheet,
        )
    }
