package app.winters.octo.ui.settings

import android.content.Context
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import app.winters.octo.design.ProvideOctoTheme
import app.winters.octo.theme.AccentChoice
import app.winters.octo.theme.ThemeChoice
import app.winters.octo.theme.ThemeStore
import app.winters.octo.theme.resolvePalette
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(qualifiers = "w320dp-h900dp")
class ThemePickerTest {
    @Before
    fun startEmpty() {
        ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("octo_theme", Context.MODE_PRIVATE).edit().clear().commit()
    }

    // Draws the picker on a 320 dp wide screen at `fontScale` and returns
    // how each theme's name was laid out, with the theme showing as given.
    private fun laidOut(fontScale: Float, showing: ThemeChoice): Map<ThemeChoice, TextLayoutResult> {
        RuntimeEnvironment.setFontScale(fontScale)
        val store = ThemeStore(ApplicationProvider.getApplicationContext())
        store.pick(showing)
        val names = mutableMapOf<ThemeChoice, TextLayoutResult>()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            ProvideOctoTheme(resolvePalette(showing, AccentChoice.Octo, null, false)) {
                ThemePicker(store, deviceDark = true) { theme, layout -> names[theme] = layout }
            }
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        return names
    }

    private fun assertEveryNameFullyDrawn(fontScale: Float) {
        for (showing in ThemeChoice.entries) {
            val names = laidOut(fontScale, showing)
            assertEquals("every theme has a card", ThemeChoice.entries.toSet(), names.keys)
            for ((theme, layout) in names) {
                val label = "${theme.label} at $fontScale showing ${showing.label}"
                assertTrue("$label takes at most two lines", layout.lineCount in 1..2)
                assertFalse("$label overflows its height", layout.didOverflowHeight)
                assertFalse("$label overflows its width", layout.didOverflowWidth)
                assertEquals("$label is drawn whole", theme.label.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = false))
            }
        }
    }

    @Test
    fun everyNameIsFullyDrawnAtNormalTextSize() = assertEveryNameFullyDrawn(1.0f)

    @Test
    fun everyNameIsFullyDrawnAtTwiceTheTextSize() = assertEveryNameFullyDrawn(2.0f)
}
