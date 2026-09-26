package app.winters.octo.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import android.os.SystemClock
import androidx.core.net.toUri
import app.winters.octo.catalog.CatalogDao
import app.winters.octo.catalog.FileTagsEntity
import app.winters.octo.catalog.CatalogMerge
import app.winters.octo.catalog.SourceDao
import app.winters.octo.catalog.toSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

// How many files are read for tags at once.
private const val READERS = 4

// Raised whenever reading tags learns something new, so every file is
// read again once. Rows saved before genres were read are version 0.
private const val TAGS_VERSION = 1

// Whether the app may read the phone's music.
enum class Access { Granted, NotAsked, Denied, DeniedForever }

// Keeps the catalog in step with the music on the phone: scans when
// access is granted, and again whenever the phone's media library changes.
@Singleton
class DeviceLibrary @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scanner: DeviceScanner,
    private val reader: TagReader,
    private val dao: CatalogDao,
    private val rules: FolderRules,
    private val sources: SourceDao,
    private val merge: CatalogMerge,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val scanLock = Mutex()
    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var started = false

    val permissionName: String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private val _access = MutableStateFlow(if (granted()) Access.Granted else Access.NotAsked)
    val access: StateFlow<Access> = _access

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    // Every folder music was found in, including switched-off ones.
    private val _folders = MutableStateFlow<List<MusicFolder>>(emptyList())
    val folders: StateFlow<List<MusicFolder>> = _folders

    // Where each song in the library from the phone sits, as folder names,
    // by media id. Kept in memory from the last scan, which runs each time
    // the app starts; null until then.
    private val _paths = MutableStateFlow<Map<Long, List<String>>?>(null)
    val paths: StateFlow<Map<Long, List<String>>?> = _paths

    // Each phone song's file as a path from the top of its storage, like
    // "Music/Kavinsky/Nightcall/01 Nightcall.mp3", by media id. For reading
    // and writing playlist files. Also from the last scan; null until then.
    private val _files = MutableStateFlow<Map<Long, String>?>(null)
    val files: StateFlow<Map<Long, String>?> = _files

    suspend fun setFolderIncluded(folder: String, included: Boolean) = rules.setIncluded(folder, included)

    @OptIn(FlowPreview::class)
    fun start() {
        if (started) return
        started = true
        // A big copy fires many changes; wait for a quiet moment, then scan once.
        scope.launch { changes.debounce(2000).collect { rescan() } }
        // Switching a folder on or off rebuilds the library straight away.
        scope.launch { rules.excluded.drop(1).distinctUntilChanged().collect { rescan() } }
        context.contentResolver.registerContentObserver(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            true,
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    changes.tryEmit(Unit)
                }
            },
        )
        scope.launch { rescan() }
    }

    // Called with the answer to the permission request.
    fun onPermissionResult(granted: Boolean, canAskAgain: Boolean) {
        _access.value = when {
            granted -> Access.Granted
            canAskAgain -> Access.Denied
            else -> Access.DeniedForever
        }
        if (granted) scope.launch { rescan() }
    }

    // Access can change in system settings while the app is away.
    fun refresh() {
        val now = granted()
        if (now && _access.value != Access.Granted) {
            _access.value = Access.Granted
            scope.launch { rescan() }
        } else if (!now && _access.value == Access.Granted) {
            _access.value = Access.NotAsked
        }
    }

    suspend fun rescan() {
        if (!granted()) return
        scanLock.withLock {
            _scanning.value = true
            try {
                val started = SystemClock.elapsedRealtime()
                val files = scanner.list()
                // Tags are kept for every file, so switching a folder back on
                // needs no re-reading.
                val tags = refreshTags(files)
                val excluded = rules.excluded.first()
                _folders.value = files.folders(excluded)
                val included = files.withoutFolders(excluded)
                val catalog = buildDeviceCatalog(included.map { it.toRow(tags[it.id]) })
                // The phone's own copy, then the library rebuilt with any
                // server's music merged in.
                sources.replaceSource(
                    DEVICE,
                    catalog.tracks.map { it.toSource() },
                    catalog.albums.map { it.toSource() },
                    catalog.artists.map { it.toSource() },
                )
                val library = merge.rebuild()
                _paths.value = included.folderPaths()
                _files.value = included.associate { it.id to relativeFile(it.folder, it.fileName) }
                // Counts only, so the library can be checked against the phone.
                Log.i(
                    "Octo",
                    "phone scan: ${catalog.tracks.size} tracks, ${catalog.albums.size} albums, " +
                        "${catalog.artists.size} artists, $lastReread files read, " +
                        "${SystemClock.elapsedRealtime() - started} ms; library ${library.tracks.size} tracks, " +
                        "${library.albums.size} albums, ${library.artists.size} artists",
                )
            } finally {
                _scanning.value = false
            }
        }
    }

    private var lastReread = 0

    // Brings the saved tags up to date: reads only files that are new or
    // changed since last time, and forgets files that are gone.
    private suspend fun refreshTags(files: List<DeviceFile>): Map<Long, FileTags> {
        val saved = dao.fileTags().associateBy { it.mediaId }
        val stale = files.filter { file ->
            val known = saved[file.id]
            known == null || known.modifiedAt != file.modifiedAt || known.size != file.sizeBytes ||
                known.tagsVersion != TAGS_VERSION
        }
        // Several files at once; each read opens its own handle.
        val fresh = coroutineScope {
            stale.chunked((stale.size / READERS).coerceAtLeast(1) + 1).map { batch ->
                async(Dispatchers.IO) { batch.map { file -> file.toSaved(reader.read(file.uri.toUri())) } }
            }.awaitAll().flatten()
        }
        fresh.chunked(500).forEach { dao.upsertFileTags(it) }
        val gone = saved.keys - files.map { it.id }.toSet()
        gone.chunked(500).forEach { dao.deleteFileTags(it) }
        lastReread = fresh.size

        return (saved - gone + fresh.associateBy { it.mediaId })
            .filterValues { it.readOk }
            .mapValues { (_, t) ->
                FileTags(
                    t.title, t.artist, t.albumArtist, t.album, t.trackNo, t.discNo, t.year, t.compilation, t.mbAlbumId,
                    genres = t.genres.lines().filter(String::isNotEmpty),
                )
            }
    }

    private fun DeviceFile.toSaved(tags: FileTags?) = FileTagsEntity(
        mediaId = id,
        modifiedAt = modifiedAt,
        size = sizeBytes,
        readOk = tags != null,
        title = tags?.title,
        artist = tags?.artist,
        albumArtist = tags?.albumArtist,
        album = tags?.album,
        trackNo = tags?.trackNo,
        discNo = tags?.discNo,
        year = tags?.year,
        compilation = tags?.compilation ?: false,
        mbAlbumId = tags?.mbAlbumId,
        genres = tags?.genres.orEmpty().joinToString("\n"),
        tagsVersion = TAGS_VERSION,
    )

    private fun granted() =
        ContextCompat.checkSelfPermission(context, permissionName) == PackageManager.PERMISSION_GRANTED
}
