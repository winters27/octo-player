package app.winters.octo.desktop.settings

import app.winters.octo.sound.SoundSettings
import app.winters.octo.subsonic.AuthMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

// Everything the desktop app remembers between runs, as one JSON file in
// the settings folder. Every field has a default, so a file from an older
// or newer version still reads. Passwords are never in here; they live in
// the system's password store.
@Serializable
data class AppSettings(
    val window: WindowSpot? = null,
    // Use the system's own title bar instead of the app's glass one.
    val systemTitleBar: Boolean = false,
    val server: SavedServer? = null,
    val appearance: Appearance = Appearance(),
    val playback: PlaybackPrefs = PlaybackPrefs(),
    // How the Songs page is ordered, as the shared sort lists save it.
    val songSort: String? = null,
    // How the Albums page is ordered, the same way.
    val albumSort: String? = null,
    // The panel open on the right: "queue", "lyrics", or none.
    val sidePanel: String? = null,
    // The equalizer, loudness and the rest of the Sound page.
    val sound: SoundPrefs = SoundPrefs(),
    // Where lyrics come from, and their timing.
    val lyrics: LyricsPrefs = LyricsPrefs(),
)

// Lyrics settings, by song id (the server's) and by output device id.
@Serializable
data class LyricsPrefs(
    // Whether the online lyrics library (LRCLIB) may be asked, as on the phone.
    val online: Boolean = true,
    // How much later than written each song's words are heard, in ms; kept
    // only for songs that were moved.
    val offsets: Map<String, Long> = emptyMap(),
    // The same for each output: a Bluetooth pair heard late, say.
    val outputOffsets: Map<String, Long> = emptyMap(),
    // Songs whose lyrics the listener hid, on a server that does not keep
    // that choice itself.
    val hidden: Set<String> = emptySet(),
    // Lyrics picked for a song from somewhere other than the server:
    // "online:<id>" for a copy in the online library, "file" for the song
    // file's own.
    val picks: Map<String, String> = emptyMap(),
)

// The Sound page's settings. With `perOutput` on, every output (a sound
// card, headphones, a USB DAC) keeps its own set, by its device id, as on
// the phone; otherwise the shared set under SHARED_SOUND is used everywhere.
@Serializable
data class SoundPrefs(
    val perOutput: Boolean = false,
    val profiles: Map<String, SoundSettings> = emptyMap(),
    // Curves for the ten bands the listener saved under a name.
    val presets: List<SavedCurve> = emptyList(),
)

@Serializable
data class SavedCurve(val name: String, val gains: List<Float>)

const val SHARED_SOUND = "all"

// Where the window was and how big, in density-independent pixels, and
// whether it filled the screen.
@Serializable
data class WindowSpot(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val maximized: Boolean = false,
)

// The server signed in to, without its password.
@Serializable
data class SavedServer(
    val address: String,
    val username: String,
    val authMode: AuthMode = AuthMode.Token,
    val serverType: String? = null,
    val serverVersion: String? = null,
    val openSubsonic: Boolean = false,
    // The OpenSubsonic extensions it listed, as "name:version".
    val extensions: List<String> = emptyList(),
)

@Serializable
data class Appearance(
    // A soft wash of the playing song's colours behind the window.
    val ambientGlow: Boolean = true,
    // How strong that wash is, from 0 to 1.
    val glowStrength: Float = 0.5f,
    // Less motion everywhere: the full player's background holds still and
    // lyrics move without springs or blooms.
    val calmMotion: Boolean = false,
    // The full player's moving background.
    val wash: WashPrefs = WashPrefs(),
)

// The full player's background, as the phone's settings have it: whether
// it moves, how fast it drifts (a share of the full pace), whether it
// follows the song's tempo, the frame rate it is held to, and how the
// cover is prepared (a cap on brightness and the saturation, both in
// percent, and the contrast as a factor).
@Serializable
data class WashPrefs(
    val moving: Boolean = true,
    val speed: Int = 25,
    val useBpm: Boolean = true,
    val fps: Int = 60,
    val brightnessCap: Int = 50,
    val saturation: Int = 180,
    val contrast: Float = 1.3f,
)

// Playback settings, read by the audio engine's player.
@Serializable
data class PlaybackPrefs(
    val volume: Float = 0.8f,
    // 0 to 12 seconds; 0 is off. Albums played in order stay gapless.
    val crossfadeSeconds: Int = 0,
    // The device picked in the output menu, or null to follow the system.
    val outputDevice: String? = null,
    // How fast music plays (0.5 to 2), whether the voice keeps its pitch at
    // other speeds, and a pitch shift in semitones on top, as on the phone.
    val speed: Float = 1f,
    val keepPitch: Boolean = true,
    val pitchSemitones: Int = 0,
)

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = true
    coerceInputValues = true
}

// Reads and writes the settings file. A write goes to a temporary file
// first and then replaces the old one in a single move, so a crash never
// leaves half a file. A file that cannot be read is set aside, never
// deleted, and the app starts from the defaults.
class SettingsStore(private val file: File) {
    private val lock = Any()
    private val _state = MutableStateFlow(read())

    val state: StateFlow<AppSettings> = _state

    val current: AppSettings get() = _state.value

    fun update(change: (AppSettings) -> AppSettings) {
        synchronized(lock) {
            val next = change(_state.value)
            if (next == _state.value) return
            _state.value = next
            write(next)
        }
    }

    private fun read(): AppSettings {
        if (!file.exists()) return AppSettings()
        return try {
            json.decodeFromString(AppSettings.serializer(), file.readText())
        } catch (e: Exception) {
            setAside()
            AppSettings()
        }
    }

    private fun setAside() {
        val aside = File(file.parentFile, file.nameWithoutExtension + ".unreadable-" + System.currentTimeMillis() + ".json")
        runCatching { Files.move(file.toPath(), aside.toPath()) }
    }

    private fun write(settings: AppSettings) {
        try {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(json.encodeToString(AppSettings.serializer(), settings))
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: IOException) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            // The settings stay in memory for this run; the next change tries again.
        }
    }

    companion object {
        const val FILE_NAME = "settings.json"
    }
}
