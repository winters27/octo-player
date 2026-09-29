package app.winters.octo.desktop.pages

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.winters.octo.design.DesktopType
import app.winters.octo.design.OctoColors
import app.winters.octo.design.Space
import app.winters.octo.design.Txt
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.settings.AppSettings
import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.desktop.system.LocalSystem
import app.winters.octo.desktop.update.UpdaterAvailability
import app.winters.octo.update.InstallWhen
import app.winters.octo.update.UpdatePrefs
import kotlinx.coroutines.launch

// Settings > About: the version, a ready update with what is new in it,
// and how Octo looks for new versions.
@Composable
internal fun AboutRows(app: AppState, settings: AppSettings) {
    val updates = app.updates
    val ready = updates?.ready
    Rows {
        InfoRow("Version", null, appVersion())
        if (updates != null && ready != null) {
            SettingRow("Update ready: ${ready.version}", updates.installHelp(ready)) {
                val system = LocalSystem.current
                RowAction(updates.installAction, { updates.install(quit = { system?.quit?.invoke() }) })
            }
        }
    }
    if (ready != null && ready.notes.isNotBlank()) {
        Txt(
            ready.notes.trim(),
            DesktopType.meta,
            OctoColors.TextSecondary,
            Modifier.fillMaxWidth().padding(horizontal = Space.M, vertical = Space.S),
            maxLines = NOTES_LINES,
        )
    }
    if (updates == null) return
    val prefs = settings.updates
    fun change(edit: (UpdatePrefs) -> UpdatePrefs) = app.settings.update { it.copy(updates = edit(it.updates)) }
    Group("Updates") {
        val availability = updates.availability
        if (availability is UpdaterAvailability.Off || !updates.enabled) {
            SettingRow("New versions", (availability as? UpdaterAvailability.Off)?.why)
            return@Group
        }
        SwitchRow(
            "Check for updates automatically",
            "Looks for a new version a little after Octo opens and every few hours, and downloads it in the background.",
            prefs.checkAutomatically,
        ) { on -> change { it.copy(checkAutomatically = on) } }
        if (app.os == DesktopOs.Windows) {
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
            "New versions before they're finished, for trying what's coming. They can have rough edges.",
            prefs.earlyVersions,
        ) { on -> change { it.copy(earlyVersions = on) } }
        ActionRow(
            "New versions",
            updates.lastLine ?: "Octo's new versions come from its releases on GitHub, signed so only real ones install.",
            if (updates.checking) "Checking" else "Check now",
            { app.scope.launch { updates.check() } },
            enabled = !updates.checking,
        )
    }
}

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
