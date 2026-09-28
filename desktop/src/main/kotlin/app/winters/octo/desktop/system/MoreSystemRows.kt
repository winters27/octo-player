package app.winters.octo.desktop.system

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.pages.Group
import app.winters.octo.desktop.pages.SwitchRow
import app.winters.octo.desktop.settings.DesktopOs

// The mini player's rows in Settings > System: open it, and keep it on top.
@Composable
fun MiniPlayerGroup(app: AppState) {
    val system = LocalSystem.current ?: return
    val settings by app.settings.state.collectAsState()
    val command = if (app.os == DesktopOs.Mac) "Cmd+M" else "Ctrl+M"
    Group("Mini player") {
        SwitchRow(
            "Mini player",
            "A small window in place of this one, for while you work. $command, the player's More menu and the ${if (app.os == DesktopOs.Mac) "menu bar" else "tray"} open it too.",
            system.miniPlayerOpen,
        ) { on -> system.setMiniPlayer(on) }
        SwitchRow(
            "Keep it above other windows",
            "So it stays in sight over whatever you work in. The pin in the mini player does the same.",
            settings.system.miniPlayerOnTop,
        ) { on -> app.settings.update { it.copy(system = it.system.copy(miniPlayerOnTop = on)) } }
    }
}

// Discord's rows in Settings > System, in a build that carries Octo's
// Discord application: showing the song in the listener's status, and
// whether songs opened from files may show too.
@Composable
fun DiscordGroup(app: AppState) {
    val system = LocalSystem.current ?: return
    if (system.discord == null) return
    val settings by app.settings.state.collectAsState()
    val prefs = settings.discord
    val waiting = if (prefs.on && !system.discordConnected) " It shows once Discord is open." else ""
    Group("Discord") {
        SwitchRow(
            "Show what you're playing",
            "Friends see the song, artist and album in your Discord status, with the time left. Nothing shows while paused.$waiting",
            prefs.on,
        ) { on -> app.settings.update { it.copy(discord = it.discord.copy(on = on)) } }
        if (prefs.on) {
            SwitchRow(
                "Include songs opened from files",
                "Songs you open from a folder on this computer show too. Off, they stay private.",
                prefs.openedFiles,
            ) { on -> app.settings.update { it.copy(discord = it.discord.copy(openedFiles = on)) } }
        }
    }
}
