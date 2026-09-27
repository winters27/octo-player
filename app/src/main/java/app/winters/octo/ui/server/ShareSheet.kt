package app.winters.octo.ui.server

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.winters.octo.data.userMessage
import app.winters.octo.design.GlassPopup
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoType
import app.winters.octo.ui.common.GlassMenuBack
import app.winters.octo.ui.common.GlassMenuHeading
import app.winters.octo.ui.common.GlassMenuNote
import app.winters.octo.ui.common.GlassMenuPage
import app.winters.octo.ui.common.GlassMenuTitle
import app.winters.octo.ui.common.LocalHaze
import app.winters.octo.ui.common.rememberOpenedBeside
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

// Sharing from a page, like an album's: a glass card beside the button.
@Composable
fun ShareSheetHost(state: ShareSheetState) {
    val open = state.open != null
    GlassPopup(
        visible = open,
        anchor = rememberOpenedBeside(open),
        onDismiss = state::close,
        backdrop = LocalHaze.current,
        title = "Share",
    ) {
        val request = state.last ?: return@GlassPopup
        key(state.shown) { ShareLinkPage(request, onBack = null, onDone = state::close) }
    }
}

// How long the link lasts, as a short list; picking one makes the link and
// opens the phone's share sheet. A page of a glass menu; `onBack` is there
// when it was opened from another page.
@Composable
fun ShareLinkPage(request: ShareRequest, onBack: (() -> Unit)?, onDone: () -> Unit, vm: ShareViewModel = hiltViewModel()) {
    val context = LocalContext.current
    // Each page starts with nothing being made and nothing gone wrong.
    LaunchedEffect(Unit) { vm.reset() }
    GlassMenuPage(
        header = {
            if (onBack != null) GlassMenuBack("Share link", onBack) else GlassMenuHeading("Share \"${request.title}\"")
        },
    ) {
        GlassMenuNote("Anyone with the link can listen.")
        GlassMenuTitle("Link expires")
        ShareExpiry.entries.forEach { expiry ->
            ExpiryLine(expiry.label, busy = vm.making == expiry, enabled = vm.making == null) {
                vm.make(request, expiry) { url ->
                    onDone()
                    shareLink(context, url, request.title)
                }
            }
        }
        vm.problem?.let { GlassMenuNote(it, color = OctoColors.Error) }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun ExpiryLine(label: String, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
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

