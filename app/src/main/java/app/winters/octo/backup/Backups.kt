package app.winters.octo.backup

import android.content.Context
import android.net.Uri
import app.winters.octo.catalog.CatalogDao
import app.winters.octo.catalog.SongKeyRow
import app.winters.octo.catalog.UserDao
import app.winters.octo.data.SessionStore
import app.winters.octo.device.FolderRules
import app.winters.octo.playback.LikeStore
import app.winters.octo.playback.PlaylistStore
import app.winters.octo.playback.RatingStore
import app.winters.octo.player.PlayerSettings
import app.winters.octo.playlists.PlaylistSyncStore
import app.winters.octo.server.QueueSync
import app.winters.octo.sound.EqPreset
import app.winters.octo.sound.SoundEngine
import app.winters.octo.sound.UserPresets
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

// The largest backup read. Even a big library's backup is a few megabytes.
private const val MAX_BACKUP_BYTES = 32 * 1024 * 1024

// Saves the app's settings, playlists, likes and ratings to a file the
// listener picks, and puts them back from one. Sign-in secrets are never
// written: only the server's address and user name, for reference.
@Singleton
class Backups @Inject constructor(
    @ApplicationContext private val context: Context,
    private val player: PlayerSettings,
    private val sound: SoundEngine,
    private val presets: UserPresets,
    private val folders: FolderRules,
    private val queueSync: QueueSync,
    private val playlistSync: PlaylistSyncStore,
    private val sessions: SessionStore,
    private val userDao: UserDao,
    private val catalog: CatalogDao,
    private val playlists: PlaylistStore,
    private val likes: LikeStore,
    private val ratings: RatingStore,
) {
    // Everything as it is now.
    suspend fun make(now: Long = System.currentTimeMillis()): Backup = withContext(Dispatchers.IO) {
        val (playerPrefs, streamPrefs) = player.snapshot()
        val (perOutput, profiles) = sound.saved()
        val songsByPlaylist = userDao.phonePlaylistSongKeys().groupBy { it.playlistId }
        Backup(
            createdAt = now,
            player = playerPrefs,
            streaming = streamPrefs,
            sound = SoundBackup(perOutput, profiles),
            presets = presets.saved().map { PresetBackup(it.name, it.gains) },
            library = LibraryBackup(
                excludedFolders = folders.excluded.first().sorted(),
                syncQueue = queueSync.enabled.first(),
                newPlaylistsOnServer = playlistSync.newOnServer.first(),
            ),
            server = sessions.read()?.let { ServerBackup(plainAddress(it.serverUrl), it.username) },
            playlists = userDao.phonePlaylists().sortedBy { it.createdAt }.map { playlist ->
                PlaylistBackup(playlist.name, songsByPlaylist[playlist.id].orEmpty().map { it.toKey() })
            },
            likes = userDao.likedSongKeys().map { it.toKey() },
            ratings = userDao.ratedSongKeys().mapNotNull { row -> row.rating?.let { RatedSong(row.toKey(), it) } },
        )
    }

    // Writes a backup to a picked file. False when it could not be written.
    suspend fun save(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val text = encodeBackup(make())
        runCatching {
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } != null
        }.getOrDefault(false)
    }

    // Reads a picked file.
    suspend fun read(uri: Uri): BackupRead = withContext(Dispatchers.IO) {
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (out.size() < MAX_BACKUP_BYTES) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }
        }.getOrNull() ?: return@withContext BackupRead.NotABackup
        decodeBackup(String(bytes, Charsets.UTF_8))
    }

    // What restoring would do with the library as it is now.
    suspend fun plan(backup: Backup): RestorePlan = withContext(Dispatchers.IO) {
        planRestore(backup, catalog.tracks().first(), userDao.playlists().first().map { it.name })
    }

    // Puts a backup's settings back, and makes its playlists, likes and
    // ratings as planned. Songs go through the same stores as when the
    // listener does it, so a connected server follows as usual.
    suspend fun restore(backup: Backup, plan: RestorePlan) = withContext(Dispatchers.IO) {
        val playerPrefs = backup.player
        val streamPrefs = backup.streaming
        if (playerPrefs != null || streamPrefs != null) {
            val (currentPlayer, currentStream) = player.snapshot()
            player.restore(playerPrefs ?: currentPlayer, streamPrefs ?: currentStream)
        }
        backup.sound?.let { sound.restore(it.perOutput, it.profiles) }
        presets.restore(backup.presets.map { EqPreset(it.name, it.gains) })
        backup.library?.let { library ->
            folders.setExcluded(library.excludedFolders.toSet())
            queueSync.setEnabled(library.syncQueue)
            playlistSync.setNewOnServer(library.newPlaylistsOnServer)
        }
        plan.playlists.filter { it.trackIds.isNotEmpty() || it.total == 0 }.forEach { playlists.create(it.name, it.trackIds) }
        plan.likes.forEach { id -> if (!userDao.isLiked(id)) likes.toggle(id) }
        plan.ratings.forEach { (id, stars) -> ratings.rate(id, stars) }
    }
}

private fun SongKeyRow.toKey() = SongKey(
    relinkKey = relinkKey,
    title = title.orEmpty(),
    artist = artist.orEmpty(),
    album = album.orEmpty(),
    durationMs = durationMs ?: 0,
)
