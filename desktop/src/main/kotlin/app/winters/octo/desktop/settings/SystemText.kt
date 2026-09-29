package app.winters.octo.desktop.settings

import app.winters.octo.desktop.system.WindowsRegistry
import java.util.concurrent.TimeUnit

// How much bigger the system asks text to be: Windows' Settings >
// Accessibility > Text size (100 to 225%), GNOME's text scaling. macOS has
// no such setting for apps. 1 when unknown.
fun systemTextScale(os: DesktopOs): Float = runCatching {
    when (os) {
        DesktopOs.Windows -> windowsTextScale(WindowsRegistry.firstByte("Software\\Microsoft\\Accessibility", "TextScaleFactor"))
        DesktopOs.Linux -> gnomeTextScale(askGnome())
        DesktopOs.Mac -> 1f
    }
}.getOrDefault(1f)

// The Windows value, a percentage kept as a number: 100 to 225.
fun windowsTextScale(percent: Int?): Float = (percent ?: 100).coerceIn(100, 225) / 100f

// What `gsettings get ... text-scaling-factor` prints, such as "1.25".
fun gnomeTextScale(answer: String?): Float = answer?.trim()?.toFloatOrNull()?.takeIf { it > 0f } ?: 1f

// The most Octo grows its words: its rows and bars keep their heights, and
// past this the words would crowd them.
const val MaxTextScale = 1.3f

// The text size in use: the listener's own choice (a percentage), or with
// 0 the system's, within what the layout holds.
fun textScale(setting: Int, system: Float): Float =
    (if (setting == 0) system else setting / 100f).coerceIn(1f, MaxTextScale)

private fun askGnome(): String? {
    val process = ProcessBuilder("gsettings", "get", "org.gnome.desktop.interface", "text-scaling-factor").redirectErrorStream(true).start()
    if (!process.waitFor(2, TimeUnit.SECONDS)) {
        process.destroy()
        return null
    }
    return if (process.exitValue() == 0) process.inputStream.bufferedReader().readText() else null
}
