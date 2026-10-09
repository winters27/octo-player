package app.winters.octo.ui.settings

import app.winters.octo.ui.nav.SettingsPageRoute
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.NavKey
import app.winters.octo.design.OctoIcons
import app.winters.octo.offline.CacheSize
import app.winters.octo.offline.OfflinePrefs
import app.winters.octo.offline.OfflineSettings
import app.winters.octo.offline.StreamCache
import app.winters.octo.playback.CopyPreference
import app.winters.octo.playback.StreamQuality
import app.winters.octo.player.PlayerSettings
import app.winters.octo.player.StreamPrefs
import app.winters.octo.ui.common.Choice
import app.winters.octo.ui.common.ChoiceRequest
import app.winters.octo.ui.common.LocalChoiceSheet
import app.winters.octo.ui.nav.DownloadsRoute
import app.winters.octo.ui.offline.sizeLabel
import app.winters.octo.ui.settings.rows.ActionRow
import app.winters.octo.ui.settings.rows.ChoiceRow
import app.winters.octo.ui.settings.rows.SettingsGroup
import app.winters.octo.ui.settings.rows.SettingsPageFrame
import app.winters.octo.ui.settings.rows.SwitchRow
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class StreamingViewModel @Inject constructor(private val settings: PlayerSettings) : ViewModel() {
    val prefs: StateFlow<StreamPrefs> =
        settings.streamPrefs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StreamPrefs())

    fun setCopies(preference: CopyPreference) {
        viewModelScope.launch { settings.setCopies(preference) }
    }

    fun setWifi(quality: StreamQuality) {
        viewModelScope.launch { settings.setStreamWifi(quality) }
    }

    fun setMobile(quality: StreamQuality) {
        viewModelScope.launch { settings.setStreamMobile(quality) }
    }
}

@HiltViewModel
class OfflineViewModel @Inject constructor(
    private val settings: OfflineSettings,
    private val cache: StreamCache,
) : ViewModel() {
    val prefs: StateFlow<OfflinePrefs> =
        settings.prefs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OfflinePrefs())

    // How much the cache holds now, measured again as the page opens.
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

private val CopyChoices = mapOf(
    CopyPreference.PhoneFirst to Choice("Phone copy first", "Plays the phone's file, and streams only songs the phone lacks."),
    CopyPreference.BestQuality to Choice("Best quality", "Plays whichever copy sounds better, even when that means streaming."),
)

// A size as its sheet lists it, with roughly how much data an hour uses.
private val StreamQuality.choice: Choice
    get() = Choice(label, kbps?.let { "MP3, about ${Math.round(it * 0.45)} MB an hour" } ?: "The server's file, unchanged")

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

// How music from a server plays, the cache of songs played lately, fetching
// the next songs ahead, and downloads. Each choice opens a sheet.
@Composable
fun StreamingPage(
    onOpen: (NavKey) -> Unit,
    onBack: () -> Unit,
    highlight: String?,
    vm: StreamingViewModel = hiltViewModel(),
    offline: OfflineViewModel = hiltViewModel(),
) {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val kept by offline.prefs.collectAsStateWithLifecycle()
    val used by offline.used.collectAsStateWithLifecycle()
    val sheet = LocalChoiceSheet.current
    val qualities = StreamQuality.entries
    val sizes = CacheSize.entries

    SettingsPageFrame("Streaming and downloads", onBack, highlight, icon = OctoIcons.Download) {
        SettingsGroup(title = "Streaming", icon = OctoIcons.Wifi) {
            ChoiceRow(SettingsIndex.Copies, value = CopyChoices.getValue(prefs.copies).label, onClick = {
                val options = CopyPreference.entries
                sheet.show(
                    ChoiceRequest(SettingsIndex.Copies.title, options.map(CopyChoices::getValue), options.indexOf(prefs.copies)) {
                        vm.setCopies(options[it])
                    },
                )
            })
            // Quality on Wi-Fi and mobile data, and a family account's own
            // choice, are on Audio quality.
            ActionRow(null, onClick = { onOpen(SettingsPageRoute(SettingsPage.AudioQuality)) }, title = SettingsPage.AudioQuality.title, helper = "On Wi-Fi ${prefs.wifi.label}, on mobile data ${prefs.mobile.label}")
        }

        SettingsGroup(title = "Cache", icon = OctoIcons.Storage) {
            ChoiceRow(
                SettingsIndex.CacheSize,
                value = if (kept.cacheSize == CacheSize.Off) "Off" else "${kept.cacheSize.label}, ${sizeLabel(used)} used",
                onClick = {
                    sheet.show(ChoiceRequest(SettingsIndex.CacheSize.title, sizes.map { it.choice }, sizes.indexOf(kept.cacheSize)) { offline.setCacheSize(sizes[it]) })
                },
            )
            if (kept.cacheSize != CacheSize.Off) {
                ChoiceRow(SettingsIndex.FetchWifi, value = ahead(kept.prefetchWifi), onClick = {
                    sheet.show(aheadRequest(SettingsIndex.FetchWifi.title, kept.prefetchWifi, offline::setPrefetchWifi))
                })
                ChoiceRow(SettingsIndex.FetchMobile, value = ahead(kept.prefetchMobile), onClick = {
                    sheet.show(aheadRequest(SettingsIndex.FetchMobile.title, kept.prefetchMobile, offline::setPrefetchMobile))
                })
                ActionRow(SettingsIndex.ClearCache, onClick = offline::clearCache, enabled = used > 0, chevron = false)
            }
        }

        SettingsGroup(title = "Downloads", icon = OctoIcons.Download) {
            ChoiceRow(SettingsIndex.DownloadQuality, value = kept.downloadQuality.downloadLabel, onClick = {
                sheet.show(
                    ChoiceRequest(SettingsIndex.DownloadQuality.title, qualities.map { it.downloadChoice }, qualities.indexOf(kept.downloadQuality)) {
                        offline.setDownloadQuality(qualities[it])
                    },
                )
            })
            SwitchRow(
                SettingsIndex.WifiOnly,
                checked = kept.wifiOnly,
                onChange = offline::setWifiOnly,
            )
            SwitchRow(
                SettingsIndex.KeepLiked,
                checked = kept.keepLiked,
                onChange = offline::setKeepLiked,
                helper = "Liked songs from your server, kept in step with your likes.",
            )
            SwitchRow(
                SettingsIndex.StreamOnWifi,
                checked = kept.streamOnWifi,
                onChange = offline::setStreamOnWifi,
                helper = "Even songs that are downloaded.",
            )
            ActionRow(SettingsIndex.DownloadedMusic, onClick = { onOpen(DownloadsRoute) })
        }
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
