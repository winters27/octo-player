package app.winters.octo.ui.home

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
import app.winters.octo.design.GlazeButton
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoType
import app.winters.octo.design.glassPanel
import app.winters.octo.ui.common.FloatingSheet
import app.winters.octo.whatsnew.WhatsNewItems
import app.winters.octo.whatsnew.WhatsNewList
import app.winters.octo.whatsnew.WhatsNewStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WhatsNewViewModel @Inject constructor(private val store: WhatsNewStore) : ViewModel() {
    // Starts hidden, so the card never flashes up before it is known.
    val due: StateFlow<Boolean> = store.due.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init {
        viewModelScope.launch { store.settle() }
    }

    fun dismiss() {
        viewModelScope.launch { store.markSeen() }
    }
}

// A quiet card at the top of Home, once after an update, saying what is
// new. Reading the list or closing the card puts it away until the next.
@Composable
fun WhatsNewCard(vm: WhatsNewViewModel = hiltViewModel()) {
    val due by vm.due.collectAsStateWithLifecycle()
    var reading by remember { mutableStateOf(false) }
    AnimatedVisibility(due, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 8.dp)
                .fillMaxWidth()
                .glassPanel(RoundedCornerShape(20.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("New in Octo", style = OctoType.caption, color = OctoColors.TextMuted)
            Text(
                WhatsNewItems.take(3).joinToString(", ") { it.title },
                style = OctoType.body,
                color = OctoColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlazeButton("See what's new", onClick = { reading = true })
                GlazeButton("Dismiss", onClick = vm::dismiss)
            }
        }
    }
    FloatingSheet(
        visible = reading,
        onDismiss = {
            reading = false
            vm.dismiss()
        },
    ) {
        WhatsNewList()
    }
}
