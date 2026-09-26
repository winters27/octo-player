package app.winters.octo.ui.server

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.winters.octo.data.userMessage
import app.winters.octo.design.AccentButton
import app.winters.octo.design.GlassSheet
import app.winters.octo.design.GlazeButton
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.server.ServerControls
import app.winters.octo.server.sharingUnavailable
import app.winters.octo.subsonic.Share
import app.winters.octo.ui.common.BackButton
import app.winters.octo.ui.common.DetailTopGap
import app.winters.octo.ui.common.LoadState
import app.winters.octo.ui.common.ScreenTitle
import app.winters.octo.ui.common.screenPadding
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SharesViewModel @Inject constructor(private val controls: ServerControls) : ViewModel() {
    var shares by mutableStateOf<LoadState<List<Share>>>(LoadState.Loading)
        private set

    // The link waiting for a yes before it is deleted.
    var deleting by mutableStateOf<Share?>(null)
        private set
    var problem by mutableStateOf<String?>(null)
        private set

    init {
        reload()
    }

    fun reload() {
        shares = LoadState.Loading
        viewModelScope.launch {
            shares = try {
                LoadState.Ready(controls.shares())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LoadState.Failed(if (sharingUnavailable(e)) "Sharing is switched off on this server." else e.userMessage())
            }
        }
    }

    fun askDelete(share: Share) {
        deleting = share
    }

    fun cancelDelete() {
        deleting = null
    }

    fun delete(share: Share) {
        deleting = null
        problem = null
        viewModelScope.launch {
            try {
                controls.deleteShare(share.id)
                (shares as? LoadState.Ready)?.let { shares = LoadState.Ready(it.data.filterNot { s -> s.id == share.id }) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problem = "Couldn't delete that link. ${e.userMessage()}"
            }
        }
    }
}

// The links shared from this account: what each holds, when it expires and
// how often it was opened. Tapping one shares it again; each can be deleted.
@Composable
fun SharesScreen(onBack: () -> Unit, vm: SharesViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val now = System.currentTimeMillis()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = screenPadding(extraTop = DetailTopGap)) {
            item(key = "title") { ScreenTitle("Shared links") }
            vm.problem?.let { item(key = "problem") { Note(it, OctoColors.Error) } }
            when (val state = vm.shares) {
                LoadState.Loading -> item(key = "loading") { Spinner(Modifier.padding(top = 24.dp)) }
                is LoadState.Failed -> item(key = "failed") {
                    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(state.message, style = OctoType.bodySmall, color = OctoColors.TextMuted)
                        GlazeButton("Try again", onClick = vm::reload)
                    }
                }
                is LoadState.Ready -> {
                    if (state.data.isEmpty()) {
                        item(key = "none") { Note("No shared links yet. Share a song or an album from its menu.") }
                    }
                    items(state.data, key = { it.id }) { share ->
                        ShareRow(
                            share,
                            now,
                            onShare = { shareLink(context, share.url, shareTitle(share)) },
                            onDelete = { vm.askDelete(share) },
                        )
                    }
                }
            }
        }
        BackButton(onBack)
        GlassSheet(visible = vm.deleting != null, onDismiss = vm::cancelDelete) {
            val share = vm.deleting ?: return@GlassSheet
            Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
                Text("Delete this link?", style = OctoType.section, color = OctoColors.TextPrimary)
                Text(
                    "\"${shareTitle(share)}\" stops working for everyone it was sent to.",
                    style = OctoType.bodySmall,
                    color = OctoColors.TextMuted,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AccentButton("Delete", onClick = { vm.delete(share) })
                    GlazeButton("Cancel", onClick = vm::cancelDelete)
                }
            }
        }
    }
}

@Composable
private fun ShareRow(share: Share, now: Long, onShare: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = share.url.isNotBlank(), role = Role.Button, onClick = onShare)
            .heightIn(min = 60.dp)
            .padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(shareTitle(share), style = OctoType.bodySmall, color = OctoColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(shareDetails(share, now), style = OctoType.caption, color = OctoColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onDelete) {
            Icon(painterResource(OctoIcons.Delete), contentDescription = "Delete link", tint = OctoColors.TextMuted, modifier = Modifier.size(20.dp))
        }
    }
}
