package app.winters.octo.ui.common

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

internal const val ADDRESS = "Server address"
internal const val NAME = "Name"

// A page that hosts a callout the way the sign-in page does: a scroll 400 dp
// tall whose own child's coordinates are taken first, two real field rows at
// its top, a key row under them, a group far below, and both effects at the
// host's level, outside anything that removes the group. Every input the
// effects read is state here, set by the rows.
internal class CalloutPage {
    var shown by mutableStateOf(true)
    var pending by mutableStateOf(false)
    var keyPending by mutableStateOf(false)
    var settled by mutableStateOf(true)
    var open by mutableStateOf(true)
    var ime by mutableIntStateOf(0)
    var above by mutableStateOf(900.dp)
    var groupHeight by mutableStateOf(160.dp)
    var below by mutableStateOf(900.dp)
    var shownCount by mutableIntStateOf(0)
    var keyShownCount by mutableIntStateOf(0)
    var addressChanges by mutableIntStateOf(0)
    val scroll = ScrollState(0)
    val nameFocus = FocusRequester()
    var group by mutableStateOf<LayoutCoordinates?>(null)
    var keyRow by mutableStateOf<LayoutCoordinates?>(null)
    var modes: InputModeManager? = null
    var topInset = 0
    var gap = 0
    var viewport = 0

    // How tall the scroll's soft bottom edge is, and that in pixels.
    var fadeDp by mutableStateOf(0.dp)
    var fade = 0

    // Where the group's top and bottom, and the key row's top, are in the
    // window, in pixels. Read on the UI thread.
    val groupTop: Float get() = group!!.positionInRoot().y
    val groupBottom: Float get() = groupTop + group!!.size.height
    val keyTop: Float get() = keyRow!!.positionInRoot().y
}

@Composable
internal fun CalloutPage.Draw() {
    modes = LocalInputModeManager.current
    val density = LocalDensity.current
    topInset = with(density) { 68.dp.roundToPx() }
    gap = with(density) { 8.dp.roundToPx() }
    viewport = with(density) { 400.dp.roundToPx() }
    fade = with(density) { fadeDp.roundToPx() }
    if (!shown) return
    val focus = remember { CalloutFocus() }
    var content by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val host = content?.let { CalloutHost(scroll, topInset, it, settled = { settled }, imeBottom = { ime }, focus = focus, fade = fade) }
    CalloutEffect(pending, host, { group }) {
        pending = false
        shownCount++
    }
    CalloutEffect(keyPending, host?.copy(settled = { true }), { keyRow }) {
        keyPending = false
        keyShownCount++
    }
    CompositionLocalProvider(LocalCalloutHost provides host) {
        Column(Modifier.fillMaxWidth().height(400.dp).testTag("page").verticalScroll(scroll)) {
            Column(Modifier.fillMaxWidth().onGloballyPositioned { content = it }) {
                Box(Modifier.onFocusChanged { addressChanges++ }) {
                    FormFieldRow(title = ADDRESS, value = "", onChange = {})
                }
                FormFieldRow(title = NAME, value = "", onChange = {}, focus = nameFocus)
                Box(Modifier.fillMaxWidth().height(48.dp).onGloballyPositioned { keyRow = it })
                Spacer(Modifier.height(above))
                if (open) Box(Modifier.fillMaxWidth().height(groupHeight).onGloballyPositioned { group = it })
                Spacer(Modifier.height(below))
            }
        }
    }
}
