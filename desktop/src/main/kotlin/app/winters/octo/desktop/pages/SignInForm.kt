package app.winters.octo.desktop.pages

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.winters.octo.data.HeaderDraft
import app.winters.octo.desktop.server.API_KEY_EXTENSION
import app.winters.octo.desktop.server.CertificateQuestion
import app.winters.octo.desktop.server.Scheme
import app.winters.octo.desktop.server.SignInRequest
import app.winters.octo.desktop.server.automaticScheme
import app.winters.octo.desktop.server.serverUrl
import app.winters.octo.desktop.server.splitScheme
import app.winters.octo.desktop.settings.SavedServer
import app.winters.octo.subsonic.AuthMode
import app.winters.octo.subsonic.isPrivateHost
import okhttp3.HttpUrl

// What the sign-in page holds while someone fills it in, as the phone's
// sign-in keeps it: the address in two parts (the scheme, and the rest as
// typed), the sign-in, and the advanced settings. It starts from the
// server signed in to last, if any.
@Stable
class SignInForm(last: SavedServer? = null) {
    // The address without its scheme, as it shows in the field.
    var address by mutableStateOf("")
        private set
    var scheme by mutableStateOf(Scheme.Https)
        private set

    // Picked by hand (or typed, or remembered): no longer follows the address.
    private var schemePicked = false

    var username by mutableStateOf(last?.username.orEmpty())
    var password by mutableStateOf("")
    var rememberPassword by mutableStateOf(last?.rememberSignIn ?: true)

    // The advanced settings, folded away unless something in them is set.
    var advancedOpen by mutableStateOf(
        last != null && (last.authMode != AuthMode.Token || last.home != null || last.headerNames.isNotEmpty()),
    )
    var legacyPassword by mutableStateOf(last?.authMode == AuthMode.LegacyPassword)
    var useApiKey by mutableStateOf(last?.authMode == AuthMode.ApiKey)
    var apiKey by mutableStateOf("")
    var home by mutableStateOf(last?.home?.removeSuffix("/").orEmpty())
    val headers = mutableStateListOf<HeaderDraft>()

    var busy by mutableStateOf(false)

    // The last test or sign-in's answer: whether it worked, and what to say.
    var result by mutableStateOf<Pair<Boolean, String>?>(null)

    // A certificate the system does not trust, waiting for an answer.
    var question by mutableStateOf<CertificateQuestion?>(null)

    // The server's extensions from last time, when it is the one typed.
    private val lastServer = last

    init {
        if (last != null) {
            typeAddress(last.address.removeSuffix("/"))
            headers.addAll(last.headerNames.map { HeaderDraft(it, "", saved = true) })
        }
    }

    // Where focus starts: the password when the server and user are known.
    val startsAtPassword: Boolean get() = lastServer != null && username.isNotBlank() && !useApiKey

    // Takes what was typed or pasted into the address field. A full address
    // sets the scheme and leaves the rest; otherwise the scheme follows the
    // address until one is picked: http at home, https anywhere else.
    fun typeAddress(text: String) {
        val (typed, rest) = splitScheme(text)
        if (typed != null) {
            scheme = typed
            schemePicked = true
            address = rest
        } else {
            address = text
            if (!schemePicked) scheme = automaticScheme(text)
        }
        result = null
    }

    // Switches between https and http by hand.
    fun toggleScheme() {
        scheme = if (scheme == Scheme.Https) Scheme.Http else Scheme.Https
        schemePicked = true
        result = null
    }

    val url: HttpUrl? get() = serverUrl(scheme, address)

    // Plain http to an address outside the home network.
    val insecure: Boolean get() = url?.let { !it.isHttps && !isPrivateHost(it) } == true

    // Whether the server takes API keys: null until known, which is before
    // signing in to it, when a key may still be tried.
    val takesApiKeys: Boolean?
        get() {
            val last = lastServer ?: return null
            if (url?.toString() != last.address) return null
            return last.extensions.any { it.startsWith("$API_KEY_EXTENSION:") }
        }

    val mode: AuthMode
        get() = when {
            useApiKey -> AuthMode.ApiKey
            legacyPassword -> AuthMode.LegacyPassword
            else -> AuthMode.Token
        }

    // Whether the form has what the chosen way of signing in needs.
    val ready: Boolean
        get() = !busy && url != null && when (mode) {
            AuthMode.ApiKey -> apiKey.isNotBlank()
            else -> username.isNotBlank() && password.isNotEmpty()
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

    fun request(): SignInRequest = SignInRequest(
        address = url?.toString() ?: (scheme.prefix + address.trim()),
        username = username,
        secret = if (mode == AuthMode.ApiKey) apiKey else password,
        mode = mode,
        home = home,
        headers = headers.toList(),
        rememberPassword = rememberPassword,
    )

    // A way of signing in that worked other than the one asked for (the
    // password itself, for a server that cannot check tokens) shows in the
    // switch, and is the one saved.
    fun worked(used: AuthMode) {
        if (used == AuthMode.LegacyPassword && mode == AuthMode.Token) legacyPassword = true
    }

    fun distrust() {
        question = null
        result = false to "Not connected. The server's certificate wasn't trusted."
    }
}
