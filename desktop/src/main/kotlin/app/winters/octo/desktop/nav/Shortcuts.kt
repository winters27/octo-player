package app.winters.octo.desktop.nav

import androidx.compose.ui.input.key.Key

// What a key press asks the app to do.
enum class Shortcut {
    PlayPause,
    SeekBack,
    SeekForward,
    VolumeUp,
    VolumeDown,
    Search,
    Lyrics,
    Queue,
    Settings,
    Back,
    Forward,
    CloseLayer,
}

// A key press, stripped to what the shortcuts look at.
data class KeyPress(
    val key: Key,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
    val meta: Boolean = false,
)

// The app's shortcuts. "Command" is Cmd on a Mac and Ctrl elsewhere. While
// someone types in a text field, Space and the arrows belong to the field.
fun shortcutFor(press: KeyPress, mac: Boolean, typing: Boolean): Shortcut? {
    val command = if (mac) press.meta else press.ctrl
    val plain = !press.ctrl && !press.alt && !press.meta
    return when {
        command && !press.alt && press.key == Key.F -> Shortcut.Search
        command && !press.alt && press.key == Key.L -> Shortcut.Lyrics
        command && !press.alt && press.key == Key.U -> Shortcut.Queue
        command && !press.alt && press.key == Key.Comma -> Shortcut.Settings
        // Back and forward: Alt with the arrows, and Cmd with the brackets
        // or arrows on a Mac, as browsers do there.
        press.alt && !press.ctrl && !press.meta && press.key == Key.DirectionLeft -> Shortcut.Back
        press.alt && !press.ctrl && !press.meta && press.key == Key.DirectionRight -> Shortcut.Forward
        mac && press.meta && press.key == Key.LeftBracket -> Shortcut.Back
        mac && press.meta && press.key == Key.RightBracket -> Shortcut.Forward
        press.key == Key.Escape -> Shortcut.CloseLayer
        typing -> null
        plain && press.key == Key.Spacebar -> Shortcut.PlayPause
        plain && press.key == Key.DirectionLeft -> Shortcut.SeekBack
        plain && press.key == Key.DirectionRight -> Shortcut.SeekForward
        plain && press.key == Key.DirectionUp -> Shortcut.VolumeUp
        plain && press.key == Key.DirectionDown -> Shortcut.VolumeDown
        else -> null
    }
}

// How far the arrow keys move things.
const val SEEK_STEP_MS = 5_000L
const val VOLUME_STEP = 0.05f

// The list shown in Settings, in the words and keys of this system.
fun shortcutList(mac: Boolean): List<Pair<String, String>> {
    val command = if (mac) "Cmd" else "Ctrl"
    return listOf(
        "Play or pause" to "Space",
        "Back or forward 5 seconds" to "Left / Right",
        "Volume up or down" to "Up / Down",
        "Search" to "$command+F",
        "Show lyrics" to "$command+L",
        "Show the queue" to "$command+U",
        "Settings" to "$command+,",
        "Previous or next page" to if (mac) "Cmd+[ / Cmd+] or mouse buttons" else "Alt+Left / Alt+Right or mouse buttons",
        "Close a menu or the player" to "Esc",
    )
}
