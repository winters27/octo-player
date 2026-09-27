package app.winters.octo.ui.common

import android.content.ContentResolver
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.winters.octo.design.LocalReduceMotion as DesignReduceMotion

// Whether motion is reduced, by Octo's own setting or the phone's. Given
// with the songs found online that are now in the library, for the rows
// that turn into them. It is the design module's own, so the glass
// pop-ups calm down with it too.
val LocalReduceMotion: ProvidableCompositionLocal<Boolean> = DesignReduceMotion

// Whether the phone's animations are switched off in its settings. Read again
// each time the app comes back, since it may have changed meanwhile.
@Composable
fun rememberSystemReduceMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    var off by remember { mutableStateOf(animationsOff(resolver)) }
    LifecycleResumeEffect(resolver) {
        off = animationsOff(resolver)
        onPauseOrDispose { }
    }
    return off
}

private fun animationsOff(resolver: ContentResolver): Boolean =
    Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
