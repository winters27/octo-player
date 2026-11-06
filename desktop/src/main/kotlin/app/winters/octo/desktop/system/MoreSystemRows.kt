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
import app.winters.octo.design.OctoIcons
import app.winters.octo.desktop.discord.DiscordMark
import app.winters.octo.desktop.pages.ActionRow
import app.winters.octo.desktop.pages.ChoiceRow
import app.winters.octo.desktop.pages.HiddenRow
import app.winters.octo.desktop.pages.SettingRow
import app.winters.octo.desktop.pages.SettingsCard
import app.winters.octo.desktop.pages.SwitchRow
import app.winters.octo.desktop.pages.settingsRowId
import app.winters.octo.desktop.pages.settingsSlug
import app.winters.octo.desktop.settings.DesktopOs

// The mini player's card in Settings > Window and startup: open it, keep it
// on top, and pin it where it is.
@Composable
fun MiniPlayerGroup(app: AppState) {
    val system = LocalSystem.current ?: return
    val settings by app.settings.state.collectAsState()
    val command = if (app.os == DesktopOs.Mac) "Cmd+M" else "Ctrl+M"
    SettingsCard("Mini player", OctoIcons.PictureInPicture) {
        SwitchRow("Mini player", "A small window in place of this one. $command opens it too.", system.miniPlayerOpen) { on -> system.setMiniPlayer(on) }
        SwitchRow("Keep it above other windows", null, settings.system.miniPlayerOnTop) { on ->
            app.settings.update { it.copy(system = it.system.copy(miniPlayerOnTop = on)) }
        }
        SwitchRow("Pin it in place", "It stays where it is. The pin in the mini player does this too.", settings.system.miniPlayerPinned) { on ->
            app.settings.update { it.copy(system = it.system.copy(miniPlayerPinned = on)) }
        }
    }
}

// Whether this build carries Octo's Discord application, so Settings has a
// Discord page.
@Composable
fun hasDiscord(): Boolean = LocalSystem.current?.discord != null

// Settings > Discord: showing the song in the listener's status, under
// Discord's own mark, then, once it is on, how it reads, its pictures, and
// the rest. While it is off, an ask for any of those finds its switch.
@Composable
fun DiscordRows(app: AppState) {
    val system = LocalSystem.current ?: return
    if (system.discord == null) return
    val settings by app.settings.state.collectAsState()
    val prefs = settings.discord
    fun update(change: (DiscordPrefs) -> DiscordPrefs) = app.settings.update { it.copy(discord = change(it.discord)) }
    SettingsCard("Sharing", DiscordMark, brand = true, id = SHARING) {
        SwitchRow(
            SHOW_PLAYING,
            if (prefs.on && !system.discordConnected) "It shows once Discord is open." else "The song, its cover and the artist, in your Discord status.",
            prefs.on,
        ) { on -> update { it.copy(on = on) } }
        if (prefs.on) {
            SwitchRow("Include songs opened from files", "Off, they stay private.", prefs.openedFiles) { on -> update { it.copy(openedFiles = on) } }
        } else {
            HiddenRow("include-songs-opened-from-files", by = settingsSlug(SHOW_PLAYING))
        }
    }
    if (!prefs.on) {
        val by = settingsRowId(SHARING, settingsSlug(SHOW_PLAYING))
        DiscordHidden.forEach { (card, rows) -> rows.forEach { row -> HiddenRow(settingsRowId(card, row), by) } }
        return
    }
    SettingsCard("Status", OctoIcons.Rename) {
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
    SettingsCard("Pictures", OctoIcons.Album) {
        ChoiceRow("Picture", "Found on iTunes or Deezer by name. Nothing from your server is shared.", DiscordPicture.entries, prefs.picture, ::pictureLabel) { v ->
            update { it.copy(picture = v) }
        }
        ChoiceRow("Badge", null, DiscordBadge.entries, prefs.badge, ::badgeLabel) { v -> update { it.copy(badge = v) } }
        SwitchRow("Album name on the cover", null, prefs.albumName) { on -> update { it.copy(albumName = on) } }
    }
    SettingsCard("More", OctoIcons.Switches) {
        SwitchRow("Keep showing while paused", "With a pause badge. Off, your status clears on pause.", prefs.whilePaused) { on -> update { it.copy(whilePaused = on) } }
        SwitchRow("Link to Last.fm", "Friends get an Open on Last.fm button.", prefs.lastFmLinks) { on -> update { it.copy(lastFmLinks = on) } }
    }
}

// The Discord page's first card, and its switch.
private const val SHARING = "sharing"
private const val SHOW_PLAYING = "Show what you're playing"

// The rows the Discord page shows only while sharing is on, by card.
private val DiscordHidden = listOf(
    "status" to listOf("friends-list-shows", "first-line", "second-line", "lines-as-they-were", "time"),
    "pictures" to listOf("picture", "badge", "album-name-on-the-cover"),
    "more" to listOf("keep-showing-while-paused", "link-to-last-fm"),
)

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
