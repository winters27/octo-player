package app.winters.octo.desktop.window

import com.sun.jna.Library
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
