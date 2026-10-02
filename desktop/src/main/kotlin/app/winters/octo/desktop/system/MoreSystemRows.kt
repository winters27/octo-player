package app.winters.octo.desktop.system

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import app.winters.octo.design.FrameSize
import app.winters.octo.design.GlassField
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.discord.DiscordBadge
import app.winters.octo.desktop.discord.DiscordListName
import app.winters.octo.desktop.discord.DiscordPicture
import app.winters.octo.desktop.discord.DiscordPrefs
import app.winters.octo.desktop.discord.DiscordTime
import app.winters.octo.desktop.discord.FIRST_LINE
import app.winters.octo.desktop.discord.SECOND_LINE
import app.winters.octo.desktop.pages.ActionRow
import app.winters.octo.desktop.pages.ChoiceRow
import app.winters.octo.desktop.pages.Group
import app.winters.octo.desktop.pages.SettingRow
import app.winters.octo.desktop.pages.SwitchRow
import app.winters.octo.desktop.settings.DesktopOs

// The mini player's rows in Settings > System: open it, and keep it on top.
@Composable
fun MiniPlayerGroup(app: AppState) {
    val system = LocalSystem.current ?: return
    val settings by app.settings.state.collectAsState()
    val command = if (app.os == DesktopOs.Mac) "Cmd+M" else "Ctrl+M"
    Group("Mini player") {
        SwitchRow("Mini player", "A small window in place of this one. $command opens it too.", system.miniPlayerOpen) { on -> system.setMiniPlayer(on) }
        SwitchRow("Keep it above other windows", null, settings.system.miniPlayerOnTop) { on ->
            app.settings.update { it.copy(system = it.system.copy(miniPlayerOnTop = on)) }
        }
    }
}

// Whether this build carries Octo's Discord application, so Settings has a
// Discord section.
@Composable
fun hasDiscord(): Boolean = LocalSystem.current?.discord != null

// Settings > Discord: showing the song in the listener's status, then, once
// it is on, how it reads, its pictures, and the rest.
@Composable
fun DiscordRows(app: AppState) {
    val system = LocalSystem.current ?: return
    if (system.discord == null) return
    val settings by app.settings.state.collectAsState()
    val prefs = settings.discord
    fun update(change: (DiscordPrefs) -> DiscordPrefs) = app.settings.update { it.copy(discord = change(it.discord)) }
    Group {
        SwitchRow(
            "Show what you're playing",
            if (prefs.on && !system.discordConnected) "It shows once Discord is open." else "The song, its cover and the artist, in your Discord status.",
            prefs.on,
        ) { on -> update { it.copy(on = on) } }
        if (prefs.on) {
            SwitchRow("Include songs opened from files", "Off, they stay private.", prefs.openedFiles) { on -> update { it.copy(openedFiles = on) } }
        }
    }
    if (!prefs.on) return
    Group("Status") {
        ChoiceRow("Friends' list shows", null, DiscordListName.entries, prefs.listName, ::listNameLabel) { v -> update { it.copy(listName = v) } }
        SettingRow("First line", "{title}, {artist} and {album} are filled in.") {
            GlassField(prefs.firstLine, { v -> update { it.copy(firstLine = v) } }, Modifier.width(FrameSize.SettingField), placeholder = FIRST_LINE)
        }
        SettingRow("Second line", null) {
            GlassField(prefs.secondLine, { v -> update { it.copy(secondLine = v) } }, Modifier.width(FrameSize.SettingField), placeholder = SECOND_LINE)
        }
        if (prefs.firstLine != FIRST_LINE || prefs.secondLine != SECOND_LINE) {
            ActionRow("Lines as they were", null, "Reset", { update { it.copy(firstLine = FIRST_LINE, secondLine = SECOND_LINE) } })
        }
        ChoiceRow("Time", null, DiscordTime.entries, prefs.time, ::timeLabel) { v -> update { it.copy(time = v) } }
    }
    Group("Pictures") {
        ChoiceRow("Picture", "Found on iTunes or Deezer by name. Nothing from your server is shared.", DiscordPicture.entries, prefs.picture, ::pictureLabel) { v ->
            update { it.copy(picture = v) }
        }
        ChoiceRow("Badge", null, DiscordBadge.entries, prefs.badge, ::badgeLabel) { v -> update { it.copy(badge = v) } }
        SwitchRow("Album name on the cover", null, prefs.albumName) { on -> update { it.copy(albumName = on) } }
    }
    Group("More") {
        SwitchRow("Keep showing while paused", "With a pause badge. Off, your status clears on pause.", prefs.whilePaused) { on -> update { it.copy(whilePaused = on) } }
        SwitchRow("Link to Last.fm", "Friends get an Open on Last.fm button.", prefs.lastFmLinks) { on -> update { it.copy(lastFmLinks = on) } }
    }
}

private fun listNameLabel(name: DiscordListName) = when (name) {
    DiscordListName.Song -> "Song"
    DiscordListName.Artist -> "Artist"
    DiscordListName.App -> "Octo"
}

private fun timeLabel(time: DiscordTime) = when (time) {
    DiscordTime.Remaining -> "Left"
    DiscordTime.Elapsed -> "Played"
    DiscordTime.Off -> "Off"
}

private fun pictureLabel(picture: DiscordPicture) = when (picture) {
    DiscordPicture.Cover -> "Album cover"
    DiscordPicture.Icon -> "Octo icon"
}

private fun badgeLabel(badge: DiscordBadge) = when (badge) {
    DiscordBadge.Artist -> "Artist photo"
    DiscordBadge.Icon -> "Octo icon"
    DiscordBadge.Off -> "None"
}
