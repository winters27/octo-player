package app.winters.octo.desktop.window

import app.winters.octo.desktop.settings.DesktopOs
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import java.awt.Window
import javax.swing.JFrame

// Asks Windows 11 to round the corners of the app's frameless window, as it
// does for every framed one. Older Windows ignores the request.
fun roundWindowsCorners(window: Window) {
    runCatching {
        val dwm = Native.load("dwmapi", Dwm::class.java)
        val handle = Native.getWindowPointer(window) ?: return
        dwm.DwmSetWindowAttribute(handle, DWMWA_WINDOW_CORNER_PREFERENCE, IntByReference(DWMWCP_ROUND), 4)
    }
}

// On macOS the window keeps its own traffic lights, over a title bar made
// see-through so the app's glass runs to the top edge.
fun seeThroughMacTitleBar(window: JFrame) {
    window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
    window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
    window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
}

@Suppress("FunctionName")
private interface Dwm : Library {
    fun DwmSetWindowAttribute(window: Pointer, attribute: Int, value: IntByReference, size: Int): Int
}

private const val DWMWA_WINDOW_CORNER_PREFERENCE = 33
private const val DWMWCP_ROUND = 2

// Windows' system title bar drawn dark or light to match the app, repainted
// at once.
fun darkTitleBar(window: Window, dark: Boolean, build: Int? = windowsBuild()) {
    runCatching {
        val handle = Native.getWindowPointer(window) ?: return
        val dwm = Native.load("dwmapi", Dwm::class.java)
        dwm.DwmSetWindowAttribute(handle, darkTitleBarAttribute(build), IntByReference(if (dark) 1 else 0), 4)
        Native.load("user32", User32::class.java).SetWindowPos(handle, null, 0, 0, 0, 0, SWP_FRAMECHANGED or SWP_NOMOVE or SWP_NOSIZE or SWP_NOZORDER)
    }
}

// The attribute that darkens the title bar: 20 from Windows 10 build 19041,
// the undocumented 19 before it.
internal fun darkTitleBarAttribute(build: Int?): Int = if ((build ?: 0) >= WINDOWS_DARK_TITLE_BUILD) DWMWA_USE_IMMERSIVE_DARK_MODE else DWMWA_USE_IMMERSIVE_DARK_MODE_OLD

private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
private const val DWMWA_USE_IMMERSIVE_DARK_MODE_OLD = 19
private const val WINDOWS_DARK_TITLE_BUILD = 19_041

@Suppress("FunctionName")
private interface User32 : Library {
    fun SetWindowPos(window: Pointer, after: Pointer?, x: Int, y: Int, width: Int, height: Int, flags: Int): Boolean
}

private const val SWP_NOSIZE = 0x0001
private const val SWP_NOMOVE = 0x0002
private const val SWP_NOZORDER = 0x0004
private const val SWP_FRAMECHANGED = 0x0020

// macOS's title bar and traffic lights in the dark or light appearance.
fun macAppearance(window: JFrame, dark: Boolean) {
    window.rootPane.putClientProperty("apple.awt.windowAppearance", macAppearanceName(dark))
}

internal fun macAppearanceName(dark: Boolean): String = if (dark) "NSAppearanceNameDarkAqua" else "NSAppearanceNameAqua"

// A Linux window's GTK theme variant, which window managers that draw a
// GTK title bar follow, set on the X11 window.
fun gtkVariant(window: Window, dark: Boolean) {
    runCatching {
        val id = Native.getWindowID(window)
        val x = Native.load("X11", X11::class.java)
        val display = x.XOpenDisplay(null) ?: return
        try {
            val property = x.XInternAtom(display, "_GTK_THEME_VARIANT", false)
            val text = x.XInternAtom(display, "UTF8_STRING", false)
            val value = gtkVariantName(dark).toByteArray(Charsets.UTF_8)
            x.XChangeProperty(display, id, property, text, 8, X_PROP_MODE_REPLACE, value, value.size)
            x.XFlush(display)
        } finally {
            x.XCloseDisplay(display)
        }
    }
}

internal fun gtkVariantName(dark: Boolean): String = if (dark) "dark" else "light"

@Suppress("FunctionName")
private interface X11 : Library {
    fun XOpenDisplay(name: String?): Pointer?
    fun XCloseDisplay(display: Pointer): Int
    fun XInternAtom(display: Pointer, name: String, onlyIfExists: Boolean): Long
    fun XChangeProperty(display: Pointer, window: Long, property: Long, type: Long, format: Int, mode: Int, data: ByteArray, count: Int): Int
    fun XFlush(display: Pointer): Int
}

private const val X_PROP_MODE_REPLACE = 0

// Whether the system rounds a frameless window's corners itself, with its
// own edge and shadow: Windows 11 (build 22000 on) does once asked
// (roundWindowsCorners). Elsewhere a window with round corners is drawn
// see-through outside its rounded shape.
fun systemRoundsWindows(os: DesktopOs, build: () -> Int? = ::windowsBuild): Boolean =
    os == DesktopOs.Windows && (build() ?: 0) >= WINDOWS_11_BUILD && System.getProperty(SOAK_AS_WINDOWS_10) == null

// Set for a soak, the app draws as it does on Windows 10 (a see-through mini player).
const val SOAK_AS_WINDOWS_10 = "octo.soak.windows10"

// The Windows build number, as the system itself has it (the version an
// app is told can be held back for older apps).
private fun windowsBuild(): Int? = runCatching {
    val info = Memory(OS_VERSION_INFO_BYTES).apply {
        clear()
        setInt(0, OS_VERSION_INFO_BYTES.toInt())
    }
    if (Native.load("ntdll", NtDll::class.java).RtlGetVersion(info) != 0) null else info.getInt(12)
}.getOrNull()

@Suppress("FunctionName")
private interface NtDll : Library {
    fun RtlGetVersion(info: Pointer): Int
}

// RTL_OSVERSIONINFOW: five 32-bit fields and 128 UTF-16 characters.
private const val OS_VERSION_INFO_BYTES = 276L
private const val WINDOWS_11_BUILD = 22_000
