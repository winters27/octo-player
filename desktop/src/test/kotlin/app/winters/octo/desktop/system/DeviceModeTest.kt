package app.winters.octo.desktop.system

import app.winters.octo.desktop.settings.DesktopOs
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceModeTest {
    // 0 is no preference and counts as dark; only 2 is light.
    @Test
    fun portalValuesZeroOneAndTwo() {
        assertTrue(portalValueIsDark(0))
        assertTrue(portalValueIsDark(1))
        assertFalse(portalValueIsDark(2))
    }

    // Windows keeps 0 for dark; no value at all is dark too.
    @Test
    fun windowsReadsTheAppsUseLightThemeValue() {
        assertTrue(DeviceMode.windowsDark(0))
        assertTrue(DeviceMode.windowsDark(null))
        assertFalse(DeviceMode.windowsDark(1))
    }

    // macOS says Dark in dark mode and fails the read in light mode.
    @Test
    fun macReadsTheInterfaceStyle() {
        assertTrue(DeviceMode.macDark("Dark\n"))
        assertFalse(DeviceMode.macDark(null))
        assertFalse(DeviceMode.macDark(""))
    }

    // The portal wraps its answer in a variant, sometimes twice.
    @Test
    fun portalAnswersAreUnwrapped() {
        assertEquals(1, portalNumber(Variant(UInt32(1))))
        assertEquals(2, portalNumber(Variant(Variant(UInt32(2)))))
        assertNull(portalNumber(Variant("dark")))
        assertNull(portalNumber(null))
    }

    // Linux with no watcher is dark, and with one it is the watcher's answer.
    @Test
    fun linuxTakesThePortalsAnswer() {
        assertTrue(DeviceMode.seed(DesktopOs.Linux))
        val light = PortalWatcher(50, FakePortal(read = 2))
        light.start()
        assertFalse(DeviceMode.seed(DesktopOs.Linux, light, waitForPortal = true))
        assertFalse(DeviceMode.seed(DesktopOs.Linux, light))
    }
}
