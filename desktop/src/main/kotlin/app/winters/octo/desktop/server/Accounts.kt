package app.winters.octo.desktop.server

import app.winters.octo.connection.LEGACY_UNSAFE_MESSAGE
import app.winters.octo.connection.LegacyRetry
import app.winters.octo.connection.ServerHeader
import app.winters.octo.connection.cleanHeaders
import app.winters.octo.connection.fingerprint
import app.winters.octo.connection.isHeaderName
import app.winters.octo.connection.isHeaderValue
import app.winters.octo.connection.legacyRetry
import app.winters.octo.connection.mask
import app.winters.octo.data.HeaderDraft
import app.winters.octo.data.resolveHeaders
import app.winters.octo.desktop.secrets.SecretStore
import app.winters.octo.desktop.secrets.SecretStoreException
import app.winters.octo.desktop.secrets.secretAccount
import app.winters.octo.desktop.settings.SavedServer
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.subsonic.AuthMode
import app.winters.octo.subsonic.Credentials
import app.winters.octo.subsonic.Extension
import app.winters.octo.subsonic.OCTO_ACQUISITIONS
import app.winters.octo.subsonic.ServerInfo
import app.winters.octo.subsonic.SubsonicClient
import app.winters.octo.subsonic.SubsonicException
import app.winters.octo.subsonic.isPrivateHost
import app.winters.octo.subsonic.normalizeServerUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

// The OpenSubsonic extensions the desktop app looks for.
const val SONG_LYRICS = "songLyrics"
const val OCTO_LYRICS = "octoLyrics"

// The OpenSubsonic extension a server lists when it takes API keys.
const val API_KEY_EXTENSION = "apiKeyAuthentication"

// How long the home address gets to answer before the main one is used.
private const val HOME_PING_SECONDS = 2L

// The signed-in server: the client that talks to it, and what it said
// about itself when signing in.
class Connection(
    val client: SubsonicClient,
    val server: SavedServer,
    // The server's extra headers, which songs the audio engine fetches
    // carry too.
    val headers: Map<String, String> = emptyMap(),
) {
    fun supports(name: String, version: Int = 1): Boolean = "$name:$version" in server.extensions

    // Octo lists its own extensions; anything else is a plain Subsonic
    // server, and the app uses nothing beyond the standard calls there.
    val isOcto: Boolean get() = server.extensions.any { it.startsWith("octo") }

    // Whether the server can fetch songs found online into the library and
    // say how that is going.
    val acquires: Boolean get() = supports(OCTO_ACQUISITIONS)

    val lyricsByIdOn: Boolean get() = supports(SONG_LYRICS)
}

// What testing a connection found: which server, which version, and the
// extensions it lists.
class ServerFacts(val info: ServerInfo, val extensions: List<Extension>) {
    val extensionKeys: List<String> get() = extensions.flatMap { ext -> ext.versions.map { "${ext.name}:$it" } }.sorted()

    // A plain sentence about the server, for the sign-in page.
    fun summary(): String {
        val name = listOfNotNull(info.type?.replaceFirstChar { it.uppercase() }, info.serverVersion).joinToString(" ").ifEmpty { "a Subsonic server" }
        val octo = extensions.any { it.name.startsWith("octo") }
        val extra = when {
            !info.openSubsonic -> "It speaks plain Subsonic."
            extensions.isEmpty() -> "It speaks OpenSubsonic with no extensions."
            octo -> "It speaks OpenSubsonic with ${extensions.size} extensions, including Octo's."
            else -> "It speaks OpenSubsonic with ${extensions.size} extensions."
        }
        return "Connected to $name. $extra"
    }
}

// What the sign-in page sends. The secret is the password, or the key
// when signing in with an API key. A saved header left without a value
// keeps its saved value.
class SignInRequest(
    val address: String,
    val username: String,
    val secret: String,
    val mode: AuthMode = AuthMode.Token,
    val home: String = "",
    val headers: List<HeaderDraft> = emptyList(),
    val rememberPassword: Boolean = true,
) {
    override fun toString() = "SignInRequest(address=$address, username=$username, secret=${mask(secret)}, mode=$mode)"
}

sealed interface SignInOutcome {
    // Signed in. `remembered` says whether the password went into the
    // system's store; when it could not, `note` says why in plain words.
    class Done(val connection: Connection, val remembered: Boolean, val note: String? = null) : SignInOutcome

    class Failed(val message: String) : SignInOutcome

    // The server showed a certificate the system does not trust.
    class Untrusted(val question: CertificateQuestion) : SignInOutcome
}

sealed interface TestOutcome {
    // `mode` is the way of signing in that worked, which may be the
    // password itself for a server that cannot check tokens.
    class Reached(val facts: ServerFacts, val mode: AuthMode = AuthMode.Token) : TestOutcome
    class Failed(val message: String) : TestOutcome
    class Untrusted(val question: CertificateQuestion) : TestOutcome
}

// Signing in to a server, remembering it, and signing out. The address,
// username, home address and header names are kept in the settings file;
// the password (or key) and the header values go only to the system's
// password store, or stay in memory for this run.
class Accounts(
    private val settings: SettingsStore,
    private val secrets: SecretStore,
    private val http: OkHttpClient,
    // Headers and trusted certificates for the shared client. Only a client
    // built with `security.install` checks the trusted certificates.
    val security: ServerSecurity = ServerSecurity(settings),
) {
    // A quick client for asking whether the home address answers.
    private val quick: OkHttpClient by lazy { http.newBuilder().callTimeout(HOME_PING_SECONDS, TimeUnit.SECONDS).build() }

    // What a try at a server found, or why it failed.
    private sealed interface Attempt {
        class Failed(val message: String) : Attempt
        class Untrusted(val question: CertificateQuestion) : Attempt
        class Reached(
            val main: HttpUrl,
            val home: HttpUrl?,
            val credentials: Credentials,
            val headers: Map<String, String>,
            val facts: ServerFacts,
        ) : Attempt
    }

    suspend fun test(address: String, username: String, password: String, mode: AuthMode = AuthMode.Token): TestOutcome =
        test(SignInRequest(address, username, password, mode))

    // Asks the server who it is and which extensions it lists, without
    // keeping anything.
    suspend fun test(request: SignInRequest): TestOutcome = when (val tried = attempt(request)) {
        is Attempt.Failed -> TestOutcome.Failed(tried.message)
        is Attempt.Untrusted -> TestOutcome.Untrusted(tried.question)
        is Attempt.Reached -> TestOutcome.Reached(tried.facts, tried.credentials.mode)
    }

    suspend fun signIn(address: String, username: String, password: String, mode: AuthMode = AuthMode.Token): SignInOutcome =
        signIn(SignInRequest(address, username, password, mode))

    // Tests the connection, then keeps it: the secrets in the system's
    // store and the rest in the settings. A store that refuses does not stop
    // the sign-in; the password is then asked for again next time. With
    // `rememberPassword` off the password is only kept while the app runs.
    suspend fun signIn(request: SignInRequest): SignInOutcome {
        val reached = when (val tried = attempt(request)) {
            is Attempt.Failed -> return SignInOutcome.Failed(tried.message)
            is Attempt.Untrusted -> return SignInOutcome.Untrusted(tried.question)
            is Attempt.Reached -> tried
        }
        val facts = reached.facts
        val secret = request.secretFor()
        val saved = SavedServer(
            address = reached.main.toString(),
            username = reached.credentials.username,
            authMode = reached.credentials.mode,
            home = reached.home?.toString(),
            headerNames = reached.headers.keys.toList(),
            rememberSignIn = request.rememberPassword,
            serverType = facts.info.type,
            serverVersion = facts.info.serverVersion,
            openSubsonic = facts.info.openSubsonic,
            extensions = facts.extensionKeys,
        )
        val old = settings.current.server
        val note = withContext(keychain) {
            // A different server or user replaces the old one, whose secrets go.
            if (old != null && (old.address != saved.address || old.username != saved.username)) {
                runCatching { secrets.delete(secretAccount(old.username, old.address)) }
            }
            if (old != null && old.address != saved.address) runCatching { secrets.delete(headersAccount(old.address)) }
            val headersNote = keepHeaders(saved.address, reached.headers)
            val passwordNote = if (!request.rememberPassword) {
                // Kept only in memory, so any copy in the store goes.
                runCatching { secrets.delete(secretAccount(saved.username, saved.address)) }
                null
            } else {
                try {
                    secrets.write(secretAccount(saved.username, saved.address), secret)
                    if (secrets.lasting) null else "This computer has no password store, so Octo will ask for the password each time it opens."
                } catch (e: SecretStoreException) {
                    "Octo couldn't save the password (${e.message}), so it will ask for it next time."
                }
            }
            passwordNote ?: headersNote
        }
        settings.update { it.copy(server = saved) }
        memory = secret
        memoryHeaders = reached.headers
        val connection = activate(saved, secret, reached.headers)
        return SignInOutcome.Done(connection, remembered = request.rememberPassword && note == null, note = note)
    }

    // The saved server, ready to use, or null when there is none or its
    // password is not in the store. Nothing is asked of the server here.
    // The store can wait on the listener (a locked keyring asks to be
    // unlocked), so this is called before the window opens, not on it.
    fun restore(): Connection? {
        val saved = settings.current.server ?: return null
        val password = memory ?: runCatching { secrets.read(secretAccount(saved.username, saved.address)) }.getOrNull() ?: return null
        val headers = memoryHeaders ?: readHeaders(saved) ?: return null
        return activate(saved, password, headers)
    }

    // The server signed in to last, for filling in the sign-in page.
    val last: SavedServer? get() = settings.current.server

    // Forgets the server at once, and its secrets off the window's thread.
    suspend fun signOut() {
        val old = settings.current.server
        memory = null
        memoryHeaders = null
        route?.close()
        route = null
        security.clear()
        settings.update { it.copy(server = null) }
        if (old != null) {
            withContext(keychain) {
                runCatching { secrets.delete(secretAccount(old.username, old.address)) }
                runCatching { secrets.delete(headersAccount(old.address)) }
            }
        }
    }

    // Whether the sign-in outlasts this run of Octo, for the settings page.
    val remembersSignIn: Boolean
        get() = settings.current.server?.rememberSignIn != false && secrets.lasting

    // The password and headers of this run, for a store that cannot keep
    // them or a password that is not to be remembered.
    @Volatile private var memory: String? = null
    @Volatile private var memoryHeaders: Map<String, String>? = null

    // Picks the address in use when the server has a home one.
    @Volatile private var route: HomeRoute? = null

    // The system's store is slow at times, and can wait on the listener,
    // so it is only used off the window's thread, one call at a time so a
    // sign-out's delete cannot land after the next sign-in's write.
    private val keychain = Dispatchers.IO.limitedParallelism(1)

    private fun SignInRequest.secretFor(): String = if (mode == AuthMode.ApiKey) secret.trim() else secret

    // Tries the server the way the request asks. A server that cannot check
    // tokens is tried again with the password itself where that is safe.
    private suspend fun attempt(request: SignInRequest, mode: AuthMode = request.mode): Attempt {
        val main = normalizeServerUrl(request.address) ?: return Attempt.Failed("That doesn't look like a server address.")
        val home = if (request.home.isBlank()) {
            null
        } else {
            val url = normalizeServerUrl(request.home) ?: return Attempt.Failed("The home network address doesn't look like a server address.")
            url.takeIf { it != main }
        }
        val secret = request.secretFor()
        if (request.mode == AuthMode.ApiKey) {
            if (secret.isEmpty()) return Attempt.Failed("Enter the API key.")
        } else if (request.username.isBlank() || secret.isEmpty()) {
            return Attempt.Failed("Enter a username and password.")
        }
        // A header row left completely empty is ignored; a half-filled or
        // malformed one is pointed out rather than dropped.
        val unusable = request.headers.any { row ->
            val name = row.name.trim()
            val value = row.value.trim()
            (name.isNotEmpty() || value.isNotEmpty()) &&
                (!isHeaderName(name) || !isHeaderValue(value) || (value.isEmpty() && !row.saved))
        }
        if (unusable) return Attempt.Failed("Each header needs a name with no spaces and a plain text value.")
        val saved = if (request.headers.any { it.saved }) withContext(keychain) { savedHeaders(main) } else emptyList()
        val headers = cleanHeaders(resolveHeaders(request.headers, saved)).associate { it.name to it.value }
        var creds = Credentials(request.username.trim(), secret, mode)

        val (reachedUrl, info) = try {
            reach(main, home, creds, headers)
        } catch (e: SubsonicException) {
            untrusted(e, main, home)?.let { return Attempt.Untrusted(it) }
            return when (legacyRetry(e, mode, main)) {
                LegacyRetry.Retry -> attempt(request, AuthMode.LegacyPassword)
                LegacyRetry.Unsafe -> Attempt.Failed(LEGACY_UNSAFE_MESSAGE)
                LegacyRetry.None -> Attempt.Failed(e.userMessage() + homeHttpsHint(main, e))
            }
        }
        // A home address with a certificate the system does not trust is
        // asked about now, while the listener is here to answer.
        if (home != null && reachedUrl != home) {
            val homeError = runCatching { SubsonicClient(home, creds, quick, headers = headers).ping() }.exceptionOrNull()
            if (homeError is SubsonicException) untrusted(homeError, home)?.let { return Attempt.Untrusted(it) }
        }
        val client = SubsonicClient(reachedUrl, creds, http, headers = headers)
        // With an API key the server says whose it is.
        if (request.mode == AuthMode.ApiKey) {
            val owner = runCatching { client.tokenInfo() }.getOrNull()
            if (!owner.isNullOrBlank()) creds = creds.named(owner)
        }
        val extensions = if (info.openSubsonic) runCatching { client.extensions() }.getOrDefault(emptyList()) else emptyList()
        return Attempt.Reached(main, home, creds, headers, ServerFacts(info, extensions))
    }

    // Pings the main address. When that cannot connect and there is a home
    // address, pings that instead: at home the main address may not loop back.
    private suspend fun reach(main: HttpUrl, home: HttpUrl?, creds: Credentials, headers: Map<String, String>): Pair<HttpUrl, ServerInfo> =
        try {
            main to SubsonicClient(main, creds, http, headers = headers).ping()
        } catch (e: SubsonicException.Unreachable) {
            if (home == null || failedOnTls(e)) throw e
            val info = runCatching { SubsonicClient(home, creds, http, headers = headers).ping() }.getOrNull() ?: throw e
            home to info
        }

    // A failure caused by a certificate the system does not trust, as a
    // question for the listener. Null for any other failure.
    private fun untrusted(e: SubsonicException, vararg urls: HttpUrl?): CertificateQuestion? {
        if (e !is SubsonicException.Unreachable || !failedOnTls(e)) return null
        for (url in urls.filterNotNull()) {
            val leaf = security.takeRejected(url.host) ?: continue
            return CertificateQuestion(url.host, leaf.fingerprint())
        }
        return null
    }

    // Makes the connection: the shared client set up for the server, the
    // home address watched when there is one, and a client whose calls
    // follow it.
    private fun activate(saved: SavedServer, secret: String, headers: Map<String, String>): Connection {
        val main = saved.address.toHttpUrlOrNull() ?: normalizeServerUrl(saved.address)!!
        val home = saved.home?.toHttpUrlOrNull()?.takeIf { it != main }
        val creds = Credentials(saved.username, secret, saved.authMode)
        route?.close()
        val chooser = home?.let { HomeRoute(main, it, { url -> answersAt(url, creds, headers) }) }
        route = chooser
        security.configure(listOfNotNull(main, home), headers)
        security.onConnectionError = chooser?.let { it::connectionFailed }
        // Open connections were made for the old server; start fresh.
        http.connectionPool.evictAll()
        // "Octo" as the client name, as the phone app sends: Octo then
        // serves covers of songs found online plain, without its mark.
        val client = SubsonicClient(main, creds, http, clientName = "Octo", route = { chooser?.activeUrl() ?: main })
        return Connection(client, saved, headers)
    }

    // Whether the home address answers like the server does.
    private suspend fun answersAt(url: HttpUrl, creds: Credentials, headers: Map<String, String>): Boolean = try {
        SubsonicClient(url, creds, quick, headers = headers).ping()
        true
    } catch (e: SubsonicException) {
        // An error from the server itself still means it is there.
        e !is SubsonicException.Unreachable && e !is SubsonicException.NotSubsonic
    }

    // The headers saved for this server, for values left unchanged.
    private fun savedHeaders(main: HttpUrl): List<ServerHeader> {
        val saved = settings.current.server?.takeIf { it.address == main.toString() } ?: return emptyList()
        return (memoryHeaders ?: readHeaders(saved)).orEmpty().map { (name, value) -> ServerHeader(name, value) }
    }

    // The saved server's header values from the store, or null when it has
    // headers and the store no longer holds them.
    private fun readHeaders(saved: SavedServer): Map<String, String>? {
        if (saved.headerNames.isEmpty()) return emptyMap()
        val text = runCatching { secrets.read(headersAccount(saved.address)) }.getOrNull() ?: return null
        return runCatching { headersJson.decodeFromString(headerList, text) }.getOrNull()?.associate { it.name to it.value }
    }

    // Puts the header values in the store, or takes them out when there are
    // none. A note when the store refuses; they are then kept for this run.
    private fun keepHeaders(address: String, headers: Map<String, String>): String? {
        if (headers.isEmpty()) {
            runCatching { secrets.delete(headersAccount(address)) }
            return null
        }
        val list = headers.map { (name, value) -> ServerHeader(name, value) }
        return try {
            secrets.write(headersAccount(address), headersJson.encodeToString(headerList, list))
            null
        } catch (e: SecretStoreException) {
            "Octo couldn't save the server's headers (${e.message}), so it will ask for them next time."
        }
    }
}

// Whether a failure to connect came from the secure handshake. With a name
// that has several addresses, the handshake's failure can sit behind
// another address's refusal, so every cause and suppressed one counts.
fun failedOnTls(e: Throwable?): Boolean {
    if (e == null) return false
    if (e is SSLException) return true
    return failedOnTls(e.cause.takeIf { it !== e }) || e.suppressed.any { failedOnTls(it) }
}

// Where a server's header values are filed in the system's store.
fun headersAccount(address: String): String = "headers@$address"

private val headersJson = Json { ignoreUnknownKeys = true }
private val headerList = ListSerializer(ServerHeader.serializer())

// A hint for an https address on the home network that could not be
// reached: servers at home often speak only plain http.
fun homeHttpsHint(url: HttpUrl, e: Throwable): String =
    if (e is SubsonicException.Unreachable && url.isHttps && isPrivateHost(url)) " Home servers often use http://. Switch to http:// and try again." else ""

// What to tell someone when a request fails, in the phone app's words.
fun Throwable.userMessage(): String = when (this) {
    is SubsonicException.Unreachable ->
        "Couldn't reach the server. Check the address, and that you're on the right network."
    is SubsonicException.NotSubsonic ->
        if (serverBusy) "The server isn't answering right now. It may be restarting, so try again in a moment."
        else "That address answered, but not like a music server. Check the port."
    is SubsonicException.WrongCredentials -> "Wrong username or password."
    is SubsonicException.AuthNotSupported ->
        if (code == 41) "This account can't sign in with a token. Turn on Legacy sign-in under Advanced."
        else "The server doesn't take this way of signing in."
    is SubsonicException.NotFound -> "That isn't on the server any more."
    is SubsonicException.Server -> "The server said: $message"
    else -> "Something went wrong."
}
