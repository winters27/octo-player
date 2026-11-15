package app.winters.octo.desktop.settings

import app.winters.octo.animated.AnimatedArtSetting
import app.winters.octo.offline.KeepChoices
import app.winters.octo.playback.BetweenSongs
import app.winters.octo.playback.DEFAULT_CROSSFADE_SECONDS
import app.winters.octo.playback.FadeCurve
import app.winters.octo.playback.StartAfter
import app.winters.octo.sound.SoundSettings
import app.winters.octo.desktop.system.SystemPrefs
import app.winters.octo.desktop.discord.DiscordPrefs
import app.winters.octo.desktop.hotkeys.HotkeyPrefs
import app.winters.octo.covers.PlaylistCoverStyle
import app.winters.octo.home.HOME_SECTIONS_KEY
import app.winters.octo.livelists.accountKey
import app.winters.octo.liveradio.LIVE_RADIUS_DEFAULT_INDEX
import app.winters.octo.liveradio.LivePlace
import app.winters.octo.liveradio.LiveSort
import app.winters.octo.server.serverName
import app.winters.octo.server.settleServers
import app.winters.octo.subsonic.AuthMode
import app.winters.octo.update.UpdatePrefs
import app.winters.octo.theme.AccentChoice
import app.winters.octo.theme.ThemeChoice
import app.winters.octo.theme.ThemeSettings
import app.winters.octo.theme.pickTheme
import app.winters.octo.theme.setMatch
import app.winters.octo.theme.themeSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import app.winters.octo.radio.RadioAdventure
import app.winters.octo.radio.RadioDiscovery
import app.winters.octo.radio.RadioTuning
import app.winters.octo.radio.RadioVariety
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
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
    // The server in use, as Octo kept it before there could be several. It
    // is always a copy of the one `activeServer` names, so an older Octo
    // opened later still finds it.
    val server: SavedServer? = null,
    // Every server kept here, in the listener's order, and the id of the one
    // in use (none while signed out).
    val servers: List<SavedServer> = emptyList(),
    val activeServer: String? = null,
    val appearance: Appearance = Appearance(),
    val playback: PlaybackPrefs = PlaybackPrefs(),
    // How the Songs page is ordered, as the shared sort lists save it.
    val songSort: String? = null,
    // How the Albums page is ordered, the same way.
    val albumSort: String? = null,
    // The panel open on the right, by its tab: "queue", "lyrics", "info",
    // or none.
    val sidePanel: String? = null,
    // The playlists songs were added to lately, newest first, by id, for
    // "Add to last playlist" and the top of the playlist chooser.
    val recentPlaylists: List<String> = emptyList(),
    // The frame: sidebar and panel sizes, and what the sidebar shows.
    val frame: FramePrefs = FramePrefs(),
    // The last searches that led somewhere, newest first.
    val recentSearches: List<String> = emptyList(),
    // Each song table's columns and widths, by the table's name, and how
    // tall rows are everywhere.
    val tables: Map<String, TablePrefs> = emptyMap(),
    val density: String = "regular",
    // The equalizer, loudness and the rest of the Sound page.
    val sound: SoundPrefs = SoundPrefs(),
    // Where lyrics come from, and their timing.
    val lyrics: LyricsPrefs = LyricsPrefs(),
    // The tray, notifications and the mini player.
    val system: SystemPrefs = SystemPrefs(),
    // Telling the server what was played, and carrying the queue between devices.
    val listening: ListeningPrefs = ListeningPrefs(),
    // Showing the song playing in the listener's Discord status (off unless asked).
    val discord: DiscordPrefs = DiscordPrefs(),
    // Keys that work from any app (off unless asked), and the ones chosen.
    val hotkeys: HotkeyPrefs = HotkeyPrefs(),
    // Certificates the listener chose to trust although the system does
    // not, as SHA-256 fingerprints by host. Each counts only for its host.
    // Not secret: a fingerprint only names a certificate.
    val trustedCertificates: Map<String, String> = emptyMap(),
    // Looking for new versions of Octo, and how they are put in.
    val updates: UpdatePrefs = UpdatePrefs(),
    // Only songs in the library: albums, search, artists and radio leave
    // out songs found online.
    val libraryOnly: Boolean = false,
    // This install's id, made once, which its own server tells it apart
    // by. Empty until first needed.
    val deviceId: String = "",
    // Songs kept on this computer to play without a connection.
    val offline: OfflinePrefs = OfflinePrefs(),
    // Live radio near you: where, how far and in what order, and the
    // stations kept here.
    val liveRadio: LiveRadioPrefs = LiveRadioPrefs(),
    // The newest server release the listener chose Later for, by server id.
    val serverUpdateLater: Map<String, String> = emptyMap(),
    // Where each server's list of songs given another version was read to,
    // by server id, as "<seq> <epoch>" (see StreamVersionFeed).
    val versionCursors: Map<String, String> = emptyMap(),
)

// The Live radio page as the listener left it: what they typed in Where
// (a postal code, or a city with its state after a comma), the country
// (an ISO code, empty for this computer's own), the place it was found to
// be, the radius chosen (by its place in the list of choices) and the
// order. `kept` is the stations kept on this computer, as KeptStations
// writes them, so a station saved by a newer Octo never breaks this file.
@Serializable
data class LiveRadioPrefs(
    val where: String = "",
    val country: String = "",
    val place: LivePlace? = null,
    val radius: Int = LIVE_RADIUS_DEFAULT_INDEX,
    val sort: LiveSort = LiveSort.Distance,
    val kept: String = "",
)

// Where kept songs go (empty for the usual folder), and what is kept from
// each server, by its id.
@Serializable
data class OfflinePrefs(
    val folder: String = "",
    // How kept songs are fetched: the file as it is, or a smaller copy at a
    // lower bitrate (a StreamQuality name), as the phone's download quality.
    val downloadQuality: String = "Original",
    val servers: Map<String, KeepChoices> = emptyMap(),
    // What a smaller copy is made as (a DownloadFormat name). A file from
    // before this was kept reads as Opus, the default for everyone.
    val downloadFormat: String = "Opus",
)

// A song table as the listener set it up: the columns shown, in order (by
// their names), and the widths of any they dragged, in dp. Empty means the
// table's own choice.
@Serializable
data class TablePrefs(
    val columns: List<String> = emptyList(),
    val widths: Map<String, Float> = emptyMap(),
)

// The frame around the page, as the listener left it.
@Serializable
data class FramePrefs(
    // The sidebar's width in dp, and whether it is folded to its icons.
    val sidebarWidth: Float = 240f,
    val sidebarRail: Boolean = false,
    // The side panel's width in dp.
    val panelWidth: Float = 328f,
    // Sidebar groups folded shut, by name ("library", "yours", "playlists").
    val foldedGroups: Set<String> = emptySet(),
    // Playlists kept at the top of the sidebar's list, by id, in order.
    val pinnedPlaylists: List<String> = emptyList(),
    // Playlist folders open in the sidebar, by id; none open until opened.
    val openFolders: Set<String> = emptySet(),
    // Whether the sidebar's Library upkeep group is open; closed until opened.
    val upkeepOpen: Boolean = false,
    // Whether the time on the right counts down (the time left) or shows
    // the song's length.
    val showTimeLeft: Boolean = true,
)

// What the server hears about listening here. Both are on unless switched
// off, as on the phone.
@Serializable
data class ListeningPrefs(
    // Plays count on the server, for play counts and recently played on
    // every app that uses it.
    val reportPlays: Boolean = true,
    // The queue is kept on the server, so another device can pick it up.
    val syncQueue: Boolean = true,
)

// Lyrics settings, by song id (the server's) and by output device id.
@Serializable
data class LyricsPrefs(
    // Whether the online lyrics library (LRCLIB) may be asked, as on the phone.
    val online: Boolean = true,
    // Fast singing blooms too, as on the phone (LyricsLook.bloomFast).
    val bloomFast: Boolean = true,
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

// The server signed in to, without its password. The values of its extra
// headers are secrets too, so only their names are kept here.
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
    // An address used instead while it answers, on the home network.
    val home: String? = null,
    val headerNames: List<String> = emptyList(),
    // Whether the password is kept in the system's store, or only while
    // the app is open.
    val rememberSignIn: Boolean = true,
    // Which server this is, for its password and its own folder of plays,
    // queue and live lists: the account's key from when it was first kept,
    // which is also the folder older versions of Octo made for it. It stays
    // when the address is edited.
    val id: String = "",
    // The name the listener gave it; none shows its host.
    val label: String = "",
    // Signed out: still in the list, its password forgotten.
    val signedOut: Boolean = false,
    // The person's own client certificate file, read each time Octo
    // connects; its password is in the system's store, beside its path.
    val certificate: CertFile? = null,
)

// A client certificate file where the person keeps it: its full path, and
// the name the form shows for it.
@Serializable
data class CertFile(val path: String, val name: String)

// The server's id, or the one it would get, for a server kept by an older
// version of Octo.
val SavedServer.key: String get() = id.ifEmpty { accountKey(username, address) }

// What the server is called in the app: its label, else its host.
val SavedServer.name: String get() = serverName(label, address)

private fun SavedServer.withId(): SavedServer = if (id.isEmpty()) copy(id = key) else this

// The servers as read from the file, in one shape: each with its id, and the
// one in use named by `activeServer`. `server` has the last word, since an
// older Octo writes only that: a file from before the list becomes a list of
// one, a server an older Octo signed in to since is added (or brought up to
// date) and made the one in use, and one it signed out of leaves none in use.
internal fun AppSettings.settledServers(): AppSettings {
    val (list, kept) = settleServers(
        servers.map { it.withId() },
        server?.withId(),
        id = { it.id },
        same = { a, b -> a.username == b.username && a.address == b.address },
        adopt = { legacy, same -> if (same == null) legacy else legacy.copy(id = same.id, label = legacy.label.ifEmpty { same.label }, signedOut = false) },
    )
    return copy(servers = list, activeServer = kept?.id, server = kept)
}

// The settings as they are written: `server` a copy of the one in use, and
// the playback fields an older Octo reads in place of the Between songs
// choice set from it.
internal fun AppSettings.mirrored(): AppSettings = mirroredServer().mirroredPlayback()

private fun AppSettings.mirroredServer(): AppSettings {
    val active = activeServer?.let { id -> servers.firstOrNull { it.id == id } }
    return if (active == server && (active != null || activeServer == null)) this else copy(server = active, activeServer = active?.id)
}

internal fun AppSettings.mirroredPlayback(): AppSettings {
    val written = playback.mirrored()
    return if (written === playback) this else copy(playback = written)
}

// The settings as a file is read into the app: servers and themes read the
// way every app reads them, and the playback mirrors set from the choice.
internal fun AppSettings.settled(): AppSettings = settledServers().settledThemes().mirroredPlayback()

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
    // What the window's colours are, when on: the glow across the top, or
    // the full player's wash behind everything.
    val ambience: AmbienceStyle = AmbienceStyle.Glow,
    // How the immersive colours move behind the pages.
    val ambienceMotion: AmbienceMotion = AmbienceMotion.Gentle,
    // How big the words are, as a percentage, or 0 for the system's own
    // text size.
    val textSize: Int = 0,
    // Playlists' pictures: designed for each one, or their album mosaics.
    val playlistCovers: PlaylistCoverStyle = PlaylistCoverStyle.Designed,
    // Home's shelves in order, a hidden one led by "-", as the phone keeps
    // them; null for the usual order, all shown.
    @SerialName(HOME_SECTIONS_KEY) val homeSections: String? = null,
    // The accent the app is drawn in, or the playing cover's.
    val accent: AccentChoice = AccentChoice.Octo,
    // The theme the app is drawn in, the light and the dark theme Match
    // chooses between, and whether Match follows the system's own mode.
    val theme: ThemeChoice = ThemeChoice.Octo,
    val themeLight: ThemeChoice = ThemeChoice.Daylight,
    val themeDark: ThemeChoice = ThemeChoice.Octo,
    val themeMatch: Boolean = false,
    // Animated album covers on Octo servers that offer them: "square" in
    // place of the cover, "full_screen" behind the player, or "off"
    // (AnimatedArtSetting's keys; anything else reads as the default).
    @SerialName(AnimatedArtSetting.PREF_KEY) val animatedArt: String = AnimatedArtSetting.Default.key,
)

// The Animated art choice.
val Appearance.animatedArtSetting: AnimatedArtSetting get() = AnimatedArtSetting.fromKey(animatedArt)

// The four theme fields as the shared theme rules take them.
val Appearance.themes: ThemeSettings get() = ThemeSettings(theme, themeLight, themeDark, themeMatch)

private fun Appearance.withThemes(next: ThemeSettings): Appearance =
    copy(theme = next.theme, themeLight = next.light, themeDark = next.dark, themeMatch = next.match)

// The settings with the theme fields read the way every app reads them: a
// theme of the wrong kind in a Day or Night slot is that slot's default.
internal fun AppSettings.settledThemes(): AppSettings {
    val read = themeSettings(appearance.theme.name, appearance.themeLight.name, appearance.themeDark.name, appearance.themeMatch)
    return if (read == appearance.themes) this else copy(appearance = appearance.withThemes(read))
}

// The window's colours: the playing cover blurred into a glow across the
// top, or the full player's moving wash of it behind the whole window.
@Serializable
enum class AmbienceStyle { Glow, Immersive }

// How the immersive colours move behind the pages: not at all, slowly, or
// at the full player's own pace.
@Serializable
enum class AmbienceMotion { Still, Gentle, Full }

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
    // What plays between two songs: Gapless, Crossfade or Automix. Albums
    // played in order stay gapless whatever is chosen.
    val betweenSongs: BetweenSongs = BetweenSongs.Automix,
    // Crossfade's length, 2 to 16 seconds.
    val crossfadeLength: Int = DEFAULT_CROSSFADE_SECONDS,
    // What an older Octo reads in place of the choice: its longest blend (0
    // was off) and its smart transitions. Only written, from the choice, by
    // mirrored(); nothing here reads them.
    @SerialName("crossfadeSeconds") val legacyCrossfadeSeconds: Int = DEFAULT_CROSSFADE_SECONDS,
    @SerialName("smartTransitions") val legacySmartTransitions: Boolean = true,
    // Automix's own two: filter sweeps over both songs, and the next song's
    // speed nudged to the beat.
    val filterSweeps: Boolean = true,
    val matchTempo: Boolean = true,
    // The shape of Crossfade's fade; Automix picks its own.
    val crossfadeCurve: FadeCurve = FadeCurve.EqualPower,
    // The device picked in the output menu, or null to follow the system.
    val outputDevice: String? = null,
    // How fast music plays (0.5 to 2), whether the voice keeps its pitch at
    // other speeds, and a pitch shift in semitones on top, as on the phone.
    val speed: Float = 1f,
    val keepPitch: Boolean = true,
    val pitchSemitones: Int = 0,
    // When the queue runs out, songs like the last one keep playing. On by
    // default, as on the phone.
    val autoplay: Boolean = true,
    // How songs stream when this app picks the quality: the file as it is,
    // or Opus at a lower bitrate (a StreamQuality name); and how much of a
    // stream is ready before it starts.
    val streamQuality: String = "Original",
    val startAfter: StartAfter = StartAfter.Short,
    // How Octo's radio is tuned (see RadioTuning).
    val radioDiscovery: RadioDiscovery = RadioDiscovery.Balanced,
    val radioAdventure: RadioAdventure = RadioAdventure.Balanced,
    val radioVariety: RadioVariety = RadioVariety.Normal,
    val radioFavorites: Boolean = false,
) {
    // How radio and Autoplay pick their songs.
    val radioTuning: RadioTuning get() = RadioTuning(radioDiscovery, radioAdventure, radioVariety, radioFavorites)
}

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = true
    coerceInputValues = true
}

// The key of how many times the settings file has been written, which every
// write raises; a pending file carries the count of the file it began from.
internal const val SAVE_COUNT_KEY = "saveCount"

// The key a pending file names the main file it began from under: the hash
// of its bytes (settingsHash), or NO_MAIN_FILE.
internal const val BASE_HASH_KEY = "baseHash"

// The settings as the file holds them: its version, its saveCount, then
// every setting. A pending file names the main file it began from too.
internal fun encodeSettings(settings: AppSettings, saveCount: Long, baseHash: String? = null): ByteArray {
    val fields = json.encodeToJsonElement(AppSettings.serializer(), settings).jsonObject
    val file = buildJsonObject {
        put(SETTINGS_VERSION_KEY, SETTINGS_VERSION)
        put(SAVE_COUNT_KEY, saveCount)
        if (baseHash != null) put(BASE_HASH_KEY, baseHash)
        fields.forEach { (key, value) -> put(key, value) }
    }
    return json.encodeToString(JsonObject.serializer(), file).toByteArray(Charsets.UTF_8)
}

// A file's settings as the app uses them, the saveCount it carries (0 for
// a file without one), what reading an older file's crossfade settings came
// to (null for a file that has the choice), and, for a pending file, the
// main file it began from (null when it names none). Throws
// SerializationException or IllegalArgumentException for words that are
// not settings.
internal class ReadSettings(val settings: AppSettings, val saveCount: Long, val migrated: MigratedFile?, val baseHash: String? = null) {
    // Whether a pending file began from the main file with this count and
    // these bytes.
    fun beganFrom(count: Long, hash: String): Boolean = saveCount == count && baseHash == hash
}

// `betweenSongs` gives the Between songs file, asked only for a file an
// older Octo wrote; it may throw IOException while that file is held.
internal fun decodeSettings(text: String, betweenSongs: () -> BetweenSongsFile? = { null }): ReadSettings {
    val file = json.parseToJsonElement(text) as? JsonObject ?: throw SerializationException("The settings are not an object")
    val count = (file[SAVE_COUNT_KEY] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: 0L
    val base = (file[BASE_HASH_KEY] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val migrated = migratedBetweenSongs(file, betweenSongs)
    return ReadSettings(json.decodeFromJsonElement(AppSettings.serializer(), migrated?.json ?: file).settled(), count, migrated, base)
}

internal fun encodeBetweenSongsFile(choice: BetweenSongsFile): ByteArray =
    json.encodeToString(BetweenSongsFile.serializer(), choice).toByteArray(Charsets.UTF_8)

// The Between songs file's settings, or null for bytes that are not them.
internal fun decodeBetweenSongsFile(bytes: ByteArray): BetweenSongsFile? = try {
    json.decodeFromString(BetweenSongsFile.serializer(), bytes.toString(Charsets.UTF_8))
} catch (e: IllegalArgumentException) {
    null
}

// Reads and writes the settings file. A write goes to a temporary file
// first, is flushed to the disk, and then replaces the old one in a single
// move, so neither a crash nor a power cut leaves half a file. A file that
// is not valid settings is set aside, never deleted, and the app starts
// from the defaults, saying so (`setAside`). A file from before the Between
// songs choice is read as the choice its crossfade settings come to, and
// written at once when they chose one (settings still at the old defaults
// are left as they are). The file is opened through `open`
// (SettingsOpening says how a held file is waited for).
//
// A write that fails means another program holds the file (Windows lets a
// reader in while refusing a replace, so a read proves nothing). On every
// failed write the whole state goes to the pending file at once, with the
// saveCount and the bytes' hash of the main file it began from, so a crash
// or a power cut mid-hold loses nothing; the next start uses it, and the
// first write that succeeds deletes it. A pending file from before that
// could not be moved aside is never deleted or written over: it is moved
// aside after each write that works, and until then a held write keeps
// nothing beside the file. `hold` and `locked` say how it stands.
//
// Every write that works also writes the Between songs file beside it when
// what it holds changed (see BetweenSongsFile), the same safe way.
//
// With `writeDelayMs`, changes are kept in memory at once and written a
// moment later, together, off the caller's thread: a slider dragged for a
// second is one write, not sixty. Whatever is waiting is written when the
// app ends. A store with no file (`readOnly`) changes memory only.
class SettingsStore internal constructor(
    private val file: File?,
    private val writeDelayMs: Long,
    private val writeRetryMs: Long,
    private val disk: SettingsDisk,
    private val clock: SettingsClock,
    start: SettingsStart,
) {
    // A store on `file`, read at once, waiting up to START_WAIT_MS while
    // another program holds it, for the tests and tools that make their own
    // file. Throws IllegalStateException when it stays held; the app opens
    // through `open`, which hands back the wait.
    constructor(file: File, writeDelayMs: Long = 0) : this(
        file,
        writeDelayMs,
        WRITE_RETRY_MS,
        RealSettingsDisk,
        SettingsClock.Real,
        SettingsOpening(file, writeDelayMs, SettingsClock.Real, START_WAIT_MS, START_RETRY_MS, WRITE_RETRY_MS, RealSettingsDisk).waitForStart()
            ?: throw IllegalStateException("Another program holds $file"),
    )

    private val lock = Any()
    private val pending: File? = file?.let(::pendingFileFor)
    private val betweenSongsFile: File? = file?.let(::betweenSongsFileFor)
    private val _state = MutableStateFlow(start.settings)

    val state: StateFlow<AppSettings> = _state

    val current: AppSettings get() = _state.value

    // A session on the defaults with no file behind it: nothing is written,
    // kept beside the file or read back.
    val readOnly: Boolean get() = file == null

    private val _hold = MutableStateFlow(SettingsHold.Free)
    val hold: StateFlow<SettingsHold> = _hold

    // Whether the last write failed: the file is held.
    private val _locked = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = _locked

    // The name an unreadable settings file was set aside under, when the
    // store started fresh because of it, until the listener has seen it.
    private val _setAside = MutableStateFlow(start.setAside)
    val setAside: StateFlow<String?> = _setAside

    // The saveCount of the file as this store last read or wrote it, and
    // the hash of its bytes: the main file a pending file begins from.
    private var saveCount = start.saveCount
    private var mainHash = start.mainHash

    // A pending file to delete once the main file is written.
    private var pendingLeft = start.pendingLeft

    // Why a pending file from before is still to be set aside, while it
    // could not be moved.
    private var stuckPending = start.stuckPending

    // The Between songs file as this store last wrote it, and whether a
    // write of it failed since the file was last written.
    private var betweenSongsWritten: BetweenSongsFile? = null
    private var betweenSongsOwed = false

    // The settings changed since the file was last written, if any.
    private var unsaved: AppSettings? = null
    private val writer = if (file != null && writeDelayMs > 0) {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "octo-settings").apply { isDaemon = true } }
    } else {
        null
    }

    // The next write the writer has, and when it runs (System.nanoTime).
    private var nextFlush: ScheduledFuture<*>? = null
    private var flushDue = Long.MAX_VALUE

    init {
        if (writer != null) Runtime.getRuntime().addShutdownHook(Thread({ flush() }, "octo-settings-exit"))
        // Settings from the pending file, or an older file that chose, are
        // written as the file at once.
        if (start.write) synchronized(lock) { keep(start.settings, 0) }
    }

    fun update(change: (AppSettings) -> AppSettings) {
        synchronized(lock) {
            val before = _state.value
            val changed = change(before)
            // A change to `server` alone is read the way an older Octo's is.
            val alone = changed.server != before.server && changed.servers == before.servers && changed.activeServer == before.activeServer
            val next = if (alone) changed.settledServers().mirroredPlayback() else changed.mirrored()
            if (next == before) return
            _state.value = next
            if (file != null) keep(next, writeDelayMs)
        }
    }

    // Writes `settings` now, or in `delayMs` with a writer.
    private fun keep(settings: AppSettings, delayMs: Long) {
        unsaved = settings
        if (writer == null) {
            if (save(settings)) unsaved = null
            return
        }
        flushIn(delayMs)
    }

    // Has the writer write what is waiting in `delayMs`, unless it already
    // will sooner.
    private fun flushIn(delayMs: Long) {
        val writer = writer ?: return
        val due = System.nanoTime() + delayMs * 1_000_000
        if (nextFlush?.isDone == false && flushDue <= due) return
        nextFlush?.cancel(false)
        flushDue = due
        nextFlush = runCatching { writer.schedule(::flush, delayMs, TimeUnit.MILLISECONDS) }.getOrNull()
    }

    // Tries to write the file again now, as the banner's Try again asks.
    fun tryAgain() {
        if (file == null) return
        if (writer == null) flush() else synchronized(lock) { flushIn(0) }
    }

    // The listener has seen that an unreadable file was set aside.
    fun dismissSetAside() {
        _setAside.value = null
    }

    // The one way the Between songs settings change: the Playback page's
    // card and anything else that sets them. The mirrors an older Octo reads
    // go with the change. A file from before the choice was read as the
    // choice it showed, so a first change keeps everything else as shown.
    fun editBetweenSongs(change: BetweenSongsChange) = update { it.copy(playback = it.playback.edited(change)) }

    // Makes `choice` the theme and fills the Day or Night slot of its kind, in
    // one change.
    fun pickTheme(choice: ThemeChoice) {
        update { it.copy(appearance = it.appearance.withThemes(pickTheme(it.appearance.themes, choice))) }
    }

    // Turns Match on or off. Turning it off keeps the theme showing for
    // `deviceDark`.
    fun setThemeMatch(on: Boolean, deviceDark: Boolean) {
        update { it.copy(appearance = it.appearance.withThemes(setMatch(it.appearance.themes, on, deviceDark))) }
    }

    // Writes what is waiting now; one that fails is tried again in
    // `writeRetryMs`. With nothing waiting, a pending file still left is
    // deleted, and one from before still to be set aside is moved (the
    // app's exit runs this too).
    fun flush() {
        if (file == null) return
        synchronized(lock) {
            nextFlush = null
            flushDue = Long.MAX_VALUE
            val settings = unsaved
            if (settings == null) {
                if (pendingLeft) dropPending()
                moveStuckAside()
                // The file holds this state: a Between songs file that
                // failed to go with it goes now.
                if (betweenSongsOwed) keepBetweenSongs(_state.value)
                return
            }
            if (save(settings)) unsaved = null else flushIn(writeRetryMs)
        }
    }

    // Writes what is waiting at once, before the system's password store
    // follows a change. True when the settings file, or the pending file
    // beside it while the file is held, holds what is in memory now, so the
    // next start reads it. False when neither does: both refuse, or a
    // pending file from before that could not be moved aside still sits
    // there, which no write goes over; and always for a store with no file.
    // A write that fails is tried again as flush's are, so the files may
    // still change after either answer.
    fun flushNow(): Boolean {
        if (file == null) return false
        return synchronized(lock) {
            // Only a write of the file itself that landed leaves nothing
            // waiting.
            val settings = unsaved ?: return true
            if (save(settings)) {
                unsaved = null
                return true
            }
            flushIn(writeRetryMs)
            _hold.value == SettingsHold.Kept
        }
    }

    // True once the file holds these settings. While it is held the whole
    // state goes to the pending file instead, encoded as the file is and
    // with the main file it began from.
    private fun save(settings: AppSettings): Boolean {
        val main = file ?: return false
        val bytes = encodeSettings(settings, saveCount + 1)
        try {
            disk.replace(main, bytes)
        } catch (e: IOException) {
            setHold(if (keepPending(settings)) SettingsHold.Kept else SettingsHold.Unkept)
            return false
        }
        saveCount += 1
        mainHash = settingsHash(bytes)
        if (pendingLeft) dropPending()
        moveStuckAside()
        keepBetweenSongs(settings)
        setHold(SettingsHold.Free)
        return true
    }

    // The whole state to the pending file; never over a pending file from
    // before that is still to be set aside. True once written.
    private fun keepPending(settings: AppSettings): Boolean {
        val kept = pending ?: return false
        if (!moveStuckAside()) return false
        return try {
            disk.replace(kept, encodeSettings(settings, saveCount, mainHash))
            pendingLeft = true
            true
        } catch (e: IOException) {
            false
        }
    }

    // Deletes the pending file; one that will not go is tried again after
    // the next write and at the app's exit.
    private fun dropPending() {
        val kept = pending ?: return
        if (disk.delete(kept)) pendingLeft = false
    }

    // Moves a pending file from before aside, never deleting it; true once
    // none is left to move.
    private fun moveStuckAside(): Boolean {
        val why = stuckPending ?: return true
        val stuck = pending ?: return true
        if (!disk.exists(stuck) || moveAside(disk, clock, stuck, why) != null) stuckPending = null
        return stuckPending == null
    }

    // The Between songs file, written when what it holds changed; a write
    // that fails is tried again after the next, or at the next flush with
    // nothing waiting (the app's exit among them).
    private fun keepBetweenSongs(settings: AppSettings) {
        val at = betweenSongsFile ?: return
        val now = BetweenSongsFile.of(settings.playback)
        if (now == betweenSongsWritten) return
        try {
            disk.replace(at, encodeBetweenSongsFile(now))
            betweenSongsWritten = now
            betweenSongsOwed = false
        } catch (e: IOException) {
            betweenSongsWritten = null
            betweenSongsOwed = true
        }
    }

    private fun setHold(hold: SettingsHold) {
        _hold.value = hold
        _locked.value = hold != SettingsHold.Free
    }

    companion object {
        const val FILE_NAME = "settings.json"

        // How long the app waits to write changes, so a drag is one write.
        const val APP_WRITE_DELAY_MS = 400L

        // How long a start waits for a held settings file, and how often it
        // looks again in that time.
        const val START_WAIT_MS = 3_000L
        const val START_RETRY_MS = 100L

        // How soon a failed write is tried again.
        const val WRITE_RETRY_MS = 2_000L

        // Opens the settings file, waiting `waitMs` while another program
        // holds it (looking again every `retryMs`); Held when it still does.
        fun open(
            file: File,
            writeDelayMs: Long = 0,
            clock: SettingsClock = SettingsClock.Real,
            waitMs: Long = START_WAIT_MS,
            retryMs: Long = START_RETRY_MS,
            writeRetryMs: Long = WRITE_RETRY_MS,
        ): SettingsOpen = openOn(RealSettingsDisk, file, writeDelayMs, clock, waitMs, retryMs, writeRetryMs)

        internal fun openOn(
            disk: SettingsDisk,
            file: File,
            writeDelayMs: Long = 0,
            clock: SettingsClock = SettingsClock.Real,
            waitMs: Long = START_WAIT_MS,
            retryMs: Long = START_RETRY_MS,
            writeRetryMs: Long = WRITE_RETRY_MS,
        ): SettingsOpen = SettingsOpening(file, writeDelayMs, clock, waitMs, retryMs, writeRetryMs, disk).open()

        // A session on the defaults that saves nothing, for Open without my
        // settings: its changes stay in memory, with no writer, no exit
        // write and no pending file.
        fun readOnly(): SettingsStore =
            SettingsStore(null, 0, 0, RealSettingsDisk, SettingsClock.Real, SettingsStart(AppSettings().settled(), 0, write = false, pendingLeft = false, setAside = null))
    }
}
