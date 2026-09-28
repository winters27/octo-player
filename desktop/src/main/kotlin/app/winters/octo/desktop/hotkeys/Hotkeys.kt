package app.winters.octo.desktop.hotkeys

import kotlinx.serialization.Serializable

// Keys that reach Octo from any app, even with its window hidden. Off
// until the listener turns them on. `keys` holds the ones they changed, by
// action (see HotkeyAction.id), as "Ctrl+Alt+Space"; an empty one means
// that action has none. Actions not in it use their usual keys.
@Serializable
data class HotkeyPrefs(
    val on: Boolean = false,
    val keys: Map<String, String> = emptyMap(),
)

// What a global shortcut can do, with its usual keys. The usual keys are
// Ctrl+Alt with keys that type nothing, so none of them is a character on
// a keyboard with AltGr (which is Ctrl+Alt), and none is one of Windows'
// own. They follow the long-standing music player layout: Page Up and
// Page Down for the songs, the arrows for the volume, Home to come back to
// Octo.
enum class HotkeyAction(val id: String, val label: String, val usual: String) {
    PlayPause("playPause", "Play or pause", "Ctrl+Alt+Space"),
    Next("next", "Next song", "Ctrl+Alt+PageDown"),
    Previous("previous", "Previous song", "Ctrl+Alt+PageUp"),
    VolumeUp("volumeUp", "Volume up", "Ctrl+Alt+Up"),
    VolumeDown("volumeDown", "Volume down", "Ctrl+Alt+Down"),
    ShowHide("showHide", "Show or hide Octo", "Ctrl+Alt+Home"),
    MiniPlayer("miniPlayer", "Mini player", "Ctrl+Alt+End"),
    Like("like", "Add the song to favourites, or take it out", "Ctrl+Alt+Insert"),
    ;

    // Holding the keys down repeats only the volume.
    val repeats: Boolean get() = this == VolumeUp || this == VolumeDown
}

// A key that can be part of a global shortcut: its name as saved, the name
// shown, and Windows' number for it. The media keys are left out on
// purpose: the system's media controls already bring them to Octo.
enum class HotKey(val saved: String, val shown: String, val windowsCode: Int) {
    Space("Space", "Space", 0x20),
    PageUp("PageUp", "Page Up", 0x21),
    PageDown("PageDown", "Page Down", 0x22),
    End("End", "End", 0x23),
    Home("Home", "Home", 0x24),
    Left("Left", "Left", 0x25),
    Up("Up", "Up", 0x26),
    Right("Right", "Right", 0x27),
    Down("Down", "Down", 0x28),
    Insert("Insert", "Insert", 0x2D),
    Delete("Delete", "Delete", 0x2E),
    Pause("Pause", "Pause", 0x13),
    Semicolon("Semicolon", ";", 0xBA),
    Equals("Equals", "=", 0xBB),
    Comma("Comma", ",", 0xBC),
    Minus("Minus", "-", 0xBD),
    Period("Period", ".", 0xBE),
    Slash("Slash", "/", 0xBF),
    Backquote("Backquote", "`", 0xC0),
    LeftBracket("LeftBracket", "[", 0xDB),
    Backslash("Backslash", "\\", 0xDC),
    RightBracket("RightBracket", "]", 0xDD),
    Quote("Quote", "'", 0xDE),
    NumPadMultiply("NumPadMultiply", "Num *", 0x6A),
    NumPadAdd("NumPadAdd", "Num +", 0x6B),
    NumPadSubtract("NumPadSubtract", "Num -", 0x6D),
    NumPadDivide("NumPadDivide", "Num /", 0x6F),
    ;

    companion object {
        private val byName = entries.associateBy { it.saved.lowercase() }

        // Letters, digits, F1 to F24 and the number pad's digits have names
        // of their own, made here rather than listed.
        fun named(name: String): Key? {
            val lower = name.lowercase()
            byName[lower]?.let { return Key.Named(it) }
            if (name.length == 1 && name[0].uppercaseChar() in 'A'..'Z') return Key.Letter(name[0].uppercaseChar())
            if (name.length == 1 && name[0] in '0'..'9') return Key.Digit(name[0])
            if (lower.startsWith("f")) lower.drop(1).toIntOrNull()?.takeIf { it in 1..24 }?.let { return Key.Function(it) }
            if (lower.startsWith("numpad")) lower.removePrefix("numpad").toIntOrNull()?.takeIf { it in 0..9 }?.let { return Key.NumPad(it) }
            return null
        }
    }
}

// The key in a shortcut.
sealed interface Key {
    val saved: String
    val shown: String
    val windowsCode: Int

    data class Named(val key: HotKey) : Key {
        override val saved get() = key.saved
        override val shown get() = key.shown
        override val windowsCode get() = key.windowsCode
    }

    data class Letter(val letter: Char) : Key {
        override val saved get() = letter.toString()
        override val shown get() = saved
        override val windowsCode get() = letter.code
    }

    data class Digit(val digit: Char) : Key {
        override val saved get() = digit.toString()
        override val shown get() = saved
        override val windowsCode get() = digit.code
    }

    data class Function(val number: Int) : Key {
        override val saved get() = "F$number"
        override val shown get() = saved
        override val windowsCode get() = 0x70 + number - 1
    }

    data class NumPad(val digit: Int) : Key {
        override val saved get() = "NumPad$digit"
        override val shown get() = "Num $digit"
        override val windowsCode get() = 0x60 + digit
    }
}

// A key with the keys held with it.
data class KeyCombo(
    val key: Key,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
    val win: Boolean = false,
) {
    // As saved: "Ctrl+Alt+PageDown".
    val saved: String get() = (modifierNames() + key.saved).joinToString("+")

    // As shown: "Ctrl+Alt+Page Down".
    val shown: String get() = (modifierNames() + key.shown).joinToString("+")

    private fun modifierNames() = listOfNotNull("Ctrl".takeIf { ctrl }, "Alt".takeIf { alt }, "Shift".takeIf { shift }, "Win".takeIf { win })

    // Windows' numbers for the keys held, as RegisterHotKey takes them.
    val windowsModifiers: Int get() = (if (alt) 0x1 else 0) or (if (ctrl) 0x2 else 0) or (if (shift) 0x4 else 0) or (if (win) 0x8 else 0)

    companion object {
        // Reads "Ctrl+Alt+Space", in any case and order of the held keys,
        // or null when it is not a shortcut.
        fun parse(text: String): KeyCombo? {
            val parts = text.split('+').map(String::trim)
            if (parts.isEmpty() || parts.any(String::isEmpty)) return plusKey(text)
            var ctrl = false
            var alt = false
            var shift = false
            var win = false
            for (part in parts.dropLast(1)) {
                when (part.lowercase()) {
                    "ctrl", "control" -> ctrl = true
                    "alt" -> alt = true
                    "shift" -> shift = true
                    "win", "windows", "meta", "super" -> win = true
                    else -> return null
                }
            }
            val key = HotKey.named(parts.last()) ?: return null
            return KeyCombo(key, ctrl, alt, shift, win)
        }

        // "Ctrl+Alt++" names the plus on the number pad the only way it can.
        private fun plusKey(text: String): KeyCombo? =
            if (text.endsWith("++")) parse(text.dropLast(2) + "+NumPadAdd") else null
    }
}

// The keys each action has: the listener's own where they set one (none
// where they cleared it), the usual keys otherwise.
fun hotkeysOf(prefs: HotkeyPrefs): Map<HotkeyAction, KeyCombo?> = HotkeyAction.entries.associateWith { action ->
    val own = prefs.keys[action.id]
    when {
        own == null -> KeyCombo.parse(action.usual)
        own.isBlank() -> null
        else -> KeyCombo.parse(own)
    }
}

// The prefs with one action's keys set (null for none). Setting the usual
// keys forgets the change, so the file keeps only what differs.
fun HotkeyPrefs.withKeys(action: HotkeyAction, combo: KeyCombo?): HotkeyPrefs {
    val usual = KeyCombo.parse(action.usual)
    val keys = when (combo) {
        usual -> this.keys - action.id
        null -> this.keys + (action.id to "")
        else -> this.keys + (action.id to combo.saved)
    }
    return copy(keys = keys)
}

// Why a shortcut can't be used, in words for the settings row.
sealed interface HotkeyProblem {
    val words: String

    // Needs Ctrl, Alt or Win, or every press of that key in any app would come to Octo.
    data object NeedsModifier : HotkeyProblem {
        override val words = "Hold Ctrl or Alt with it, so typing that key still works in other apps."
    }

    // Ctrl with a letter is every app's own (copy, paste, save).
    data class AppsUseIt(val combo: KeyCombo) : HotkeyProblem {
        override val words get() = "Apps use ${combo.shown} for their own work. Add Alt or Shift."
    }

    // Windows keeps it for itself.
    data class WindowsUsesIt(val combo: KeyCombo) : HotkeyProblem {
        override val words get() = "Windows keeps ${combo.shown} for itself."
    }

    // One of Octo's own shortcuts in its window.
    data class OctoUsesIt(val what: String) : HotkeyProblem {
        override val words get() = "Octo already uses these for $what."
    }

    // A key Octo can't claim (a media key, Tab, a key it doesn't know).
    data object UnknownKey : HotkeyProblem {
        override val words = "Octo can't use that key for a shortcut. Try another."
    }

    // Another global shortcut has it.
    data class Taken(val by: HotkeyAction) : HotkeyProblem {
        override val words get() = "Already set for ${by.label.lowercase()}."
    }
}

// Windows' own keys, which an app cannot or should not take.
private val windowsKeeps = listOf("Ctrl+Alt+Delete", "Alt+F4", "Alt+Space")

// Octo's own shortcuts in its window that hold Ctrl or Alt (see
// nav/Shortcuts.kt). A global one on the same keys would take them from it.
private val octoKeeps = mapOf(
    "Ctrl+Shift+P" to "Commands",
    "Alt+Left" to "going back a page",
    "Alt+Right" to "going forward a page",
)

// Whether `combo` can be set for `action` while the others have `current`,
// or why not.
fun hotkeyProblem(action: HotkeyAction, combo: KeyCombo, current: Map<HotkeyAction, KeyCombo?>): HotkeyProblem? {
    if (!combo.ctrl && !combo.alt && !combo.win) return HotkeyProblem.NeedsModifier
    if (combo.win || windowsKeeps.any { sameKeys(it, combo) }) return HotkeyProblem.WindowsUsesIt(combo)
    octoKeeps.entries.firstOrNull { sameKeys(it.key, combo) }?.let { return HotkeyProblem.OctoUsesIt(it.value) }
    val plainCtrl = combo.ctrl && !combo.alt && !combo.shift
    if (plainCtrl && (combo.key is Key.Letter || combo.key is Key.Digit || combo.key.let { it is Key.Named && it.key in ctrlEverywhere })) {
        return HotkeyProblem.AppsUseIt(combo)
    }
    current.entries.firstOrNull { (other, keys) -> other != action && keys == combo }?.let { return HotkeyProblem.Taken(it.key) }
    return null
}

// Keys every app gives a meaning with Ctrl alone.
private val ctrlEverywhere = setOf(HotKey.Space, HotKey.Left, HotKey.Right, HotKey.Up, HotKey.Down, HotKey.Home, HotKey.End, HotKey.PageUp, HotKey.PageDown, HotKey.Insert, HotKey.Delete, HotKey.Equals, HotKey.Minus, HotKey.Comma, HotKey.Slash)

private fun sameKeys(text: String, combo: KeyCombo): Boolean = KeyCombo.parse(text) == combo
