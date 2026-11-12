package app.winters.octo.backup

import android.content.Context
import android.net.Uri
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.CatalogMerge
import app.winters.octo.catalog.FavouritesDao
import app.winters.octo.catalog.FreeSpace
import app.winters.octo.catalog.ID_CHUNK
import app.winters.octo.catalog.NotEnoughSpace
import app.winters.octo.catalog.PinKind
import app.winters.octo.catalog.PlaylistSets
import app.winters.octo.catalog.SpaceFor
import app.winters.octo.catalog.UserDao
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.catalog.followAll
import app.winters.octo.catalog.marks
import app.winters.octo.catalog.read
import app.winters.octo.data.Exports
import app.winters.octo.data.SessionStore
import app.winters.octo.data.copyExport
import app.winters.octo.data.spaceWords
import app.winters.octo.device.FolderRules
import app.winters.octo.favourites.FavouriteStore
import app.winters.octo.favourites.PinKey
import app.winters.octo.favourites.PinStore
import app.winters.octo.listening.FavouriteKind
import app.winters.octo.listening.ListenBrainzStore
import app.winters.octo.liveradio.LiveRadioStore
import app.winters.octo.lyrics.LyricsLookSettings
import app.winters.octo.lyrics.LyricsTiming
import app.winters.octo.offline.OfflineSettings
import app.winters.octo.playback.LikeStore
import app.winters.octo.playback.PlaylistStore
import app.winters.octo.playback.RatingStore
import app.winters.octo.player.PlayerSettings
import app.winters.octo.playlists.MatchJobs
import app.winters.octo.playlists.MatchSong
import app.winters.octo.playlists.PlaylistSyncStore
import app.winters.octo.server.QueueSync
import app.winters.octo.sort.SortSettings
import app.winters.octo.sound.EqPreset
import app.winters.octo.sound.SoundEngine
import app.winters.octo.sound.UserPresets
import app.winters.octo.theme.ThemeStore
import app.winters.octo.home.encodeHomeSections
import app.winters.octo.home.homeSectionOrder
import app.winters.octo.ui.home.HomePrefs
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

// The disk one song of a backup takes in its file (a liked or rated song,
// or a playlist's): its keys with their indent, measured on long titles
// (BackupSpaceTest). An export asks for this per row free, with a tenth more.
internal const val BACKUP_BYTES_PER_ROW = 420L

// How many rows each page of an export or a restore reads or writes.
internal const val BACKUP_PAGE = 500

// What saving a backup came to: saved, or the words for why not.
sealed interface BackupSave {
    data object Saved : BackupSave

    data class Failed(val words: String) : BackupSave
}

// The words for a backup the picked file could not take.
const val BACKUP_NOT_WRITTEN = "Couldn't write the backup there."

// Saves the app's settings, playlists, likes, ratings, favourites, pins and
// kept live radio stations to a file the listener picks, and puts them back
// from one. Sign-in secrets are never written: only the server's address
// and user name, for reference, and no ListenBrainz token at all.
//
// Nothing song-sized is held (BackupSongs): a backup is written to a file of
// the app's own a page of songs at a time, then copied to the picked file;
// one read back stages its songs in a matching job a page at a time, the job
// finds them in the library in one walk, and a restore writes them a page
// at a time from the job's rows.
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
    private val playlists: PlaylistStore,
    private val likes: LikeStore,
    private val ratings: RatingStore,
    private val favouritesDao: FavouritesDao,
    private val favourites: FavouriteStore,
    private val pins: PinStore,
    private val lyrics: LyricsTiming,
    private val lyricsLook: LyricsLookSettings,
    private val offline: OfflineSettings,
    private val listenBrainz: ListenBrainzStore,
    private val sorts: SortSettings,
    private val home: HomePrefs,
    private val themes: ThemeStore,
    private val db: CatalogDatabase,
    merge: CatalogMerge,
    sets: PlaylistSets,
    owners: WorkOwners,
    jobs: MatchJobs,
    exports: Exports,
    freeSpace: FreeSpace,
    liveRadioStore: LiveRadioStore,
) {
    private val liveRadio = LiveRadioBackup(liveRadioStore)
    private val songs = BackupSongs(db, merge, sets, owners, jobs, exports, freeSpace)

    // Everything but the songs as it is now: the backup's own small values,
    // with no playlists, likes or ratings, which the export writes a page at
    // a time.
    suspend fun header(now: Long = System.currentTimeMillis()): Backup = withContext(Dispatchers.IO) {
        val (playerPrefs, streamPrefs) = player.snapshot()
        val (perOutput, profiles) = sound.saved()
        val playlistNames = userDao.playlists().first().associate { it.id to it.name }
        val look = themes.settings.value
        val likedAlbums = favouritesDao.likedAlbumRows().sortedBy { it.likedAt }
        val likedArtists = favouritesDao.likedArtistRows().sortedBy { it.likedAt }
        val pinned = favouritesDao.pins()
        val albums = albumsOf((likedAlbums.map { it.albumId } + pinned.filter { PinKind.of(it.kind) == PinKind.Album }.map { it.itemId }).distinct())
        val artists = artistsOf((likedArtists.map { it.artistId } + pinned.filter { PinKind.of(it.kind) == PinKind.Artist }.map { it.itemId }).distinct())
        val offsets = lyrics.offsets()
        val keyed = songKeysOf(offsets.keys.toList())
        Backup(
            createdAt = now,
            player = PlayerBackup(playerPrefs, look.theme.name, look.light.name, look.dark.name, look.match),
            streaming = streamPrefs,
            sound = SoundBackup(perOutput, profiles),
            presets = presets.saved().map { PresetBackup(it.name, it.gains) },
            library = LibraryBackup(
                excludedFolders = folders.excluded.first().sorted(),
                syncQueue = queueSync.enabled.first(),
                newPlaylistsOnServer = playlistSync.newOnServer.first(),
            ),
            server = sessions.read().inUse?.let { ServerBackup(plainAddress(it.serverUrl), it.username) },
            favouriteAlbums = likedAlbums.map { HeldKey(relinkKey = it.relinkKey, name = albums[it.albumId]?.first.orEmpty(), artist = albums[it.albumId]?.second.orEmpty()) },
            favouriteArtists = likedArtists.map { HeldKey(relinkKey = it.relinkKey, name = artists[it.artistId].orEmpty()) },
            pins = pinned.mapNotNull { pin ->
                when (PinKind.of(pin.kind)) {
                    PinKind.Album -> HeldKey(relinkKey = pin.relinkKey, name = albums[pin.itemId]?.first.orEmpty(), artist = albums[pin.itemId]?.second.orEmpty())
                    PinKind.Artist -> HeldKey(relinkKey = pin.relinkKey, name = artists[pin.itemId].orEmpty())
                    PinKind.Playlist -> playlistNames[pin.itemId]?.let { HeldKey(name = it) }
                    null -> null
                }?.let { PinBackup(pin.kind, it) }
            },
            lyrics = LyricsBackup(
                keepScreenOn = lyrics.keepScreenOn.first(),
                offsets = offsets.mapNotNull { (id, ms) -> keyed[id]?.let { LyricsOffsetBackup(it, ms) } }.sortedBy { it.song.relinkKey },
                look = lyricsLook.current(),
                outputOffsets = lyrics.outputOffsets().toSortedMap(),
            ),
            offline = offline.prefs.first().toBackup(playlistNames),
            listenBrainz = listenBrainz.current().let { ListenBrainzBackup(it.enabled, it.sendPlays, it.nowPlaying) },
            homeSections = encodeHomeSections(home.homeSections.first()),
            sortOrders = sorts.saved(),
            keptStations = liveRadio.saved(),
        )
    }

    // Writes a backup to a picked file: to a file of the app's own first, a
    // page of songs at a time, as one state of the library, then copied
    // across outside every lock (the picked file may be on a network).
    suspend fun save(uri: Uri): BackupSave = songs.export(header()) { file ->
        runCatching { context.contentResolver.openOutputStream(uri, "wt")?.use { copyExport(file, it) } != null }.getOrDefault(false)
    }

    // Reads a picked file a value at a time: its small values kept, its
    // songs staged in a matching job that stays open until the restore or
    // `close`.
    suspend fun read(uri: Uri): BackupOpened = songs.read { context.contentResolver.openInputStream(uri) }

    // Lets a file read and not restored go.
    suspend fun close(file: BackupFile) = songs.close(file)

    // What restoring would do with the library as it is now.
    suspend fun plan(file: BackupFile): BackupPlan = songs.plan(file, userDao.playlists().first().map { it.name })

    // Puts a backup's settings back, and makes its playlists, likes and
    // ratings as planned. Answers the words for a restore the disk stopped,
    // or null. The file is let go.
    suspend fun restore(file: BackupFile, plan: BackupPlan): String? = withContext(Dispatchers.IO) {
        val backup = file.header
        try {
            restoreSettings(backup, plan)
            // Playlists by name, with the ones made here, so pins can find them.
            val playlistIds = userDao.playlists().first().associate { it.name.trim().lowercase() to it.id }.toMutableMap()
            playlistIds += songs.restore(file, plan, playlists::createFrom, likes::restored) { id, stars -> ratings.restoreNow(id, stars) }
            val likedAlbums = favouritesDao.likedAlbumRows().mapTo(HashSet()) { it.albumId }
            plan.favouriteAlbums.filterNot { it in likedAlbums }.forEach { favourites.set(FavouriteKind.Album, it, true) }
            val likedArtists = favouritesDao.likedArtistRows().mapTo(HashSet()) { it.artistId }
            plan.favouriteArtists.filterNot { it in likedArtists }.forEach { favourites.set(FavouriteKind.Artist, it, true) }
            // After the playlists, so the ones kept downloaded can be found by name.
            backup.offline?.let { saved ->
                val kept = plan.keptPlaylists.mapNotNullTo(HashSet()) { playlistIds[it.lowercase()] }
                offline.restore(saved.toPrefs(kept))
            }
            // Added after the pins already here, in the backup's order, while Home has room.
            plan.pins.forEach { pin ->
                val id = if (pin.kind == PinKind.Playlist) playlistIds[pin.target.lowercase()] else pin.target
                if (id != null) pins.pin(PinKey(pin.kind, id))
            }
            null
        } catch (e: NotEnoughSpace) {
            spaceWords(SpaceFor.Restore, e.neededBytes)
        } finally {
            songs.close(file)
        }
    }

    private suspend fun restoreSettings(backup: Backup, plan: BackupPlan) {
        val playerPrefs = backup.player?.prefs
        val streamPrefs = backup.streaming
        if (playerPrefs != null || streamPrefs != null) {
            val (currentPlayer, currentStream) = player.snapshot()
            player.restore(playerPrefs ?: currentPlayer, streamPrefs ?: currentStream)
        }
        backup.player?.let { themes.write(it.themes()) }
        backup.sound?.let { sound.restore(it.perOutput, it.profiles) }
        presets.restore(backup.presets.map { EqPreset(it.name, it.gains) })
        backup.library?.let { library ->
            folders.setExcluded(library.excludedFolders.toSet())
            queueSync.setEnabled(library.syncQueue)
            playlistSync.setNewOnServer(library.newPlaylistsOnServer)
        }
        backup.listenBrainz?.let { listenBrainz.restoreChoices(it.enabled, it.sendPlays, it.nowPlaying) }
        backup.homeSections?.let { home.setHomeSections(homeSectionOrder(it)) }
        sorts.restore(backup.sortOrders)
        liveRadio.restore(backup)
        backup.lyrics?.let { lyrics.setKeepScreenOn(it.keepScreenOn) }
        backup.lyrics?.look?.let { lyricsLook.restore(it) }
        // Each song's timing under the song it is now.
        val now = db.followAll(plan.lyricsOffsets.keys)
        plan.lyricsOffsets.forEach { (id, ms) -> now[id]?.let { lyrics.setOffset(it, ms) } }
        backup.lyrics?.outputOffsets?.forEach { (output, ms) -> lyrics.setOutputOffset(output, ms) }
    }

    // Albums by id: their title and artist, a chunk at a time.
    private fun albumsOf(ids: List<String>): Map<String, Pair<String, String>> {
        val found = HashMap<String, Pair<String, String>>()
        ids.chunked(ID_CHUNK).forEach { chunk ->
            db.read("SELECT id, title, artist FROM album WHERE id IN (${marks(chunk.size)})", *chunk.toTypedArray()) { it.getString(0) to (it.getString(1) to it.getString(2)) }
                .forEach { (id, named) -> found[id] = named }
        }
        return found
    }

    private fun artistsOf(ids: List<String>): Map<String, String> {
        val found = HashMap<String, String>()
        ids.chunked(ID_CHUNK).forEach { chunk ->
            db.read("SELECT id, name FROM artist WHERE id IN (${marks(chunk.size)})", *chunk.toTypedArray()) { it.getString(0) to it.getString(1) }
                .forEach { (id, name) -> found[id] = name }
        }
        return found
    }

    // Songs by id as a backup names them, a chunk at a time.
    private fun songKeysOf(ids: List<String>): Map<String, SongKey> {
        val found = HashMap<String, SongKey>()
        ids.chunked(ID_CHUNK).forEach { chunk ->
            db.read("SELECT id, relinkKey, title, artist, album, durationMs FROM track WHERE id IN (${marks(chunk.size)})", *chunk.toTypedArray()) {
                it.getString(0) to SongKey(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getLong(5))
            }.forEach { (id, key) -> found[id] = key }
        }
        return found
    }

    companion object {
        // The job's lists: the likes, the ratings, the lyrics offsets, then
        // each playlist's songs from PLAYLISTS on.
        const val LIKES = 0
        const val RATINGS = 1
        const val OFFSETS = 2
        const val PLAYLISTS = 3
    }
}

// A backup file read and waiting to be restored: its small values (no
// playlists, likes or ratings), how many songs each list has, its playlists
// by name and list, and the open job holding its songs.
class BackupFile(
    val header: Backup,
    val likes: Int,
    val ratings: Int,
    val playlists: List<FilePlaylist>,
    internal val job: app.winters.octo.playlists.MatchJob,
) {
    val sizes: BackupSizes get() = BackupSizes(playlists.size, playlists.sumOf { it.songs }, likes, ratings)
}

// One playlist of a backup file: its name, its job list and its songs.
data class FilePlaylist(val name: String, val list: Int, val songs: Int)

// What reading a picked file gave.
sealed interface BackupOpened {
    data class Opened(val file: BackupFile) : BackupOpened

    data object NotABackup : BackupOpened

    data object TooNew : BackupOpened

    // The phone ran short of room for the file's songs.
    data class Problem(val words: String) : BackupOpened
}

// One playlist a restore makes: its name, the job list it comes from, how
// many of its songs were found and how many the file has.
data class PlannedPlaylist(val name: String, val list: Int, val found: Int, val total: Int)

// What restoring a file would do with this library, as RestorePlan says
// it, with counts where RestorePlan holds songs.
data class BackupPlan(
    val playlists: List<PlannedPlaylist>,
    val skippedPlaylists: List<String>,
    val likes: Int,
    val likesTotal: Int,
    val ratings: Int,
    val ratingsTotal: Int,
    val favouriteAlbums: List<String>,
    val favouriteAlbumsTotal: Int,
    val favouriteArtists: List<String>,
    val favouriteArtistsTotal: Int,
    val pins: List<PinPlan>,
    val pinsTotal: Int,
    val lyricsOffsets: Map<String, Long>,
    val lyricsOffsetsTotal: Int,
    val keptPlaylists: List<String>,
    val keptPlaylistsTotal: Int,
) {
    val counts: PlanCounts
        get() = PlanCounts(
            playlistsPlanned = playlists.isNotEmpty(),
            playlistSongs = playlists.sumOf { it.found },
            playlistSongsTotal = playlists.sumOf { it.total },
            skippedPlaylists = skippedPlaylists,
            likes = likes, likesTotal = likesTotal, ratings = ratings, ratingsTotal = ratingsTotal,
            favouriteAlbums = favouriteAlbums.size, favouriteAlbumsTotal = favouriteAlbumsTotal,
            favouriteArtists = favouriteArtists.size, favouriteArtistsTotal = favouriteArtistsTotal,
            pins = pins.size, pinsTotal = pinsTotal,
            lyricsOffsets = lyricsOffsets.size, lyricsOffsetsTotal = lyricsOffsetsTotal,
            keptPlaylists = keptPlaylists.size, keptPlaylistsTotal = keptPlaylistsTotal,
        )
}

fun describePlan(plan: BackupPlan): List<String> = describeCounts(plan.counts)

// Stages a song of a backup in its job.
internal fun SongKey.toMatch(value: Int = 0) = MatchSong(relinkKey, title, artist, album, durationMs, value)
