package app.winters.octo.desktop.system

import app.winters.octo.desktop.settings.AppSettings
import app.winters.octo.desktop.settings.SettingsStore
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StartAtLoginTest {
    @get:Rule val folder = TemporaryFolder()

    private val program = "C:\\Users\\A B\\AppData\\Local\\Octo\\Octo.exe"

    // The sign-in list in memory, counting its writes.
    private class FakeEntries(var turnedOffNow: Boolean = false) : StartupEntries {
        val values = HashMap<String, String>()
        var writes = 0

        override fun read(name: String) = values[name]

        override fun write(name: String, command: String): Boolean {
            writes++
            values[name] = command
            return true
        }

        override fun remove(name: String): Boolean {
            values.remove(name)
            return true
        }

        override fun turnedOff(name: String) = turnedOffNow
    }

    @Test
    fun theCommandQuotesTheProgramAndAsksForTheTrayWhenWanted() {
        assertEquals("\"$program\"", startCommand(program, inTray = false))
        assertEquals("\"$program\" --tray", startCommand(program, inTray = true))
        assertTrue(startsInTray(listOf("--tray")))
        assertTrue(startsInTray(listOf("octo://home", " --TRAY ")))
        assertFalse(startsInTray(listOf("C:\\Music\\--tray.flac")))
        assertFalse(startsInTray(emptyList()))
    }

    @Test
    fun onlyOctosOwnLineCountsAsOctos() {
        assertTrue(isOctoCommand(startCommand(program, inTray = true)))
        assertTrue(isOctoCommand("/opt/octo/bin/Octo --tray"))
        assertFalse(isOctoCommand("\"C:\\Program Files\\Other\\Other.exe\" --octo"))
        assertFalse(isOctoCommand("\"C:\\Octo Tools\\helper.exe\""))
        assertFalse(isOctoCommand(null))
    }

    @Test
    fun turningItOnWritesTheLineOnceAndFollowsTheTraySwitch() {
        val entries = FakeEntries()
        assertTrue(syncStartAtLogin(on = true, inTray = false, program, entries))
        assertEquals("\"$program\"", entries.values[RUN_VALUE])
        assertTrue(syncStartAtLogin(on = true, inTray = false, program, entries))
        assertEquals(1, entries.writes, "nothing is written when it is already right")
        assertTrue(syncStartAtLogin(on = true, inTray = true, program, entries))
        assertEquals("\"$program\" --tray", entries.values[RUN_VALUE])
        // Octo moved: the line follows it.
        assertTrue(syncStartAtLogin(on = true, inTray = true, "D:\\Apps\\Octo\\Octo.exe", entries))
        assertEquals("\"D:\\Apps\\Octo\\Octo.exe\" --tray", entries.values[RUN_VALUE])
    }

    @Test
    fun turningItOffRemovesOctosLineButNeverAnotherPrograms() {
        val entries = FakeEntries()
        syncStartAtLogin(on = true, inTray = false, program, entries)
        assertTrue(syncStartAtLogin(on = false, inTray = false, program, entries))
        assertNull(entries.values[RUN_VALUE])
        entries.values[RUN_VALUE] = "\"C:\\Other\\Other.exe\""
        assertTrue(syncStartAtLogin(on = false, inTray = false, program, entries))
        assertEquals("\"C:\\Other\\Other.exe\"", entries.values[RUN_VALUE])
        assertTrue(syncStartAtLogin(on = false, inTray = false, program, FakeEntries()), "off with nothing there is as wanted")
    }

    @Test
    fun taskManagersSwitchReadsOddAsOff() {
        assertFalse(turnedOffBy(null))
        assertFalse(turnedOffBy(2))
        assertTrue(turnedOffBy(3))
        assertFalse(turnedOffBy(6))
        assertTrue(turnedOffBy(7))
        assertEquals("Opens Octo when you sign in, for music ready as soon as you are.", startCaption(turnedOff = false))
        assertTrue(startCaption(turnedOff = true).startsWith("Turned off in Task Manager"))
    }

    @Test
    fun bothSwitchesSurviveARestart() {
        val file = File(folder.root, "octo/${SettingsStore.FILE_NAME}")
        val changed = AppSettings(system = SystemPrefs(startWithWindows = true, startInTray = true))
        SettingsStore(file).update { changed }
        assertEquals(changed, SettingsStore(file).current)
        assertFalse(AppSettings().system.startWithWindows, "off until asked for")
        assertFalse(AppSettings().system.startInTray)
    }

    // The real registry calls, on keys of the test's own. Windows' own Run
    // key and Octo's line in it are never touched.
    @Test
    fun theRegistryKeepsTheLineExactly() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val base = "Software\\OctoTest-" + System.nanoTime()
        val entries = WindowsStartupEntries(runKey = "$base\\Run", approvedKey = "$base\\StartupApproved")
        val name = "OctoTest"
        try {
            assertNull(entries.read(name))
            assertFalse(entries.turnedOff(name), "no switch is on")
            assertTrue(syncStartAtLogin(on = true, inTray = true, program, entries, name))
            assertEquals("\"$program\" --tray", entries.read(name))
            assertTrue(syncStartAtLogin(on = false, inTray = true, program, entries, name))
            assertNull(entries.read(name))
            assertTrue(entries.remove(name), "removing what is gone is fine")
        } finally {
            WindowsRegistry.remove(base)
        }
        assertNull(WindowsRegistry.read("$base\\Run", name))
    }
}
