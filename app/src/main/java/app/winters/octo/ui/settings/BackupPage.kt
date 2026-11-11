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
import app.winters.octo.backup.BackupFile
import app.winters.octo.backup.BackupOpened
import app.winters.octo.backup.BackupPlan
import app.winters.octo.backup.BackupSave
import app.winters.octo.backup.Backups
import app.winters.octo.backup.describeBackup
import app.winters.octo.backup.describePlan
import app.winters.octo.data.userMessage
import app.winters.octo.design.OctoIcons
import app.winters.octo.ui.settings.rows.ActionRow
import app.winters.octo.ui.settings.rows.NoteRow
import app.winters.octo.ui.settings.rows.SettingsGroup
import app.winters.octo.ui.settings.rows.SettingsPageFrame
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// Where saving or restoring a backup is up to.
sealed interface BackupState {
    data object Idle : BackupState

    data object Working : BackupState

    // Saved, or the words for why not.
    data class Saved(val problem: String?) : BackupState

    // A backup read and matched against the library, waiting to be restored.
    data class Ready(val file: BackupFile, val plan: BackupPlan) : BackupState

    data class Restored(val plan: BackupPlan) : BackupState

    data class Problem(val message: String) : BackupState
}

@HiltViewModel
class BackupViewModel @Inject constructor(private val backups: Backups) : ViewModel() {
    private val _state = MutableStateFlow<BackupState>(BackupState.Idle)
    val state: StateFlow<BackupState> = _state

    fun save(uri: Uri) {
        _state.value = BackupState.Working
        viewModelScope.launch {
            _state.value = BackupState.Saved((backups.save(uri) as? BackupSave.Failed)?.words)
        }
    }

    fun open(uri: Uri) {
        letGo()
        _state.value = BackupState.Working
        viewModelScope.launch {
            _state.value = when (val read = backups.read(uri)) {
                is BackupOpened.Opened -> try {
                    BackupState.Ready(read.file, backups.plan(read.file))
                } catch (e: CancellationException) {
                    backups.close(read.file)
                    throw e
                } catch (e: Exception) {
                    backups.close(read.file)
                    BackupState.Problem(e.userMessage())
                }
                BackupOpened.TooNew -> BackupState.Problem("This backup was made by a newer Octo. Update the app to restore it.")
                BackupOpened.NotABackup -> BackupState.Problem("That file isn't an Octo backup.")
                is BackupOpened.Problem -> BackupState.Problem(read.words)
            }
        }
    }

    fun restore() {
        val ready = _state.value as? BackupState.Ready ?: return
        _state.value = BackupState.Working
        viewModelScope.launch {
            val problem = backups.restore(ready.file, ready.plan)
            _state.value = if (problem == null) BackupState.Restored(ready.plan) else BackupState.Problem(problem)
        }
    }

    fun cancel() {
        letGo()
        _state.value = BackupState.Idle
    }

    // A file read and not restored is let go.
    private fun letGo() {
        (_state.value as? BackupState.Ready)?.let { ready -> RELEASE.launch { backups.close(ready.file) } }
    }

    override fun onCleared() = letGo()
}

// Where a backup read and not restored is let go once the page is gone.
private val RELEASE = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
                    icon = OctoIcons.Backup,
                    footer = "Restoring replaces your settings and adds the rest to what you have.",
                ) {
                    (describeBackup(now.file.header, now.file.sizes) + describePlan(now.plan)).forEach { line -> NoteRow(line) }
                }
                SettingsGroup {
                    ActionRow(null, title = "Restore", onClick = vm::restore, chevron = false)
                    ActionRow(null, title = "Cancel", onClick = vm::cancel, chevron = false)
                }
            }
            else -> {
                val note = when (now) {
                    is BackupState.Saved -> now.problem ?: "Backup saved."
                    is BackupState.Restored -> (listOf("Restored.") + describePlan(now.plan)).joinToString("\n")
                    is BackupState.Problem -> now.message
                    else -> null
                }
                SettingsGroup(
                    footer = "Settings, sound, playlists, likes and ratings, in one file. Never passwords or keys.",
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
