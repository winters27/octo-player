package app.winters.octo.ui.common

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import app.winters.octo.design.GlassSheet
import kotlinx.coroutines.delay

// How long the sheet takes to slide away before its window closes.
private const val CLOSE_MS = 250L

// A GlassSheet for places that have no full-screen box to host one, such
// as a card on a scrolling page or the lyrics inside the player. It opens
// in a window of its own over everything, and looks and moves the same.
@Composable
fun FloatingSheet(visible: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    // The window stays open while the sheet slides away.
    var open by remember { mutableStateOf(visible) }
    LaunchedEffect(visible) {
        if (visible) {
            open = true
        } else {
            delay(CLOSE_MS)
            open = false
        }
    }
    if (!open) return
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // The sheet dims the screen itself.
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(0f) }
        // Shown a frame after the window opens, so it slides in.
        var entered by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { entered = true }
        GlassSheet(visible = visible && entered, onDismiss = onDismiss, content = content)
    }
}
