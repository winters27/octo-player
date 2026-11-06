package app.winters.octo.theme

import android.graphics.Color
import androidx.activity.SystemBarStyle
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf

// What the status and navigation bars should look like: `statusLight` and
// `navigationLight` are true for a light bar, which has dark icons.
data class BarWish(val statusLight: Boolean, val navigationLight: Boolean)

// The wish of whatever is drawn full screen over the page, such as the
// player. The root reads it and is the only one that writes the window;
// null means the page's own lightness.
val LocalBarRequest = staticCompositionLocalOf<MutableState<BarWish?>> { mutableStateOf(null) }

// The bars to show: the wish while there is one, else both light on a
// light page and both dark on a dark one.
fun barsFor(wish: BarWish?, pageLight: Boolean): BarWish = wish ?: BarWish(statusLight = pageLight, navigationLight = pageLight)

// The status bar's and the navigation bar's styles. Both are transparent
// bars drawn under the app; `scrim` is the color behind 3-button
// navigation.
fun barStyleFor(statusLight: Boolean, navigationLight: Boolean, scrim: Int): Pair<SystemBarStyle, SystemBarStyle> {
    val status = if (statusLight) {
        SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
    } else {
        SystemBarStyle.dark(Color.TRANSPARENT)
    }
    val navigation = if (navigationLight) SystemBarStyle.light(scrim, scrim) else SystemBarStyle.dark(scrim)
    return status to navigation
}
