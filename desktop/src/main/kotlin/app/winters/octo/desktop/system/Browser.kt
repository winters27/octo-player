package app.winters.octo.desktop.system

import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.desktop.settings.currentOs
import java.awt.Desktop
import java.net.URI

// Opens a web address in the person's own browser: Java's way where the
// platform has one, else the platform's own command. Only http and https,
// so nothing else can be launched through it. False when nothing opened.
fun openInBrowser(url: String, os: DesktopOs = currentOs()): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    if (uri.scheme != "https" && uri.scheme != "http") return false
    val desktop = runCatching { Desktop.getDesktop().takeIf { Desktop.isDesktopSupported() && it.isSupported(Desktop.Action.BROWSE) } }.getOrNull()
    if (desktop != null && runCatching { desktop.browse(uri) }.isSuccess) return true
    val command = when (os) {
        DesktopOs.Windows -> listOf("rundll32", "url.dll,FileProtocolHandler", uri.toString())
        DesktopOs.Mac -> listOf("open", uri.toString())
        DesktopOs.Linux -> listOf("xdg-open", uri.toString())
    }
    return runCatching { ProcessBuilder(command).start() }.isSuccess
}
