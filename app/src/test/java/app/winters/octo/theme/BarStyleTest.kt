package app.winters.octo.theme

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BarStyleTest {
    @Test
    fun theBarsFollowThePageWhenNothingAsksOtherwise() {
        assertEquals(BarWish(statusLight = true, navigationLight = true), barsFor(null, pageLight = true))
        assertEquals(BarWish(statusLight = false, navigationLight = false), barsFor(null, pageLight = false))
    }

    @Test
    fun aWishWinsWhileItIsSetAndThePageAfter() {
        val request = mutableStateOf<BarWish?>(null)
        val wish = BarWish(statusLight = false, navigationLight = true)
        listOf(true, false).forEach { pageLight ->
            request.value = wish
            assertEquals(wish, barsFor(request.value, pageLight))
            request.value = null
            assertEquals(BarWish(pageLight, pageLight), barsFor(request.value, pageLight))
        }
    }

    // Every kind of bar keeps the system's own scrim behind 3-button
    // navigation, and the status bar stays clear.
    @Test
    fun theSystemKeepsItsNavigationScrimForEveryKindOfBar() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        listOf(true, false).forEach { status ->
            listOf(true, false).forEach { navigation ->
                val (statusStyle, navigationStyle) = barStyleFor(status, navigation)
                activity.enableEdgeToEdge(statusStyle, navigationStyle)
                val what = "status light $status, navigation light $navigation"
                assertTrue("$what: the navigation scrim is the system's", activity.window.isNavigationBarContrastEnforced)
                assertFalse("$what: the status bar has no scrim", activity.window.isStatusBarContrastEnforced)
            }
        }
    }
}
