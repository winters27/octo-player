package app.winters.octo.desktop.system

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.pages.InfoRow
import app.winters.octo.desktop.pages.Rows
import app.winters.octo.desktop.pages.SwitchRow
import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.design.IconAction
import app.winters.octo.design.IconPath
import app.winters.octo.design.IconSource

// How Octo fits into the system: the tray, notifications, the window's
// frame, and whether the media keys reach it. The mini player and Discord
// have groups of their own below (MoreSystemRows.kt).
@Composable
fun SystemRows(app: AppState) {
    val system = LocalSystem.current
    val settings by app.settings.state.collectAsState()
    val prefs = settings.system
    val mac = app.os == DesktopOs.Mac
    val tray = if (mac) "menu bar" else "tray"
    Rows {
        if (system?.trayAvailable == true) {
            SwitchRow(
                "Keep playing when the window closes",
                "Octo stays in the $tray, for music while the window is out of the way. Open it again from there.",
                prefs.closeToTray,
            ) { on -> app.settings.update { it.copy(system = it.system.copy(closeToTray = on)) } }
        }
        if (system?.notificationsAvailable == true) {
            SwitchRow("Now playing notifications", "A notice with each new song, while Octo's window is behind others.", prefs.nowPlayingNotices) { on ->
                app.settings.update { it.copy(system = it.system.copy(nowPlayingNotices = on)) }
            }
        }
        if (!mac) {
            SwitchRow("Use the system title bar", "Your system's own window frame in place of Octo's. Takes effect the next time Octo opens.", settings.systemTitleBar) { on ->
                app.settings.update { it.copy(systemTitleBar = on) }
            }
        }
        if (system != null) {
            InfoRow(
                "Media keys",
                "Play, pause and skip from your keyboard and the system's media controls.",
                if (system.mediaKeysWork) "Working" else "Not available here",
            )
        }
    }
}

// The mini player's button for the now-playing bar: a small window inside
// a larger one.
@Composable
fun MiniPlayerButton() {
    val system = LocalSystem.current ?: return
    IconAction(MiniPlayerIcon, if (system.miniPlayerOpen) "Close the mini player" else "Mini player", system::toggleMiniPlayer, size = 34.dp, iconSize = 19.dp, active = system.miniPlayerOpen)
}

// A frame with a smaller filled one in its lower right, on the icons' grid.
private val MiniPlayerIcon by lazy {
    IconSource(
        "mini_player",
        24f,
        24f,
        960f,
        960f,
        listOf(
            IconPath(
                "M160,800Q127,800 103.5,776.5Q80,753 80,720L80,240Q80,207 103.5,183.5Q127,160 160,160L800,160Q833,160 856.5,183.5Q880,207 880,240L880,720Q880,753 856.5,776.5Q833,800 800,800L160,800Z" +
                    "M160,720L800,720L800,240L160,240L160,720Z" +
                    "M480,640L720,640Q737,640 748.5,628.5Q760,617 760,600L760,440Q760,423 748.5,411.5Q737,400 720,400L480,400Q463,400 451.5,411.5Q440,423 440,440L440,600Q440,617 451.5,628.5Q463,640 480,640Z",
                evenOdd = true,
            ),
        ),
    ).toImageVector()
}
