package app.winters.octo.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalWindowInfo

// Gives the design what the desktop window knows: whether it is the window
// in front (behind another, the glass loses its specular and lit rim), and
// whether motion is reduced, by the app's setting or the system's. Wrap
// the window's content in it once.
@Composable
fun ProvideWindowLook(reduceMotion: Boolean, content: @Composable () -> Unit) {
    val focused = LocalWindowInfo.current.isWindowFocused
    CompositionLocalProvider(
        LocalWindowFocused provides focused,
        LocalReduceMotion provides reduceMotion,
        LocalMotionScale provides if (reduceMotion) MotionScale.Reduced else MotionScale.Full,
        content = content,
    )
}
