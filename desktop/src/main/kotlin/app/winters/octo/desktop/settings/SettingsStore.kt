package app.winters.octo.desktop.settings

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
)

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
)

// Playback settings, read by the audio engine's player.
@Serializable
data class PlaybackPrefs(
    val volume: Float = 0.8f,
    // 0 to 12 seconds; 0 is off. Albums played in order stay gapless.
    val crossfadeSeconds: Int = 0,
    // The device picked in the output menu, or null to follow the system.
    val outputDevice: String? = null,
    // Playback speed (0.5 to 2, pitch kept) and a pitch shift on top, in
    // semitones.
    val speed: Float = 1f,
    val pitchSemitones: Float = 0f,
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
