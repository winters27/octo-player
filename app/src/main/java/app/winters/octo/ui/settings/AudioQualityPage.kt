package app.winters.octo.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.winters.octo.data.SessionRepository
import app.winters.octo.data.SessionState
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.family.FamilyHub
import app.winters.octo.family.family
import app.winters.octo.player.PlayerSettings
import app.winters.octo.player.StreamPrefs
import app.winters.octo.playback.StreamQuality
import app.winters.octo.subsonic.DeviceQualityMode
import app.winters.octo.ui.common.Choice
import app.winters.octo.ui.common.ChoiceRequest
import app.winters.octo.ui.common.LocalChoiceSheet
import app.winters.octo.ui.family.QUALITY_AT_HOME
import app.winters.octo.ui.family.QUALITY_AWAY
import app.winters.octo.ui.family.QUALITY_ON_THIS_DEVICE
import app.winters.octo.ui.family.appPicksQuality
import app.winters.octo.ui.family.awayLimit
import app.winters.octo.ui.family.deviceModeLine
import app.winters.octo.ui.family.deviceModeName
import app.winters.octo.ui.family.familyLimitLines
import app.winters.octo.ui.family.homeLimit
import app.winters.octo.ui.family.qualityName
import app.winters.octo.ui.family.qualityOptions
import app.winters.octo.ui.settings.rows.ChoiceRow
import app.winters.octo.ui.settings.rows.NoteRow
import app.winters.octo.ui.settings.rows.SettingsGroup
import app.winters.octo.ui.settings.rows.SettingsPageFrame
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AudioQualityViewModel @Inject constructor(
    val hub: FamilyHub,
    private val sessions: SessionRepository,
    private val settings: PlayerSettings,
) : ViewModel() {
    val prefs: StateFlow<StreamPrefs> = settings.streamPrefs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StreamPrefs())

    // Whether the server in use has Family on.
    val familyOn: Boolean get() = (sessions.state.value as? SessionState.SignedIn)?.session?.family == true

    fun load() {
        viewModelScope.launch { hub.plan() }
    }

    fun setWifi(quality: StreamQuality) {
        viewModelScope.launch { settings.setStreamWifi(quality) }
    }

    fun setMobile(quality: StreamQuality) {
        viewModelScope.launch { settings.setStreamMobile(quality) }
    }
}

// Audio quality: with Family on, the account's own quality at home and
// away (kept on the server for every app), the family's limits in plain
// words, and who picks this phone's quality. Then this app's own quality on
// Wi-Fi and on mobile data, which applies without a family or when this
// phone is left to the app.
@Composable
fun AudioQualityPage(onBack: () -> Unit, highlight: String?, vm: AudioQualityViewModel = hiltViewModel()) {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val sheet = LocalChoiceSheet.current
    val familyOn = vm.familyOn
    LaunchedEffect(familyOn) { if (familyOn) vm.load() }
    val model = vm.hub.model
    val me = model.me
    val qualities = StreamQuality.entries
    SettingsPageFrame(SettingsPage.AudioQuality.title, onBack, highlight, icon = OctoIcons.AudioQuality) {
        if (familyOn && me != null) {
            val quality = me.quality
            SettingsGroup(title = "Your account, on every app", icon = OctoIcons.Listeners) {
                familyLimitLines(quality).forEach { NoteRow(it, color = OctoColors.SignalOrange) }
                ChoiceRow(SettingsIndex.AccountHome, value = qualityName(quality.home), onClick = {
                    val options = qualityOptions(homeLimit(quality))
                    sheet.show(ChoiceRequest(QUALITY_AT_HOME, options.map { Choice(it.name, limitedLine(it.line, it.limited, homeLimit(quality))) }, options.indexOfFirst { it.quality == quality.home }) {
                        model.setQuality(home = options[it].quality)
                    })
                })
                ChoiceRow(SettingsIndex.AccountAway, value = qualityName(quality.away), onClick = {
                    val options = qualityOptions(awayLimit(quality))
                    sheet.show(ChoiceRequest(QUALITY_AWAY, options.map { Choice(it.name, limitedLine(it.line, it.limited, awayLimit(quality))) }, options.indexOfFirst { it.quality == quality.away }) {
                        model.setQuality(away = options[it].quality)
                    })
                })
            }
            model.deviceMode?.let { mode ->
                SettingsGroup(title = QUALITY_ON_THIS_DEVICE, icon = OctoIcons.Phone) {
                    ChoiceRow(SettingsIndex.DeviceQuality, value = deviceModeName(mode), onClick = {
                        val modes = DeviceQualityMode.entries
                        sheet.show(ChoiceRequest(QUALITY_ON_THIS_DEVICE, modes.map { Choice(deviceModeName(it), deviceModeLine(it)) }, modes.indexOf(mode)) {
                            model.setDeviceMode(modes[it])
                        })
                    })
                }
            }
        }
        val applies = appPicksQuality(familyOn, model.deviceMode)
        SettingsGroup(title = if (familyOn) "This app's quality" else "Streaming", icon = OctoIcons.Wifi) {
            if (!applies) NoteRow("Your account's choice applies now. These apply when this phone is left to the app.")
            ChoiceRow(SettingsIndex.StreamWifi, value = prefs.wifi.label, onClick = {
                sheet.show(ChoiceRequest(SettingsIndex.StreamWifi.title, qualities.map { it.choice }, qualities.indexOf(prefs.wifi)) { vm.setWifi(qualities[it]) })
            })
            ChoiceRow(SettingsIndex.StreamMobile, value = prefs.mobile.label, onClick = {
                sheet.show(ChoiceRequest(SettingsIndex.StreamMobile.title, qualities.map { it.choice }, qualities.indexOf(prefs.mobile)) { vm.setMobile(qualities[it]) })
            })
        }
    }
}

private fun limitedLine(line: String, limited: Boolean, limit: Int): String = if (limited) "Plays at $limit kbps, your family's limit" else line

// A size as its sheet lists it, with roughly how much data an hour uses.
private val StreamQuality.choice: Choice
    get() = Choice(label, kbps?.let { "MP3, about ${Math.round(it * 0.45)} MB an hour" } ?: "The server's file, unchanged")
