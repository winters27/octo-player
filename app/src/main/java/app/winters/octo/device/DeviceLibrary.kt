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
import app.winters.octo.catalog.CatalogDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

// Whether the app may read the phone's music.
enum class Access { Granted, NotAsked, Denied, DeniedForever }

// Keeps the catalog in step with the music on the phone: scans when
// access is granted, and again whenever the phone's media library changes.
@Singleton
class DeviceLibrary @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scanner: DeviceScanner,
    private val dao: CatalogDao,
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

    @OptIn(FlowPreview::class)
    fun start() {
        if (started) return
        started = true
        // A big copy fires many changes; wait for a quiet moment, then scan once.
        scope.launch { changes.debounce(2000).collect { rescan() } }
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
                val catalog = buildDeviceCatalog(scanner.read())
                dao.replaceSource(DEVICE, catalog.tracks, catalog.albums, catalog.artists)
                // Counts only, so the library can be checked against the phone.
                Log.i("Octo", "phone scan: ${catalog.tracks.size} tracks, ${catalog.albums.size} albums, ${catalog.artists.size} artists")
            } finally {
                _scanning.value = false
            }
        }
    }

    private fun granted() =
        ContextCompat.checkSelfPermission(context, permissionName) == PackageManager.PERMISSION_GRANTED
}
