package app.winters.octo.ui.server

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.winters.octo.data.userMessage
import app.winters.octo.design.GlassSheet
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoType
import app.winters.octo.server.ServerControls
import app.winters.octo.server.ShareExpiry
import app.winters.octo.server.sharingUnavailable
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import javax.inject.Inject

// Something to share: server ids (songs, or one album), and its name for
// the sheet and the link's description.
class ShareRequest(val ids: List<String>, val title: String)

// The sheet that makes a shared link. It is drawn over everything, so the
// song menu and any page can open it.
class ShareSheetState {
    var open by mutableStateOf<ShareRequest?>(null)
        private set

    // The last request shown, kept after closing so the sheet can slide away.
    var last by mutableStateOf<ShareRequest?>(null)
        private set

    // Counts every showing, so each starts fresh.
    var shown by mutableIntStateOf(0)
        private set

    fun show(request: ShareRequest) {
        open = request
        last = request
        shown++
    }

    fun close() {
        open = null
    }
}

val LocalShareSheet = staticCompositionLocalOf<ShareSheetState> { error("No share sheet") }

@HiltViewModel
class ShareViewModel @Inject constructor(private val controls: ServerControls) : ViewModel() {
    // The expiry whose link is being made, if any.
    var making by mutableStateOf<ShareExpiry?>(null)
        private set
    var problem by mutableStateOf<String?>(null)
        private set

    fun reset() {
        making = null
        problem = null
    }

    // Makes the link and hands its address on.
    fun make(request: ShareRequest, expiry: ShareExpiry, onReady: (String) -> Unit) {
        if (making != null) return
        making = expiry
        problem = null
        viewModelScope.launch {
            try {
                val share = controls.share(request.ids, request.title, expiry)
                if (share.url.isBlank()) problem = "The server made the link but did not say where it is." else onReady(share.url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problem = if (sharingUnavailable(e)) "Sharing is switched off on this server." else e.userMessage()
            } finally {
                making = null
            }
        }
    }
}

// Opens the phone's share sheet with a link.
fun shareLink(context: Context, url: String, title: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, url)
        putExtra(Intent.EXTRA_SUBJECT, title)
    }
    runCatching { context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

// How long the link lasts, as a short list; picking one makes the link and
// opens the phone's share sheet.
@Composable
fun ShareSheetHost(state: ShareSheetState, vm: ShareViewModel = hiltViewModel()) {
    val context = LocalContext.current
    GlassSheet(visible = state.open != null, onDismiss = state::close) {
        val request = state.last ?: return@GlassSheet
        key(state.shown) {
            LaunchedEffect(Unit) { vm.reset() }
            Text(
                "Share \"${request.title}\"",
                style = OctoType.section,
                color = OctoColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Text(
                "Anyone with the link can listen.",
                style = OctoType.caption,
                color = OctoColors.TextMuted,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp),
            )
            Text(
                "Link expires",
                style = OctoType.label,
                color = OctoColors.TextSecondary,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
            )
            ShareExpiry.entries.forEach { expiry ->
                ExpiryLine(expiry.label, busy = vm.making == expiry, enabled = vm.making == null) {
                    vm.make(request, expiry) { url ->
                        state.close()
                        shareLink(context, url, request.title)
                    }
                }
            }
            vm.problem?.let {
                Text(it, style = OctoType.caption, color = OctoColors.Error, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ExpiryLine(label: String, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .height(52.dp)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            label,
            style = OctoType.bodySmall,
            color = if (enabled || busy) OctoColors.TextPrimary else OctoColors.TextMuted,
            modifier = Modifier.weight(1f),
        )
        if (busy) CircularProgressIndicator(color = OctoColors.Accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
    }
}
