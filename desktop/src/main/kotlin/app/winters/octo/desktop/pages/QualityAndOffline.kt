package app.winters.octo.desktop.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import app.winters.octo.design.ControlHeight
import app.winters.octo.design.DesktopType
import app.winters.octo.design.FitOrElse
import app.winters.octo.design.GlazeCapsule
import app.winters.octo.design.GlazeSegments
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.PopupPadding
import app.winters.octo.design.SettingsSize
import app.winters.octo.design.Space
import app.winters.octo.design.Txt
import app.winters.octo.desktop.AppState
import app.winters.octo.playback.DOWNLOAD_FORMAT_HELP
import app.winters.octo.playback.DOWNLOAD_FORMAT_TITLE
import app.winters.octo.playback.DownloadFormat
import app.winters.octo.playback.START_AFTER_HELP
import app.winters.octo.playback.START_AFTER_SETTING
import app.winters.octo.playback.StartAfter
import app.winters.octo.playback.detail
import app.winters.octo.playback.downloadQualityLabel
import app.winters.octo.desktop.ui.ChoiceButton
import app.winters.octo.desktop.settings.AppSettings
import app.winters.octo.offline.keptLine
import app.winters.octo.subsonic.DeviceQualityMode
import app.winters.octo.subsonic.StreamQuality
import app.winters.octo.ui.family.QUALITY_AT_HOME
import app.winters.octo.ui.family.QUALITY_AWAY
import app.winters.octo.ui.family.QUALITY_ON_THIS_DEVICE
import app.winters.octo.ui.family.ORIGINAL_LABEL
import app.winters.octo.ui.family.appPicksQuality
import app.winters.octo.ui.family.awayLimit
import app.winters.octo.ui.family.deviceModeLine
import app.winters.octo.ui.family.deviceModeName
import app.winters.octo.ui.family.familyLimitLines
import app.winters.octo.ui.family.homeLimit
import app.winters.octo.ui.family.qualityName
import app.winters.octo.ui.family.qualityOptions
import javax.swing.JFileChooser

// Audio quality: with Family on, the account's own quality at home and
// away (kept on the server for every app), the family's limits in plain
// words, and who picks this computer's quality. Then this app's own
// quality, which applies without a family or when this computer is left to
// the app.
@Composable
internal fun AudioQualityRows(app: AppState, settings: AppSettings) {
    val connection = app.connection
    val family = connection?.family == true
    val model = app.family
    if (family) LaunchedEffect(connection) { model.plan() }
    val me = model.me
    if (family && me != null) {
        val quality = me.quality
        familyLimitLines(quality).forEach { Txt(it, DesktopType.body, OctoColors.SignalOrange, Modifier.padding(horizontal = RowInset), maxLines = 2) }
        SettingsCard("Your account, on every app", OctoIcons.Family) {
            QualityRow(QUALITY_AT_HOME, "On your home network.", quality.home, homeLimit(quality)) { model.setQuality(home = it) }
            QualityRow(QUALITY_AWAY, "Anywhere else.", quality.away, awayLimit(quality)) { model.setQuality(away = it) }
        }
        model.deviceMode?.let { mode ->
            SettingsCard(QUALITY_ON_THIS_DEVICE, OctoIcons.Device) {
                SettingRow(deviceModeName(mode), deviceModeLine(mode), stable = QUALITY_ON_THIS_DEVICE) {
                    val name: (DeviceQualityMode) -> String = { if (it == DeviceQualityMode.Account) "My account" else "This app" }
                    FitOrElse(
                        { GlazeSegments(DeviceQualityMode.entries, mode, name, model::setDeviceMode) },
                        { ChoiceButton(DeviceQualityMode.entries, mode, name, model::setDeviceMode, title = QUALITY_ON_THIS_DEVICE) },
                    )
                }
            }
        }
    }
    val own = StreamQuality.entries.firstOrNull { it.name == settings.playback.streamQuality } ?: StreamQuality.Original
    val applies = appPicksQuality(family, model.deviceMode)
    SettingsCard(if (family) "This app's quality" else "From your server", OctoIcons.Lossless, id = "stream") {
        QualityRow(
            "Stream quality",
            when {
                !family -> "How songs stream from the server. Songs kept offline stay as they are."
                applies -> "Used now: this computer is left to the app."
                else -> "Used when this computer is left to the app. Your account's choice applies now."
            },
            own,
            limit = if (family && me != null) homeLimit(me.quality) else 0,
            dim = !applies,
        ) { picked -> app.settings.update { it.copy(playback = it.playback.copy(streamQuality = picked.name)) } }
    }
}

// A quality picker, Original first. Under a family limit Original cannot be
// picked and the caption says why; a bitrate above the limit plays at it.
@Composable
private fun QualityRow(title: String, caption: String?, chosen: StreamQuality, limit: Int, dim: Boolean = false, pick: (StreamQuality) -> Unit) {
    SettingRow(title, qualityCaption(caption, chosen, limit), dim = dim) {
        val options = qualityOptions(limit)
        // The one-button form offers only the qualities that can be picked.
        FitOrElse(
            { GlazeSegments(options.map { it.quality }, chosen, ::qualityName, pick, enabled = { q -> options.first { it.quality == q }.enabled }) },
            { ChoiceButton(options.filter { it.enabled }.map { it.quality }, chosen, ::qualityName, pick, title = title) },
        )
    }
}

// What a quality picker says under its title: its own words, what Original
// is, and what a family limit does to the choices.
internal fun qualityCaption(caption: String?, chosen: StreamQuality, limit: Int): String {
    val options = qualityOptions(limit)
    val blocked = options.firstNotNullOfOrNull { it.blocked }
    val held = options.filter { it.limited && it.enabled }.map { it.name }
    val verb = if (held.size == 1) "plays" else "play"
    val note = when {
        blocked != null && held.isNotEmpty() -> "$blocked, so Original is off and ${held.joinToString(" and ")} $verb at $limit kbps."
        blocked != null -> "$blocked, so Original is off."
        else -> null
    }
    val chosenNote = if (limit > 0 && options.first { it.quality == chosen }.limited && chosen != StreamQuality.Original) "Plays at $limit kbps." else null
    return listOfNotNull(caption, if (blocked == null) "$ORIGINAL_LABEL." else null, note, chosenNote).joinToString(" ")
}

// What the Download quality row says under its title: with a smaller
// quality, the bitrate and format a bigger file is made as.
internal fun downloadQualityCaption(quality: StreamQuality, format: DownloadFormat): String =
    if (quality == StreamQuality.Original) {
        "For songs fetched from now on. Smaller files fit more songs."
    } else {
        "For songs fetched from now on, as ${downloadQualityLabel(quality.kbps, format)} at most. Smaller files fit more songs."
    }

// What the Download format row says under its title: what it is for, and
// what the chosen format is like. Its last two words are joined, so a
// narrow window never leaves one word alone on the last line.
internal fun downloadFormatCaption(format: DownloadFormat): String {
    val detail = format.detail
    val last = detail.lastIndexOf(' ')
    val joined = if (last > 0) detail.substring(0, last) + ' ' + detail.substring(last + 1) else detail
    return "$DOWNLOAD_FORMAT_HELP. $joined."
}

// Streaming and downloads: how long a song buffers before it starts, then
// the songs kept on this computer to play without a connection: the folder
// they go in, the Liked songs, chosen playlists, and how many are kept, and
// last, in red and asked first, letting every one of them go. A family
// account with offline copies off keeps what it has.
@Composable
internal fun OfflineRows(app: AppState, settings: AppSettings) {
    val offline = app.offline
    val status = offline.status
    val choices = offline.choices()
    SettingsCard("Streaming", OctoIcons.Cloud) {
        ChoiceRow(START_AFTER_SETTING, START_AFTER_HELP, StartAfter.entries, settings.playback.startAfter, { it.label }) { wait ->
            app.settings.update { it.copy(playback = it.playback.copy(startAfter = wait)) }
        }
    }
    SettingsCard("Kept on this computer", OctoIcons.HardDrive, id = "kept") {
        SettingRow(
            "Kept on this computer",
            buildList {
                add(keptLine(status.kept, status.bytes))
                if (status.waiting > 0) add(if (status.busy) "${status.waiting} on the way" else "${status.waiting} waiting")
                if (status.failed > 0) add("${status.failed} could not be fetched")
            }.joinToString(", "),
        ) {
            RowAction("Check now", offline::poke)
        }
        SettingRow("Folder", offline.folder.absolutePath) {
            RowAction("Change", {
                val chooser = JFileChooser(offline.folder).apply {
                    fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                    dialogTitle = "Choose where kept songs go"
                }
                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) offline.moveTo(chooser.selectedFile)
            })
        }
        SwitchRow("Keep Liked songs", "Every song you like, as you like it.", choices.liked) { on -> offline.keepLiked(on) }
        val format = DownloadFormat.named(settings.offline.downloadFormat)
        val chosen = StreamQuality.entries.firstOrNull { it.name == settings.offline.downloadQuality } ?: StreamQuality.Original
        SettingRow("Download quality", downloadQualityCaption(chosen, format)) {
            FitOrElse(
                { GlazeSegments(StreamQuality.entries, chosen, ::qualityName, offline::setDownloadQuality) },
                { ChoiceButton(StreamQuality.entries, chosen, ::qualityName, offline::setDownloadQuality, title = "Download quality") },
            )
        }
        // Always shown, so the card keeps its shape whatever the quality, and
        // as tall as its longest caption, so picking a format moves nothing. A
        // menu like Download quality's above it, which it belongs to, so it
        // never outweighs it.
        SettingRow(DOWNLOAD_FORMAT_TITLE, downloadFormatCaption(format), captionRoom = DownloadFormat.entries.map(::downloadFormatCaption)) {
            ChoiceButton(DownloadFormat.entries, format, { it.label }, offline::setDownloadFormat, title = DOWNLOAD_FORMAT_TITLE)
        }
    }
    val playlists = app.playlists
    if (playlists.isNotEmpty()) {
        SettingsCard("Playlists", OctoIcons.Playlists) {
            playlists.forEach { playlist ->
                SwitchRow(
                    playlist.name,
                    "${playlist.songCount} songs",
                    offline.isPlaylistKept(playlist.id),
                    id = "playlist-${settingsSlug(playlist.id)}",
                    stable = "[each playlist]",
                ) { on -> offline.keepPlaylist(playlist.id, on) }
            }
        }
    }
    if (status.kept > 0 || choices.byHand.isNotEmpty() || choices.liked || choices.playlists.isNotEmpty()) {
        UntitledCard { ActionRow(REMOVE_ALL, "Lets every kept song go and stops keeping any.", "Remove", { askToRemoveAll(app) }, danger = true) }
    }
}

private const val REMOVE_ALL = "Remove all"

// Asks before every kept song goes, saying what goes and what stays.
internal fun askToRemoveAll(app: AppState) {
    val opening = Any()
    // Cancel takes the keyboard first, so Enter never lets go by itself.
    val cancel = FocusRequester()
    app.popups.showCentred(width = SettingsSize.Sheet, over = app.settingsColumn(), first = cancel) { close ->
        key(opening) {
            PopupPadding {
                SheetQuestion(
                    "Remove every kept song?",
                    "Octo deletes the songs it kept on this computer and stops keeping any. They still play from your server.",
                )
                Row(
                    Modifier.fillMaxWidth().padding(vertical = Space.Xs),
                    horizontalArrangement = Arrangement.spacedBy(Space.M, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlazeCapsule(null, "Cancel", close, Modifier.focusRequester(cancel), height = ControlHeight.L)
                    DangerAction(REMOVE_ALL, {
                        close()
                        app.offline.removeAll()
                    }, icon = OctoIcons.Delete)
                }
            }
        }
    }
}
