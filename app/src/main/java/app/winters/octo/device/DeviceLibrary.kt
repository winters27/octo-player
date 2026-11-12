package app.winters.octo.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.CatalogMerge
import app.winters.octo.catalog.FreeSpace
import app.winters.octo.catalog.NotEnoughSpace
import app.winters.octo.catalog.StageAborted
import app.winters.octo.data.userMessage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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

// Whether the app may read the phone's music.
enum class Access { Granted, NotAsked, Denied, DeniedForever }

// Keeps the catalog in step with the music on the phone: scans when
// access is granted, and again whenever the phone's media library changes.
//
// One scan runs at a time. Asking for a scan waits for one that starts
// after the ask; asks that come while one runs share the next, and an
// asker that stops waiting (a screen closed) leaves the scan running.
@Singleton
class DeviceLibrary @Inject constructor(
    @ApplicationContext private val context: Context,
    scanner: DeviceScanner,
    reader: TagReader,
    private val rules: FolderRules,
    db: CatalogDatabase,
    private val merge: CatalogMerge,
    freeSpace: FreeSpace,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val scanLock = Mutex()
    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val dao = db.phoneScanDao()
    private var started = false

    internal val scan = PhoneScan(db, merge, scanner, reader, freeSpace)

    // Scans asked for and not yet begun, and whether the runner is going.
    private val asks = ArrayList<CompletableDeferred<Unit>>()
    private var running = false

    val permissionName: String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private val _access = MutableStateFlow(if (granted()) Access.Granted else Access.NotAsked)
    val access: StateFlow<Access> = _access

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    // Whether a scan has finished, now or on an earlier launch, so "no phone
    // songs" can be told from "not looked yet".
    private val _scanned = MutableStateFlow(false)
    val scanned: StateFlow<Boolean> = _scanned

    // Why the last scan could not be saved, in words, while it stands: the
    // phone ran short of room (as a copy of the library says it). Null once
    // a scan is saved.
    private val _problem = MutableStateFlow<String?>(null)
    val problem: StateFlow<String?> = _problem

    // Every folder music was found in, including switched-off ones.
    private val _folders = MutableStateFlow<List<MusicFolder>>(emptyList())
    val folders: StateFlow<List<MusicFolder>> = _folders

    suspend fun setFolderIncluded(folder: String, included: Boolean) = rules.setIncluded(folder, included)

    @OptIn(FlowPreview::class)
    fun start() {
        if (started) return
        started = true
        scope.launch { if (dao.state()?.digest != null) _scanned.value = true }
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
        // A merge cut off by the app closing is finished, phone access or not.
        scope.launch { merge.repair() }
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

    // Octo just changed the phone's files, such as deleting some: scans now,
    // without waiting for the phone's media library to say so.
    fun filesChanged() {
        scope.launch { rescan() }
    }

    // Asks for a scan and waits for it.
    suspend fun rescan() {
        if (!granted()) return
        val done = CompletableDeferred<Unit>()
        val start = synchronized(asks) {
            asks += done
            if (running) false else true.also { running = true }
        }
        if (start) scope.launch { runScans() }
        done.await()
    }

    private suspend fun runScans() {
        while (true) {
            val batch = synchronized(asks) {
                if (asks.isEmpty()) {
                    running = false
                    return
                }
                asks.toList().also { asks.clear() }
            }
            try {
                scanLock.withLock { scanTwice() }
                batch.forEach { it.complete(Unit) }
            } catch (e: CancellationException) {
                batch.forEach { it.cancel(e) }
                synchronized(asks) { running = false }
                throw e
            } catch (e: Exception) {
                batch.forEach { it.completeExceptionally(e) }
            }
        }
    }

    // One scan; when the merge drops its copy, one more, and then no more
    // until something else asks. A full disk, a database that refuses a
    // write and a dropped copy end it without failing the askers.
    private suspend fun scanTwice() {
        _scanning.value = true
        try {
            for (attempt in 1..2) {
                val started = SystemClock.elapsedRealtime()
                try {
                    val excluded = rules.excluded.first()
                    val result = scan.run(excluded) { _folders.value = it }
                    _problem.value = null
                    // Counts only, so the library can be checked against the phone.
                    Log.i(
                        "Octo",
                        "phone scan: ${if (result.quiet) "nothing changed" else "built"}, ${result.tracks} tracks, " +
                            "${result.albums} albums, ${result.artists} artists, ${result.reread} files read, " +
                            "${SystemClock.elapsedRealtime() - started} ms",
                    )
                    return
                } catch (e: StageAborted) {
                    if (e.noRoom) _problem.value = e.reason
                    Log.w("Octo", "phone scan not saved: the merge did not take it in" + if (attempt == 2) ", stopping until asked again" else "")
                } catch (e: NotEnoughSpace) {
                    _problem.value = e.userMessage()
                    Log.w("Octo", "phone scan not saved: not enough space")
                    return
                } catch (e: SQLiteException) {
                    if (e is SQLiteFullException) _problem.value = e.userMessage()
                    Log.w("Octo", "phone scan not saved: ${e.javaClass.simpleName}")
                    return
                }
            }
        } finally {
            _scanning.value = false
            _scanned.value = true
        }
    }

    private fun granted() =
        ContextCompat.checkSelfPermission(context, permissionName) == PackageManager.PERMISSION_GRANTED
}
