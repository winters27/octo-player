package app.winters.octo.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import app.winters.octo.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

// What the About page shows of the updater.
data class UpdateState(
    // Why the updater is off, or null when it runs.
    val off: String? = null,
    val checking: Boolean = false,
    // A newer version found but not downloaded yet (on mobile data).
    val available: UpdateCheck.Available? = null,
    // A newer version downloaded and checked, ready to install.
    val ready: UpdateCheck.Ready? = null,
    // The last check or install in a line, said quietly.
    val line: String? = null,
)

// Whether this build looks for updates, and if not, why not. Only the
// release build does: a debug build is a different app (app.winters.octo.debug)
// that the release could never update. -Pocto.updates.force=true builds
// one that tries anyway.
fun phoneUpdaterOff(debug: Boolean, forced: Boolean, version: String, hasKeys: Boolean): String? = when {
    PlayerVersion.parse(version) == null -> "This is a test build, so it doesn't update itself."
    !hasKeys -> "This build has no key to check updates with."
    forced -> null
    debug -> "This is a test build, so it doesn't update itself."
    else -> null
}

// The phone's updater: checks a while after Octo starts and every six
// hours while it runs, downloads on Wi-Fi (or when asked), checks the file
// against the release's signed manifest and this app's own signing key,
// and installs it through Android's package installer.
@Singleton
class AppUpdates @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: UpdateSettings,
) {
    private val keys = trustedKeys()
    private val running = PlayerVersion.parse(BuildConfig.VERSION_NAME)
    private val off = phoneUpdaterOff(BuildConfig.DEBUG, BuildConfig.UPDATES_FORCED, BuildConfig.VERSION_NAME, keys.isNotEmpty())
    private val folder = File(context.cacheDir, "updates")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow(UpdateState(off = off))
    val state: StateFlow<UpdateState> = _state

    private val updater: PlayerUpdater? = running?.takeIf { off == null }?.let { version ->
        // A client of its own: the system's certificates only, never a
        // server's trusted ones or its headers.
        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        val api = BuildConfig.UPDATES_API.toHttpUrlOrNull() ?: GITHUB_API
        PlayerUpdater(
            PlayerApp.Android,
            version,
            ReleaseFeed(client, File(folder, "releases.json"), "Octo/$version (android)", apiBase = api),
            client,
            folder,
            keys,
            pick = ::apkFor,
        )
    }

    private var loop: Job? = null

    fun start() {
        if (updater == null || loop != null) return
        loop = scope.launch {
            delay(UpdateTiming.FIRST_CHECK_DELAY_MS)
            while (isActive) {
                val retryAt = if (settings.prefs.first().checkAutomatically) check(manual = false) else null
                delay(UpdateTiming.nextWait(System.currentTimeMillis(), retryAt))
            }
        }
    }

    // Checks now. By itself it downloads only on a network that is not
    // metered; asked by the listener it downloads anyway.
    fun checkNow() {
        scope.launch { check(manual = true) }
    }

    private suspend fun check(manual: Boolean): Long? {
        val updater = updater ?: return null
        if (_state.value.checking) return null
        _state.update { it.copy(checking = true) }
        val early = settings.prefs.first().earlyVersions
        val download = manual || !metered()
        val result = withContext(Dispatchers.IO) { updater.check(early, download) }
        val verified = if (result is UpdateCheck.Ready) withContext(Dispatchers.IO) { checkApk(result) } else result
        // Nothing newer: downloads kept from before (an early version no
        // longer wanted, say) go.
        if (verified is UpdateCheck.UpToDate) withContext(Dispatchers.IO) { folder.listFiles()?.filter(File::isDirectory)?.forEach { it.deleteRecursively() } }
        _state.update { current ->
            when (verified) {
                is UpdateCheck.Ready -> current.copy(ready = verified, available = null, line = null)
                is UpdateCheck.Available -> current.copy(available = verified, line = "Octo ${verified.version} is out. It downloads on Wi-Fi, or now if you check.")
                is UpdateCheck.UpToDate -> current.copy(ready = null, available = null, line = "Octo is up to date.")
                is UpdateCheck.NoInstaller -> current.copy(line = "Octo ${verified.version} is out, but not yet for phones.")
                is UpdateCheck.Refused -> current.copy(ready = null, available = null, line = "An update didn't pass its safety check, so it was deleted. Octo will look again later.")
                is UpdateCheck.Unavailable -> current.copy(line = "Couldn't check for updates just now. Octo will try again later.")
            }.copy(checking = false)
        }
        return (verified as? UpdateCheck.Unavailable)?.retryAt
    }

    // The APK must be this app, newer, and signed with this copy's key.
    private fun checkApk(ready: UpdateCheck.Ready): UpdateCheck {
        val pm = context.packageManager
        val installed = apkFacts(runCatching { pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES) }.getOrNull())
        val downloaded = apkFacts(pm.getPackageArchiveInfo(ready.file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES))
        val refusal = if (installed == null) "this Octo could not be read" else apkRefusal(installed, downloaded)
        if (refusal != null) {
            ready.file.parentFile?.deleteRecursively()
            Log.w("Octo", "update refused: $refusal")
            return UpdateCheck.Refused(refusal)
        }
        return ready
    }

    private fun metered(): Boolean =
        (context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.isActiveNetworkMetered ?: true

    // Whether Android lets Octo install apps; the first time it must be
    // allowed in the system's settings.
    fun mayInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    // The system's page for allowing Octo to install its updates.
    fun allowInstallsIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    // Hands the ready update to Android's installer. `asked` is true when
    // the listener tapped Install, so Android may show its confirmation;
    // when Octo is left with "When I leave Octo", only an install that needs
    // no confirmation goes ahead.
    fun install(asked: Boolean) {
        val ready = _state.value.ready ?: return
        if (!mayInstall()) return
        scope.launch {
            val failed = withContext(Dispatchers.IO) {
                try {
                    commit(ready.file, asked)
                    null
                } catch (e: IOException) {
                    e.message ?: "the installer could not read the file"
                } catch (e: SecurityException) {
                    e.message ?: "Android would not start the installer"
                }
            }
            if (failed != null) _state.update { it.copy(line = "The update couldn't start. It stays ready for the next try.") }
        }
    }

    // Octo was left: with "When I leave Octo", a ready update goes in if
    // nothing is playing.
    fun leaving(playing: Boolean) {
        if (playing || _state.value.ready == null) return
        scope.launch {
            if (settings.prefs.first().install == InstallWhen.OnQuit) install(asked = false)
        }
    }

    // What the installer said, from UpdateInstallReceiver.
    fun installFinished(status: Int, message: String?) {
        if (status == PackageInstaller.STATUS_SUCCESS || status == PackageInstaller.STATUS_PENDING_USER_ACTION) return
        Log.w("Octo", "update install ended with $status: $message")
        val line = if (status == PackageInstaller.STATUS_FAILURE_ABORTED) null else "The update didn't install. It stays ready for the next try."
        _state.update { it.copy(line = line ?: it.line) }
    }

    private fun commit(file: File, asked: Boolean) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            if (!asked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("octo.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val result = Intent(context, UpdateInstallReceiver::class.java).putExtra(UpdateInstallReceiver.EXTRA_ASKED, asked)
            // Mutable: the installer adds its status to it.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            session.commit(PendingIntent.getBroadcast(context, id, result, flags).intentSender)
        }
    }
}
