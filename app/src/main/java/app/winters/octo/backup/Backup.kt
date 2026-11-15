package app.winters.octo.backup

import app.winters.octo.catalog.PinKind
import app.winters.octo.catalog.SongIdentity
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.matchKey
import app.winters.octo.discovery.TitleIndex
import app.winters.octo.discovery.sameSong
import app.winters.octo.listening.SendPlays
import app.winters.octo.liveradio.LiveStation
import app.winters.octo.lyrics.LyricsLook
import app.winters.octo.offline.CacheSize
import app.winters.octo.offline.OfflinePrefs
import app.winters.octo.playback.BETWEEN_SONGS_SETTINGS_VERSION
import app.winters.octo.playback.BetweenSongs
import app.winters.octo.playback.BetweenSongsWrite
import app.winters.octo.playback.DownloadFormat
import app.winters.octo.playback.RestoredBetweenSongs
import app.winters.octo.playback.StoredBetweenSongs
import app.winters.octo.playback.StreamQuality
import app.winters.octo.playback.restoredBetweenSongs
import app.winters.octo.player.PlayerPrefs
import app.winters.octo.player.StreamPrefs
import app.winters.octo.playlists.BYTE_ORDER_MARK
import app.winters.octo.sound.SoundSettings
import app.winters.octo.theme.ThemeSettings
import app.winters.octo.theme.themeSettings
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.net.URI
import kotlin.math.abs

// The backup format. Raised when a change means an older app would read a
// newer file wrongly; an older file is always read.
const val BACKUP_VERSION = 1

// Marks a file as an Octo settings backup, so any other JSON is refused.
const val BACKUP_KIND = "octo-settings"

// A song as a backup names it, so it can be found again after a reinstall
// or in another library: its relink key (album artist, album, disc, track,
// title and length), and its title, artist, album and length on their own.
@Serializable
data class SongKey(
    val relinkKey: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0,
)

@Serializable
data class RatedSong(val song: SongKey, val rating: Int)

@Serializable
data class PlaylistBackup(val name: String, val songs: List<SongKey> = emptyList())

@Serializable
data class PresetBackup(val name: String, val gains: List<Float>)

// The sound settings: whether each output keeps its own, and every saved
// set by output ("all" is the shared one).
@Serializable
data class SoundBackup(val perOutput: Boolean = false, val profiles: Map<String, SoundSettings> = emptyMap())

// Which folders are left out of the library, and what goes to the server.
@Serializable
data class LibraryBackup(
    val excludedFolders: List<String> = emptyList(),
    val syncQueue: Boolean = true,
    val newPlaylistsOnServer: Boolean = false,
)

// A favourite or pinned album or artist as a backup names it: its relink
// key (the library's search key for it) and its names, so it can be found
// again after a reinstall or in another library. An artist has no artist.
@Serializable
data class HeldKey(val relinkKey: String = "", val name: String = "", val artist: String = "")

// Something pinned to Home, in row order. A playlist is named by its name.
@Serializable
data class PinBackup(val kind: String, val item: HeldKey)

// A song's lyrics moved earlier or later by hand.
@Serializable
data class LyricsOffsetBackup(val song: SongKey, val offsetMs: Long)

// Whether the screen stays on while lyrics show, each song's timing, how
// synced lyrics look (missing from older backups), and each sound output's
// timing by output key, like "speaker" or "bluetooth:<name>" (missing
// from older backups too).
@Serializable
data class LyricsBackup(
    val keepScreenOn: Boolean = true,
    val offsets: List<LyricsOffsetBackup> = emptyList(),
    val look: LyricsLook? = null,
    val outputOffsets: Map<String, Long> = emptyMap(),
)

private val offlineDefaults = OfflinePrefs()

// What is kept on the phone for playing without a connection. Playlists
// kept downloaded are named by their name, since restored playlists get
// new ids.
@Serializable
data class OfflineBackup(
    val cacheSize: CacheSize = offlineDefaults.cacheSize,
    val prefetchWifi: Int = offlineDefaults.prefetchWifi,
    val prefetchMobile: Int = offlineDefaults.prefetchMobile,
    val downloadQuality: StreamQuality = offlineDefaults.downloadQuality,
    val downloadFormat: DownloadFormat = offlineDefaults.downloadFormat,
    val wifiOnly: Boolean = offlineDefaults.wifiOnly,
    val streamOnWifi: Boolean = offlineDefaults.streamOnWifi,
    val keepLiked: Boolean = offlineDefaults.keepLiked,
    val keptPlaylists: List<String> = emptyList(),
) {
    // The settings as the app keeps them, with the kept playlists by id.
    fun toPrefs(keptPlaylistIds: Set<String> = emptySet()) = OfflinePrefs(
        cacheSize = cacheSize,
        prefetchWifi = prefetchWifi,
        prefetchMobile = prefetchMobile,
        downloadQuality = downloadQuality,
        downloadFormat = downloadFormat,
        wifiOnly = wifiOnly,
        streamOnWifi = streamOnWifi,
        keepLiked = keepLiked,
        keptPlaylists = keptPlaylistIds,
    )
}

// The offline settings as a backup keeps them, each kept playlist by its
// name. A kept playlist that is no longer here is left out.
fun OfflinePrefs.toBackup(playlistNames: Map<String, String>) = OfflineBackup(
    cacheSize = cacheSize,
    prefetchWifi = prefetchWifi,
    prefetchMobile = prefetchMobile,
    downloadQuality = downloadQuality,
    downloadFormat = downloadFormat,
    wifiOnly = wifiOnly,
    streamOnWifi = streamOnWifi,
    keepLiked = keepLiked,
    keptPlaylists = keptPlaylists.mapNotNull(playlistNames::get).sorted(),
)

// The ListenBrainz choices. Who was connected, and how, is never in a
// backup, so connecting again is needed.
@Serializable
data class ListenBrainzBackup(
    val enabled: Boolean = false,
    val sendPlays: SendPlays = SendPlays.All,
    val nowPlaying: Boolean = true,
)

// The server signed in to, for reference: its address and user name only.
// Passwords, keys, header values and certificates are never in a backup,
// so signing in again is needed.
@Serializable
data class ServerBackup(val address: String, val username: String)

// The player's settings with the theme beside them: one JSON object, the
// player's fields as they always were, then `theme`, `themeLight`,
// `themeDark` and `themeMatch`. A backup from before themes has none of the
// four and reads as Octo's.
//
// The Between songs choice: `settingsVersion` says the backup has it,
// `betweenSongs` is there only when the device stored a choice, and
// `crossfade` and `smartTransitions` say the same to older apps. Read
// back, `prefs` holds what a restore leaves the device showing: a backup
// from before the choice goes through the untouched rule on its old
// fields, and a newer one without a choice is untouched.
@Serializable(with = PlayerBackupSerializer::class)
data class PlayerBackup(
    val prefs: PlayerPrefs = PlayerPrefs(),
    val theme: String? = null,
    val themeLight: String? = null,
    val themeDark: String? = null,
    val themeMatch: Boolean? = null,
) {
    // The theme settings this holds, a name this app does not know read as
    // that field's default.
    fun themes(): ThemeSettings = themeSettings(theme, themeLight, themeDark, themeMatch ?: false)
}

object PlayerBackupSerializer : KSerializer<PlayerBackup> {
    private const val SETTINGS_VERSION = "settingsVersion"
    private const val BETWEEN_SONGS = "betweenSongs"
    private const val CROSSFADE = "crossfade"
    private const val SMART_TRANSITIONS = "smartTransitions"

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("PlayerBackup")

    override fun serialize(encoder: Encoder, value: PlayerBackup) {
        val json = encoder as JsonEncoder
        val fields = json.json.encodeToJsonElement(PlayerPrefs.serializer(), value.prefs).jsonObject.toMutableMap()
        val mirrors = BetweenSongsWrite(value.prefs.betweenSongs)
        fields[CROSSFADE] = JsonPrimitive(mirrors.crossfade)
        fields[SMART_TRANSITIONS] = JsonPrimitive(mirrors.smartTransitions)
        fields[SETTINGS_VERSION] = JsonPrimitive(BETWEEN_SONGS_SETTINGS_VERSION)
        if (value.prefs.betweenSongsStored) fields[BETWEEN_SONGS] = JsonPrimitive(value.prefs.betweenSongs.stored)
        value.theme?.let { fields["theme"] = JsonPrimitive(it) }
        value.themeLight?.let { fields["themeLight"] = JsonPrimitive(it) }
        value.themeDark?.let { fields["themeDark"] = JsonPrimitive(it) }
        value.themeMatch?.let { fields["themeMatch"] = JsonPrimitive(it) }
        json.encodeJsonElement(JsonObject(fields))
    }

    override fun deserialize(decoder: Decoder): PlayerBackup {
        val json = decoder as JsonDecoder
        val fields = json.decodeJsonElement().jsonObject
        fun text(key: String) = (fields[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        fun flag(key: String) = (fields[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        fun number(key: String) = (fields[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        val prefs = json.json.decodeFromJsonElement(PlayerPrefs.serializer(), fields)
        val saved = StoredBetweenSongs(
            betweenSongs = text(BETWEEN_SONGS),
            crossfade = flag(CROSSFADE),
            crossfadeSeconds = number("crossfadeSeconds"),
            smartTransitions = flag(SMART_TRANSITIONS),
            filterSweeps = flag("filterSweeps"),
            matchTempo = flag("matchTempo"),
            crossfadeCurve = text("crossfadeCurve"),
        )
        val restored = when (val between = restoredBetweenSongs(number(SETTINGS_VERSION), saved)) {
            RestoredBetweenSongs.Untouched -> prefs.copy(betweenSongs = BetweenSongs.Automix, betweenSongsStored = false, matchTempo = true)
            is RestoredBetweenSongs.Chosen -> prefs.copy(
                betweenSongs = between.write.choice,
                betweenSongsStored = true,
                matchTempo = between.write.matchTempo ?: prefs.matchTempo,
            )
        }
        return PlayerBackup(
            prefs = restored,
            theme = text("theme"),
            themeLight = text("themeLight"),
            themeDark = text("themeDark"),
            themeMatch = (fields["themeMatch"] as? JsonPrimitive)?.booleanOrNull,
        )
    }
}

// Everything a backup holds. Every part is optional, so a backup from an
// older app with fewer settings still reads.
@Serializable
data class Backup(
    val kind: String = BACKUP_KIND,
    val version: Int = BACKUP_VERSION,
    val createdAt: Long = 0,
    val player: PlayerBackup? = null,
    val streaming: StreamPrefs? = null,
    val sound: SoundBackup? = null,
    val presets: List<PresetBackup> = emptyList(),
    val library: LibraryBackup? = null,
    val server: ServerBackup? = null,
    val playlists: List<PlaylistBackup> = emptyList(),
    val likes: List<SongKey> = emptyList(),
    val ratings: List<RatedSong> = emptyList(),
    val favouriteAlbums: List<HeldKey> = emptyList(),
    val favouriteArtists: List<HeldKey> = emptyList(),
    val pins: List<PinBackup> = emptyList(),
    val lyrics: LyricsBackup? = null,
    val offline: OfflineBackup? = null,
    val listenBrainz: ListenBrainzBackup? = null,
    // Home's shelves in order, a hidden one led by "-", as kept under
    // "home_sections". A backup without it leaves Home as it is.
    val homeSections: String? = null,
    // The order each list was left in, by the list's name, as "<order>:asc"
    // or "<order>:desc". Lists never reordered are not named.
    val sortOrders: Map<String, String> = emptyMap(),
    // The live radio stations kept on the phone, newest first.
    val keptStations: List<LiveStation> = emptyList(),
)

internal val BackupJson = Json {
    prettyPrint = true
    encodeDefaults = true
    ignoreUnknownKeys = true
    // A setting value this app does not know falls back to its default.
    coerceInputValues = true
    explicitNulls = false
}

fun encodeBackup(backup: Backup): String = BackupJson.encodeToString(Backup.serializer(), backup)

// What reading a picked file gave.
sealed interface BackupRead {
    data class Read(val backup: Backup) : BackupRead

    // Not an Octo backup, or not readable at all.
    data object NotABackup : BackupRead

    // Made by a newer Octo in a format this one does not know.
    data object TooNew : BackupRead
}

fun decodeBackup(text: String): BackupRead {
    val backup = runCatching { BackupJson.decodeFromString(Backup.serializer(), text.removePrefix(BYTE_ORDER_MARK)) }.getOrNull()
        ?: return BackupRead.NotABackup
    if (backup.kind != BACKUP_KIND) return BackupRead.NotABackup
    if (backup.version > BACKUP_VERSION) return BackupRead.TooNew
    return BackupRead.Read(backup)
}

// A server address with nothing but where it is: no user name or password
// written into it, no query and no fragment.
fun plainAddress(address: String): String {
    val uri = runCatching { URI(address.trim()) }.getOrNull() ?: return address.substringBefore('?').substringBefore('#').trim()
    if (uri.host == null) return address.substringBefore('?').substringBefore('#').substringAfterLast('@').trim()
    val port = if (uri.port >= 0) ":${uri.port}" else ""
    return "${uri.scheme ?: "https"}://${uri.host}$port${uri.rawPath.orEmpty()}".trimEnd('/')
}

// Finds songs named in a backup in this library: by relink key first, then
// by title (with its kind of recording), artist and album, then as the same
// song by the same artist, of about the same length when both lengths are
// known.
class SongFinder(library: List<TrackEntity>) {
    private val byRelink = library.filter { it.relinkKey.isNotEmpty() }.groupBy { it.relinkKey }
    private val byWhole = library.groupBy { wholeKey(it.title, it.artist, it.album) }
    private val byTitle = TitleIndex(library, { it.title }, { it.artist })

    fun find(song: SongKey): String? {
        if (song.relinkKey.isNotEmpty()) byRelink[song.relinkKey]?.firstOrNull()?.let { return it.id }
        if (song.title.isBlank()) return null
        byWhole[wholeKey(song.title, song.artist, song.album)]?.let { same -> return closest(same, song.durationMs).id }
        if (song.artist.isBlank()) return null
        val candidates = byTitle.candidates(song.title, song.artist).filter { sameSong(song.title, song.artist, song.durationMs, it) }
        return candidates.takeIf { it.isNotEmpty() }?.let { closest(it, song.durationMs).id }
    }

    private fun wholeKey(title: String, artist: String, album: String) =
        "${SongIdentity.songKeys(artist, title).title}|${matchKey(artist)}|${matchKey(album)}"

    private fun closest(tracks: List<TrackEntity>, durationMs: Long): TrackEntity =
        if (durationMs <= 0) tracks.first() else tracks.minBy { abs(it.durationMs - durationMs) }
}

// A library album or artist as the finder sees it. An artist has no artist.
data class Named(val id: String, val relinkKey: String, val name: String, val artist: String = "")

// Finds albums or artists named in a backup in this library: by relink key
// first, then by name (and artist, for an album). When several match, the
// first by id wins, so the choice is the same every time.
class NameFinder(library: List<Named>) {
    private val byRelink = library.filter { it.relinkKey.isNotEmpty() }.groupBy { it.relinkKey }
    private val byName = library.groupBy { nameKey(it.name, it.artist) }

    fun find(key: HeldKey): String? {
        if (key.relinkKey.isNotEmpty()) byRelink[key.relinkKey]?.minOf { it.id }?.let { return it }
        if (key.name.isBlank()) return null
        return byName[nameKey(key.name, key.artist)]?.minOf { it.id }
    }

    private fun nameKey(name: String, artist: String) = "${matchKey(name)}|${matchKey(artist)}"
}

// A pin to add on restore: an album or artist by its library id, or a
// playlist by its name, since restored playlists get new ids.
data class PinPlan(val kind: PinKind, val target: String)

// One playlist to make on restore: its name, the songs found, and how many
// the backup had.
data class PlaylistPlan(val name: String, val trackIds: List<String>, val total: Int)

// What restoring would do with this library: the playlists to make, the
// playlists skipped because one of the same name is already here, and the
// songs to like and rate, with how many the backup named.
data class RestorePlan(
    val playlists: List<PlaylistPlan>,
    val skippedPlaylists: List<String>,
    val likes: List<String>,
    val likesTotal: Int,
    val ratings: Map<String, Int>,
    val ratingsTotal: Int,
    val favouriteAlbums: List<String> = emptyList(),
    val favouriteAlbumsTotal: Int = 0,
    val favouriteArtists: List<String> = emptyList(),
    val favouriteArtistsTotal: Int = 0,
    val pins: List<PinPlan> = emptyList(),
    val pinsTotal: Int = 0,
    val lyricsOffsets: Map<String, Long> = emptyMap(),
    val lyricsOffsetsTotal: Int = 0,
    // Playlists to keep downloaded, by name, and how many the backup named.
    val keptPlaylists: List<String> = emptyList(),
    val keptPlaylistsTotal: Int = 0,
) {
    val playlistSongs: Int get() = playlists.sumOf { it.trackIds.size }
    val playlistSongsTotal: Int get() = playlists.sumOf { it.total }
}

fun planRestore(
    backup: Backup,
    library: List<TrackEntity>,
    existingPlaylists: Collection<String>,
    albums: List<Named> = emptyList(),
    artists: List<Named> = emptyList(),
): RestorePlan {
    val finder = SongFinder(library)
    val albumFinder = NameFinder(albums)
    val artistFinder = NameFinder(artists)
    val taken = existingPlaylists.mapTo(HashSet()) { it.trim().lowercase() }
    val (skipped, wanted) = backup.playlists.partition { it.name.trim().lowercase() in taken }
    // Playlists here after the restore: those already here and those it makes.
    val playlistNames = taken + wanted.map { it.name.trim().lowercase() }
    return RestorePlan(
        playlists = wanted.map { playlist ->
            PlaylistPlan(playlist.name.trim(), playlist.songs.mapNotNull(finder::find), playlist.songs.size)
        },
        skippedPlaylists = skipped.map { it.name },
        likes = backup.likes.mapNotNull(finder::find).distinct(),
        likesTotal = backup.likes.size,
        ratings = backup.ratings.filter { it.rating in 1..5 }
            .mapNotNull { rated -> finder.find(rated.song)?.let { it to rated.rating } }
            .toMap(),
        ratingsTotal = backup.ratings.size,
        favouriteAlbums = backup.favouriteAlbums.mapNotNull(albumFinder::find).distinct(),
        favouriteAlbumsTotal = backup.favouriteAlbums.size,
        favouriteArtists = backup.favouriteArtists.mapNotNull(artistFinder::find).distinct(),
        favouriteArtistsTotal = backup.favouriteArtists.size,
        pins = backup.pins.mapNotNull { pin ->
            when (PinKind.of(pin.kind)) {
                PinKind.Album -> albumFinder.find(pin.item)?.let { PinPlan(PinKind.Album, it) }
                PinKind.Artist -> artistFinder.find(pin.item)?.let { PinPlan(PinKind.Artist, it) }
                PinKind.Playlist -> pin.item.name.trim().takeIf { it.lowercase() in playlistNames }?.let { PinPlan(PinKind.Playlist, it) }
                null -> null
            }
        }.distinct(),
        pinsTotal = backup.pins.size,
        lyricsOffsets = backup.lyrics?.offsets.orEmpty()
            .mapNotNull { moved -> finder.find(moved.song)?.let { it to moved.offsetMs } }
            .toMap(),
        lyricsOffsetsTotal = backup.lyrics?.offsets?.size ?: 0,
        keptPlaylists = backup.offline?.keptPlaylists.orEmpty().map { it.trim() }
            .filter { it.lowercase() in playlistNames }
            .distinctBy { it.lowercase() },
        keptPlaylistsTotal = backup.offline?.keptPlaylists?.size ?: 0,
    )
}

// How many playlists, playlist songs, likes and ratings a backup holds,
// counted from its lists, or as its read counted them.
data class BackupSizes(val playlists: Int, val playlistSongs: Int, val likes: Int, val ratings: Int) {
    companion object {
        fun of(backup: Backup) = BackupSizes(backup.playlists.size, backup.playlists.sumOf { it.songs.size }, backup.likes.size, backup.ratings.size)
    }
}

// What a backup holds, a line each, for showing before it is restored.
fun describeBackup(backup: Backup, sizes: BackupSizes = BackupSizes.of(backup)): List<String> = buildList {
    val settings = listOfNotNull(
        "player".takeIf { backup.player != null },
        "streaming".takeIf { backup.streaming != null },
        "sound".takeIf { backup.sound != null },
        "library".takeIf { backup.library != null },
        "lyrics".takeIf { backup.lyrics != null },
        "cache and downloads".takeIf { backup.offline != null },
        "scrobbling".takeIf { backup.listenBrainz != null },
        "home sections".takeIf { backup.homeSections != null },
        "sort orders".takeIf { backup.sortOrders.isNotEmpty() },
    )
    if (settings.isNotEmpty()) add("Settings: ${settings.joinToString(", ")}")
    backup.sound?.let { sound ->
        val outputs = sound.profiles.keys.count { it != "all" }
        if (sound.perOutput && outputs > 0) add(plural(outputs, "output with its own sound", "outputs with their own sound"))
    }
    if (backup.presets.isNotEmpty()) add(plural(backup.presets.size, "equalizer preset", "equalizer presets"))
    if (sizes.playlists > 0) {
        add(plural(sizes.playlists, "playlist", "playlists") + ", " + plural(sizes.playlistSongs, "song", "songs"))
    }
    if (sizes.likes > 0) add(plural(sizes.likes, "liked song", "liked songs"))
    if (sizes.ratings > 0) add(plural(sizes.ratings, "rating", "ratings"))
    if (backup.favouriteAlbums.isNotEmpty()) add(plural(backup.favouriteAlbums.size, "favorite album", "favorite albums"))
    if (backup.favouriteArtists.isNotEmpty()) add(plural(backup.favouriteArtists.size, "favorite artist", "favorite artists"))
    if (backup.pins.isNotEmpty()) add(plural(backup.pins.size, "pin on Home", "pins on Home"))
    if (backup.keptStations.isNotEmpty()) add(plural(backup.keptStations.size, "kept live radio station", "kept live radio stations"))
    backup.lyrics?.offsets?.takeIf { it.isNotEmpty() }?.let { add("Lyrics timing for " + plural(it.size, "song", "songs")) }
    backup.lyrics?.outputOffsets?.takeIf { it.isNotEmpty() }?.let { add("Lyrics timing for " + plural(it.size, "sound output", "sound outputs")) }
    backup.offline?.keptPlaylists?.takeIf { it.isNotEmpty() }?.let {
        add(plural(it.size, "playlist kept downloaded", "playlists kept downloaded"))
    }
    if (backup.listenBrainz?.enabled == true) add("ListenBrainz: connect again to send plays.")
    backup.server?.let { add("Server: ${it.address} as ${it.username}. Sign in again to use it.") }
}

// How much of a backup a library has, counted.
data class PlanCounts(
    val playlistsPlanned: Boolean,
    val playlistSongs: Int,
    val playlistSongsTotal: Int,
    val skippedPlaylists: List<String>,
    val likes: Int,
    val likesTotal: Int,
    val ratings: Int,
    val ratingsTotal: Int,
    val favouriteAlbums: Int,
    val favouriteAlbumsTotal: Int,
    val favouriteArtists: Int,
    val favouriteArtistsTotal: Int,
    val pins: Int,
    val pinsTotal: Int,
    val lyricsOffsets: Int,
    val lyricsOffsetsTotal: Int,
    val keptPlaylists: Int,
    val keptPlaylistsTotal: Int,
)

// How much of a backup this library has, a line each, like "Playlists:
// 331 of 340 songs found".
fun describePlan(plan: RestorePlan): List<String> = describeCounts(
    PlanCounts(
        playlistsPlanned = plan.playlists.isNotEmpty(),
        playlistSongs = plan.playlistSongs,
        playlistSongsTotal = plan.playlistSongsTotal,
        skippedPlaylists = plan.skippedPlaylists,
        likes = plan.likes.size, likesTotal = plan.likesTotal, ratings = plan.ratings.size, ratingsTotal = plan.ratingsTotal,
        favouriteAlbums = plan.favouriteAlbums.size, favouriteAlbumsTotal = plan.favouriteAlbumsTotal,
        favouriteArtists = plan.favouriteArtists.size, favouriteArtistsTotal = plan.favouriteArtistsTotal,
        pins = plan.pins.size, pinsTotal = plan.pinsTotal,
        lyricsOffsets = plan.lyricsOffsets.size, lyricsOffsetsTotal = plan.lyricsOffsetsTotal,
        keptPlaylists = plan.keptPlaylists.size, keptPlaylistsTotal = plan.keptPlaylistsTotal,
    ),
)

fun describeCounts(plan: PlanCounts): List<String> = buildList {
    if (plan.playlistsPlanned) {
        add("Playlists: ${plan.playlistSongs.grouped()} of ${plural(plan.playlistSongsTotal, "song", "songs")} found")
    }
    if (plan.skippedPlaylists.isNotEmpty()) {
        add("Already here, left as they are: ${plan.skippedPlaylists.joinToString(", ")}")
    }
    if (plan.likesTotal > 0) add("Likes: ${plan.likes.grouped()} of ${plan.likesTotal.grouped()} found")
    if (plan.ratingsTotal > 0) add("Ratings: ${plan.ratings.grouped()} of ${plan.ratingsTotal.grouped()} found")
    if (plan.favouriteAlbumsTotal > 0) {
        add("Favorite albums: ${plan.favouriteAlbums.grouped()} of ${plan.favouriteAlbumsTotal.grouped()} found")
    }
    if (plan.favouriteArtistsTotal > 0) {
        add("Favorite artists: ${plan.favouriteArtists.grouped()} of ${plan.favouriteArtistsTotal.grouped()} found")
    }
    if (plan.pinsTotal > 0) add("Pins: ${plan.pins.grouped()} of ${plan.pinsTotal.grouped()} found")
    if (plan.lyricsOffsetsTotal > 0) {
        add("Lyrics timing: ${plan.lyricsOffsets.grouped()} of ${plan.lyricsOffsetsTotal.grouped()} songs found")
    }
    if (plan.keptPlaylistsTotal > 0) {
        add("Kept downloaded: ${plan.keptPlaylists.grouped()} of ${plural(plan.keptPlaylistsTotal, "playlist", "playlists")} found")
    }
}

private fun Int.grouped() = "%,d".format(this)

internal fun plural(count: Int, one: String, many: String) = if (count == 1) "1 $one" else "%,d $many".format(count)
