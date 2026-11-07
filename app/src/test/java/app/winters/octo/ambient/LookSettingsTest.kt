package app.winters.octo.ambient

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.winters.octo.player.PlayerSettings
import app.winters.octo.theme.AccentChoice
import app.winters.octo.theme.ThemeChoice
import app.winters.octo.theme.ThemeStore
import app.winters.octo.theme.resolvePalette
import app.winters.octo.ui.nav.HomeRoute
import app.winters.octo.ui.nav.LibraryRoute
import app.winters.octo.ui.nav.SearchRoute
import app.winters.octo.ui.nav.SettingsRoute
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// A theme supplies colors only: every look setting draws as set in every
// theme, and picking a theme writes none of them.
@RunWith(RobolectricTestRunner::class)
class LookSettingsTest {
    private val cover = listOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFFFFFF00.toInt())
    private val routes = mapOf(AmbientArea.Home to HomeRoute, AmbientArea.Library to LibraryRoute, AmbientArea.Search to SearchRoute, AmbientArea.Settings to SettingsRoute)

    // The glow on the page for `area`, playing a song with `cover` or not.
    private fun look(theme: ThemeChoice, prefs: AmbientPrefs, area: AmbientArea, playing: Boolean): AmbientLook {
        val route = routes.getValue(area)
        val source = ambientSource(prefs, route, null, playing, if (playing) "art" else null)
        val glow = resolvePalette(theme, AccentChoice.Octo, null, false).glow
        return ambientLook(prefs, areaOf(route), source, theme, glow, if (source == AmbientSource.None) null else cover)
    }

    @Test
    fun offDrawsNoGlowInAnyTheme() {
        val off = AmbientPrefs(strength = AmbientStrength.Off)
        for (theme in ThemeChoice.entries) for (area in AmbientArea.entries) for (playing in listOf(true, false)) {
            assertEquals("$theme $area playing $playing", 0f, look(theme, off, area, playing).alpha)
        }
    }

    @Test
    fun anAreaSwitchedOffDrawsNoGlowThere() {
        for (theme in ThemeChoice.entries) for (area in AmbientArea.entries) {
            val prefs = AmbientPrefs(
                home = area != AmbientArea.Home,
                library = area != AmbientArea.Library,
                search = area != AmbientArea.Search,
                settings = area != AmbientArea.Settings,
            )
            for (playing in listOf(true, false)) assertEquals("$theme $area playing $playing", 0f, look(theme, prefs, area, playing).alpha)
            // The other areas still glow.
            AmbientArea.entries.filter { it != area }.forEach { assertTrue("$theme $it", look(theme, prefs, it, playing = true).alpha > 0f) }
        }
    }

    @Test
    fun subtleAndRichDrawAtTheirAlphas() {
        for (theme in ThemeChoice.entries) for (strength in listOf(AmbientStrength.Subtle, AmbientStrength.Rich)) {
            val prefs = AmbientPrefs(strength = strength)
            for (area in AmbientArea.entries) {
                assertEquals("$theme $strength $area playing", ambientAlpha(strength), look(theme, prefs, area, playing = true).alpha)
                val resting = look(theme, prefs, area, playing = false)
                // The theme's own colors rest at the same strength; Octo has none.
                if (theme == ThemeChoice.Octo) {
                    assertEquals(0f, resting.alpha)
                    assertFalse(resting.rests)
                } else {
                    assertEquals("$theme $strength $area at rest", ambientAlpha(strength), resting.alpha)
                    assertTrue(resting.rests)
                    assertEquals(4, resting.mesh!!.size)
                }
            }
        }
        assertEquals(0.14f, ambientAlpha(AmbientStrength.Subtle))
        assertEquals(0.24f, ambientAlpha(AmbientStrength.Rich))
    }

    @Test
    fun theGlowAtRestNeverMoves() {
        for (theme in ThemeChoice.entries.filter { it != ThemeChoice.Octo }) {
            val resting = look(theme, AmbientPrefs(), AmbientArea.Home, playing = false)
            assertTrue(resting.rests)
            for (reduce in listOf(false, true)) for (playing in listOf(false, true)) {
                assertFalse("$theme reduce $reduce playing $playing", ambientMoves(awake = true, playing = playing, reduceMotion = reduce, rests = true))
            }
        }
        // The cover's glow behaves as it always has.
        assertTrue(ambientMoves(awake = true, playing = true, reduceMotion = false, rests = false))
        assertFalse(ambientMoves(awake = true, playing = true, reduceMotion = true, rests = false))
        assertFalse(ambientMoves(awake = false, playing = true, reduceMotion = false, rests = false))
    }

    @Test
    fun aSettingsReadNotYetDrawsNothing() {
        for (theme in ThemeChoice.entries) {
            val glow = resolvePalette(theme, AccentChoice.Octo, null, false).glow
            val look = ambientLook(null, AmbientArea.Home, AmbientSource.None, theme, glow, null)
            assertEquals(0f, look.alpha)
            assertNull(look.mesh)
        }
    }

    // Every file the app keeps but the theme's own, by path, with its bytes.
    private fun keptFiles(context: Context): Map<String, List<Byte>> {
        val root = context.filesDir.parentFile!!
        return root.walkTopDown()
            .filter { it.isFile && it.name != "octo_theme.xml" }
            .associate { it.relativeTo(root).path to it.readBytes().toList() }
    }

    @Test
    fun pickingAThemeWritesNoLookSetting() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        val settings = PlayerSettings(context)
        val before = settings.prefs.first()
        val files = keptFiles(context)
        val themes = ThemeStore(context)
        for (theme in ThemeChoice.entries) {
            themes.pick(theme)
            assertEquals(theme, themes.settings.value.theme)
            assertEquals("the look settings after picking $theme", before, settings.prefs.first())
            assertEquals("the files after picking $theme", files, keptFiles(context))
        }
    }
}
