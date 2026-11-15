package app.winters.octo.desktop.pages

import app.winters.octo.desktop.system.OctopusPainter
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import app.winters.octo.design.FrameSize
import app.winters.octo.design.DesktopType
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.Space
import app.winters.octo.design.Txt
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.settings.AppSettings
import app.winters.octo.desktop.system.LocalSystem
import app.winters.octo.desktop.system.copyBugDetails
import app.winters.octo.desktop.system.logFolder
import app.winters.octo.desktop.update.UpdaterAvailability
import app.winters.octo.update.InstallWhen
import app.winters.octo.update.UpdatePrefs
import kotlinx.coroutines.launch

// Settings > About Octo: Octo and its version, a ready update with what is
// new in it, how Octo looks for new versions, and help for a bug report.
@Composable
internal fun AboutRows(app: AppState, settings: AppSettings) {
    val updates = app.updates
    val ready = updates?.ready
    AboutCard()
    if (updates != null) {
        val prefs = settings.updates
        fun change(edit: (UpdatePrefs) -> UpdatePrefs) = app.settings.update { it.copy(updates = edit(it.updates)) }
        SettingsCard("Updates", OctoIcons.Upgrade) {
            if (ready != null) {
                SettingRow("Update ready: ${ready.version}", updates.installHelp(ready), stable = "Update ready: ...", id = "update-ready") {
                    val system = LocalSystem.current
                    AccentPill(updates.installAction, { updates.install(quit = { system?.quit?.invoke() }) })
                }
                if (ready.notes.isNotBlank()) ReleaseNotes(ready.notes)
            }
            val availability = updates.availability
            if (availability is UpdaterAvailability.Off || !updates.enabled) {
                SettingRow("New versions", (availability as? UpdaterAvailability.Off)?.why)
                return@SettingsCard
            }
            SwitchRow(
                "Check for updates automatically",
                "Looks every few hours and downloads in the background.",
                prefs.checkAutomatically,
            ) { on -> change { it.copy(checkAutomatically = on) } }
            if (updates.selfInstalls) {
                ChoiceRow(
                    "Install updates",
                    installHelp(prefs.install),
                    InstallWhen.entries,
                    prefs.install,
                    ::installName,
                ) { choice -> change { it.copy(install = choice) } }
            }
            SwitchRow(
                "Try early versions",
                "Before they're finished. They can have rough edges.",
                prefs.earlyVersions,
            ) { on -> change { it.copy(earlyVersions = on) } }
            ActionRow(
                "New versions",
                updates.lastLine ?: "Signed releases from GitHub.",
                if (updates.checking) "Checking" else "Check now",
                { app.scope.launch { updates.check() } },
                enabled = !updates.checking,
            )
        }
    }
    SettingsCard("Help", OctoIcons.Info) {
        SettingRow("Details for a bug report", "Version, graphics and memory, to paste into an issue.") {
            RowAction("Copy", { app.scope.launch { copyBugDetails(app, null, logFolder(app.os)) } })
        }
    }
}

// Octo's octopus, and its name with the version beside it.
@Composable
private fun AboutCard() {
    val octopus = remember { octopusPicture() }
    Card {
        Row(
            Modifier.padding(horizontal = RowInset, vertical = Space.Xl),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.Xl),
        ) {
            Image(octopus, contentDescription = null, modifier = Modifier.size(FrameSize.OpeningMark / 2))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.M)) {
                Txt("Octo", DesktopType.section, modifier = Modifier.alignByBaseline())
                val version = appVersion()
                Txt(if (version.first().isDigit()) "Version $version" else version, DesktopType.meta, OctoColors.TextMuted, Modifier.alignByBaseline())
            }
        }
    }
}

// A release's notes under its row, their first lines: a "- " item drawn
// as a bullet, its words hanging clear of it when they run on.
@Composable
private fun ReleaseNotes(notes: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = RowInset, vertical = Space.L), verticalArrangement = Arrangement.spacedBy(Space.Xxs)) {
        notes.trim().lines().filter { it.isNotBlank() }.take(NOTES_LINES).forEach { line ->
            val item = noteItem(line)
            if (item == null) {
                Txt(line.trim(), DesktopType.meta, OctoColors.TextSecondary, maxLines = 3)
            } else {
                Row {
                    Txt("•", DesktopType.meta, OctoColors.TextSecondary, Modifier.width(NoteIndent))
                    Txt(item, DesktopType.meta, OctoColors.TextSecondary, Modifier.weight(1f), maxLines = 3)
                }
            }
        }
    }
}

// A note's words when it is a "- " (or "* ") item, else null.
internal fun noteItem(line: String): String? {
    val trimmed = line.trimStart()
    return if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) trimmed.drop(2).trim() else null
}

// How far a note item's words hang in from its bullet.
private val NoteIndent = Space.L

// The octopus the window opens on, drawn from the vector at its size.
private fun octopusPicture(): Painter = OctopusPainter()

// How many lines of a release's notes show under its row.
private const val NOTES_LINES = 12

internal fun installName(choice: InstallWhen): String = when (choice) {
    InstallWhen.Ask -> "Ask me"
    InstallWhen.OnQuit -> "When I quit"
}

private fun installHelp(choice: InstallWhen): String = when (choice) {
    InstallWhen.Ask -> "A ready update waits here until you choose Restart to update."
    InstallWhen.OnQuit -> "A ready update goes in when you quit Octo, and it's new the next time you open it."
}
