package app.winters.octo.desktop.hotkeys

import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.desktop.settings.SettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.event.KeyEvent
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

// A pretend system to claim keys from: it keeps what is claimed, refuses
// the keys another app is said to have, and can be pressed. No real key is
// ever claimed or pressed here.
class FakeHotkeys(private val othersHave: Set<KeyCombo> = emptySet(), private val works: Boolean = true) : HotkeyBackend {
    val claimed = HashMap<Int, KeyCombo>()
    val repeating = HashSet<Int>()
    var started = 0
    var closed = false
    private var press: (Int) -> Unit = {}

    override fun start(onPress: (Int) -> Unit): Boolean {
        started++
        press = onPress
        return works
    }

    override fun register(id: Int, combo: KeyCombo, repeats: Boolean): Boolean {
        if (combo in othersHave || id in claimed) return false
        claimed[id] = combo
        if (repeats) repeating += id
        return true
    }

    override fun unregister(id: Int) {
        claimed.remove(id)
        repeating.remove(id)
    }

    fun pressKeys(combo: KeyCombo) = claimed.entries.firstOrNull { it.value == combo }?.let { press(it.key) }

    override fun close() {
        closed = true
    }
}

class HotkeyKeeperTest {
    @get:Rule val folder = TemporaryFolder()

    private fun combo(text: String) = KeyCombo.parse(text)!!

    @Test
    fun offClaimsNothingAndOnClaimsEveryAction() {
        val system = FakeHotkeys()
        val keeper = HotkeyKeeper(system)
        keeper.apply(false, hotkeysOf(HotkeyPrefs()))
        assertEquals("never even starts while off", 0, system.started)
        assertTrue(system.claimed.isEmpty())
        keeper.apply(true, hotkeysOf(HotkeyPrefs()))
        assertEquals(HotkeyAction.entries.size, system.claimed.size)
        assertEquals("only the volume repeats while held", 2, system.repeating.size)
        keeper.apply(false, hotkeysOf(HotkeyPrefs()))
        assertTrue("off lets every one go", system.claimed.isEmpty())
    }

    @Test
    fun aPressDoesItsAction() {
        val system = FakeHotkeys()
        val keeper = HotkeyKeeper(system)
        val done = ArrayList<HotkeyAction>()
        keeper.onAction = { done += it }
        keeper.apply(true, hotkeysOf(HotkeyPrefs()))
        system.pressKeys(combo("Ctrl+Alt+PageDown"))
        system.pressKeys(combo("Ctrl+Alt+Insert"))
        assertEquals(listOf(HotkeyAction.Next, HotkeyAction.Like), done)
    }

    @Test
    fun keysAnotherAppHasAreReported() {
        val system = FakeHotkeys(othersHave = setOf(KeyCombo.parse("Ctrl+Alt+Up")!!))
        val keeper = HotkeyKeeper(system)
        assertEquals(setOf(HotkeyAction.VolumeUp), keeper.apply(true, hotkeysOf(HotkeyPrefs())))
        // Moved to free keys, it is claimed and no longer reported.
        val moved = HotkeyPrefs().withKeys(HotkeyAction.VolumeUp, combo("Ctrl+Alt+F10"))
        assertEquals(emptySet<HotkeyAction>(), keeper.apply(true, hotkeysOf(moved)))
        assertEquals(combo("Ctrl+Alt+F10"), system.claimed[HotkeyAction.VolumeUp.ordinal + 1])
    }

    @Test
    fun newKeysReplaceTheOldAndNoneLetsGo() {
        val system = FakeHotkeys()
        val keeper = HotkeyKeeper(system)
        keeper.apply(true, hotkeysOf(HotkeyPrefs()))
        val prefs = HotkeyPrefs().withKeys(HotkeyAction.Next, combo("Ctrl+Alt+F9")).withKeys(HotkeyAction.Like, null)
        keeper.apply(true, hotkeysOf(prefs))
        assertEquals(combo("Ctrl+Alt+F9"), system.claimed[HotkeyAction.Next.ordinal + 1])
        assertFalse("the old keys are free again", combo("Ctrl+Alt+PageDown") in system.claimed.values)
        assertNull(system.claimed[HotkeyAction.Like.ordinal + 1])
    }

    @Test
    fun heldOffWhileNewKeysArePressedThenBack() {
        val system = FakeHotkeys()
        val keeper = HotkeyKeeper(system)
        keeper.apply(true, hotkeysOf(HotkeyPrefs()))
        keeper.apply(true, hotkeysOf(HotkeyPrefs()), holdOff = true)
        assertTrue(system.claimed.isEmpty())
        keeper.apply(true, hotkeysOf(HotkeyPrefs()))
        assertEquals(HotkeyAction.entries.size, system.claimed.size)
    }

    @Test
    fun aSystemWithoutShortcutsRefusesThemAll() {
        val keeper = HotkeyKeeper(FakeHotkeys(works = false))
        assertEquals(HotkeyAction.entries.toSet(), keeper.apply(true, hotkeysOf(HotkeyPrefs())))
        assertFalse(keeper.available)
    }

    @Test
    fun closingLetsEveryKeyGo() {
        val system = FakeHotkeys()
        HotkeyKeeper(system).apply {
            apply(true, hotkeysOf(HotkeyPrefs()))
            close()
        }
        assertTrue(system.claimed.isEmpty())
        assertTrue(system.closed)
    }

    // Pressing new keys in Settings: what is kept, what is refused and why.
    @Test
    fun keysPressedInSettingsAreCheckedThenKept() {
        val settings = SettingsStore(File(folder.root, "settings.json"))
        val shortcuts = GlobalShortcuts(settings, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), DesktopOs.Windows, {}, FakeHotkeys())
        assertFalse("not listening, keys pass by", shortcuts.pressed(KeyEvent.VK_K, ctrl = true, alt = true, shift = false, meta = false))

        shortcuts.record(HotkeyAction.Like)
        assertTrue("Ctrl alone waits for the rest", shortcuts.pressed(KeyEvent.VK_CONTROL, ctrl = true, alt = false, shift = false, meta = false))
        assertEquals(HotkeyAction.Like, shortcuts.recording)
        shortcuts.pressed(KeyEvent.VK_SPACE, ctrl = true, alt = true, shift = false, meta = false)
        assertEquals("taken by play or pause: still listening", HotkeyAction.Like, shortcuts.recording)
        assertEquals(HotkeyProblem.Taken(HotkeyAction.PlayPause), shortcuts.problem?.second)
        shortcuts.pressed(KeyEvent.VK_L, ctrl = true, alt = true, shift = true, meta = false)
        assertNull(shortcuts.recording)
        assertEquals("Ctrl+Alt+Shift+L", settings.current.hotkeys.keys["like"])

        shortcuts.record(HotkeyAction.Next)
        shortcuts.pressed(KeyEvent.VK_BACK_SPACE, ctrl = false, alt = false, shift = false, meta = false)
        assertNull("Backspace leaves it with none", hotkeysOf(settings.current.hotkeys)[HotkeyAction.Next])

        shortcuts.record(HotkeyAction.Previous)
        shortcuts.pressed(KeyEvent.VK_ESCAPE, ctrl = false, alt = false, shift = false, meta = false)
        assertNull(shortcuts.recording)
        assertEquals("Esc changes nothing", combo("Ctrl+Alt+PageUp"), hotkeysOf(settings.current.hotkeys)[HotkeyAction.Previous])

        shortcuts.useUsualKeys()
        assertEquals(hotkeysOf(HotkeyPrefs()), hotkeysOf(settings.current.hotkeys))
        shortcuts.close()
    }
}
