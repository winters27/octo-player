package app.winters.octo.sound

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

// Where the sound is going: the phone's speaker, a cable, or one Bluetooth
// or USB device, each keeping its own sound settings.
data class AudioOutput(val key: String, val label: String)

val PhoneSpeaker = AudioOutput("speaker", "Phone speaker")

// The start of a Bluetooth output's key; the rest is the device's address.
const val BLUETOOTH_OUTPUT_PREFIX = "bluetooth:"

private val Context.soundData by preferencesDataStore("sound")

// The sound settings, for the output playing now. With "each output its
// own sound" on, every output remembers its own; otherwise one set is used
// everywhere. The audio path reads `current` on every change.
@Singleton
class SoundEngine @Inject constructor(@ApplicationContext private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val mapSerializer = MapSerializer(String.serializer(), SoundSettings.serializer())

    private val _output = MutableStateFlow(currentOutput())
    val output: StateFlow<AudioOutput> = _output

    // Settings saved per output key; "all" holds the shared set.
    private val saved: StateFlow<Map<String, SoundSettings>> = context.soundData.data
        .map { p -> p[PROFILES]?.let { runCatching { json.decodeFromString(mapSerializer, it) }.getOrNull() }.orEmpty() }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val perOutput: StateFlow<Boolean> = context.soundData.data
        .map { it[PER_OUTPUT] ?: false }
        .stateIn(scope, SharingStarted.Eagerly, false)

    // What plays now. Starts from defaults until the saved settings are read.
    val current: StateFlow<SoundSettings> = combine(saved, perOutput, _output) { all, split, out ->
        all[keyFor(split, out)] ?: all[SHARED] ?: SoundSettings()
    }.stateIn(scope, SharingStarted.Eagerly, SoundSettings())

    init {
        audio.registerAudioDeviceCallback(
            object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) { _output.value = currentOutput() }
                override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) { _output.value = currentOutput() }
            },
            Handler(Looper.getMainLooper()),
        )
    }

    // Changes the settings for the output playing now.
    fun update(change: (SoundSettings) -> SoundSettings) {
        scope.launch {
            context.soundData.edit { p ->
                val all = p[PROFILES]?.let { runCatching { json.decodeFromString(mapSerializer, it) }.getOrNull() }.orEmpty()
                val key = keyFor(p[PER_OUTPUT] ?: false, _output.value)
                val before = all[key] ?: all[SHARED] ?: SoundSettings()
                p[PROFILES] = json.encodeToString(mapSerializer, all + (key to change(before)))
            }
        }
    }

    // Whether each output keeps its own settings. Turning it on starts every
    // output from the shared set.
    fun setPerOutput(on: Boolean) {
        scope.launch { context.soundData.edit { it[PER_OUTPUT] = on } }
    }

    // Every saved set by output key, and whether each output keeps its own,
    // for a backup.
    suspend fun saved(): Pair<Boolean, Map<String, SoundSettings>> {
        val p = context.soundData.data.first()
        val all = p[PROFILES]?.let { runCatching { json.decodeFromString(mapSerializer, it) }.getOrNull() }.orEmpty()
        return (p[PER_OUTPUT] ?: false) to all
    }

    // Puts back the sets from a backup. Outputs the backup does not name
    // keep what they have.
    suspend fun restore(perOutput: Boolean, profiles: Map<String, SoundSettings>) {
        context.soundData.edit { p ->
            val all = p[PROFILES]?.let { runCatching { json.decodeFromString(mapSerializer, it) }.getOrNull() }.orEmpty()
            p[PROFILES] = json.encodeToString(mapSerializer, all + profiles)
            p[PER_OUTPUT] = perOutput
        }
    }

    private fun keyFor(perOutput: Boolean, output: AudioOutput) = if (perOutput) output.key else SHARED

    // The output Android routes music to: a Bluetooth or USB device or a
    // cable when one is connected, otherwise the speaker.
    private fun currentOutput(): AudioOutput {
        val devices = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val best = devices.minByOrNull { rank(it.type) }?.takeIf { rank(it.type) < SPEAKER_RANK } ?: return PhoneSpeaker
        val name = best.productName?.toString()?.trim().orEmpty()
        return when (best.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER ->
                AudioOutput("$BLUETOOTH_OUTPUT_PREFIX${best.address.ifEmpty { name }}", name.ifEmpty { "Bluetooth" })
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE ->
                AudioOutput("usb:$name", name.ifEmpty { "USB audio" })
            else -> AudioOutput("wired", "Headphones")
        }
    }

    private fun rank(type: Int): Int = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> 0
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> 1
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> 2
        else -> SPEAKER_RANK
    }

    private companion object {
        const val SPEAKER_RANK = 9
        const val SHARED = "all"
        val PROFILES = stringPreferencesKey("profiles")
        val PER_OUTPUT = booleanPreferencesKey("per_output")
    }
}
