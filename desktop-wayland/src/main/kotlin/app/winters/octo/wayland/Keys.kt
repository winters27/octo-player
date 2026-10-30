package app.winters.octo.wayland

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import org.jetbrains.desktop.linux.KeyCode
import org.jetbrains.desktop.linux.KeyModifiers
import org.jetbrains.desktop.linux.KeySym

internal fun Set<KeyModifiers>.composeModifiers() = PointerKeyboardModifiers(
    isCtrlPressed = KeyModifiers.Control in this,
    isAltPressed = KeyModifiers.Alt in this,
    isShiftPressed = KeyModifiers.Shift in this,
    isMetaPressed = KeyModifiers.Logo in this,
)

// Use the layout's logical symbol for shortcuts (AZERTY/QWERTZ included).
// Non-Latin symbols retain the familiar physical Latin shortcut positions.
// Text is still delivered separately from XKB/IME, never from this map.
internal fun composeKey(code: UInt, symbol: UInt): Key {
    if (symbol in KeySym.a..KeySym.z) return letters[(symbol - KeySym.a).toInt()]
    if (symbol in KeySym.A..KeySym.Z) return letters[(symbol - KeySym.A).toInt()]
    symbols[symbol]?.let { return it }
    // Do not turn an unmapped ASCII symbol (e.g. AZERTY punctuation) into a
    // letter shortcut just because it occupies a US letter's physical key.
    return if (symbol == 0u || (symbol > 0x7fu && symbol !in 0xfe00u..0xffffu)) physicalKeys[code] ?: Key.Unknown else Key.Unknown
}
private val letters = listOf(Key.A, Key.B, Key.C, Key.D, Key.E, Key.F, Key.G, Key.H, Key.I, Key.J, Key.K, Key.L, Key.M,
    Key.N, Key.O, Key.P, Key.Q, Key.R, Key.S, Key.T, Key.U, Key.V, Key.W, Key.X, Key.Y, Key.Z)
private val symbols = mapOf(
    KeySym.Escape to Key.Escape, KeySym.BackSpace to Key.Backspace, KeySym.Delete to Key.Delete,
    KeySym.Tab to Key.Tab, 0xfe20u to Key.Tab, // ISO_Left_Tab (Shift+Tab)
    KeySym.Return to Key.Enter, KeySym.KP_Enter to Key.NumPadEnter,
    KeySym.space to Key.Spacebar, KeySym.Home to Key.MoveHome, KeySym.End to Key.MoveEnd,
    KeySym.Left to Key.DirectionLeft, KeySym.Right to Key.DirectionRight,
    KeySym.Up to Key.DirectionUp, KeySym.Down to Key.DirectionDown,
    KeySym.Page_Up to Key.PageUp, KeySym.Page_Down to Key.PageDown, KeySym.Insert to Key.Insert,
    KeySym.KP_Home to Key.MoveHome, KeySym.KP_End to Key.MoveEnd,
    KeySym.KP_Left to Key.DirectionLeft, KeySym.KP_Right to Key.DirectionRight,
    KeySym.KP_Up to Key.DirectionUp, KeySym.KP_Down to Key.DirectionDown,
    KeySym.KP_Page_Up to Key.PageUp, KeySym.KP_Page_Down to Key.PageDown,
    KeySym.KP_Insert to Key.Insert, KeySym.KP_Delete to Key.Delete,
    KeySym.comma to Key.Comma, KeySym.bracketleft to Key.LeftBracket, KeySym.bracketright to Key.RightBracket,
    KeySym.Menu to Key.Menu,
    KeySym.F1 to Key.F1, KeySym.F2 to Key.F2, KeySym.F3 to Key.F3, KeySym.F4 to Key.F4,
    KeySym.F5 to Key.F5, KeySym.F6 to Key.F6, KeySym.F7 to Key.F7, KeySym.F8 to Key.F8,
    KeySym.F9 to Key.F9, KeySym.F10 to Key.F10, KeySym.F11 to Key.F11, KeySym.F12 to Key.F12,
)
private val physicalKeys = mapOf(
    KeyCode.Escape to Key.Escape, KeyCode.BackSpace to Key.Backspace, KeyCode.Delete to Key.Delete,
    KeyCode.Tab to Key.Tab, KeyCode.Return to Key.Enter, KeyCode.KP_Enter to Key.NumPadEnter,
    KeyCode.space to Key.Spacebar, KeyCode.Home to Key.MoveHome, KeyCode.End to Key.MoveEnd,
    KeyCode.Up to Key.DirectionUp, KeyCode.Down to Key.DirectionDown,
    KeyCode.Left to Key.DirectionLeft, KeyCode.Right to Key.DirectionRight,
    KeyCode.Page_Up to Key.PageUp, KeyCode.Page_Down to Key.PageDown, KeyCode.Insert to Key.Insert,
    KeyCode.Menu to Key.Menu, KeyCode.comma to Key.Comma,
    KeyCode.A to Key.A, KeyCode.B to Key.B, KeyCode.C to Key.C, KeyCode.D to Key.D,
    KeyCode.E to Key.E, KeyCode.F to Key.F, KeyCode.G to Key.G, KeyCode.H to Key.H,
    KeyCode.I to Key.I, KeyCode.J to Key.J, KeyCode.K to Key.K, KeyCode.L to Key.L,
    KeyCode.M to Key.M, KeyCode.N to Key.N, KeyCode.O to Key.O, KeyCode.P to Key.P,
    KeyCode.Q to Key.Q, KeyCode.R to Key.R, KeyCode.S to Key.S, KeyCode.T to Key.T,
    KeyCode.U to Key.U, KeyCode.V to Key.V, KeyCode.W to Key.W, KeyCode.X to Key.X,
    KeyCode.Y to Key.Y, KeyCode.Z to Key.Z,
    KeyCode.bracketleft to Key.LeftBracket, KeyCode.bracketright to Key.RightBracket,
    KeyCode.F1 to Key.F1, KeyCode.F2 to Key.F2, KeyCode.F3 to Key.F3, KeyCode.F4 to Key.F4,
    KeyCode.F5 to Key.F5, KeyCode.F6 to Key.F6, KeyCode.F7 to Key.F7, KeyCode.F8 to Key.F8,
    KeyCode.F9 to Key.F9, KeyCode.F10 to Key.F10, KeyCode.F11 to Key.F11, KeyCode.F12 to Key.F12,
)
