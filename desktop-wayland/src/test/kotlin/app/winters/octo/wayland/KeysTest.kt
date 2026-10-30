package app.winters.octo.wayland

import androidx.compose.ui.input.key.Key
import org.jetbrains.desktop.linux.KeyCode
import org.jetbrains.desktop.linux.KeySym
import org.junit.Test
import kotlin.test.assertEquals

class KeysTest {
    @Test fun latinShortcutsFollowTheLayoutRatherThanTheUsPosition() {
        // Actual xkbcommon fr/de keymap results (XKB keycodes, not evdev).
        assertEquals(Key.A, composeKey(24u, KeySym.a))
        assertEquals(Key.Q, composeKey(38u, KeySym.q))
        assertEquals(Key.C, composeKey(54u, KeySym.c))
        assertEquals(Key.V, composeKey(55u, KeySym.v))
        assertEquals(Key.Z, composeKey(29u, KeySym.z))
        assertEquals(Key.Z, composeKey(29u, KeySym.Z)) // Shift+Ctrl+Z redo.
        assertEquals(Key.Y, composeKey(52u, KeySym.y))
        assertEquals(Key.A, composeKey(KeyCode.A, KeySym.A))
        assertEquals(Key.Z, composeKey(KeyCode.Z, KeySym.z))
        assertEquals(Key.Comma, composeKey(KeyCode.M, KeySym.comma))
        assertEquals(Key.Unknown, composeKey(KeyCode.A, KeySym.percent))
        assertEquals(Key.F11, composeKey(KeyCode.F9, KeySym.F11))
    }

    @Test fun nonLatinLayoutsRetainPhysicalShortcutFallback() {
        assertEquals(Key.A, composeKey(KeyCode.A, 0x06c6u)) // Cyrillic ef
        assertEquals(Key.C, composeKey(KeyCode.C, 0x06d3u)) // Cyrillic es
        assertEquals(Key.V, composeKey(KeyCode.V, 0x06cdu)) // Cyrillic em
    }

    @Test fun numLockOffKeypadEditsAndNavigates() {
        assertEquals(Key.MoveHome, composeKey(KeyCode.KP_7, KeySym.KP_Home))
        assertEquals(Key.DirectionUp, composeKey(KeyCode.KP_8, KeySym.KP_Up))
        assertEquals(Key.PageUp, composeKey(KeyCode.KP_9, KeySym.KP_Page_Up))
        assertEquals(Key.DirectionLeft, composeKey(KeyCode.KP_4, KeySym.KP_Left))
        assertEquals(Key.DirectionRight, composeKey(KeyCode.KP_6, KeySym.KP_Right))
        assertEquals(Key.MoveEnd, composeKey(KeyCode.KP_1, KeySym.KP_End))
        assertEquals(Key.DirectionDown, composeKey(KeyCode.KP_2, KeySym.KP_Down))
        assertEquals(Key.PageDown, composeKey(KeyCode.KP_3, KeySym.KP_Page_Down))
        assertEquals(Key.Insert, composeKey(KeyCode.KP_0, KeySym.KP_Insert))
        assertEquals(Key.Delete, composeKey(KeyCode.KP_DOT, KeySym.KP_Delete))
        assertEquals(Key.NumPadEnter, composeKey(KeyCode.KP_Enter, KeySym.KP_Enter))
        assertEquals(Key.Tab, composeKey(KeyCode.Tab, 0xfe20u))
    }
}
