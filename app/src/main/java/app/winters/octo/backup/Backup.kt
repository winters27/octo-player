package app.winters.octo.backup

import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.matchKey
import app.winters.octo.discovery.sameArtist
import app.winters.octo.discovery.titleKeys
import app.winters.octo.player.PlayerPrefs
import app.winters.octo.player.StreamPrefs
import app.winters.octo.playlists.BYTE_ORDER_MARK
import app.winters.octo.sound.SoundSettings
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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

// The server signed in to, for reference: its address and user name only.
// Passwords, keys, header values and certificates are never in a backup,
// so signing in again is needed.
@Serializable
data class ServerBackup(val address: String, val username: String)

// Everything a backup holds. Every part is optional, so a backup from an
// older app with fewer settings still reads.
@Serializable
data class Backup(
    val kind: String = BACKUP_KIND,
    val version: Int = BACKUP_VERSION,
    val createdAt: Long = 0,
    val player: PlayerPrefs? = null,
    val streaming: StreamPrefs? = null,
    val sound: SoundBackup? = null,
    val presets: List<PresetBackup> = emptyList(),
    val library: LibraryBackup? = null,
    val server: ServerBackup? = null,
    val playlists: List<PlaylistBackup> = emptyList(),
    val likes: List<SongKey> = emptyList(),
    val ratings: List<RatedSong> = emptyList(),
)

private val BackupJson = Json {
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

// Lengths further apart than this are different recordings.
private const val SAME_LENGTH_MS = 10_000L

// Finds songs named in a backup in this library: by relink key first, then
// by title, artist and album, then by title and artist, of about the same
// length when both lengths are known.
class SongFinder(library: List<TrackEntity>) {
    private val byRelink = library.filter { it.relinkKey.isNotEmpty() }.groupBy { it.relinkKey }
    private val byWhole = library.groupBy { wholeKey(it.title, it.artist, it.album) }
    private val byTitle = HashMap<String, MutableList<TrackEntity>>().apply {
        library.forEach { track -> titleKeys(track.title).forEach { getOrPut(it) { mutableListOf() } += track } }
    }

    fun find(song: SongKey): String? {
        if (song.relinkKey.isNotEmpty()) byRelink[song.relinkKey]?.firstOrNull()?.let { return it.id }
        if (song.title.isBlank()) return null
        byWhole[wholeKey(song.title, song.artist, song.album)]?.let { same -> return closest(same, song.durationMs).id }
        if (song.artist.isBlank()) return null
        val candidates = titleKeys(song.title).flatMap { byTitle[it].orEmpty() }.distinctBy { it.id }
            .filter { sameArtist(song.artist, it.artist) && sameLength(song.durationMs, it.durationMs) }
        return candidates.takeIf { it.isNotEmpty() }?.let { closest(it, song.durationMs).id }
    }

    private fun wholeKey(title: String, artist: String, album: String) = "${matchKey(title)}|${matchKey(artist)}|${matchKey(album)}"

    private fun closest(tracks: List<TrackEntity>, durationMs: Long): TrackEntity =
        if (durationMs <= 0) tracks.first() else tracks.minBy { abs(it.durationMs - durationMs) }

    private fun sameLength(a: Long, b: Long) = a <= 0 || b <= 0 || abs(a - b) <= SAME_LENGTH_MS
}

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
) {
    val playlistSongs: Int get() = playlists.sumOf { it.trackIds.size }
    val playlistSongsTotal: Int get() = playlists.sumOf { it.total }
}

fun planRestore(backup: Backup, library: List<TrackEntity>, existingPlaylists: Collection<String>): RestorePlan {
    val finder = SongFinder(library)
    val taken = existingPlaylists.mapTo(HashSet()) { it.trim().lowercase() }
    val (skipped, wanted) = backup.playlists.partition { it.name.trim().lowercase() in taken }
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
    )
}

// What a backup holds, a line each, for showing before it is restored.
fun describeBackup(backup: Backup): List<String> = buildList {
    val settings = listOfNotNull(
        "player".takeIf { backup.player != null },
        "streaming".takeIf { backup.streaming != null },
        "sound".takeIf { backup.sound != null },
        "library".takeIf { backup.library != null },
    )
    if (settings.isNotEmpty()) add("Settings: ${settings.joinToString(", ")}")
    backup.sound?.let { sound ->
        val outputs = sound.profiles.keys.count { it != "all" }
        if (sound.perOutput && outputs > 0) add(plural(outputs, "output with its own sound", "outputs with their own sound"))
    }
    if (backup.presets.isNotEmpty()) add(plural(backup.presets.size, "equalizer preset", "equalizer presets"))
    if (backup.playlists.isNotEmpty()) {
        add(plural(backup.playlists.size, "playlist", "playlists") + ", " + plural(backup.playlists.sumOf { it.songs.size }, "song", "songs"))
    }
    if (backup.likes.isNotEmpty()) add(plural(backup.likes.size, "liked song", "liked songs"))
    if (backup.ratings.isNotEmpty()) add(plural(backup.ratings.size, "rating", "ratings"))
    backup.server?.let { add("Server: ${it.address} as ${it.username}. Sign in again to use it.") }
}

// How much of a backup this library has, a line each, like "Playlists:
// 331 of 340 songs found".
fun describePlan(plan: RestorePlan): List<String> = buildList {
    if (plan.playlists.isNotEmpty()) {
        add("Playlists: ${plan.playlistSongs.grouped()} of ${plural(plan.playlistSongsTotal, "song", "songs")} found")
    }
    if (plan.skippedPlaylists.isNotEmpty()) {
        add("Already here, left as they are: ${plan.skippedPlaylists.joinToString(", ")}")
    }
    if (plan.likesTotal > 0) add("Likes: ${plan.likes.size.grouped()} of ${plan.likesTotal.grouped()} found")
    if (plan.ratingsTotal > 0) add("Ratings: ${plan.ratings.size.grouped()} of ${plan.ratingsTotal.grouped()} found")
}

private fun Int.grouped() = "%,d".format(this)

internal fun plural(count: Int, one: String, many: String) = if (count == 1) "1 $one" else "%,d $many".format(count)
