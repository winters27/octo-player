package app.winters.octo.desktop.settings

import app.winters.octo.sound.SoundSettings
import app.winters.octo.desktop.system.SystemPrefs
import app.winters.octo.subsonic.AuthMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
    // The tray, notifications and the mini player.
    val system: SystemPrefs = SystemPrefs(),
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
// first, is flushed to the disk, and then replaces the old one in a single
// move, so neither a crash nor a power cut leaves half a file. A file that
// is not valid settings is set aside, never deleted, and the app starts
// from the defaults.
//
// With `writeDelayMs`, changes are kept in memory at once and written a
// moment later, together, off the caller's thread: a slider dragged for a
// second is one write, not sixty. Whatever is waiting is written when the
// app ends.
class SettingsStore(private val file: File, private val writeDelayMs: Long = 0) {
    private val lock = Any()
    private val _state = MutableStateFlow(read())

    val state: StateFlow<AppSettings> = _state

    val current: AppSettings get() = _state.value

    // The settings changed since the file was last written, if any.
    private var unsaved: AppSettings? = null
    private val writer = if (writeDelayMs > 0) {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "octo-settings").apply { isDaemon = true } }
    } else {
        null
    }

    init {
        if (writer != null) Runtime.getRuntime().addShutdownHook(Thread({ flush() }, "octo-settings-exit"))
    }

    fun update(change: (AppSettings) -> AppSettings) {
        synchronized(lock) {
            val next = change(_state.value)
            if (next == _state.value) return
            _state.value = next
            if (writer == null) {
                write(next)
                return
            }
            val waiting = unsaved != null
            unsaved = next
            if (!waiting) writer.schedule(::flush, writeDelayMs, TimeUnit.MILLISECONDS)
        }
    }

    // Writes what is waiting now. A write that fails is tried again later.
    fun flush() {
        synchronized(lock) {
            val settings = unsaved ?: return
            if (write(settings)) {
                unsaved = null
            } else {
                runCatching { writer?.schedule(::flush, RETRY_MS, TimeUnit.MILLISECONDS) }
            }
        }
    }

    private fun read(): AppSettings {
        if (!file.exists()) return AppSettings()
        // Another program (a sync tool, a virus scanner) can hold the file
        // for a moment; that is not a broken file.
        var text: String? = null
        for (attempt in 1..READ_ATTEMPTS) {
            text = try {
                file.readText()
            } catch (e: IOException) {
                if (attempt < READ_ATTEMPTS) Thread.sleep(RETRY_READ_MS)
                null
            }
            if (text != null) break
        }
        if (text == null) return AppSettings()
        return try {
            json.decodeFromString(AppSettings.serializer(), text)
        } catch (e: SerializationException) {
            setAside()
            AppSettings()
        } catch (e: IllegalArgumentException) {
            setAside()
            AppSettings()
        }
    }

    private fun setAside() {
        val aside = File(file.parentFile, file.nameWithoutExtension + ".unreadable-" + System.currentTimeMillis() + ".json")
        runCatching { Files.move(file.toPath(), aside.toPath()) }
    }

    // True once the file holds these settings.
    private fun write(settings: AppSettings): Boolean {
        return try {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            FileOutputStream(temp).use { out ->
                out.write(json.encodeToString(AppSettings.serializer(), settings).toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: IOException) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            true
        } catch (e: IOException) {
            // The settings stay in memory; the next write tries again.
            false
        }
    }

    companion object {
        const val FILE_NAME = "settings.json"

        // How long the app waits to write changes, so a drag is one write.
        const val APP_WRITE_DELAY_MS = 400L
        private const val RETRY_MS = 2_000L
        private const val READ_ATTEMPTS = 3
        private const val RETRY_READ_MS = 150L
    }
}
