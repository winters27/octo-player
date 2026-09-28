package app.winters.octo.desktop.settings

import com.sun.jna.Library
import com.sun.jna.Native
import java.util.concurrent.TimeUnit

// Whether the system asks apps to hold still: "Animation effects" off on
// Windows, "Reduce motion" on macOS, animations off in GNOME. Read once
// when Octo opens; the app's own Calm motion setting covers the rest.
fun systemReducesMotion(os: DesktopOs): Boolean = runCatching {
    when (os) {
        DesktopOs.Windows -> !windowsAnimates()
        DesktopOs.Mac -> macReduceMotion(ask("defaults", "read", "com.apple.universalaccess", "reduceMotion"))
        DesktopOs.Linux -> gnomeAnimationsOff(ask("gsettings", "get", "org.gnome.desktop.interface", "enable-animations"))
    }
}.getOrDefault(false)

// What `defaults read ... reduceMotion` prints when it is on.
fun macReduceMotion(answer: String?): Boolean = answer?.trim() == "1"

// What `gsettings get ... enable-animations` prints when animations are off.
fun gnomeAnimationsOff(answer: String?): Boolean = answer?.trim() == "false"

private interface User32 : Library {
    fun SystemParametersInfoW(action: Int, param: Int, out: IntArray, winIni: Int): Boolean
}

// SPI_GETCLIENTAREAANIMATION: the switch behind "Animation effects".
private const val SPI_GETCLIENTAREAANIMATION = 0x1042

private fun windowsAnimates(): Boolean {
    val on = IntArray(1)
    val user32 = Native.load("user32", User32::class.java)
    return !user32.SystemParametersInfoW(SPI_GETCLIENTAREAANIMATION, 0, on, 0) || on[0] != 0
}

// A command's output, or null when it is missing, fails or hangs.
private fun ask(vararg command: String): String? {
    val process = ProcessBuilder(*command).redirectErrorStream(true).start()
    if (!process.waitFor(2, TimeUnit.SECONDS)) {
        process.destroy()
        return null
    }
    return if (process.exitValue() == 0) process.inputStream.bufferedReader().readText() else null
}
