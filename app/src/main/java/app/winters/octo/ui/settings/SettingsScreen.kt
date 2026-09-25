package app.winters.octo.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.winters.octo.BuildConfig
import app.winters.octo.catalog.CatalogDao
import app.winters.octo.design.AccentButton
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoType
import app.winters.octo.design.glassPanel
import app.winters.octo.device.Access
import app.winters.octo.device.DEVICE
import app.winters.octo.device.DeviceLibrary
import app.winters.octo.ui.common.ScreenTitle
import app.winters.octo.ui.common.screenPadding
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    val library: DeviceLibrary,
    dao: CatalogDao,
) : ViewModel() {
    val songCount: StateFlow<Int> =
        dao.trackCount(DEVICE).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun rescan() {
        viewModelScope.launch { library.rescan() }
    }
}

private val CardShape = RoundedCornerShape(20.dp)

@Composable
fun SettingsScreen(vm: SettingsViewModel = hiltViewModel()) {
    val access by vm.library.access.collectAsStateWithLifecycle()
    val scanning by vm.library.scanning.collectAsStateWithLifecycle()
    val count by vm.songCount.collectAsStateWithLifecycle()
    val padding = screenPadding()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding()),
    ) {
        ScreenTitle("Settings")

        Card("Music on this phone") {
            Line("Access", if (access == Access.Granted) "Allowed" else "Not allowed")
            Line("Songs", "%,d".format(count))
            if (scanning) Line("Status", "Looking for music…")
            AccentButton(
                text = "Rescan",
                onClick = vm::rescan,
                enabled = access == Access.Granted,
                loading = scanning,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        Card("About", Modifier.padding(top = 16.dp)) {
            Line("Octo", BuildConfig.VERSION_NAME)
        }
    }
}

@Composable
private fun Card(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .glassPanel(CardShape)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, style = OctoType.section, color = OctoColors.TextPrimary)
        content()
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = OctoType.bodySmall, color = OctoColors.TextSecondary, modifier = Modifier.weight(1f))
        Text(value, style = OctoType.bodySmall, color = OctoColors.TextPrimary)
    }
}
