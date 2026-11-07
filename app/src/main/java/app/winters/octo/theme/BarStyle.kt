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

// The scrims Android's own edge to edge puts behind 3-button navigation on
// the versions that draw none themselves: light, and dark.
private val DefaultLightScrim = Color.argb(0xE6, 0xFF, 0xFF, 0xFF)
private val DefaultDarkScrim = Color.argb(0x80, 0x1B, 0x1B, 0x1B)

// The status bar's and the navigation bar's styles. The status bar is
// transparent, drawn under the app; the navigation bar keeps the system's
// own scrim behind 3-button navigation, light or dark with the bar.
fun barStyleFor(statusLight: Boolean, navigationLight: Boolean): Pair<SystemBarStyle, SystemBarStyle> {
    val status = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { !statusLight }
    val navigation = SystemBarStyle.auto(DefaultLightScrim, DefaultDarkScrim) { !navigationLight }
    return status to navigation
}
