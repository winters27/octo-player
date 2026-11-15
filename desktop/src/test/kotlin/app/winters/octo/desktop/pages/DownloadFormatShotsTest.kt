package app.winters.octo.desktop.pages

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import app.winters.octo.desktop.a11y.A11yScene
import app.winters.octo.desktop.nav.SettingsKey
import app.winters.octo.desktop.settings.AppSettings
import app.winters.octo.desktop.settings.themes
import app.winters.octo.theme.AccentChoice
import app.winters.octo.theme.ThemeChoice
import app.winters.octo.theme.pickTheme
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

// The Kept on this computer card with each Download format picked, at a
// smaller Download quality and at Original, in a dark and a light theme, and
// at a width where its choices fold into a button, kept as pictures for
// looking at. Only when asked: OCTO_SHOTS=1 ./gradlew :desktop:test --tests
// '*DownloadFormatShotsTest*'; saved to OCTO_SHOTS_DIR, else build/shots.
class DownloadFormatShotsTest {
    @get:Rule val folder = TemporaryFolder()

    private fun look(theme: ThemeChoice, quality: String, format: String): (AppSettings) -> AppSettings = { settings ->
        val themes = pickTheme(settings.appearance.themes, theme)
        settings.copy(
            appearance = settings.appearance.copy(
                ambientGlow = false,
                accent = AccentChoice.Octo,
                theme = themes.theme,
                themeLight = themes.light,
                themeDark = themes.dark,
                themeMatch = themes.match,
            ),
            offline = settings.offline.copy(downloadQuality = quality, downloadFormat = format),
        )
    }

    private fun keep(image: Image, name: String) {
        val dir = File(System.getenv("OCTO_SHOTS_DIR") ?: "build/shots").apply { mkdirs() }
        File(dir, "$name.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }

    // With `menu`, the format's menu is opened too and kept as "<name>-menu".
    private fun shot(name: String, theme: ThemeChoice, quality: String, format: String, width: Int = 1440, menu: Boolean = false) {
        A11yScene(folder.newFolder(), width = width, height = 1_100, look = look(theme, quality, format)).use { s ->
            s.onUi { s.app.openSettings(SettingsKey.Offline) }
            keep(s.settle(1_500), name)
            if (menu) {
                val label = app.winters.octo.playback.DownloadFormat.named(format).label
                val button = s.nodes(merged = true).first { node ->
                    node.config.getOrNull(SemanticsActions.OnClick) != null &&
                        node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label }
                }
                s.onUi { button.config[SemanticsActions.OnClick].action?.invoke() }
                keep(s.settle(1_200), "$name-menu")
            }
        }
    }

    @Test(timeout = 600_000)
    fun theDownloadFormatRowAsPictures() {
        assumeTrue(System.getenv("OCTO_SHOTS") == "1")
        for (format in listOf("Opus", "Aac", "Mp3")) {
            shot("download-format-dark-standard-${format.lowercase()}", ThemeChoice.Octo, "Standard", format, menu = format == "Opus")
        }
        shot("download-format-dark-original-opus", ThemeChoice.Octo, "Original", "Opus")
        shot("download-format-light-standard-opus", ThemeChoice.Daylight, "Standard", "Opus", menu = true)
        shot("download-format-light-standard-aac", ThemeChoice.Daylight, "Standard", "Aac")
        shot("download-format-dark-narrow-opus", ThemeChoice.Octo, "Standard", "Opus", width = 900)
        shot("download-format-dark-narrow-mp3", ThemeChoice.Octo, "Standard", "Mp3", width = 900)
    }
}
