package app.winters.octo.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import java.util.concurrent.atomic.AtomicBoolean

// Whether this part of the screen is still drawn: true until it leaves. Work
// that answers after it may have gone (a change of password whose pop-up was
// closed under it) asks before it hands its words here.
@Composable
fun rememberStillDrawn(): () -> Boolean {
    val drawn = remember { AtomicBoolean(true) }
    DisposableEffect(drawn) { onDispose { drawn.set(false) } }
    return drawn::get
}
