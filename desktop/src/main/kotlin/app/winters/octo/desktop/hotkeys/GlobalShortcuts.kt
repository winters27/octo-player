package app.winters.octo.desktop.hotkeys

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.desktop.settings.SettingsStore
import java.awt.event.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// The global shortcuts as the app and the settings page see them: claims
// the listener's keys while they are on, hears the presses, and takes a new
// set of keys pressed in Settings (checked for clashes before it is kept).
@Stable
class GlobalShortcuts(
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    os: DesktopOs,
    onAction: (HotkeyAction) -> Unit,
    backend: HotkeyBackend = if (os == DesktopOs.Windows) WindowsHotkeys() else NoHotkeys(),
) : AutoCloseable {
    private val keeper = HotkeyKeeper(backend).also { keeper -> keeper.onAction = { action -> scope.launch { onAction(action) } } }

    // Claiming and letting go wait on the system, so they happen off the
    // window's thread, one at a time and in order.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val claims = Dispatchers.IO.limitedParallelism(1)

    // Only Windows lets Octo take keys from other apps so far.
    val available: Boolean = os == DesktopOs.Windows

    // The action whose new keys are being pressed in Settings.
    var recording by mutableStateOf<HotkeyAction?>(null)
        private set

    // Why the last keys pressed could not be used, and for which action.
    var problem by mutableStateOf<Pair<HotkeyAction, HotkeyProblem>?>(null)
        private set

    // The actions whose keys another app already has.
    var refused by mutableStateOf<Set<HotkeyAction>>(emptySet())
        private set

    fun start() {
        if (!available) return
        scope.launch { settings.state.map { it.hotkeys }.distinctUntilChanged().collect { claim() } }
    }

    private fun claim() {
        val prefs = settings.current.hotkeys
        val holdOff = recording != null
        scope.launch {
            val answer = withContext(claims) { keeper.apply(prefs.on, hotkeysOf(prefs), holdOff) }
            refused = answer
        }
    }

    // Starts listening for new keys for `action`; the keys already claimed
    // are let go meanwhile, so pressing them reaches the window.
    fun record(action: HotkeyAction) {
        recording = action
        problem = null
        claim()
    }

    fun stopRecording() {
        if (recording == null) return
        recording = null
        claim()
    }

    // A key pressed while recording, from the window: Esc stops, Backspace
    // or Delete alone leaves the action with no keys, the keys held alone
    // wait for the rest. Answers whether the press was used.
    fun pressed(awtKeyCode: Int, ctrl: Boolean, alt: Boolean, shift: Boolean, meta: Boolean): Boolean {
        val action = recording ?: return false
        val plain = !ctrl && !alt && !shift && !meta
        when {
            awtKeyCode in modifierKeys -> return true
            awtKeyCode == KeyEvent.VK_ESCAPE && plain -> stopRecording()
            (awtKeyCode == KeyEvent.VK_BACK_SPACE || awtKeyCode == KeyEvent.VK_DELETE) && plain -> {
                settings.update { it.copy(hotkeys = it.hotkeys.withKeys(action, null)) }
                stopRecording()
            }
            else -> {
                val key = keyForAwt(awtKeyCode)
                if (key == null) {
                    problem = action to HotkeyProblem.UnknownKey
                    return true
                }
                val combo = KeyCombo(key, ctrl = ctrl, alt = alt, shift = shift, win = meta)
                val why = hotkeyProblem(action, combo, hotkeysOf(settings.current.hotkeys))
                if (why != null) {
                    problem = action to why
                    return true
                }
                settings.update { it.copy(hotkeys = it.hotkeys.withKeys(action, combo)) }
                stopRecording()
            }
        }
        return true
    }

    // Every action back on its usual keys.
    fun useUsualKeys() {
        problem = null
        settings.update { it.copy(hotkeys = it.hotkeys.copy(keys = emptyMap())) }
    }

    override fun close() = keeper.close()
}

private val modifierKeys = setOf(KeyEvent.VK_CONTROL, KeyEvent.VK_ALT, KeyEvent.VK_SHIFT, KeyEvent.VK_META, KeyEvent.VK_WINDOWS, KeyEvent.VK_ALT_GRAPH)

// The key a window key press names, by Java's key numbers (which Compose
// uses on the desktop), or null for one a global shortcut can't use.
fun keyForAwt(code: Int): Key? = when (code) {
    in KeyEvent.VK_A..KeyEvent.VK_Z -> Key.Letter(code.toChar())
    in KeyEvent.VK_0..KeyEvent.VK_9 -> Key.Digit(code.toChar())
    in KeyEvent.VK_F1..KeyEvent.VK_F12 -> Key.Function(code - KeyEvent.VK_F1 + 1)
    in KeyEvent.VK_F13..KeyEvent.VK_F24 -> Key.Function(code - KeyEvent.VK_F13 + 13)
    in KeyEvent.VK_NUMPAD0..KeyEvent.VK_NUMPAD9 -> Key.NumPad(code - KeyEvent.VK_NUMPAD0)
    else -> awtNamed[code]?.let(Key::Named)
}

private val awtNamed = mapOf(
    KeyEvent.VK_SPACE to HotKey.Space,
    KeyEvent.VK_PAGE_UP to HotKey.PageUp,
    KeyEvent.VK_PAGE_DOWN to HotKey.PageDown,
    KeyEvent.VK_END to HotKey.End,
    KeyEvent.VK_HOME to HotKey.Home,
    KeyEvent.VK_LEFT to HotKey.Left,
    KeyEvent.VK_UP to HotKey.Up,
    KeyEvent.VK_RIGHT to HotKey.Right,
    KeyEvent.VK_DOWN to HotKey.Down,
    KeyEvent.VK_INSERT to HotKey.Insert,
    KeyEvent.VK_DELETE to HotKey.Delete,
    KeyEvent.VK_PAUSE to HotKey.Pause,
    KeyEvent.VK_SEMICOLON to HotKey.Semicolon,
    KeyEvent.VK_EQUALS to HotKey.Equals,
    KeyEvent.VK_COMMA to HotKey.Comma,
    KeyEvent.VK_MINUS to HotKey.Minus,
    KeyEvent.VK_PERIOD to HotKey.Period,
    KeyEvent.VK_SLASH to HotKey.Slash,
    KeyEvent.VK_BACK_QUOTE to HotKey.Backquote,
    KeyEvent.VK_OPEN_BRACKET to HotKey.LeftBracket,
    KeyEvent.VK_BACK_SLASH to HotKey.Backslash,
    KeyEvent.VK_CLOSE_BRACKET to HotKey.RightBracket,
    KeyEvent.VK_QUOTE to HotKey.Quote,
    KeyEvent.VK_MULTIPLY to HotKey.NumPadMultiply,
    KeyEvent.VK_ADD to HotKey.NumPadAdd,
    KeyEvent.VK_SUBTRACT to HotKey.NumPadSubtract,
    KeyEvent.VK_DIVIDE to HotKey.NumPadDivide,
)
