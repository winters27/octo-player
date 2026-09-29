package app.winters.octo.design

import androidx.compose.foundation.LocalIndication
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalWindowInfo

// Gives the design what the desktop window knows: whether it is the window
// in front (behind another, the glass loses its specular and lit rim), and
// whether motion is reduced, by the app's setting or the system's. It also
// gives every click the keyboard's focus ring, shown while `focus` says the
// keyboard is in use, and says which control holds the arrow keys. Wrap
// the window's content in it once.
@Composable
fun ProvideWindowLook(
    reduceMotion: Boolean,
    focus: FocusVisibility = remember { FocusVisibility() },
    arrows: ArrowKeys = remember { ArrowKeys() },
    content: @Composable () -> Unit,
) {
    val focused = LocalWindowInfo.current.isWindowFocused
    CompositionLocalProvider(
        LocalWindowFocused provides focused,
        LocalReduceMotion provides reduceMotion,
        LocalMotionScale provides if (reduceMotion) MotionScale.Reduced else MotionScale.Full,
        LocalIndication provides DefaultFocusRing,
        LocalFocusVisibility provides focus,
        LocalArrowKeys provides arrows,
        content = content,
    )
}

// The ring a click without its own shape wears.
private val DefaultFocusRing = FocusRing(Corner.ControlShape)
