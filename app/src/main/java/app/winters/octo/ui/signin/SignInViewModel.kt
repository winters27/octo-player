package app.winters.octo.ui.signin

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.winters.octo.connection.LEGACY_UNSAFE_MESSAGE
import app.winters.octo.connection.LegacyRetry
import app.winters.octo.connection.isHeaderName
import app.winters.octo.connection.isHeaderValue
import app.winters.octo.connection.legacyRetry
import app.winters.octo.connection.pinKey
import app.winters.octo.data.HeaderDraft
import app.winters.octo.data.SessionRepository
import app.winters.octo.data.SignInError
import app.winters.octo.data.SignInRequest
import app.winters.octo.data.userMessage
import app.winters.octo.subsonic.AuthMode
import app.winters.octo.subsonic.isPrivateHost
import app.winters.octo.subsonic.normalizeServerUrl
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SignInViewModel @Inject constructor(private val sessions: SessionRepository) : ViewModel() {
    var address by mutableStateOf("")
    var username by mutableStateOf("")
    var password by mutableStateOf("")
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    // True once signed in, so the screen can close. The library copy
    // starts on its own.
    var signedIn by mutableStateOf(false)
        private set

    // Changing the saved connection rather than signing in fresh. Secrets
    // left empty keep their saved values.
    var editing by mutableStateOf(false)
        private set

    // The advanced settings, folded away unless something in them is set.
    var advancedOpen by mutableStateOf(false)
    var legacyPassword by mutableStateOf(false)
    var useApiKey by mutableStateOf(false)
    var apiKey by mutableStateOf("")
    var home by mutableStateOf("")
    val headers = mutableStateListOf<HeaderDraft>()

    // The client certificate's name in the phone's store, once picked.
    var clientCert by mutableStateOf<String?>(null)

    // Whether the server takes API keys: null until known, which is before
    // signing in, when a key may still be tried.
    var takesApiKeys by mutableStateOf<Boolean?>(null)
        private set

    // A certificate the phone does not trust, waiting for the user's answer.
    var question by mutableStateOf<SignInError.Untrusted?>(null)
        private set

    // Certificates the user trusted, by host.
    private val pins = HashMap<String, String>()

    // Whether a secret is saved for each way of signing in, when editing.
    private var savedPassword = false
    private var savedKey = false

    // Plain http to an address outside the home network.
    val insecure by derivedStateOf {
        val url = normalizeServerUrl(address)
        url != null && !url.isHttps && !isPrivateHost(url)
    }

    // Starts from the saved connection. Nothing secret is filled in.
    fun startEditing() {
        if (editing) return
        val draft = sessions.connectionDraft() ?: return
        editing = true
        address = draft.address
        username = draft.username
        legacyPassword = draft.authMode == AuthMode.LegacyPassword
        useApiKey = draft.authMode == AuthMode.ApiKey
        home = draft.home
        headers.clear()
        headers.addAll(draft.headers)
        clientCert = draft.clientCertAlias
        pins.putAll(draft.pins)
        takesApiKeys = draft.takesApiKeys
        savedKey = useApiKey
        savedPassword = !useApiKey
        advancedOpen = draft.authMode != AuthMode.Token || draft.home.isNotEmpty() ||
            draft.headers.isNotEmpty() || draft.clientCertAlias != null
    }

    fun addHeader() {
        headers.add(HeaderDraft("", ""))
    }

    fun removeHeader(index: Int) {
        if (index in headers.indices) headers.removeAt(index)
    }

    // A renamed saved header no longer keeps its saved value.
    fun setHeaderName(index: Int, name: String) {
        val old = headers.getOrNull(index) ?: return
        headers[index] = old.copy(name = name, saved = old.saved && name.trim().equals(old.name.trim(), ignoreCase = true))
    }

    fun setHeaderValue(index: Int, value: String) {
        val old = headers.getOrNull(index) ?: return
        headers[index] = old.copy(value = value)
    }

    private val mode: AuthMode
        get() = when {
            useApiKey -> AuthMode.ApiKey
            legacyPassword -> AuthMode.LegacyPassword
            else -> AuthMode.Token
        }

    // Whether the form has what the chosen way of signing in needs.
    val ready: Boolean
        get() = address.isNotBlank() && when (mode) {
            AuthMode.ApiKey -> apiKey.isNotBlank() || (editing && savedKey)
            else -> username.isNotBlank() && (password.isNotEmpty() || (editing && savedPassword))
        }

    fun submit() {
        if (busy || !ready) return
        // A header row left completely empty is ignored; a half-filled or
        // malformed one is pointed out rather than dropped.
        val unusable = headers.any { row ->
            val name = row.name.trim()
            val value = row.value.trim()
            (name.isNotEmpty() || value.isNotEmpty()) &&
                (!isHeaderName(name) || !isHeaderValue(value) || (value.isEmpty() && !row.saved))
        }
        if (unusable) {
            error = "Each header needs a name with no spaces and a plain text value."
            return
        }
        busy = true
        error = null
        val request = SignInRequest(
            address = address,
            username = username,
            secret = if (mode == AuthMode.ApiKey) apiKey.trim() else password,
            authMode = mode,
            home = home,
            headers = headers.toList(),
            pins = pins.toMap(),
            clientCertAlias = clientCert,
        )
        viewModelScope.launch {
            when (val failed = sessions.signIn(request)) {
                null -> {
                    // Once in, the secrets are kept only in the sealed vault.
                    password = ""
                    apiKey = ""
                    signedIn = true
                }
                is SignInError.Untrusted -> question = failed
                else -> {
                    // A server that cannot check tokens gets the password
                    // itself where that is safe, and the switch shows it.
                    val retry = (failed as? SignInError.Failed)?.cause
                    val url = normalizeServerUrl(address)
                    when (if (url == null) LegacyRetry.None else legacyRetry(retry, mode, url)) {
                        LegacyRetry.Retry -> {
                            legacyPassword = true
                            busy = false
                            submit()
                            return@launch
                        }
                        LegacyRetry.Unsafe -> error = LEGACY_UNSAFE_MESSAGE
                        LegacyRetry.None -> error = failed.userMessage()
                    }
                }
            }
            busy = false
        }
    }

    // Trusts the certificate asked about, for its host only, and tries again.
    fun trust() {
        val asked = question ?: return
        pins[pinKey(asked.host)] = asked.fingerprint
        question = null
        submit()
    }

    fun distrust() {
        question = null
        error = "Not connected. The server's certificate wasn't trusted."
    }
}
