package app.winters.octo.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.winters.octo.design.GlassSheet
import app.winters.octo.design.Glaze
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoSwitch
import app.winters.octo.design.OctoType
import app.winters.octo.design.ProvideOctoTheme
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// A theme picked while the app is open: an open sheet stays open with what
// it holds, and the page then draws exactly what a fresh start in that
// theme draws.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-mdpi")
class LiveFlipTest {
    @Before
    fun startEmpty() {
        ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("octo_theme", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private class Window(val activity: ComponentActivity, val sheet: androidx.compose.runtime.MutableState<Boolean>, val taps: androidx.compose.runtime.MutableIntState)

    // The page drawn the way the app's root draws it, in the theme the store
    // holds, with a sheet that counts what was done in it.
    private fun open(store: ThemeStore, sheetOpen: Boolean): Window {
        val sheet = mutableStateOf(sheetOpen)
        val taps = mutableIntStateOf(0)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            val settings by store.settings.collectAsState()
            ProvideOctoTheme(resolvePalette(activeTheme(settings, true), AccentChoice.Octo, null, false)) {
                Box(Modifier.fillMaxSize().background(OctoColors.Background)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Library", style = OctoType.title, color = OctoColors.TextPrimary)
                        Text("Radiohead", style = OctoType.body, color = OctoColors.TextSecondary)
                        Text("5 songs", style = OctoType.caption, color = OctoColors.TextMuted)
                        Glaze(Modifier.fillMaxWidth().height(56.dp)) { Text("A card", color = OctoColors.TextPrimary, modifier = Modifier.padding(12.dp)) }
                        OctoSwitch(checked = true, onCheckedChange = {})
                        OctoSwitch(checked = false, onCheckedChange = {})
                    }
                    GlassSheet(visible = sheet.value, onDismiss = { sheet.value = false }) {
                        val held = remember { taps }
                        Text("Tapped ${held.intValue} times", style = OctoType.body, color = OctoColors.TextPrimary, modifier = Modifier.padding(16.dp))
                    }
                }
            }
        }
        settle()
        return Window(activity, sheet, taps)
    }

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

    private fun Window.picture(): Bitmap {
        val view = activity.window.decorView
        return Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
    }

    private fun difference(a: Bitmap, b: Bitmap): String? {
        if (a.width != b.width || a.height != b.height) return "sizes differ"
        var count = 0
        var first: Pair<Int, Int>? = null
        for (y in 0 until a.height) for (x in 0 until a.width) {
            if (a.getPixel(x, y) != b.getPixel(x, y)) {
                if (first == null) first = x to y
                count++
            }
        }
        return if (count == 0) null else "$count pixels differ, the first at $first"
    }

    private fun sameAsFresh(theme: ThemeChoice, flipped: Bitmap, sheetOpen: Boolean, what: String) {
        val freshStore = ThemeStore(ApplicationProvider.getApplicationContext())
        freshStore.pick(theme)
        val fresh = open(freshStore, sheetOpen).picture()
        val distinct = (0 until fresh.width step 8).flatMap { x -> (0 until fresh.height step 8).map { y -> fresh.getPixel(x, y) } }.toSet()
        assertTrue("$what: something is drawn", distinct.size > 2)
        val differs = difference(flipped, fresh)
        assertTrue("$what, switched against fresh: $differs", differs == null)
    }

    @Test
    fun anOpenSheetStaysOpenThroughAThemePick() {
        val store = ThemeStore(ApplicationProvider.getApplicationContext())
        val window = open(store, sheetOpen = true)
        window.taps.intValue = 3
        settle()
        store.pick(ThemeChoice.Daylight)
        settle()
        store.pick(ThemeChoice.Moss)
        settle()
        assertTrue("the sheet is still open", window.sheet.value)
        assertEquals("what it held is kept", 3, window.taps.intValue)
        // An open sheet keeps the look it opened with; opened again it
        // draws as a fresh start does.
        window.sheet.value = false
        settle()
        window.sheet.value = true
        settle()
        window.taps.intValue = 0
        settle()
        sameAsFresh(ThemeChoice.Moss, window.picture(), sheetOpen = true, what = "the sheet opened again")
    }

    @Test
    fun aSwitchedPageDrawsWhatAFreshStartDraws() {
        val store = ThemeStore(ApplicationProvider.getApplicationContext())
        val window = open(store, sheetOpen = false)
        for (theme in listOf(ThemeChoice.Daylight, ThemeChoice.Moss)) {
            store.pick(theme)
            settle()
        }
        val flipped = window.picture()
        sameAsFresh(ThemeChoice.Moss, flipped, sheetOpen = false, what = "the page")
    }
}
