package app.winters.octo.ui.home

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.winters.octo.design.AccentButton
import app.winters.octo.design.GlazeButton
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoType
import app.winters.octo.design.glassPanel
import app.winters.octo.playback.PlaybackConnection
import app.winters.octo.server.QueueSync
import app.winters.octo.server.ResumeOffer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ResumeViewModel @Inject constructor(
    private val queue: QueueSync,
    private val playback: PlaybackConnection,
) : ViewModel() {
    val offer: StateFlow<ResumeOffer?> = queue.offer

    // Loading the other device's queue.
    var loading by mutableStateOf(false)
        private set

    // Puts the other device's queue in place of this one, paused where it was.
    fun resume(offer: ResumeOffer) {
        if (loading) return
        loading = true
        viewModelScope.launch {
            try {
                val songs = queue.take(offer) ?: return@launch
                playback.loadQueue(songs.trackIds, songs.index, songs.positionMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("Octo", "resume failed: ${e.javaClass.simpleName}")
            } finally {
                loading = false
            }
        }
    }

    fun dismiss(offer: ResumeOffer) = queue.dismiss(offer)
}

// A quiet offer at the top of Home to carry on from another device, when
// the server has a newer queue saved by another app.
@Composable
fun ResumeCard(vm: ResumeViewModel = hiltViewModel()) {
    val offer by vm.offer.collectAsStateWithLifecycle()
    // The last offer, kept while the card folds away.
    val last = remember { mutableStateOf<ResumeOffer?>(null) }
    offer?.let { last.value = it }
    AnimatedVisibility(offer != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        val shown = offer ?: last.value ?: return@AnimatedVisibility
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 8.dp)
                .fillMaxWidth()
                .glassPanel(RoundedCornerShape(20.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Pick up where you left off on ${shown.device}", style = OctoType.caption, color = OctoColors.TextMuted)
            Text(shown.title, style = OctoType.body, color = OctoColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            shown.artist?.takeIf(String::isNotBlank)?.let {
                Text(it, style = OctoType.bodySmall, color = OctoColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AccentButton("Resume", onClick = { vm.resume(shown) }, loading = vm.loading)
                GlazeButton("Not now", onClick = { vm.dismiss(shown) })
            }
        }
    }
}
