package app.winters.octo.desktop

import app.winters.octo.desktop.audio.AudioEngine
import app.winters.octo.desktop.audio.NativeAudioEngine
import app.winters.octo.desktop.secrets.SecretStore
import app.winters.octo.desktop.secrets.secretsForRun
import app.winters.octo.desktop.server.Accounts
import app.winters.octo.desktop.server.Connection
import app.winters.octo.desktop.server.ServerSecurity
import app.winters.octo.desktop.server.certificateAccountFor
import app.winters.octo.desktop.server.desktopUserAgent
import app.winters.octo.desktop.server.userAgentOf
import app.winters.octo.desktop.settings.AppPlaces
import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.desktop.settings.separateProfile
import app.winters.octo.desktop.settings.systemReducesMotion
import app.winters.octo.desktop.settings.systemTextScale
import app.winters.octo.desktop.update.DesktopUpdates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

// What the app is made from once its window is up (see Startup).
class StartupParts(
    val http: OkHttpClient,
    val accounts: Accounts,
    // The saved server, read from the password store.
    val restored: Connection?,
    val updates: DesktopUpdates,
    // The system's "Animation effects" and text size.
    val systemCalm: Boolean,
    val systemText: Float,
    // The audio engine, or why it could not start.
    val engine: Result<AudioEngine>,
)

// The slow part of starting, on threads of its own beside the window
// rather than before it: the HTTP client (its certificates take a while),
// the saved sign-in from the password store (which can wait on the
// listener, a locked keyring asking to be unlocked), the updater, the
// system's motion and text settings, and the audio engine's library. The
// window shows the page colour meanwhile, and the app is made once these
// are in (Main.kt).
class Startup(
    settings: SettingsStore,
    places: AppPlaces,
    os: DesktopOs,
    // Opens the password store; the tests give a pretend one. It comes
    // before openEngine, so a call that gives the engine as a trailing
    // lambda still opens the engine.
    openSecrets: () -> SecretStore = { SecretStore.forSystem(os) },
    // Opens the engine; the tests give a pretend one.
    openEngine: () -> AudioEngine = { NativeAudioEngine.open() },
) {
    private val engine = CompletableFuture.supplyAsync({ runCatching(openEngine) }, thread("octo-start-engine"))

    private val rest = CompletableFuture.supplyAsync({
        // One client for everything, set up for the signed-in server's
        // headers and the certificates the listener trusted. Each check of a
        // server starts from the same name and limits, with a security of
        // its own.
        val security = ServerSecurity(settings)
        val userAgent = desktopUserAgent(os = os)
        val base = {
            OkHttpClient.Builder().addInterceptor(userAgentOf(userAgent))
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
        }
        val http = security.install(base()).build()
        // The computer's name can take a moment to read; read it here.
        security.deviceHeaders()
        // A read-only run (Open without my settings) keeps every change to
        // the password store in memory, chosen once here; a run with a
        // folder of its own files its certificate items apart.
        val accounts = Accounts(
            settings,
            secretsForRun(settings, openSecrets()),
            http,
            security,
            base,
            certificateAccount = certificateAccountFor(if (separateProfile()) places.config else null, os),
        )
        val restored = accounts.restore()
        Rest(http, accounts, restored, DesktopUpdates.forThisApp(settings, places.cache, os), systemReducesMotion(os), systemTextScale(os))
    }, thread("octo-start"))

    private class Rest(val http: OkHttpClient, val accounts: Accounts, val restored: Connection?, val updates: DesktopUpdates, val calm: Boolean, val text: Float)

    // Everything, when it is ready.
    suspend fun parts(): StartupParts = withContext(Dispatchers.IO) { now() }

    // The same, waiting here.
    fun now(): StartupParts {
        val made = try {
            rest.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
        return StartupParts(made.http, made.accounts, made.restored, made.updates, made.calm, made.text, engine.get())
    }

    private companion object {
        fun thread(name: String) = Executor { work -> Thread(work, name).apply { isDaemon = true }.start() }
    }
}
