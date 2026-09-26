package app.winters.octo.ui.settings

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
import app.winters.octo.design.GlazeButton
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoType
import app.winters.octo.offline.CacheSize
import app.winters.octo.offline.OfflinePrefs
import app.winters.octo.offline.OfflineSettings
import app.winters.octo.offline.StreamCache
import app.winters.octo.playback.StreamQuality
import app.winters.octo.ui.common.Choice
import app.winters.octo.ui.common.ChoiceRequest
import app.winters.octo.ui.common.LocalChoiceSheet
import app.winters.octo.ui.offline.sizeLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OfflineViewModel @Inject constructor(
    private val settings: OfflineSettings,
    private val cache: StreamCache,
) : ViewModel() {
    val prefs: StateFlow<OfflinePrefs> =
        settings.prefs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OfflinePrefs())

    // How much the cache holds now, measured again as the settings open.
    val used: StateFlow<Long> = cache.usedBytes

    init {
        viewModelScope.launch(Dispatchers.IO) { cache.refreshUsed() }
    }

    private fun change(block: suspend OfflineSettings.() -> Unit) {
        viewModelScope.launch { settings.block() }
    }

    fun setCacheSize(size: CacheSize) = change { setCacheSize(size) }
    fun setPrefetchWifi(count: Int) = change { setPrefetchWifi(count) }
    fun setPrefetchMobile(count: Int) = change { setPrefetchMobile(count) }
    fun setDownloadQuality(quality: StreamQuality) = change { setDownloadQuality(quality) }
    fun setWifiOnly(on: Boolean) = change { setWifiOnly(on) }
    fun setStreamOnWifi(on: Boolean) = change { setStreamOnWifi(on) }
    fun setKeepLiked(on: Boolean) = change { setKeepLiked(on) }

    fun clearCache() {
        viewModelScope.launch { cache.clear() }
    }
}

// The cache sizes as the sheet lists them.
private val CacheSize.label: String get() = if (this == CacheSize.Off) "Off" else "${bytes shr 30} GB"

private val CacheSize.choice: Choice
    get() = Choice(label, if (this == CacheSize.Off) "Songs stream every time, and nothing is fetched ahead" else null)

// How many songs ahead the sheets offer.
private val AheadCounts = listOf(0, 1, 2, 3, 5, 10)

private fun ahead(count: Int): String = when (count) {
    0 -> "Off"
    1 -> "1 song"
    else -> "$count songs"
}

private val StreamQuality.downloadLabel: String get() = kbps?.let { "$it kbps MP3" } ?: "Original"

private val StreamQuality.downloadChoice: Choice
    get() = Choice(downloadLabel, kbps?.let { "About ${Math.round(it * 0.03)} MB for a 4 minute song" } ?: "The server's file, unchanged")

// Playing without a connection: the cache of songs played lately, fetching
// the next songs ahead, and downloads.
@Composable
fun OfflineCard(modifier: Modifier = Modifier, vm: OfflineViewModel = hiltViewModel()) {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val used by vm.used.collectAsStateWithLifecycle()
    val sheet = LocalChoiceSheet.current
    val sizes = CacheSize.entries
    val qualities = StreamQuality.entries

    Card("Offline", modifier) {
        Text("Cache", style = OctoType.label, color = OctoColors.TextSecondary)
        ChoiceLine(
            "Cache size",
            if (prefs.cacheSize == CacheSize.Off) "Off" else "${prefs.cacheSize.label}, ${sizeLabel(used)} used",
        ) {
            sheet.show(ChoiceRequest("Cache size", sizes.map { it.choice }, sizes.indexOf(prefs.cacheSize)) { vm.setCacheSize(sizes[it]) })
        }
        if (prefs.cacheSize != CacheSize.Off) {
            ChoiceLine("Fetch ahead on Wi-Fi", ahead(prefs.prefetchWifi)) {
                sheet.show(aheadRequest("Fetch ahead on Wi-Fi", prefs.prefetchWifi, vm::setPrefetchWifi))
            }
            ChoiceLine("Fetch ahead on mobile data", ahead(prefs.prefetchMobile)) {
                sheet.show(aheadRequest("Fetch ahead on mobile data", prefs.prefetchMobile, vm::setPrefetchMobile))
            }
            GlazeButton("Clear cache", onClick = vm::clearCache, enabled = used > 0, modifier = Modifier.padding(top = 4.dp))
        }

        Text("Downloads", style = OctoType.label, color = OctoColors.TextSecondary, modifier = Modifier.padding(top = 8.dp))
        ChoiceLine("Download quality", prefs.downloadQuality.downloadLabel) {
            sheet.show(
                ChoiceRequest("Download quality", qualities.map { it.downloadChoice }, qualities.indexOf(prefs.downloadQuality)) {
                    vm.setDownloadQuality(qualities[it])
                },
            )
        }
        SwitchLine(
            label = "Download on Wi-Fi only",
            detail = "Downloads wait for Wi-Fi instead of using mobile data.",
            checked = prefs.wifiOnly,
            onChange = vm::setWifiOnly,
        )
        SwitchLine(
            label = "Keep Liked songs downloaded",
            detail = "Liked songs only on your server are downloaded, and follow your likes.",
            checked = prefs.keepLiked,
            onChange = vm::setKeepLiked,
        )
        SwitchLine(
            label = "Prefer streaming on Wi-Fi",
            detail = "On Wi-Fi, stream songs even when they are downloaded.",
            checked = prefs.streamOnWifi,
            onChange = vm::setStreamOnWifi,
        )
    }
}

private fun aheadRequest(title: String, current: Int, onPick: (Int) -> Unit): ChoiceRequest {
    val counts = if (current in AheadCounts) AheadCounts else (AheadCounts + current).sorted()
    return ChoiceRequest(
        title,
        counts.map { Choice(ahead(it), if (it == 0) "Only the playing song is saved" else null) },
        counts.indexOf(current),
    ) { onPick(counts[it]) }
}
