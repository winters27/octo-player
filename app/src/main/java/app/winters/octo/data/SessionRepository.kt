package app.winters.octo.data

import app.winters.octo.subsonic.Credentials
import app.winters.octo.subsonic.SubsonicClient
import app.winters.octo.subsonic.SubsonicException
import app.winters.octo.subsonic.normalizeServerUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

class Session(
    val client: SubsonicClient,
    val serverType: String?,
    val serverVersion: String?,
    val isOcto: Boolean,
    val adminReachable: Boolean,
    val extensions: Set<String>,
)

sealed interface SessionState {
    data object Loading : SessionState
    data object SignedOut : SessionState
    class SignedIn(val session: Session) : SessionState
}

sealed interface SignInError {
    data object BadAddress : SignInError
    class Failed(val cause: SubsonicException) : SignInError
}

@Singleton
class SessionRepository @Inject constructor(
    private val store: SessionStore,
    private val vault: CredentialVault,
    private val probe: OctoProbe,
    private val http: OkHttpClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<SessionState>(SessionState.Loading)
    val state: StateFlow<SessionState> = _state

    init {
        scope.launch { _state.value = restore() }
    }

    // Returns null on success.
    suspend fun signIn(address: String, username: String, password: String): SignInError? {
        val base = normalizeServerUrl(address) ?: return SignInError.BadAddress
        val client = SubsonicClient(base, Credentials(username.trim(), password), http)
        val info = try {
            client.ping()
        } catch (e: SubsonicException) {
            return SignInError.Failed(e)
        }
        val extensions = runCatching { client.extensions() }.getOrDefault(emptyList())
            .flatMap { ext -> ext.versions.map { "${ext.name}:$it" } }.toSet()
        // The status page is the only Octo signal for now, so the two match.
        val admin = probe.adminReachable(base)
        val session = Session(client, info.type, info.serverVersion, admin, admin, extensions)
        store.write(
            StoredSession(
                serverUrl = base.toString(),
                username = client.username,
                passwordSealed = vault.seal(password),
                serverType = info.type,
                serverVersion = info.serverVersion,
                isOcto = admin,
                octoAdminReachable = admin,
                extensions = extensions,
            ),
        )
        _state.value = SessionState.SignedIn(session)
        return null
    }

    suspend fun signOut() {
        store.clear()
        vault.forget()
        _state.value = SessionState.SignedOut
    }

    private suspend fun restore(): SessionState {
        val saved = store.read() ?: return SessionState.SignedOut
        val password = vault.open(saved.passwordSealed) ?: run {
            store.clear()
            return SessionState.SignedOut
        }
        val client = SubsonicClient(saved.serverUrl.toHttpUrl(), Credentials(saved.username, password), http)
        return SessionState.SignedIn(
            Session(
                client,
                saved.serverType,
                saved.serverVersion,
                saved.isOcto,
                saved.octoAdminReachable,
                saved.extensions,
            ),
        )
    }
}
