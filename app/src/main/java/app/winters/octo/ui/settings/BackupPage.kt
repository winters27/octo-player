package app.winters.octo.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.winters.octo.backup.Backup
import app.winters.octo.backup.BackupRead
import app.winters.octo.backup.Backups
import app.winters.octo.backup.RestorePlan
import app.winters.octo.backup.describeBackup
import app.winters.octo.backup.describePlan
import app.winters.octo.ui.settings.rows.ActionRow
import app.winters.octo.ui.settings.rows.NoteRow
import app.winters.octo.ui.settings.rows.SettingsGroup
import app.winters.octo.ui.settings.rows.SettingsPageFrame
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

// Where saving or restoring a backup is up to.
sealed interface BackupState {
    data object Idle : BackupState

    data object Working : BackupState

    data class Saved(val ok: Boolean) : BackupState

    // A backup read and matched against the library, waiting to be restored.
    data class Ready(val backup: Backup, val plan: RestorePlan) : BackupState

    data class Restored(val plan: RestorePlan) : BackupState

    data class Problem(val message: String) : BackupState
}

@HiltViewModel
class BackupViewModel @Inject constructor(private val backups: Backups) : ViewModel() {
    private val _state = MutableStateFlow<BackupState>(BackupState.Idle)
    val state: StateFlow<BackupState> = _state

    fun save(uri: Uri) {
        _state.value = BackupState.Working
        viewModelScope.launch { _state.value = BackupState.Saved(backups.save(uri)) }
    }

    fun open(uri: Uri) {
        _state.value = BackupState.Working
        viewModelScope.launch {
            _state.value = when (val read = backups.read(uri)) {
                is BackupRead.Read -> BackupState.Ready(read.backup, backups.plan(read.backup))
                BackupRead.TooNew -> BackupState.Problem("This backup was made by a newer Octo. Update the app to restore it.")
                BackupRead.NotABackup -> BackupState.Problem("That file isn't an Octo backup.")
            }
        }
    }

    fun restore() {
        val ready = _state.value as? BackupState.Ready ?: return
        _state.value = BackupState.Working
        viewModelScope.launch {
            backups.restore(ready.backup, ready.plan)
            _state.value = BackupState.Restored(ready.plan)
        }
    }

    fun cancel() {
        _state.value = BackupState.Idle
    }
}

// Saving the app's settings to a file, and putting them back from one. A
// restore first shows what the file holds and how much of it this library
// has, and waits for the go-ahead.
@Composable
fun BackupPage(onBack: () -> Unit, highlight: String?, vm: BackupViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val saveTo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let(vm::save)
    }
    val openFrom = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::open)
    }
    val working = state == BackupState.Working

    SettingsPageFrame("Backup and restore", onBack, highlight) {
        when (val now = state) {
            is BackupState.Ready -> {
                SettingsGroup(
                    title = "In this backup",
                    footer = "Restoring replaces your settings. Playlists are added, songs are liked and rated, " +
                        "and albums and artists are added to your favourites and pins.",
                ) {
                    (describeBackup(now.backup) + describePlan(now.plan)).forEach { line -> NoteRow(line) }
                }
                SettingsGroup {
                    ActionRow(null, title = "Restore", onClick = vm::restore, chevron = false)
                    ActionRow(null, title = "Cancel", onClick = vm::cancel, chevron = false)
                }
            }
            else -> {
                val note = when (now) {
                    is BackupState.Saved -> if (now.ok) "Backup saved." else "Couldn't write the backup there."
                    is BackupState.Restored -> (listOf("Restored.") + describePlan(now.plan)).joinToString("\n")
                    is BackupState.Problem -> now.message
                    else -> null
                }
                SettingsGroup(
                    footer = "Saves your settings, sound, equalizer presets, playlists, likes, favourites, pins and ratings " +
                        "to a file. Passwords, keys and certificates are never saved.",
                ) {
                    ActionRow(
                        SettingsIndex.SaveBackup,
                        onClick = { saveTo.launch("Octo backup ${LocalDate.now()}.json") },
                        busy = working,
                        chevron = false,
                    )
                    ActionRow(
                        SettingsIndex.RestoreBackup,
                        onClick = { openFrom.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                        enabled = !working,
                        chevron = false,
                    )
                    note?.let { NoteRow(it) }
                }
            }
        }
    }
}
