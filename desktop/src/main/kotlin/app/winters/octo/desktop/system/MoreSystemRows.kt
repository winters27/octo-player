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
            "Friends see the song, the album cover and the artist in your Discord status, with the time left.$waiting",
            prefs.on,
        ) { on -> app.settings.update { it.copy(discord = it.discord.copy(on = on)) } }
        if (prefs.on) {
            SwitchRow(
                "Include songs opened from files",
                "Songs you open from a folder on this computer show too. Off, they stay private.",
                prefs.openedFiles,
            ) { on -> app.settings.update { it.copy(discord = it.discord.copy(openedFiles = on)) } }
            DiscordLookRows(prefs) { change -> app.settings.update { it.copy(discord = change(it.discord)) } }
        }
    }
}

// How the Discord status looks: its two lines, what the member list names,
// the time, the pictures, and the links. Shown once it is on.
@Composable
private fun DiscordLookRows(prefs: DiscordPrefs, update: ((DiscordPrefs) -> DiscordPrefs) -> Unit) {
    ChoiceRow("Friends' list shows", "What follows \"Listening to\" beside your name.", DiscordListName.entries, prefs.listName, ::listNameLabel) { v ->
        update { it.copy(listName = v) }
    }
    SettingRow("First line", "{title}, {artist} and {album} are filled in.") {
        GlassField(prefs.firstLine, { v -> update { it.copy(firstLine = v) } }, Modifier.width(FrameSize.SettingField), placeholder = FIRST_LINE)
    }
    SettingRow("Second line", "{title}, {artist} and {album} are filled in.") {
        GlassField(prefs.secondLine, { v -> update { it.copy(secondLine = v) } }, Modifier.width(FrameSize.SettingField), placeholder = SECOND_LINE)
    }
    ActionRow(
        "Lines as they were",
        null,
        "Reset",
        { update { it.copy(firstLine = FIRST_LINE, secondLine = SECOND_LINE) } },
        enabled = prefs.firstLine != FIRST_LINE || prefs.secondLine != SECOND_LINE,
    )
    ChoiceRow("Time", "The time left, the time played, or none.", DiscordTime.entries, prefs.time, ::timeLabel) { v ->
        update { it.copy(time = v) }
    }
    ChoiceRow(
        "Picture",
        "Album covers are found on iTunes or Deezer by the album's name. Nothing from your server is shared.",
        DiscordPicture.entries,
        prefs.picture,
        ::pictureLabel,
    ) { v -> update { it.copy(picture = v) } }
    ChoiceRow("Badge", "The small round picture on the cover. Artist photos come from Deezer.", DiscordBadge.entries, prefs.badge, ::badgeLabel) { v ->
        update { it.copy(badge = v) }
    }
    SwitchRow("Show the album name", "When someone points at the cover.", prefs.albumName) { on -> update { it.copy(albumName = on) } }
    SwitchRow(
        "Keep showing while paused",
        "With a pause badge and no time. Off, your status clears when you pause.",
        prefs.whilePaused,
    ) { on -> update { it.copy(whilePaused = on) } }
    SwitchRow(
        "Link to Last.fm",
        "The song and artist open their Last.fm pages, and friends get an Open on Last.fm button.",
        prefs.lastFmLinks,
    ) { on -> update { it.copy(lastFmLinks = on) } }
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
