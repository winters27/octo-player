package app.winters.octo.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

@Composable
fun OctoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = OctoColors.Accent,
            background = OctoColors.Background,
            surface = OctoColors.BackgroundTertiary,
            onBackground = OctoColors.TextPrimary,
            onSurface = OctoColors.TextPrimary,
            error = OctoColors.Error,
        ),
        content = content,
    )
}
