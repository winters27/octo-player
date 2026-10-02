package app.winters.octo.desktop.hotkeys

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import app.winters.octo.design.Corner
import app.winters.octo.design.DesktopType
import app.winters.octo.design.GlazeSelected
import app.winters.octo.design.OctoColors
import app.winters.octo.design.RowHeight
import app.winters.octo.design.Space
import app.winters.octo.design.Txt
import app.winters.octo.design.hoverLift
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.pages.RowInset
import app.winters.octo.desktop.pages.ActionRow
import app.winters.octo.desktop.pages.Group
import app.winters.octo.desktop.pages.SwitchRow
import app.winters.octo.desktop.system.LocalSystem

// Settings > Keyboard, below Octo's own keys: the global shortcuts, a
// switch for all of them and a row per action with its keys. A click on a
// row takes the next keys pressed as its new ones.
@Composable
fun GlobalShortcutGroup(app: AppState) {
    val shortcuts = LocalSystem.current?.shortcuts ?: return
    if (!shortcuts.available) return
    val settings by app.settings.state.collectAsState()
    val prefs = settings.hotkeys
    val keys = hotkeysOf(prefs)
    // Leaving the page stops listening for keys.
    DisposableEffect(shortcuts) { onDispose { shortcuts.stopRecording() } }
    Group("Global shortcuts") {
        SwitchRow(
            "Use these keys from any app",
            "Play, skip and set the volume from any app, even with Octo hidden.",
            prefs.on,
        ) { on -> app.settings.update { it.copy(hotkeys = it.hotkeys.copy(on = on)) } }
        HotkeyAction.entries.forEach { action ->
            ShortcutRow(action, keys[action], shortcuts, on = prefs.on)
        }
        if (prefs.keys.isNotEmpty()) {
            ActionRow("Use the usual keys", null, "Reset", shortcuts::useUsualKeys)
        }
    }
}

// One action and its keys, as dense as the list of Octo's own keys above
// it. While it listens, the keys' place says so on the darker pill and the
// line under the name says how to finish; keys that can't be used, or that
// another app has, say why in orange.
@Composable
private fun ShortcutRow(action: HotkeyAction, combo: KeyCombo?, shortcuts: GlobalShortcuts, on: Boolean) {
    val listening = shortcuts.recording == action
    val problem = shortcuts.problem?.takeIf { it.first == action }?.second
    val refused = on && !listening && action in shortcuts.refused
    val caption = when {
        problem != null -> problem.words
        listening -> "Press the new keys. Esc to stop, Backspace for none."
        refused -> "Another app already has these keys, so they won't reach Octo. Click to pick others."
        else -> null
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = RowHeight.Regular)
            .hoverLift(Corner.RowShape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button) {
                if (listening) shortcuts.stopRecording() else shortcuts.record(action)
            }
            .padding(horizontal = RowInset, vertical = Space.S),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = Space.Xl)) {
            Txt(action.label, DesktopType.body, if (on || listening) OctoColors.TextPrimary else OctoColors.TextMuted, maxLines = 2)
            if (caption != null) {
                val warn = problem != null || refused
                Txt(caption, DesktopType.meta, if (warn) OctoColors.SignalOrange else OctoColors.TextMuted, Modifier.padding(top = Space.Xxs), maxLines = 3)
            }
        }
        // Its words line up with the keys in the list above.
        Box(Modifier.offset(x = Space.M), contentAlignment = Alignment.Center) {
            if (listening) GlazeSelected(Modifier.matchParentSize(), Corner.ControlShape)
            val words = when {
                listening -> "Press keys"
                combo == null -> "None"
                else -> combo.shown
            }
            val colour = when {
                listening -> OctoColors.TextPrimary
                combo == null || !on -> OctoColors.TextMuted
                else -> OctoColors.TextSecondary
            }
            Txt(words, DesktopType.meta.copy(fontFeatureSettings = "tnum"), colour, Modifier.padding(horizontal = Space.M, vertical = Space.Xs))
        }
    }
}
