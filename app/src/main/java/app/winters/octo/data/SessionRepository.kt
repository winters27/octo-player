package app.winters.octo.data

import app.winters.octo.connection.ConnectionChooser
import app.winters.octo.connection.ConnectionSecurity
import app.winters.octo.connection.ConnectionSettings
import app.winters.octo.connection.FolderChoice
import app.winters.octo.connection.ServerHeader
import app.winters.octo.connection.StoredConnection
import app.winters.octo.connection.cleanHeaders
import app.winters.octo.connection.decodeConnection
import app.winters.octo.connection.encodeConnection
import app.winters.octo.connection.fingerprint
import app.winters.octo.connection.mask
import app.winters.octo.connection.opened
import app.winters.octo.connection.sealed
import app.winters.octo.server.serverSourceId
import app.winters.octo.subsonic.AuthMode
import app.winters.octo.subsonic.Credentials
import app.winters.octo.subsonic.MusicFolder
import app.winters.octo.subsonic.ServerInfo
import app.winters.octo.subsonic.SubsonicClient
import app.winters.octo.subsonic.SubsonicException
import app.winters.octo.subsonic.normalizeServerUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLException

// The OpenSubsonic extension a server lists when it takes API keys.
const val API_KEY_EXTENSION = "apiKeyAuthentication"

// How long the home address gets to answer before the main one is used.
private const val HOME_PING_SECONDS = 2L

class Session(
    val client: SubsonicClient,
    val serverType: String?,
    val serverVersion: String?,
    val isOcto: Boolean,
    val adminReachable: Boolean,
    val extensions: Set<String>,
    val connection: ConnectionSettings = ConnectionSettings(),
) {
    // The source the library is kept under: from the main address, whichever
    // address calls go to.
    val sourceId: String get() = serverSourceId(client.primaryUrl)

    val takesApiKeys: Boolean get() = extensions.any { it.startsWith("$API_KEY_EXTENSION:") }
}

sealed interface SessionState {
    data object Loading : SessionState
    data object SignedOut : SessionState
    class SignedIn(val session: Session) : SessionState
}

// What the sign-in form sends. The secret is the password, or the key when
// signing in with an API key. When editing the connection, an empty secret
// keeps the saved one, and so does a header left without a value.
class SignInRequest(
    val address: String,
    val username: String,
    val secret: String,
    val authMode: AuthMode = AuthMode.Token,
    val home: String = "",
    val headers: List<HeaderDraft> = emptyList(),
    val pins: Map<String, String> = emptyMap(),
    val clientCertAlias: String? = null,
) {
    override fun toString() = "SignInRequest(address=$address, username=$username, secret=${mask(secret)}, authMode=$authMode)"
}

// The saved connection, as the form starts from when editing it. No secret
// is in here: the password, key and header values stay sealed.
class ConnectionDraft(
    val address: String,
    val username: String,
    val authMode: AuthMode,
    val home: String,
    val headers: List<HeaderDraft>,
    val pins: Map<String, String>,
    val clientCertAlias: String?,
    val takesApiKeys: Boolean,
)

sealed interface SignInError {
    data object BadAddress : SignInError
    data object BadHomeAddress : SignInError

    // Editing the connection in a way that needs a secret not saved yet.
    data object MissingSecret : SignInError
    class Failed(val cause: SubsonicException) : SignInError

    // The server showed a certificate the phone does not trust. The user
    // may choose to trust exactly this one, for this host only.
    class Untrusted(val host: String, val fingerprint: String) : SignInError
}

@Singleton
class SessionRepository @Inject constructor(
    private val store: SessionStore,
    private val vault: CredentialVault,
    private val probe: OctoProbe,
    private val http: OkHttpClient,
    private val security: ConnectionSecurity,
    private val chooser: ConnectionChooser,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<SessionState>(SessionState.Loading)
    val state: StateFlow<SessionState> = _state

    // A quick client for asking whether the home address answers.
    private val quick: OkHttpClient by lazy { http.newBuilder().callTimeout(HOME_PING_SECONDS, TimeUnit.SECONDS).build() }

    // Changes to the session happen one at a time.
    private val changes = Mutex()

    // The sign-in in use, kept for rebuilding the client when a setting changes.
    @Volatile private var credentials: Credentials? = null

    init {
        scope.launch { _state.value = changes.withLock { restore() } }
    }

    suspend fun signIn(address: String, username: String, password: String): SignInError? =
        signIn(SignInRequest(address, username, password))

    // Returns null on success.
    suspend fun signIn(request: SignInRequest): SignInError? = changes.withLock {
        val main = normalizeServerUrl(request.address) ?: return SignInError.BadAddress
        val home = if (request.home.isBlank()) null
        else (normalizeServerUrl(request.home) ?: return SignInError.BadHomeAddress).takeIf { it != main }
        val current = (_state.value as? SessionState.SignedIn)?.session
        val secret = request.secret.ifEmpty { savedSecret(request.authMode) ?: return SignInError.MissingSecret }
        val settings = ConnectionSettings(
            home = home,
            authMode = request.authMode,
            headers = cleanHeaders(resolveHeaders(request.headers, current?.connection?.headers.orEmpty())),
            pins = request.pins,
            clientCertAlias = request.clientCertAlias,
            // The folder stays when only the way in changed.
            folder = current?.takeIf { it.client.primaryUrl == main }?.connection?.folder,
        )
        var creds = Credentials(request.username.trim(), secret, request.authMode)

        // Tries the connection with the new settings, and puts the old ones
        // back if it does not work.
        applySecurity(main, settings)
        val reached = try {
            reach(main, home, creds)
        } catch (e: SubsonicException) {
            // The certificate is read before the settings go back, which forgets it.
            val question = untrusted(e, main, home)
            if (current != null) applySecurity(current.client.primaryUrl, current.connection) else security.clear()
            return question ?: SignInError.Failed(e)
        }
        // A home address with a certificate the phone does not trust is
        // asked about now, while the user is here to answer.
        if (home != null && reached.url != home) {
            val homeError = runCatching { SubsonicClient(home, creds, quick).ping() }.exceptionOrNull()
            if (homeError is SubsonicException) {
                untrusted(homeError, home, null)?.let { question ->
                    if (current != null) applySecurity(current.client.primaryUrl, current.connection) else security.clear()
                    return question
                }
            }
        }

        val client = SubsonicClient(reached.url, creds, http)
        // With an API key the server says whose it is.
        if (request.authMode == AuthMode.ApiKey) {
            val owner = runCatching { client.tokenInfo() }.getOrNull()
            if (!owner.isNullOrBlank()) creds = creds.named(owner)
        }
        val extensions = runCatching { client.extensions() }.getOrDefault(emptyList())
            .flatMap { ext -> ext.versions.map { "${ext.name}:$it" } }.toSet()
        // The status page is the only Octo signal for now, so the two match.
        val admin = probe.adminReachable(reached.url)
        store.write(
            StoredSession(
                serverUrl = main.toString(),
                username = creds.username,
                passwordSealed = vault.seal(secret),
                serverType = reached.info.type,
                serverVersion = reached.info.serverVersion,
                isOcto = admin,
                octoAdminReachable = admin,
                extensions = extensions,
                connection = encodeConnection(settings.sealed(vault::seal)),
            ),
        )
        _state.value = SessionState.SignedIn(
            activate(main, settings, creds, reached.info.type, reached.info.serverVersion, admin, admin, extensions),
        )
        null
    }

    suspend fun signOut() = changes.withLock {
        store.clear()
        vault.forget()
        credentials = null
        chooser.clear()
        security.clear()
        _state.value = SessionState.SignedOut
    }

    // The library folders on the signed-in server.
    suspend fun musicFolders(): List<MusicFolder> {
        val session = (_state.value as? SessionState.SignedIn)?.session ?: return emptyList()
        return session.client.musicFolders()
    }

    // Limits the library to one folder, or null for all of them. The caller
    // copies the library again afterwards.
    suspend fun chooseMusicFolder(folder: FolderChoice?) = changes.withLock {
        val session = (_state.value as? SessionState.SignedIn)?.session ?: return@withLock
        val creds = credentials ?: return@withLock
        val settings = session.connection.copy(folder = folder)
        val saved = store.read() ?: return@withLock
        store.write(saved.copy(connection = encodeConnection(settings.sealed(vault::seal))))
        _state.value = SessionState.SignedIn(
            activate(
                session.client.primaryUrl,
                settings,
                creds,
                session.serverType,
                session.serverVersion,
                session.isOcto,
                session.adminReachable,
                session.extensions,
            ),
        )
    }

    // The saved connection for the edit form, with no secrets in it.
    fun connectionDraft(): ConnectionDraft? {
        val session = (_state.value as? SessionState.SignedIn)?.session ?: return null
        val connection = session.connection
        return ConnectionDraft(
            address = session.client.primaryUrl.toString().removeSuffix("/"),
            username = session.client.username,
            authMode = connection.authMode,
            home = connection.home?.toString()?.removeSuffix("/").orEmpty(),
            headers = connection.headers.map { HeaderDraft(it.name, "", saved = true) },
            pins = connection.pins,
            clientCertAlias = connection.clientCertAlias,
            takesApiKeys = session.takesApiKeys,
        )
    }

    private suspend fun restore(): SessionState {
        val saved = store.read() ?: return SessionState.SignedOut
        val secret = vault.open(saved.passwordSealed)
        val stored = saved.connection?.let { decodeConnection(it) } ?: StoredConnection()
        val settings = stored.opened(vault::open)
        if (secret == null || settings == null) {
            store.clear()
            return SessionState.SignedOut
        }
        val main = saved.serverUrl.toHttpUrl()
        applySecurity(main, settings)
        return SessionState.SignedIn(
            activate(
                main,
                settings,
                Credentials(saved.username, secret, settings.authMode),
                saved.serverType,
                saved.serverVersion,
                saved.isOcto,
                saved.octoAdminReachable,
                saved.extensions,
            ),
        )
    }

    // Makes a signed-in session: the network set up for the server, the
    // address chooser started, and a client whose calls follow it.
    private fun activate(
        main: HttpUrl,
        settings: ConnectionSettings,
        creds: Credentials,
        serverType: String?,
        serverVersion: String?,
        isOcto: Boolean,
        adminReachable: Boolean,
        extensions: Set<String>,
    ): Session {
        applySecurity(main, settings)
        credentials = creds
        chooser.use(main, settings.home) { url -> answersAt(url, creds) }
        val client = SubsonicClient(main, creds, http, musicFolderId = settings.folder?.id, route = { chooser.activeUrl(main) })
        return Session(client, serverType, serverVersion, isOcto, adminReachable, extensions, settings)
    }

    private fun applySecurity(main: HttpUrl, settings: ConnectionSettings) {
        security.configure(main, settings)
        // Open connections were made under the old trust; start fresh.
        http.connectionPool.evictAll()
    }

    // The saved secret, when it fits the way of signing in asked for: a
    // saved password serves token or legacy sign-in, a saved key only a key.
    private suspend fun savedSecret(mode: AuthMode): String? {
        val saved = store.read() ?: return null
        val stored = saved.connection?.let { decodeConnection(it) } ?: StoredConnection()
        val sameKind = (stored.authMode == AuthMode.ApiKey) == (mode == AuthMode.ApiKey)
        return if (sameKind) vault.open(saved.passwordSealed) else null
    }

    private class Reached(val url: HttpUrl, val info: ServerInfo)

    // Pings the main address. When that cannot connect and there is a home
    // address, pings that instead: at home the main address may not loop back.
    private suspend fun reach(main: HttpUrl, home: HttpUrl?, creds: Credentials): Reached = try {
        Reached(main, SubsonicClient(main, creds, http).ping())
    } catch (e: SubsonicException.Unreachable) {
        if (home == null || e.cause is SSLException) throw e
        val info = runCatching { SubsonicClient(home, creds, http).ping() }.getOrNull() ?: throw e
        Reached(home, info)
    }

    // Whether the home address answers like the server does.
    private suspend fun answersAt(url: HttpUrl, creds: Credentials): Boolean = try {
        SubsonicClient(url, creds, quick).ping()
        true
    } catch (e: SubsonicException) {
        // An error from the server itself still means it is there.
        e !is SubsonicException.Unreachable && e !is SubsonicException.NotSubsonic
    }

    // A failure caused by a certificate the phone does not trust, as a
    // question for the user. Null for any other failure.
    private fun untrusted(e: SubsonicException, vararg urls: HttpUrl?): SignInError.Untrusted? {
        if (e !is SubsonicException.Unreachable || e.cause !is SSLException) return null
        for (url in urls.filterNotNull()) {
            val leaf = security.takeRejected(url.host) ?: continue
            return SignInError.Untrusted(url.host, leaf.fingerprint())
        }
        return null
    }
}
