package app.winters.octo.desktop.hotkeys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.event.KeyEvent

class HotkeysTest {
    private fun combo(text: String) = KeyCombo.parse(text)!!

    @Test
    fun shortcutsReadAndWriteTheSameWay() {
        listOf("Ctrl+Alt+Space", "Ctrl+Alt+PageDown", "Ctrl+Shift+F9", "Alt+Shift+K", "Ctrl+Alt+7", "Ctrl+Alt+NumPad4", "Ctrl+Alt+Equals", "Ctrl+Alt+F24").forEach { text ->
            assertEquals(text, combo(text).saved)
        }
        assertEquals("held keys in any order and case", combo("Ctrl+Alt+Space"), combo("alt+CTRL+space"))
        assertEquals("Ctrl+Alt+Page Down", combo("Ctrl+Alt+PageDown").shown)
        assertEquals("Ctrl+Alt+Num 4", combo("Ctrl+Alt+NumPad4").shown)
        assertEquals("Ctrl+Alt+=", combo("Ctrl+Alt+Equals").shown)
        assertEquals("the number pad's plus", HotKey.NumPadAdd, (combo("Ctrl+Alt++").key as Key.Named).key)
    }

    @Test
    fun nonsenseIsNoShortcut() {
        listOf("", "Ctrl+", "Ctrl+Alt+Nope", "Hyper+A", "F25", "Ctrl+Alt+MediaPlay").forEach { assertNull(it, KeyCombo.parse(it)) }
    }

    @Test
    fun windowsGetsItsOwnNumbers() {
        val playPause = combo("Ctrl+Alt+Space")
        assertEquals(0x20, playPause.key.windowsCode)
        assertEquals("MOD_ALT | MOD_CONTROL", 0x3, playPause.windowsModifiers)
        assertEquals(0x22, combo("Ctrl+Alt+PageDown").key.windowsCode)
        assertEquals(0x4B, combo("Ctrl+Shift+K").key.windowsCode)
        assertEquals(0x35, combo("Ctrl+Alt+5").key.windowsCode)
        assertEquals(0x78, combo("Ctrl+Alt+F9").key.windowsCode)
        assertEquals(0x87, combo("Ctrl+Alt+F24").key.windowsCode)
        assertEquals(0x64, combo("Ctrl+Alt+NumPad4").key.windowsCode)
        assertEquals(0x2D, combo("Ctrl+Alt+Insert").key.windowsCode)
        assertEquals(0x6, combo("Ctrl+Shift+A").windowsModifiers)
    }

    @Test
    fun theUsualKeysAreFreeOfEveryClash() {
        val usual = hotkeysOf(HotkeyPrefs())
        assertEquals(HotkeyAction.entries.size, usual.values.filterNotNull().toSet().size)
        usual.forEach { (action, keys) ->
            assertNull("${action.label}: ${keys!!.shown}", hotkeyProblem(action, keys, usual))
            // None types a character with AltGr, which is Ctrl+Alt: no
            // letters, digits or punctuation.
            assertTrue(keys.shown, keys.key is Key.Named && keys.key.windowsCode < 0x30)
        }
    }

    @Test
    fun clashesAreCaughtAndSaidPlainly() {
        val usual = hotkeysOf(HotkeyPrefs())
        val taken = hotkeyProblem(HotkeyAction.Like, combo("Ctrl+Alt+Space"), usual)
        assertEquals(HotkeyProblem.Taken(HotkeyAction.PlayPause), taken)
        assertEquals("Already set for play or pause.", taken!!.words)
        assertEquals("the same action's own keys are fine", null, hotkeyProblem(HotkeyAction.PlayPause, combo("Ctrl+Alt+Space"), usual))
        assertEquals(HotkeyProblem.NeedsModifier, hotkeyProblem(HotkeyAction.Next, combo("F9"), usual))
        assertEquals(HotkeyProblem.NeedsModifier, hotkeyProblem(HotkeyAction.Next, combo("Shift+F9"), usual))
        assertTrue(hotkeyProblem(HotkeyAction.Next, combo("Ctrl+C"), usual) is HotkeyProblem.AppsUseIt)
        assertTrue(hotkeyProblem(HotkeyAction.Next, combo("Ctrl+Right"), usual) is HotkeyProblem.AppsUseIt)
        assertTrue(hotkeyProblem(HotkeyAction.Next, combo("Ctrl+Alt+Delete"), usual) is HotkeyProblem.WindowsUsesIt)
        assertTrue(hotkeyProblem(HotkeyAction.Next, combo("Alt+F4"), usual) is HotkeyProblem.WindowsUsesIt)
        assertTrue(hotkeyProblem(HotkeyAction.Next, combo("Win+N"), usual) is HotkeyProblem.WindowsUsesIt)
        assertEquals(HotkeyProblem.OctoUsesIt("Commands"), hotkeyProblem(HotkeyAction.Next, combo("Ctrl+Shift+P"), usual))
        assertEquals(HotkeyProblem.OctoUsesIt("going back a page"), hotkeyProblem(HotkeyAction.Next, combo("Alt+Left"), usual))
        assertNull("Ctrl+Shift with a letter is fine", hotkeyProblem(HotkeyAction.Next, combo("Ctrl+Shift+N"), usual))
        assertNull(hotkeyProblem(HotkeyAction.Next, combo("Ctrl+Alt+F9"), usual))
    }

    @Test
    fun onlyChangesAreSavedAndNoneIsKept() {
        val moved = HotkeyPrefs().withKeys(HotkeyAction.Next, combo("Ctrl+Alt+F9"))
        assertEquals(mapOf("next" to "Ctrl+Alt+F9"), moved.keys)
        assertEquals(combo("Ctrl+Alt+F9"), hotkeysOf(moved)[HotkeyAction.Next])
        val none = moved.withKeys(HotkeyAction.Like, null)
        assertEquals("", none.keys["like"])
        assertNull(hotkeysOf(none)[HotkeyAction.Like])
        val back = none.withKeys(HotkeyAction.Next, combo(HotkeyAction.Next.usual))
        assertFalse("the usual keys are not stored", "next" in back.keys)
        assertEquals(combo("Ctrl+Alt+PageDown"), hotkeysOf(back)[HotkeyAction.Next])
    }

    @Test
    fun aSavedShortcutThatNoLongerReadsIsNone() {
        assertNull(hotkeysOf(HotkeyPrefs(keys = mapOf("next" to "Ctrl+Alt+Gibberish")))[HotkeyAction.Next])
    }

    @Test
    fun windowKeysBecomeShortcutKeys() {
        assertEquals(Key.Letter('K'), keyForAwt(KeyEvent.VK_K))
        assertEquals(Key.Digit('5'), keyForAwt(KeyEvent.VK_5))
        assertEquals(Key.Function(9), keyForAwt(KeyEvent.VK_F9))
        assertEquals(Key.Function(13), keyForAwt(KeyEvent.VK_F13))
        assertEquals(Key.NumPad(4), keyForAwt(KeyEvent.VK_NUMPAD4))
        assertEquals(Key.Named(HotKey.PageDown), keyForAwt(KeyEvent.VK_PAGE_DOWN))
        assertEquals(Key.Named(HotKey.Insert), keyForAwt(KeyEvent.VK_INSERT))
        assertEquals(Key.Named(HotKey.Space), keyForAwt(KeyEvent.VK_SPACE))
        assertNull("Tab is the system's", keyForAwt(KeyEvent.VK_TAB))
        assertNull("so is Enter", keyForAwt(KeyEvent.VK_ENTER))
        // Every key read from the window is one a shortcut can be saved as.
        (0..0xFFFF).mapNotNull(::keyForAwt).forEach { key -> assertEquals(key, HotKey.named(key.saved)) }
    }
}
