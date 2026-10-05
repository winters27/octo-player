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
import app.winters.octo.desktop.settings.name
import app.winters.octo.server.PasswordChange
import app.winters.octo.server.changeOwnPassword
import app.winters.octo.server.freshServerId
import app.winters.octo.server.passwordRefusedWords
import app.winters.octo.server.switchFailedWords
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

// The OpenSubsonic extensions the desktop app looks for.
const val SONG_LYRICS = "songLyrics"
const val OCTO_LYRICS = "octoLyrics"

// The OpenSubsonic extension a server lists when it takes API keys.
const val API_KEY_EXTENSION = "apiKeyAuthentication"

// How long the home address gets to answer before the main one is used.
private const val HOME_PING_SECONDS = 2L

// How long a server not in use gets to answer a quick look.
private const val GLANCE_SECONDS = 6L

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
// keeps its saved value; when editing a kept server, an empty secret keeps
// its saved one.
class SignInRequest(
    val address: String,
    val username: String,
    val secret: String,
    val mode: AuthMode = AuthMode.Token,
    val home: String = "",
    val headers: List<HeaderDraft> = emptyList(),
    val rememberPassword: Boolean = true,
) {
    fun withSecret(secret: String) = SignInRequest(address, username, secret, mode, home, headers, rememberPassword)

    override fun toString() = "SignInRequest(address=$address, username=$username, secret=${mask(secret)}, mode=$mode)"
}

sealed interface SignInOutcome {
    // Signed in. `remembered` says whether the password went into the
    // system's store; when it could not, `note` says why in plain words.
    class Done(val connection: Connection, val remembered: Boolean, val note: String? = null) : SignInOutcome

    // Kept in the list without being switched to: a server added, or one
    // not in use edited.
    class Saved(val server: SavedServer, val note: String? = null) : SignInOutcome

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

sealed interface SwitchOutcome {
    class Done(val connection: Connection) : SwitchOutcome

    // Its password is not kept here (signed out, or only remembered while
    // Octo was open), or the server no longer takes it: ask for it.
    class NeedsPassword(val server: SavedServer, val note: String? = null) : SwitchOutcome

    class Failed(val message: String) : SwitchOutcome
}

// How a kept server answered a quick look: whether it is there, and how
// long it took.
// The words and shapes are shared with the phone.
typealias ServerCheck = app.winters.octo.server.ServerCheck

typealias Reach = app.winters.octo.server.Reach

// A change of password, and a note when the system's store would not keep
// the new one.
class PasswordOutcome(val result: PasswordChange, val note: String? = null)

// The servers kept on this computer, and the one in use: signing in, adding
// another, switching between them, editing, signing out and removing. The
// addresses, usernames, home addresses and header names are kept in the
// settings file; each server's password (or key) and header values go only
// to the system's password store, under that server's own account name, or
// stay in memory for this run.
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

    // A client that gives up soon, for looking at a server not in use.
    private val glance: OkHttpClient by lazy { http.newBuilder().callTimeout(GLANCE_SECONDS, TimeUnit.SECONDS).build() }

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

    // Every server kept here, in order.
    val servers: List<SavedServer> get() = settings.current.servers

    // The server in use, or none while signed out.
    val active: SavedServer? get() = find(settings.current.activeServer)

    fun find(id: String?): SavedServer? = id?.let { wanted -> servers.firstOrNull { it.id == wanted } }

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

    // Tests the connection, then keeps the server and makes it the one in
    // use: the secrets in the system's store and the rest in the settings.
    // A store that refuses does not stop the sign-in; the password is then
    // asked for again next time. With `rememberPassword` off the password is
    // only kept while the app runs. Other kept servers stay as they are.
    suspend fun signIn(request: SignInRequest): SignInOutcome = keep(request, use = true)

    // Tests the connection, then keeps the server beside the others without
    // leaving the one in use. The same account again is brought up to date
    // rather than kept twice.
    suspend fun add(request: SignInRequest): SignInOutcome = keep(request, use = false)

    // Changes a kept server's address, username, headers or way of signing
    // in, after a sign-in with the new details works. An empty password
    // keeps the saved one. The server in use is signed in to again.
    suspend fun edit(id: String, request: SignInRequest, label: String? = null): SignInOutcome {
        val old = find(id) ?: return SignInOutcome.Failed("That server isn't in your list any more.")
        val filled = if (request.secret.isNotEmpty()) {
            request
        } else {
            val saved = savedSecret(old) ?: return SignInOutcome.Failed(if (request.mode == AuthMode.ApiKey) "Enter the API key." else "Enter the password.")
            request.withSecret(saved)
        }
        return keep(filled, use = id == settings.current.activeServer, replacing = old, label = label)
    }

    // Gives a kept server a name of its own; an empty one shows its host.
    fun rename(id: String, label: String) {
        settings.update { s -> s.copy(servers = s.servers.map { if (it.id == id) it.copy(label = label.trim()) else it }) }
    }

    private suspend fun keep(request: SignInRequest, use: Boolean, replacing: SavedServer? = null, label: String? = null): SignInOutcome {
        val reached = when (val tried = attempt(request, replacing)) {
            is Attempt.Failed -> return SignInOutcome.Failed(tried.message)
            is Attempt.Untrusted -> return SignInOutcome.Untrusted(tried.question)
            is Attempt.Reached -> tried
        }
        val facts = reached.facts
        val secret = request.secretFor()
        val address = reached.main.toString()
        val username = reached.credentials.username
        // The same account already kept (other than the one edited).
        val twin = servers.firstOrNull { it.address == address && it.username == username && it.id != replacing?.id }
        if (replacing != null && twin != null) return SignInOutcome.Failed("${twin.name} is already in your list with that address and username.")
        val base = replacing ?: twin
        val id = when {
            base == null -> freshId(username, address)
            // Another account on the server gets its own plays and queue.
            base.username != username -> freshId(username, address, except = base.id)
            else -> base.id
        }
        val saved = SavedServer(
            address = address,
            username = username,
            authMode = reached.credentials.mode,
            home = reached.home?.toString(),
            headerNames = reached.headers.keys.toList(),
            rememberSignIn = request.rememberPassword,
            serverType = facts.info.type,
            serverVersion = facts.info.serverVersion,
            openSubsonic = facts.info.openSubsonic,
            extensions = facts.extensionKeys,
            id = id,
            label = label?.trim() ?: base?.label.orEmpty(),
        )
        val note = withContext(keychain) {
            if (base != null && (base.address != address || base.username != username)) {
                runCatching { secrets.delete(secretAccount(base.username, base.address)) }
                if (base.address != address && servers.none { it.id != base.id && it.address == base.address }) {
                    runCatching { secrets.delete(headersAccount(base.address)) }
                }
            }
            val headersNote = keepHeaders(address, reached.headers)
            val passwordNote = if (!request.rememberPassword) {
                // Kept only in memory, so any copy in the store goes.
                runCatching { secrets.delete(secretAccount(username, address)) }
                null
            } else {
                try {
                    secrets.write(secretAccount(username, address), secret)
                    if (secrets.lasting) null else "This computer has no password store, so Octo will ask for the password each time it opens."
                } catch (e: SecretStoreException) {
                    "Octo couldn't save the password (${e.message}), so it will ask for it next time."
                }
            }
            passwordNote ?: headersNote
        }
        base?.id?.takeIf { it != id }?.let { memory.remove(it); memoryHeaders.remove(it) }
        memory[id] = secret
        memoryHeaders[id] = reached.headers
        settings.update { s ->
            val list = if (base == null) s.servers + saved else s.servers.map { if (it.id == base.id) saved else it }
            val inUse = when {
                use -> id
                // The server in use, edited under a new id, keeps being it.
                s.activeServer != null && s.activeServer == base?.id -> id
                else -> s.activeServer
            }
            s.copy(servers = list, activeServer = inUse)
        }
        if (!use) return SignInOutcome.Saved(saved, note)
        val connection = activate(saved, secret, reached.headers)
        return SignInOutcome.Done(connection, remembered = request.rememberPassword && note == null, note = note)
    }

    // An id for a new server: the account's key, as older versions named its
    // folder, unless another kept server has it already.
    private fun freshId(username: String, address: String, except: String? = null): String =
        freshServerId(username, address, servers.map { it.id }.toSet() - setOfNotNull(except))

    // Makes another kept server the one in use, signed in with its saved
    // secret. It is asked first, so one out of reach leaves the listener
    // where they were; what it says about itself is kept fresh.
    suspend fun switchTo(id: String): SwitchOutcome {
        val server = find(id) ?: return SwitchOutcome.Failed("That server isn't in your list any more.")
        if (server.signedOut) return SwitchOutcome.NeedsPassword(server)
        val secret = savedSecret(server) ?: return SwitchOutcome.NeedsPassword(server)
        val headers = memoryHeaders[id] ?: withContext(keychain) { readHeaders(server) } ?: return SwitchOutcome.NeedsPassword(server)
        val main = server.address.toHttpUrlOrNull() ?: return SwitchOutcome.Failed("That server's address can't be read. Edit it and try again.")
        val home = server.home?.toHttpUrlOrNull()?.takeIf { it != main }
        val creds = Credentials(server.username, secret, server.authMode)
        val (reachedUrl, info) = try {
            reach(main, home, creds, headers)
        } catch (e: SubsonicException) {
            if (e is SubsonicException.WrongCredentials) {
                return SwitchOutcome.NeedsPassword(server, passwordRefusedWords(server.name))
            }
            return SwitchOutcome.Failed(switchFailedWords(server.name, e.userMessage()))
        }
        val extensions = if (info.openSubsonic) {
            runCatching { SubsonicClient(reachedUrl, creds, http, headers = headers).extensions() }.getOrNull()
        } else {
            emptyList()
        }
        val fresh = server.copy(
            serverType = info.type ?: server.serverType,
            serverVersion = info.serverVersion ?: server.serverVersion,
            openSubsonic = info.openSubsonic,
            extensions = extensions?.let { ServerFacts(info, it).extensionKeys } ?: server.extensions,
        )
        memory[id] = secret
        memoryHeaders[id] = headers
        settings.update { s -> s.copy(servers = s.servers.map { if (it.id == id) fresh else it }, activeServer = id) }
        return SwitchOutcome.Done(activate(fresh, secret, headers))
    }

    // The server in use, ready to use, or null when there is none or its
    // password is not in the store. Nothing is asked of the server here.
    // The store can wait on the listener (a locked keyring asks to be
    // unlocked), so this is called before the window opens, not on it.
    fun restore(): Connection? {
        val saved = active ?: return null
        if (saved.signedOut) return null
        val password = memory[saved.id] ?: runCatching { secrets.read(secretAccount(saved.username, saved.address)) }.getOrNull() ?: return null
        val headers = memoryHeaders[saved.id] ?: readHeaders(saved) ?: return null
        return activate(saved, password, headers)
    }

    // The server to fill the sign-in page with: the one in use, else the one
    // signed out of last, else the first kept.
    val last: SavedServer? get() = active ?: find(recent) ?: servers.firstOrNull()

    // Signs out of a kept server, the one in use unless another is named. It
    // stays in the list; its password is forgotten at once from memory and
    // from the store off the window's thread. Its header values are kept,
    // so signing in again needs only the password.
    suspend fun signOut(id: String? = settings.current.activeServer) {
        val old = find(id) ?: return
        forgetInMemory(old)
        recent = old.id
        settings.update { s ->
            s.copy(
                servers = s.servers.map { if (it.id == old.id) it.copy(signedOut = true) else it },
                activeServer = s.activeServer.takeIf { it != old.id },
            )
        }
        withContext(keychain) { runCatching { secrets.delete(secretAccount(old.username, old.address)) } }
    }

    // Takes a server off the list, with its password and, when no other
    // kept server shares its address, its header values. Its songs and
    // playlists stay on the server; this computer's plays, queue and live
    // lists for it are the caller's to keep or delete.
    suspend fun remove(id: String) {
        val old = find(id) ?: return
        forgetInMemory(old)
        if (recent == old.id) recent = null
        settings.update { s -> s.copy(servers = s.servers.filterNot { it.id == old.id }, activeServer = s.activeServer.takeIf { it != old.id }) }
        withContext(keychain) {
            runCatching { secrets.delete(secretAccount(old.username, old.address)) }
            if (servers.none { it.address == old.address }) runCatching { secrets.delete(headersAccount(old.address)) }
        }
    }

    // Forgets a server's secrets for this run, and lets go of its addresses
    // when it is the one in use.
    private fun forgetInMemory(server: SavedServer) {
        memory.remove(server.id)
        memoryHeaders.remove(server.id)
        if (server.id == settings.current.activeServer) {
            route?.close()
            route = null
            security.clear()
        }
    }

    // Changes the signed-in user's password on the server in use, then keeps
    // the new one where the old one was kept.
    suspend fun changePassword(connection: Connection, current: String, new: String): PasswordOutcome {
        val result = changeOwnPassword(connection.client, current, new)
        if (result != PasswordChange.Changed) return PasswordOutcome(result)
        val server = find(connection.server.id) ?: connection.server
        memory[server.id] = new
        if (!server.rememberSignIn) return PasswordOutcome(result)
        val note = withContext(keychain) {
            try {
                secrets.write(secretAccount(server.username, server.address), new)
                null
            } catch (e: SecretStoreException) {
                "Octo couldn't save the new password (${e.message}), so it will ask for it next time."
            }
        }
        return PasswordOutcome(result, note)
    }

    // A quick look at a kept server: whether it answers with its saved
    // password, and how long that took. The server in use is asked through
    // its own client. A signed-out one is not asked, so no wrong password is
    // ever sent to it.
    suspend fun check(id: String, inUse: Connection? = null): ServerCheck {
        val server = find(id) ?: return ServerCheck(Reach.Unknown)
        val client = if (inUse != null && inUse.server.id == id) {
            inUse.client
        } else {
            if (server.signedOut) return ServerCheck(Reach.Unknown)
            val secret = savedSecret(server) ?: return ServerCheck(Reach.Unknown)
            val headers = memoryHeaders[id] ?: withContext(keychain) { readHeaders(server) } ?: return ServerCheck(Reach.Unknown)
            val main = server.address.toHttpUrlOrNull() ?: return ServerCheck(Reach.Unreachable)
            SubsonicClient(main, Credentials(server.username, secret, server.authMode), glance, headers = headers)
        }
        val start = System.nanoTime()
        val info = try {
            client.ping()
        } catch (e: SubsonicException) {
            return ServerCheck(if (e is SubsonicException.WrongCredentials) Reach.WrongPassword else Reach.Unreachable)
        }
        val ms = (System.nanoTime() - start) / 1_000_000
        // A server updated since shows its new version.
        if ((info.serverVersion != null && info.serverVersion != server.serverVersion) || (info.type != null && info.type != server.serverType)) {
            settings.update { s ->
                s.copy(servers = s.servers.map { if (it.id == id) it.copy(serverType = info.type ?: it.serverType, serverVersion = info.serverVersion ?: it.serverVersion) else it })
            }
        }
        return ServerCheck(Reach.Answers, ms)
    }

    // Whether a server's sign-in outlasts this run of Octo.
    fun remembers(server: SavedServer): Boolean = server.rememberSignIn && secrets.lasting

    // Whether the sign-in in use outlasts this run of Octo, for the settings page.
    val remembersSignIn: Boolean
        get() = active?.let(::remembers) ?: secrets.lasting

    // The passwords and headers of this run, by server id, for a store that
    // cannot keep them or a password that is not to be remembered.
    private val memory = ConcurrentHashMap<String, String>()
    private val memoryHeaders = ConcurrentHashMap<String, Map<String, String>>()

    // The server signed out of last, for the sign-in page.
    @Volatile private var recent: String? = null

    // Picks the address in use when the server has a home one.
    @Volatile private var route: HomeRoute? = null

    // A kept server's secret from this run, or from the store.
    private suspend fun savedSecret(server: SavedServer): String? =
        memory[server.id] ?: withContext(keychain) { runCatching { secrets.read(secretAccount(server.username, server.address)) }.getOrNull() }

    // The system's store is slow at times, and can wait on the listener,
    // so it is only used off the window's thread, one call at a time so a
    // sign-out's delete cannot land after the next sign-in's write.
    private val keychain = Dispatchers.IO.limitedParallelism(1)

    private fun SignInRequest.secretFor(): String = if (mode == AuthMode.ApiKey) secret.trim() else secret

    // Tries the server the way the request asks. A server that cannot check
    // tokens is tried again with the password itself where that is safe.
    private suspend fun attempt(request: SignInRequest, editing: SavedServer? = null, mode: AuthMode = request.mode): Attempt {
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
        val saved = if (request.headers.any { it.saved }) withContext(keychain) { savedHeaders(main, editing) } else emptyList()
        val headers = cleanHeaders(resolveHeaders(request.headers, saved)).associate { it.name to it.value }
        var creds = Credentials(request.username.trim(), secret, mode)

        val (reachedUrl, info) = try {
            reach(main, home, creds, headers)
        } catch (e: SubsonicException) {
            untrusted(e, main, home)?.let { return Attempt.Untrusted(it) }
            return when (legacyRetry(e, mode, main)) {
                LegacyRetry.Retry -> attempt(request, editing, AuthMode.LegacyPassword)
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

    // The headers saved for this server, for values left unchanged: the one
    // being edited, else one kept at the same address.
    private fun savedHeaders(main: HttpUrl, editing: SavedServer?): List<ServerHeader> {
        val saved = editing ?: servers.firstOrNull { it.address == main.toString() } ?: return emptyList()
        return (memoryHeaders[saved.id] ?: readHeaders(saved)).orEmpty().map { (name, value) -> ServerHeader(name, value) }
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
