package app.winters.octo.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
import app.winters.octo.design.AccentButton
import app.winters.octo.design.GlazeButton
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoType
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
internal fun BackupCard(modifier: Modifier = Modifier, vm: BackupViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val saveTo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let(vm::save)
    }
    val openFrom = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::open)
    }
    val working = state == BackupState.Working

    Card("Backup", modifier) {
        Text(
            "Saves your settings, sound, equalizer presets, playlists, likes and ratings to a file. " +
                "Passwords, keys and certificates are never saved.",
            style = OctoType.caption,
            color = OctoColors.TextMuted,
        )
        when (val now = state) {
            is BackupState.Ready -> {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("In this backup", style = OctoType.label, color = OctoColors.TextSecondary)
                    (describeBackup(now.backup) + describePlan(now.plan)).forEach { line ->
                        Text(line, style = OctoType.bodySmall, color = OctoColors.TextPrimary)
                    }
                    Text(
                        "Restoring replaces your settings. Playlists are added, and songs are liked and rated.",
                        style = OctoType.caption,
                        color = OctoColors.TextMuted,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AccentButton("Restore", onClick = vm::restore)
                    GlazeButton("Cancel", onClick = vm::cancel)
                }
            }
            else -> {
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AccentButton(
                        "Save backup",
                        onClick = { saveTo.launch("Octo backup ${LocalDate.now()}.json") },
                        enabled = !working,
                        loading = working,
                    )
                    GlazeButton(
                        "Restore",
                        onClick = { openFrom.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                        enabled = !working,
                    )
                }
                val note = when (now) {
                    is BackupState.Saved -> if (now.ok) "Backup saved." else "Couldn't write the backup there."
                    is BackupState.Restored -> (listOf("Restored.") + describePlan(now.plan)).joinToString("\n")
                    is BackupState.Problem -> now.message
                    else -> null
                }
                note?.let { Text(it, style = OctoType.caption, color = OctoColors.TextMuted) }
            }
        }
    }
}
