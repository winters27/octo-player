package app.winters.octo.theme

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ThemeStoreTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun startEmpty() {
        context.getSharedPreferences("octo_theme", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun agrees(s: ThemeSettings) =
        s.light.light && !s.dark.light && if (s.theme.light) s.light == s.theme else s.dark == s.theme

    @Test
    fun aNewInstallShowsOctoAndMatchIsOff() {
        assertEquals(ThemeSettings(ThemeChoice.Octo, ThemeChoice.Daylight, ThemeChoice.Octo, false), ThemeStore(context).settings.value)
    }

    @Test
    fun noEmissionDisagrees() = runTest(UnconfinedTestDispatcher()) {
        val store = ThemeStore(context)
        val seen = mutableListOf<ThemeSettings>()
        val job = launch { store.settings.toList(seen) }
        ThemeChoice.entries.forEach { store.pick(it) }
        store.setMatch(true, deviceDark = true)
        ThemeChoice.entries.reversed().forEach { store.pick(it) }
        store.setMatch(false, deviceDark = false)
        job.cancel()
        assertTrue(seen.size > 1)
        seen.forEach { assertTrue("$it", agrees(it)) }
    }

    @Test
    fun aPickIsKeptForTheNextRun() {
        val store = ThemeStore(context)
        store.pick(ThemeChoice.Paper)
        store.pick(ThemeChoice.Dusk)
        store.setMatch(true, deviceDark = true)
        val again = ThemeStore(context).settings.value
        assertEquals(ThemeSettings(ThemeChoice.Dusk, ThemeChoice.Paper, ThemeChoice.Dusk, true), again)
    }

    @Test
    fun turningMatchOffKeepsWhatIsShowing() {
        val store = ThemeStore(context)
        store.pick(ThemeChoice.Paper)
        store.pick(ThemeChoice.Moss)
        store.setMatch(true, deviceDark = false)
        store.setMatch(false, deviceDark = false)
        assertEquals(ThemeChoice.Paper, store.settings.value.theme)
        assertEquals(false, store.settings.value.match)
    }

    @Test
    fun storedGarbageReadsAsEachFieldsDefault() {
        context.getSharedPreferences("octo_theme", Context.MODE_PRIVATE).edit()
            .putString("theme", "Aurora")
            .putString("theme_light", "Moss")
            .putString("theme_dark", "Paper")
            .putBoolean("theme_match", true)
            .commit()
        val read = ThemeStore(context).settings.value
        assertEquals(ThemeSettings(ThemeChoice.Octo, ThemeChoice.Daylight, ThemeChoice.Octo, true), read)
    }

    @Test
    fun theAccentSeenLastTimeRoundTrips() {
        val store = ThemeStore(context)
        assertEquals(AccentSeen(), store.seen)
        val seen = AccentSeen(AccentChoice.FromArt, 0xFF336699.toInt(), glass = true)
        store.see(seen)
        assertEquals(seen, ThemeStore(context).seen)
        store.see(AccentSeen(AccentChoice.Coral, null, glass = false))
        val back = ThemeStore(context).seen
        assertEquals(AccentChoice.Coral, back.choice)
        assertNull(back.art)
        assertEquals(false, back.glass)
    }

    @Test
    fun theAccentSeenIsNotATheme() {
        val store = ThemeStore(context)
        store.see(AccentSeen(AccentChoice.Mint, 1, glass = true))
        assertEquals(ThemeSettings(), store.settings.value)
    }
}
