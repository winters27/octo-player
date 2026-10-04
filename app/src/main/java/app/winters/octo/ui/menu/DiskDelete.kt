package app.winters.octo.ui.menu

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.winters.octo.data.LibraryFiles
import app.winters.octo.design.GlassPopup
import app.winters.octo.health.DELETE_FROM_DISK
import app.winters.octo.health.countText
import app.winters.octo.health.deleteBody
import app.winters.octo.health.deleteTitle
import app.winters.octo.ui.common.LocalHaze
import app.winters.octo.ui.common.PopupQuestion
import app.winters.octo.ui.common.rememberLast
import app.winters.octo.ui.common.rememberOpenedBeside
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

// What the question names: the song's title for one, "3 songs" for more.
fun deleteNames(titles: List<String>): String = titles.singleOrNull() ?: countText(titles.size, "song", "songs")

// The question before songs leave the signed-in server's disk, with its
// two answers. The words are the desktop's.
@Composable
fun DiskDeleteQuestion(count: Int, names: String, keepDays: Int, onCancel: () -> Unit, onConfirm: () -> Unit) {
    PopupQuestion(deleteTitle(count), deleteBody(names, keepDays), "Delete", onConfirm = onConfirm, onCancel = onCancel)
}

// Songs picked in a list, waiting for the answer to deleting them from disk.
class DiskDeleteAsk(val trackIds: List<String>, val titles: List<String>)

@HiltViewModel
class DiskDeleteViewModel @Inject constructor(private val files: LibraryFiles) : ViewModel() {
    var asking by mutableStateOf<DiskDeleteAsk?>(null)
        private set

    val keepDays: StateFlow<Int> = files.actions.map { it?.keepDays ?: 0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val canRemove: StateFlow<Boolean> = files.actions.map { it?.canRemove == true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // The songs among these the server has a copy of, to delete.
    suspend fun deletable(trackIds: List<String>): List<String> = files.deletable(trackIds)

    fun ask(trackIds: List<String>, titles: List<String>) {
        if (trackIds.isNotEmpty()) asking = DiskDeleteAsk(trackIds, titles)
    }

    fun cancel() {
        asking = null
    }

    fun confirm() {
        val ask = asking ?: return
        asking = null
        files.deleteFromDisk(ask.trackIds, ask.titles.singleOrNull())
    }
}

// Asks before picked songs are deleted from disk, over everything.
@Composable
fun DiskDeleteHost(vm: DiskDeleteViewModel) {
    val open = vm.asking != null
    val keepDays by vm.keepDays.collectAsStateWithLifecycle()
    GlassPopup(
        visible = open,
        anchor = rememberOpenedBeside(open),
        onDismiss = vm::cancel,
        backdrop = LocalHaze.current,
        title = DELETE_FROM_DISK,
    ) {
        // Kept while the card fades, after the answer.
        val ask = rememberLast(vm.asking) ?: return@GlassPopup
        DiskDeleteQuestion(ask.trackIds.size, deleteNames(ask.titles), keepDays, onCancel = vm::cancel, onConfirm = vm::confirm)
    }
}
