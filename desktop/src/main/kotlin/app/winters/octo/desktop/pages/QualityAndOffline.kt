package app.winters.octo.desktop.pages

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import app.winters.octo.design.DesktopType
import app.winters.octo.design.FitOrElse
import app.winters.octo.design.GlazeSegments
import app.winters.octo.design.OctoColors
import app.winters.octo.design.Txt
import app.winters.octo.desktop.AppState
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
import app.winters.octo.desktop.system.DesktopServices
import app.winters.octo.desktop.system.FileRequest
import kotlinx.coroutines.launch

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
        Group("Your account, on every app") {
            Rows {
                QualityRow(QUALITY_AT_HOME, "On your home network.", quality.home, homeLimit(quality)) { model.setQuality(home = it) }
                QualityRow(QUALITY_AWAY, "Anywhere else.", quality.away, awayLimit(quality)) { model.setQuality(away = it) }
            }
        }
        model.deviceMode?.let { mode ->
            Group(QUALITY_ON_THIS_DEVICE) {
                Rows {
                    SettingRow(deviceModeName(mode), deviceModeLine(mode)) {
                        val name: (DeviceQualityMode) -> String = { if (it == DeviceQualityMode.Account) "My account" else "This app" }
                        FitOrElse(
                            { GlazeSegments(DeviceQualityMode.entries, mode, name, model::setDeviceMode) },
                            { ChoiceButton(DeviceQualityMode.entries, mode, name, model::setDeviceMode, title = QUALITY_ON_THIS_DEVICE) },
                        )
                    }
                }
            }
        }
    }
    val own = StreamQuality.entries.firstOrNull { it.name == settings.playback.streamQuality } ?: StreamQuality.Original
    val applies = appPicksQuality(family, model.deviceMode)
    Group(if (family) "This app's quality" else null) {
        Rows {
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

// Offline: songs kept on this computer to play without a connection. The
// folder they go in, the Liked songs, chosen playlists, and how many are
// kept. A family account with offline copies off keeps what it has.
@Composable
internal fun OfflineRows(app: AppState, settings: AppSettings) {
    val offline = app.offline
    val status = offline.status
    val choices = offline.choices()
    Rows {
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
                app.scope.launch {
                    DesktopServices.files.choose(FileRequest("Choose where kept songs go", folder = offline.folder, directory = true))?.let(offline::moveTo)
                }
            })
        }
        SwitchRow("Keep Liked songs", "Every song you like, as you like it.", choices.liked) { on -> offline.keepLiked(on) }
        SettingRow("Download quality", "For songs fetched from now on. Smaller files fit more songs.") {
            val chosen = StreamQuality.entries.firstOrNull { it.name == settings.offline.downloadQuality } ?: StreamQuality.Original
            FitOrElse(
                { GlazeSegments(StreamQuality.entries, chosen, ::qualityName, offline::setDownloadQuality) },
                { ChoiceButton(StreamQuality.entries, chosen, ::qualityName, offline::setDownloadQuality, title = "Download quality") },
            )
        }
    }
    val playlists = app.playlists
    if (playlists.isNotEmpty()) {
        Group("Playlists") {
            Rows {
                playlists.forEach { playlist ->
                    SwitchRow(playlist.name, "${playlist.songCount} songs", offline.isPlaylistKept(playlist.id)) { on -> offline.keepPlaylist(playlist.id, on) }
                }
            }
        }
    }
    if (status.kept > 0 || choices.byHand.isNotEmpty() || choices.liked || choices.playlists.isNotEmpty()) {
        Rows { ActionRow("Remove all", "Lets every kept song go and stops keeping any.", "Remove all", offline::removeAll) }
    }
}
