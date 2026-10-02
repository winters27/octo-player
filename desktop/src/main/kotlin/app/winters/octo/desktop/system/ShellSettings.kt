package app.winters.octo.desktop.system

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.pages.Group
import app.winters.octo.desktop.pages.InfoRow
import app.winters.octo.desktop.pages.SwitchRow
import app.winters.octo.desktop.settings.DesktopOs

// Windows only: starting Octo when the listener signs in, and whether it
// then waits in the tray. A build shows the row but says it is for the
// installed app, since Windows could not start a build at sign-in.
@Composable
fun TaskbarAndStartupRows(app: AppState) {
    if (app.os != DesktopOs.Windows) return
    val system = LocalSystem.current ?: return
    val settings by app.settings.state.collectAsState()
    StartupRows(settings.system, system.shell.startAtLoginWorks, system.shell.startupTurnedOff, system.trayAvailable) { change ->
        app.settings.update { it.copy(system = change(it.system)) }
    }
}

@Composable
internal fun StartupRows(prefs: SystemPrefs, works: Boolean, turnedOff: Boolean, tray: Boolean, change: ((SystemPrefs) -> SystemPrefs) -> Unit) {
    Group("Taskbar and startup") {
        if (!works) {
            InfoRow("Start with Windows", "Works in the installed Octo.", "Installed app only")
            return@Group
        }
        SwitchRow("Start with Windows", startCaption(turnedOff), prefs.startWithWindows) { on -> change { it.copy(startWithWindows = on) } }
        if (prefs.startWithWindows && tray) {
            SwitchRow("Start in the tray", "Waits there instead of opening its window.", prefs.startInTray) { on ->
                change { it.copy(startInTray = on) }
            }
        }
    }
}
