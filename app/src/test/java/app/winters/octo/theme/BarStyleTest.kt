package app.winters.octo.theme

import androidx.compose.runtime.mutableStateOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

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

    @Test
    fun everyKindOfBarHasAStyle() {
        listOf(true, false).forEach { status ->
            listOf(true, false).forEach { navigation ->
                val (statusStyle, navigationStyle) = barStyleFor(status, navigation, scrim = 0x80000000.toInt())
                assertNotNull(statusStyle)
                assertNotNull(navigationStyle)
            }
        }
    }
}
