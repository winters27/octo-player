package app.winters.octo.desktop.server

import app.winters.octo.connection.DISTRUSTED_MESSAGE
import app.winters.octo.connection.GatewayDraft
import app.winters.octo.connection.GatewayWords
import app.winters.octo.connection.LegacyRetry
import app.winters.octo.connection.ResolvedHeaders
import app.winters.octo.connection.ServerHeader
import app.winters.octo.connection.SignInWords
import app.winters.octo.connection.gatewayAllowed
import app.winters.octo.connection.isHeaderName
import app.winters.octo.connection.isDistrusted
import app.winters.octo.connection.isHeaderValue
import app.winters.octo.connection.legacyRetry
import app.winters.octo.connection.mask
import app.winters.octo.connection.resolveConnectionHeaders
import app.winters.octo.data.HeaderDraft
import app.winters.octo.desktop.secrets.RunOnlySecrets
import app.winters.octo.desktop.secrets.SecretStore
import app.winters.octo.desktop.secrets.SecretStoreException
import app.winters.octo.desktop.secrets.secretAccount
import app.winters.octo.desktop.settings.CertFile
import app.winters.octo.desktop.settings.SavedServer
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.desktop.settings.currentOs
import app.winters.octo.desktop.settings.name
import app.winters.octo.server.PasswordChange
import app.winters.octo.server.Reach
import app.winters.octo.server.changeOwnPassword
import app.winters.octo.server.freshServerId
import app.winters.octo.server.passwordRefusedWords
import app.winters.octo.server.switchFailedWords
import app.winters.octo.subsonic.AuthMode
import app.winters.octo.subsonic.Credentials
import app.winters.octo.subsonic.Extension
import app.winters.octo.subsonic.OCTO_ACQUISITIONS
import app.winters.octo.subsonic.OCTO_FAMILY
import app.winters.octo.subsonic.ServerInfo
import app.winters.octo.subsonic.SubsonicClient
import app.winters.octo.subsonic.SubsonicException
import app.winters.octo.subsonic.gateway.Gateway
import app.winters.octo.subsonic.gateway.GatewayKind
import app.winters.octo.subsonic.gateway.GatewayNames
import app.winters.octo.subsonic.gateway.savedGatewayKind
import app.winters.octo.subsonic.gateway.savedGatewayNames
import app.winters.octo.subsonic.gatewayOrigins
import app.winters.octo.subsonic.isPrivateHost
import app.winters.octo.subsonic.normalizeServerUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
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

// How long one call of a sign-in, a sign-up or a hand-over may take, so a
// step nothing can leave while it commits always ends.
internal const val COMMIT_SECONDS = 30L

// The signed-in server: the client that talks to it, and what it said
// about itself when signing in.
class Connection(
    val client: SubsonicClient,
    val server: SavedServer,
    // The server's extra headers, which songs the audio engine fetches
    // carry too.
    val headers: Map<String, String> = emptyMap(),
    // The ticket of the sign-in, edit or switch that made it (see
    // Accounts.ticket), so the window shows it only while it is the one in
    // use.
    val generation: Long = 0,
    // The addresses the sign-in page headers among `headers` may go to.
    val gatewayOrigins: Set<String> = emptySet(),
) {
    fun supports(name: String, version: Int = 1): Boolean = "$name:$version" in server.extensions

    // Octo lists its own extensions; anything else is a plain Subsonic
    // server, and the app uses nothing beyond the standard calls there.
    val isOcto: Boolean get() = server.extensions.any { it.startsWith("octo") }

    // Whether the server can fetch songs found online into the library and
    // say how that is going.
    val acquires: Boolean get() = supports(OCTO_ACQUISITIONS)

    val lyricsByIdOn: Boolean get() = supports(SONG_LYRICS)

    // Whether the server has Family on: members, requests and devices.
    val family: Boolean get() = supports(OCTO_FAMILY)

    // Whether a sign-in page sits in front of the server, by the headers
    // kept for it: any name a sign-in page reads, whatever its case, as on
    // the phones.
    val behindSignInPage: Boolean get() = headers.keys.any(GatewayNames::isGatewayName)
}

// The saved form of a server's extensions: "name:version" for each version
// it lists, in order.
fun extensionKeysOf(extensions: List<Extension>): List<String> =
    extensions.flatMap { ext -> ext.versions.map { "${ext.name}:$it" } }.sorted()

// What testing a connection found: which server, which version, and the
// extensions it lists.
class ServerFacts(val info: ServerInfo, val extensions: List<Extension>) {
    val extensionKeys: List<String> get() = extensionKeysOf(extensions)

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
// its saved one. `gateway` is the sign-in page fields; a saved kind left
// with both fields empty keeps its saved values. `certificate` is what the
// form asks for the client certificate; every form passes its own.
data class SignInRequest(
    val address: String,
    val username: String,
    val secret: String,
    val mode: AuthMode = AuthMode.Token,
    val home: String = "",
    val headers: List<HeaderDraft> = emptyList(),
    val rememberPassword: Boolean = true,
    val gateway: GatewayDraft = GatewayDraft(),
    val certificate: CertificateDraft = CertificateDraft.None,
) {
    fun withSecret(secret: String) = copy(secret = secret)

    override fun toString() = "SignInRequest(address=$address, username=$username, secret=${mask(secret)}, mode=$mode, certificate=$certificate)"
}

sealed interface SignInOutcome {
    // Signed in. `remembered` says whether the password went into the
    // system's store; when it could not, `note` says why in plain words.
    class Done(val connection: Connection, val remembered: Boolean, val note: String? = null) : SignInOutcome

    // Kept in the list without being switched to: a server added, or one
    // not in use edited.
    class Saved(val server: SavedServer, val note: String? = null) : SignInOutcome

    // Why not, and the sign-in page that answered, if one did. With
    // `certificate`, the client certificate is why, and the form opens on
    // it. `tried` is the certificate the try sent and how loading it went.
    class Failed(val message: String, val gateway: Gateway? = null, val certificate: Boolean = false, val tried: CertificateTry? = null) : SignInOutcome

    // The server showed a certificate the system does not trust.
    class Untrusted(val question: CertificateQuestion) : SignInOutcome
}

sealed interface TestOutcome {
    // `mode` is the way of signing in that worked, which may be the
    // password itself for a server that cannot check tokens.
    class Reached(val facts: ServerFacts, val mode: AuthMode = AuthMode.Token, val tried: CertificateTry? = null) : TestOutcome
    class Failed(val message: String, val gateway: Gateway? = null, val certificate: Boolean = false, val tried: CertificateTry? = null) : TestOutcome
    class Untrusted(val question: CertificateQuestion) : TestOutcome
}

sealed interface SwitchOutcome {
    class Done(val connection: Connection) : SwitchOutcome

    // Its password is not kept here (signed out, or only remembered while
    // Octo was open), or the server no longer takes it: ask for it.
    class NeedsPassword(val server: SavedServer, val note: String? = null) : SwitchOutcome

    class Failed(val message: String) : SwitchOutcome

    // Its client certificate can't be read now: its sign-in form opens on
    // the certificate, saying why, with the saved password kept.
    class NeedsCertificate(val server: SavedServer, val words: String) : SwitchOutcome

    // A sign-out or removal of the server in use was asked for after this
    // switch began: the window has left already, so nothing changes.
    data object Overtaken : SwitchOutcome
}

// The sign-in page kind a kept server's saved headers make up, and which of
// its saved headers make it up, for the form. No value leaves Accounts.
class SavedGateway(val kind: GatewayKind, val names: Set<String>)

// The kept server whose sign-in page opens at start, and why; with
// `certificate`, it is its client certificate that could not be read.
class StartAsk(val id: String, val words: String, val certificate: Boolean = false)

// A client of its own for one check of a server: its own headers, trusted
// certificates (read from the settings, so a saved one holds) and sign-in
// page cookies, so nothing the server in use holds speaks for the details
// being checked. Closed once the check is done.
class CheckClient(val security: ServerSecurity, val http: OkHttpClient) : AutoCloseable {
    override fun close() {
        security.clear()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }
}

// How a kept server answered a quick look: whether it is there, and how
// long it took.
// The words and shapes are shared with the phone.
typealias ServerCheck = app.winters.octo.server.ServerCheck

// A change of password, and a note when the system's store would not keep
// the new one.
class PasswordOutcome(val result: PasswordChange, val note: String? = null)

// The servers kept on this computer, and the one in use: signing in, adding
// another, switching between them, editing, signing out and removing. The
// addresses, usernames, home addresses and header names are kept in the
// settings file; each server's password (or key) and header values go only
// to the system's password store, under that server's own account name and
// id, or stay in memory for this run. One change at a time: a sign-in, add,
// edit, switch, sign-out, removal or password change waits for the one
// before to finish.
class Accounts(
    private val settings: SettingsStore,
    private val secrets: SecretStore,
    private val http: OkHttpClient,
    // Headers and trusted certificates for the shared client. Only a client
    // built with `security.install` checks the trusted certificates.
    val security: ServerSecurity = ServerSecurity(settings),
    // What each check's client starts from: the app's name and time limits,
    // with none of the shared client's headers, cookies or certificates.
    private val base: () -> OkHttpClient.Builder = { OkHttpClient.Builder() },
    // Reads a client certificate file where the person keeps it; the tests
    // count, hold or fail reads with their own.
    readCertificateBytes: (Path) -> ByteArray = ::readCapped,
    // Where each server's certificate item is filed in the system's store.
    private val certificateAccount: (String) -> String = certificateAccountFor(null, currentOs()),
    // The time, for whether a certificate is in date.
    private val clock: () -> Instant = Instant::now,
    // How long reading a certificate file may take.
    certificateReadMs: Long = CERT_READ_MS,
    // Whether the audio engine can sign with a certificate's key: its own
    // library, asked with no engine open, so the start checks the saved
    // certificate before the engine is attached; the tests give their own.
    private val engineCheck: (chain: List<ByteArray>, key: ByteArray) -> String? = ::engineKeyProblem,
) {
    // What a try at a server found, or why it failed. `tried` is the client
    // certificate it sent and how loading it went.
    private sealed interface Attempt {
        class Failed(val message: String, val gateway: Gateway? = null, val certificate: Boolean = false, val tried: CertificateTry? = null) : Attempt
        class Untrusted(val question: CertificateQuestion) : Attempt
        class Reached(
            val main: HttpUrl,
            val home: HttpUrl?,
            val credentials: Credentials,
            val headers: Map<String, String>,
            val facts: ServerFacts,
            val tried: CertificateTry,
        ) : Attempt
    }

    // The client certificate the audio engine offers, as the connection in
    // use has it.
    val engineIdentity = EngineIdentity()

    // Certificate items the store would not keep, for this run, by server
    // id. Read before the store; a sign-out leaves them.
    private val memoryCerts = ConcurrentHashMap<String, CertSecret>()

    // The certificate files' reads, one per path at a time.
    private val certificateReads = CertificateReads(readCertificateBytes, certificateReadMs)

    // The certificate files being read now, for tests.
    internal val certificateReading: Set<Path> get() = certificateReads.reading

    // Every server kept here, in order.
    val servers: List<SavedServer> get() = settings.current.servers

    // The server in use, or none while signed out.
    val active: SavedServer? get() = find(settings.current.activeServer)

    fun find(id: String?): SavedServer? = id?.let { wanted -> servers.firstOrNull { it.id == wanted } }

    // One change to the kept servers at a time.
    private val writer = Mutex()

    // Tickets for changes, rising with each one taken; the newest taken by a
    // sign-out or removal of the server in use; and the one the server in
    // use was made so under.
    private val tickets = AtomicLong()
    private val stopped = AtomicLong()
    @Volatile private var activeGeneration = 0L

    // Held while the server in use changes hands, so a sign-in, edit or
    // switch making its server the one in use and a sign-out or removal
    // letting go of it never land halfway through each other.
    private val handover = Any()

    // The ticket each kept server was last signed out of or removed under,
    // so a change begun before it never undoes it.
    private val signedOutAt = ConcurrentHashMap<String, Long>()

    // The ticket each kept server was last removed under, so a change begun
    // before it never brings it back, under its id or a new one.
    private val removedAt = ConcurrentHashMap<String, Long>()

    // A ticket for a sign-in, edit or switch, taken when it is asked for.
    fun ticket(): Long = tickets.incrementAndGet()

    // A ticket for signing out of or removing the server in use, `id`,
    // taken when it is asked for, before anything waits: from then on, a
    // sign-in, edit or switch begun earlier no longer counts as the one in
    // use, here or in the window, and one of `id` begun earlier leaves it
    // signed out (or, `removing`, not kept), even when it lands before the
    // sign-out or removal itself.
    fun stopTicket(id: String?, removing: Boolean = false): Long = synchronized(handover) {
        ticket().also { taken ->
            stopped.accumulateAndGet(taken) { a, b -> maxOf(a, b) }
            if (id != null) {
                signedOutAt[id] = taken
                if (removing) removedAt[id] = taken
            }
        }
    }

    // Whether a connection is the one in use now: made by the last sign-in,
    // edit or switch to finish, and begun after any sign-out or removal of
    // the one in use asked for since.
    fun isInUse(connection: Connection): Boolean = connection.generation == activeGeneration && connection.generation > stopped.get()

    // Told when the server in use moves between its home and main
    // addresses, so songs queued at one are asked for again at the other.
    @Volatile var onRouteMoved: (() -> Unit)? = null

    suspend fun test(address: String, username: String, password: String, mode: AuthMode = AuthMode.Token): TestOutcome =
        test(SignInRequest(address, username, password, mode))

    // Asks the server who it is and which extensions it lists, without
    // keeping anything. With `keptId`, the kept server the form signs in to
    // again: an empty password or key is its saved one, as signing in fills
    // it, and its saved headers and certificate count.
    suspend fun test(request: SignInRequest, keptId: String? = null): TestOutcome {
        val kept = keptId?.let(::find)
        val asked = if (kept == null) request else filled(kept, request) ?: return TestOutcome.Failed(missingSecretWords(request))
        return when (val tried = attempt(asked, kept)) {
            is Attempt.Failed -> TestOutcome.Failed(tried.message, tried.gateway, tried.certificate, tried.tried)
            is Attempt.Untrusted -> TestOutcome.Untrusted(tried.question)
            is Attempt.Reached -> TestOutcome.Reached(tried.facts, tried.credentials.mode, tried.tried)
        }
    }

    suspend fun signIn(address: String, username: String, password: String, mode: AuthMode = AuthMode.Token): SignInOutcome =
        signIn(SignInRequest(address, username, password, mode))

    // Tests the connection, then keeps the server and makes it the one in
    // use: the secrets in the system's store and the rest in the settings.
    // A store that refuses does not stop the sign-in; the password is then
    // asked for again next time. With `rememberPassword` off the password is
    // only kept while the app runs. Other kept servers stay as they are.
    suspend fun signIn(request: SignInRequest, generation: Long = ticket()): SignInOutcome =
        writer.withLock { keep(request, use = true, generation) }

    // Tests the connection, then keeps the server beside the others without
    // leaving the one in use. The same account again is brought up to date
    // rather than kept twice.
    suspend fun add(request: SignInRequest, generation: Long = ticket()): SignInOutcome = writer.withLock { keep(request, use = false, generation) }

    // Changes a kept server's address, username, headers, sign-in page or
    // way of signing in, after a sign-in with the new details works. An
    // empty password keeps the saved one. The server in use is signed in to
    // again; which one that is is decided once the change has its turn, so a
    // switch finished meanwhile counts.
    suspend fun edit(id: String, request: SignInRequest, label: String? = null, generation: Long = ticket()): SignInOutcome =
        writer.withLock {
            val old = find(id) ?: return@withLock SignInOutcome.Failed("That server isn't in your list any more.")
            val filled = filled(old, request) ?: return@withLock missingSecret(request)
            keep(filled, use = id == settings.current.activeServer, generation, replacing = old, label = label)
        }

    // Signs in again to a kept server and makes it the one in use: the same
    // server, brought up to date with the form's details (an address moved
    // to https, say), and `label` its name when given. An empty password
    // keeps the saved one, and empty sign-in page fields their saved values,
    // as an edit does. With `sameAccount`, a sign-in that reaches another
    // account than the kept one's keeps it beside the kept one instead.
    suspend fun signInAgain(id: String, request: SignInRequest, generation: Long = ticket(), label: String? = null, sameAccount: Boolean = false): SignInOutcome =
        writer.withLock {
            val old = find(id) ?: return@withLock SignInOutcome.Failed("That server isn't in your list any more.")
            val filled = filled(old, request) ?: return@withLock missingSecret(request)
            keep(filled, use = true, generation, replacing = old, label = label, sameAccount = sameAccount)
        }

    // The request with its secret: the one typed, or else the kept server's
    // saved one. A server signed out of has none to fall back on, whatever
    // the store still holds while its sign-out finishes.
    private suspend fun filled(old: SavedServer, request: SignInRequest): SignInRequest? = when {
        request.secret.isNotEmpty() -> request
        old.signedOut -> null
        // A saved password is never sent as a key, nor a key as a password.
        (old.authMode == AuthMode.ApiKey) != (request.mode == AuthMode.ApiKey) -> null
        else -> savedSecret(old)?.let(request::withSecret)
    }

    private fun missingSecretWords(request: SignInRequest) = if (request.mode == AuthMode.ApiKey) "Enter the API key." else "Enter the password."

    private fun missingSecret(request: SignInRequest) = SignInOutcome.Failed(missingSecretWords(request))

    // Gives a kept server a name of its own; an empty one shows its host.
    fun rename(id: String, label: String) {
        settings.update { s -> s.copy(servers = s.servers.map { if (it.id == id) it.copy(label = label.trim()) else it }) }
    }

    private suspend fun keep(
        request: SignInRequest,
        use: Boolean,
        generation: Long,
        replacing: SavedServer? = null,
        label: String? = null,
        sameAccount: Boolean = false,
    ): SignInOutcome {
        val reached = when (val tried = attempt(request, replacing)) {
            is Attempt.Failed -> return SignInOutcome.Failed(tried.message, tried.gateway, tried.certificate, tried.tried)
            is Attempt.Untrusted -> return SignInOutcome.Untrusted(tried.question)
            is Attempt.Reached -> tried
        }
        val facts = reached.facts
        val secret = request.secretFor()
        val address = reached.main.toString()
        val username = reached.credentials.username
        // With `sameAccount`, a sign-in that reached another account (an API
        // key of someone else's) keeps that account as a server of its own
        // and leaves the kept one as it was.
        val edited = replacing?.takeUnless { sameAccount && it.username != username }
        // The same account already kept (other than the one edited).
        val twin = servers.firstOrNull { it.address == address && it.username == username && it.id != edited?.id }
        if (edited != null && twin != null) return SignInOutcome.Failed("${twin.name} is already in your list with that address and username.")
        val base = edited ?: twin
        val id = when {
            base == null -> freshId(username, address)
            // Another account on the server gets its own plays and queue.
            base.username != username -> freshId(username, address, except = base.id)
            else -> base.id
        }
        // What becomes of the client certificate, read before anything is
        // written: a carried certificate whose item can't be read stops the
        // keep here, with nothing changed.
        val certs = certificatesFor(request.certificate, reached, replacing, edited, twin, id, use)
            ?: return SignInOutcome.Failed(CertificateWords.CARRY_UNREAD)
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
            certificate = certs.file,
        )
        val (note, headersKept) = withContext(keychain) {
            val headersNote = keepHeaders(saved, reached.headers)
            val passwordNote = if (!request.rememberPassword) {
                // Kept only in memory, so any copy in the store goes.
                runCatching { secrets.delete(secretAccount(username, address)) }
                null
            } else {
                try {
                    secrets.write(secretAccount(username, address), secret)
                    if (secrets.lasting || settings.readOnly) null else "This computer has no password store, so Octo will ask for the password each time it opens."
                } catch (e: SecretStoreException) {
                    "Octo couldn't save the password (${e.message}), so it will ask for it next time."
                }
            }
            (passwordNote ?: headersNote) to (headersNote == null)
        }
        base?.id?.takeIf { it != id }?.let { memory.remove(it); memoryHeaders.remove(it) }
        memory[id] = secret
        memoryHeaders[id] = reached.headers
        val made = synchronized(handover) {
            // Removed while this ran, or asked to be: it is not brought back.
            if (base != null && (find(base.id) == null || (removedAt[base.id] ?: 0L) > generation)) return@synchronized null
            // A sign-out or removal of the server in use asked for after this
            // change began leaves the server saved but not in use, and a
            // sign-out of this server asked for after it began keeps it
            // signed out, whatever the order the two reach the settings in.
            val overtaken = use && generation <= stopped.get()
            val stored = if (base != null && (signedOutAt[base.id] ?: 0L) > generation) saved.copy(signedOut = true) else saved
            val activates = use && !overtaken && !stored.signedOut
            settings.update { s ->
                val list = if (base == null) s.servers + stored else s.servers.map { if (it.id == base.id) stored else it }
                val inUse = when {
                    activates -> id
                    // The server in use, edited under a new id, keeps being
                    // it, unless a sign-out of it came first.
                    !overtaken && s.activeServer != null && s.activeServer == base?.id -> id
                    else -> s.activeServer
                }
                s.copy(servers = list, activeServer = inUse)
            }
            stored to if (activates) activate(stored, secret, reached.headers, generation, certs.identity) else null
        }
        // Removed or signed out of meanwhile: nothing of this sign-in is
        // kept, in memory or in the store, whatever address it was for; a
        // removed server's header values go too, under whichever id.
        if (made == null || made.first.signedOut) {
            memory.remove(id)
            memoryHeaders.remove(id)
            if (made == null) memoryCerts.remove(id)
            withContext(keychain) {
                runCatching { secrets.delete(secretAccount(username, address)) }
                if (made == null) runCatching { secrets.delete(headersAccount(saved)) }
            }
        }
        // The settings that name the new state go to disk (the settings
        // file, or the pending file beside it while the file is held) before
        // the store follows them. Whatever this gives, the store work goes
        // on: a write that failed is tried again, and again when Octo closes.
        val flushed = withContext(Dispatchers.IO) { settings.flushNow() }
        val storeNotes = withContext(keychain) {
            // The old account's password and header values, once the
            // settings name the new one.
            if (base != null && (base.address != address || base.username != username)) {
                runCatching { secrets.delete(secretAccount(base.username, base.address)) }
            }
            // An account edited under a new id leaves its old header values behind.
            if (base != null && base.id != id) runCatching { secrets.delete(headersAccount(base)) }
            // Removed meanwhile: nothing of its certificate is written.
            if (made == null) return@withContext emptyList<String>()
            // First the item the settings now name, then the ones they no
            // longer name.
            val notes = listOfNotNull(certs.item?.let { writeCertItem(id, it) }) + certs.deletes.mapNotNull { deleteCertItem(it) }
            certs.memoryDrop?.let { memoryCerts.remove(it) }
            // Header values an older version filed by address go once no kept
            // server reads them there.
            if (headersKept) {
                dropAddressHeaders(address)
                if (base != null && base.address != address) dropAddressHeaders(base.address)
            }
            notes
        }
        val (kept, connection) = made ?: return SignInOutcome.Failed("That server isn't in your list any more.")
        // Settings neither settings file would take yet, with a certificate
        // item already changed: what the next start finds if they never are.
        // A read-only run saves no settings, and its banner says so.
        val unsaved = when {
            flushed || settings.readOnly || (certs.item == null && certs.deletes.isEmpty()) -> null
            certs.removed -> CertificateWords.unsavedRemove(kept.name)
            base == null -> CertificateWords.unsavedAdd(kept.name)
            else -> CertificateWords.UNSAVED_KEEP
        }
        val said = (listOfNotNull(note, certs.words.takeIf { connection != null }) + storeNotes + listOfNotNull(unsaved)).joinToString(" ").ifEmpty { null }
        if (connection == null) return SignInOutcome.Saved(kept, said)
        return SignInOutcome.Done(connection, remembered = request.rememberPassword && note == null, note = said)
    }

    // An id for a new server: the account's key, as older versions named its
    // folder, unless another kept server has it already.
    private fun freshId(username: String, address: String, except: String? = null): String =
        freshServerId(username, address, servers.map { it.id }.toSet() - setOfNotNull(except))

    // Makes another kept server the one in use, signed in with its saved
    // secret. It is asked first, through a client of its own, so one out of
    // reach or behind a sign-in page that refuses leaves the listener where
    // they were, with every password kept; what it says about itself is
    // kept fresh.
    suspend fun switchTo(id: String, generation: Long = ticket()): SwitchOutcome = writer.withLock { switchLocked(id, generation) }

    private suspend fun switchLocked(id: String, generation: Long): SwitchOutcome {
        val server = find(id) ?: return SwitchOutcome.Failed("That server isn't in your list any more.")
        if (server.signedOut) return SwitchOutcome.NeedsPassword(server)
        val secret = savedSecret(server) ?: return SwitchOutcome.NeedsPassword(server)
        val headers = memoryHeaders[id] ?: withContext(keychain) { readHeaders(server) } ?: return SwitchOutcome.NeedsPassword(server)
        val main = server.address.toHttpUrlOrNull() ?: return SwitchOutcome.Failed("That server's address can't be read. Edit it and try again.")
        val home = server.home?.toHttpUrlOrNull()?.takeIf { it != main }
        // A sign-in page's password that would cross the internet readable
        // is never sent: its sign-in form opens saying why, as on the phones.
        if (needsHttps(main, headers)) return SwitchOutcome.NeedsPassword(server, GatewayWords.NEEDS_HTTPS)
        // Its client certificate, when one can go to these addresses: read
        // after its password, before the server is asked anything.
        val file = server.certificate?.takeIf { sendsCertificate(server, main, home) }
        val certificate = when (val load = file?.let { loadSaved(server.id, it) }) {
            null -> null
            is CertificateLoad.Found -> load.certificate
            is CertificateLoad.Unreadable -> return SwitchOutcome.NeedsCertificate(server, load.words)
        }
        val creds = Credentials(server.username, secret, server.authMode)
        // Each call gets as long as a look at a server's row (GLANCE_SECONDS,
        // the phone's switch limit too): the switch holds the other changes
        // while it asks, so a sign-in page that never answers holds them for
        // seconds, not the minute the base client's read limit would allow.
        val (info, extensions) = checkClient(listOfNotNull(main, home), headers, certificate, GLANCE_SECONDS).use { own ->
            val (reachedUrl, info) = try {
                reach(own.http, main, home, creds)
            } catch (e: SubsonicException) {
                if (e is SubsonicException.WrongCredentials) {
                    return SwitchOutcome.NeedsPassword(server, passwordRefusedWords(server.name))
                }
                // A certificate the system does not trust keeps today's
                // words; otherwise what the server said about the client
                // certificate, when it asked for one.
                val urls = listOfNotNull(main, home)
                val rejected = urls.any { own.security.peekRejected(it.host) != null }
                val why = (if (rejected) null else own.security.certificateWords(urls)) ?: e.userMessage()
                return SwitchOutcome.Failed(switchFailedWords(server.name, why))
            }
            val extensions = if (info.openSubsonic) {
                runCatching { SubsonicClient(reachedUrl, creds, own.http).extensions() }.getOrNull()
            } else {
                emptyList()
            }
            info to extensions
        }
        val fresh = server.copy(
            serverType = info.type ?: server.serverType,
            serverVersion = info.serverVersion ?: server.serverVersion,
            openSubsonic = info.openSubsonic,
            extensions = extensions?.let { ServerFacts(info, it).extensionKeys } ?: server.extensions,
        )
        return synchronized(handover) {
            val now = find(id)
            when {
                // A sign-out or removal of the server in use asked for after
                // this switch began: the window has left already.
                generation <= stopped.get() -> SwitchOutcome.Overtaken
                now == null -> SwitchOutcome.Failed("That server isn't in your list any more.")
                // Signed out of while it was asked.
                now.signedOut -> SwitchOutcome.NeedsPassword(now)
                else -> {
                    memory[id] = secret
                    memoryHeaders[id] = headers
                    settings.update { s -> s.copy(servers = s.servers.map { if (it.id == id) fresh else it }, activeServer = id) }
                    SwitchOutcome.Done(activate(fresh, secret, headers, generation, certificate))
                }
            }
        }
    }

    // The server in use, ready to use, or null when there is none, its
    // password is not in the store, or its client certificate can't be read
    // now. Nothing is asked of the server here. The store can wait on the
    // listener (a locked keyring asks to be unlocked), so this is called
    // before the window opens, not on it.
    fun restore(): Connection? {
        val saved = active ?: return null
        if (saved.signedOut) return null
        val password = memory[saved.id] ?: runCatching { secrets.read(secretAccount(saved.username, saved.address)) }.getOrNull() ?: return null
        val headers = memoryHeaders[saved.id] ?: readHeaders(saved) ?: return null
        // A sign-in page's password that would cross the internet readable
        // is never sent: the server is no longer the one in use, keeps its
        // password, and its sign-in page opens saying why, as on the phones.
        val main = saved.address.toHttpUrlOrNull()
        if (main != null && needsHttps(main, headers)) {
            settings.update { it.copy(activeServer = null) }
            startAsk = StartAsk(saved.id, GatewayWords.NEEDS_HTTPS)
            return null
        }
        // A certificate that can't be read now leaves the server the one in
        // use, so a drive connected later is used at the next start; its
        // sign-in page opens saying why, with the saved password kept.
        val home = saved.home?.toHttpUrlOrNull()?.takeIf { it != main }
        val file = saved.certificate?.takeIf { sendsCertificate(saved, main, home) }
        val certificate = when (val load = file?.let { loadSavedNow(saved.id, it) }) {
            null -> null
            is CertificateLoad.Found -> load.certificate
            is CertificateLoad.Unreadable -> {
                startAsk = StartAsk(saved.id, load.words, certificate = true)
                return null
            }
        }
        return activate(saved, password, headers, ticket(), certificate)
    }

    // What the server in use offers is saved at sign-in, and a server gains
    // extensions over time (an Octo updated since). So it is asked again:
    // the saved list is brought up to date, and the connection comes back
    // with it. Null when the list is the same, when the server gives none
    // (what was saved stands), or when `connection` is no longer in use.
    suspend fun refreshExtensions(connection: Connection): Connection? {
        val fresh = runCatching { connection.client.extensions() }.getOrNull()?.let(::extensionKeysOf) ?: return null
        if (fresh.isEmpty() || fresh == connection.server.extensions) return null
        return synchronized(handover) {
            val id = connection.server.id
            if (!isInUse(connection) || active?.takeIf { it.id == id && !it.signedOut } == null) return null
            settings.update { s -> s.copy(servers = s.servers.map { if (it.id == id) it.copy(extensions = fresh) else it }) }
            val saved = find(id) ?: return null
            Connection(connection.client, saved, connection.headers, connection.generation, connection.gatewayOrigins)
        }
    }

    // The kept server whose sign-in page opens at start, and why: the one
    // in use when Octo closed, which could not be signed in to as kept.
    @Volatile private var startAsk: StartAsk? = null

    // The same, taken once.
    fun takeStartAsk(): StartAsk? = startAsk.also { startAsk = null }

    // The server to fill the sign-in page with: the one in use, else the one
    // signed out of last, else the first kept.
    val last: SavedServer? get() = active ?: find(recent) ?: servers.firstOrNull()

    // Signs out of a kept server, the one in use unless another is named. It
    // stays in the list. Its password is forgotten from memory and the
    // settings say it is signed out at once, so a quit while another change
    // holds the accounts never brings it back; the password store forgets
    // it once that change is done, off the window's thread. Its header
    // values are kept, so signing in again needs only the password. `stop`
    // is the ticket a sign-out of the server in use took when asked for; a
    // change begun before it (or before this call, for any other server)
    // never undoes it.
    suspend fun signOut(id: String? = settings.current.activeServer, stop: Long? = null) {
        val old = synchronized(handover) {
            val old = find(id)
            if (old != null) {
                signedOutAt[old.id] = stop ?: ticket()
                forgetInMemory(old)
                recent = old.id
                settings.update { s ->
                    s.copy(
                        servers = s.servers.map { if (it.id == old.id) it.copy(signedOut = true) else it },
                        activeServer = s.activeServer.takeIf { it != old.id },
                    )
                }
            }
            leaveOutdated(stop)
            old
        }
        if (old != null) writer.withLock { withContext(keychain) { runCatching { secrets.delete(secretAccount(old.username, old.address)) } } }
    }

    // Takes a server off the list, with its password and its header values.
    // It leaves the list and memory at once, as a sign-out does; the store
    // lets its secrets go once the change before is done. Its songs and
    // playlists stay on the server; this computer's plays, queue and live
    // lists for it are the caller's to keep or delete. `stop` as for
    // signOut, when it is the server in use.
    suspend fun remove(id: String, stop: Long? = null) {
        val old = synchronized(handover) {
            val old = find(id)
            if (old != null) {
                signedOutAt[old.id] = stop ?: ticket()
                forgetInMemory(old)
                memoryCerts.remove(old.id)
                if (recent == old.id) recent = null
                settings.update { s -> s.copy(servers = s.servers.filterNot { it.id == old.id }, activeServer = s.activeServer.takeIf { it != old.id }) }
            }
            leaveOutdated(stop)
            old
        }
        if (old != null) {
            writer.withLock {
                // The list without it goes to disk before its items go.
                withContext(Dispatchers.IO) { settings.flushNow() }
                withContext(keychain) {
                    runCatching { secrets.delete(secretAccount(old.username, old.address)) }
                    runCatching { secrets.delete(headersAccount(old)) }
                    // Whether or not it names a certificate, so an item left
                    // behind earlier goes with its server.
                    runCatching { secrets.delete(certificateAccount(old.id)) }
                    dropAddressHeaders(old.address)
                }
            }
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
            engineIdentity.use(null, emptySet())
        }
    }

    // A sign-out or removal of the server in use, asked for with `stop`,
    // also lets go of a server made the one in use since by a sign-in, edit
    // or switch begun before it: the window has left it already. That
    // server keeps its password, as after any switch.
    private fun leaveOutdated(stop: Long?) {
        if (stop == null || activeGeneration > stop || settings.current.activeServer == null) return
        route?.close()
        route = null
        security.clear()
        engineIdentity.use(null, emptySet())
        settings.update { it.copy(activeServer = null) }
    }

    // Changes the signed-in user's password on the server in use, then keeps
    // the new one where the old one was kept.
    suspend fun changePassword(connection: Connection, current: String, new: String, family: Boolean = false): PasswordOutcome =
        writer.withLock {
            val result = changeOwnPassword(connection.client, current, new, family)
            if (result != PasswordChange.Changed) return@withLock PasswordOutcome(result)
            val server = find(connection.server.id) ?: connection.server
            memory[server.id] = new
            // A read-only run keeps the new password where the old one is
            // kept, whatever this run's Remember says, so the next start
            // never signs in with one the server no longer takes.
            val runOnly = secrets as? RunOnlySecrets
            if (!server.rememberSignIn && runOnly == null) return@withLock PasswordOutcome(result)
            val note = withContext(keychain) {
                try {
                    val account = secretAccount(server.username, server.address)
                    if (runOnly != null) runOnly.writeThrough(account, new) else secrets.write(account, new)
                    null
                } catch (e: SecretStoreException) {
                    "Octo couldn't save the new password (${e.message}), so it will ask for it next time."
                }
            }
            PasswordOutcome(result, note)
        }

    // A quick look at a kept server: whether it answers with its saved
    // password, and how long that took. The server in use is asked through
    // its own client; any other through a client of its own with its own
    // headers. A signed-out one is not asked, so no wrong password is ever
    // sent to it, and neither is one whose sign-in page headers would cross
    // the internet in plain http, in use or not.
    suspend fun check(id: String, inUse: Connection? = null): ServerCheck {
        val server = find(id) ?: return ServerCheck(Reach.Unknown)
        if (inUse != null && inUse.server.id == id) {
            if (needsHttps(inUse.client.primaryUrl, inUse.headers)) return ServerCheck(Reach.NeedsHttps)
            return look(id, server, inUse.client)
        }
        if (server.signedOut) return ServerCheck(Reach.Unknown)
        val secret = savedSecret(server) ?: return ServerCheck(Reach.Unknown)
        val headers = memoryHeaders[id] ?: withContext(keychain) { readHeaders(server) } ?: return ServerCheck(Reach.Unknown)
        val main = server.address.toHttpUrlOrNull() ?: return ServerCheck(Reach.Unreachable)
        if (needsHttps(main, headers)) return ServerCheck(Reach.NeedsHttps)
        // The look goes to the main address alone, so a certificate counts
        // only when that is https; one that can't be read asks nothing.
        val certificate = when (val load = server.certificate?.takeIf { main.isHttps }?.let { loadSaved(server.id, it) }) {
            null -> null
            is CertificateLoad.Found -> load.certificate
            is CertificateLoad.Unreadable -> return ServerCheck(Reach.Unknown)
        }
        return checkClient(listOf(main), headers, certificate, GLANCE_SECONDS).use { glance ->
            look(id, server, SubsonicClient(main, Credentials(server.username, secret, server.authMode), glance.http))
        }
    }

    // Pings a kept server through `client` and says how it answered; a
    // server updated since shows its new version.
    private suspend fun look(id: String, server: SavedServer, client: SubsonicClient): ServerCheck {
        val start = System.nanoTime()
        val info = try {
            client.ping()
        } catch (e: SubsonicException) {
            return ServerCheck(reachOf(e))
        }
        val ms = (System.nanoTime() - start) / 1_000_000
        if ((info.serverVersion != null && info.serverVersion != server.serverVersion) || (info.type != null && info.type != server.serverType)) {
            settings.update { s ->
                s.copy(servers = s.servers.map { if (it.id == id) it.copy(serverType = info.type ?: it.serverType, serverVersion = info.serverVersion ?: it.serverVersion) else it })
            }
        }
        return ServerCheck(Reach.Answers, ms)
    }

    // How a failed look reads on a server's row. A sign-in page in front of
    // the server is named, never taken for a wrong password.
    private fun reachOf(e: SubsonicException): Reach {
        val refusal = e as? SubsonicException.NotSubsonic
        val gateway = refusal?.gateway
        return when {
            refusal != null && gateway != null -> Reach.SignInPage(gateway, refusal.loginSent)
            e is SubsonicException.WrongCredentials -> Reach.WrongPassword
            else -> Reach.Unreachable
        }
    }

    // Whether a server's sign-in outlasts this run of Octo. Nothing does in
    // a read-only run.
    fun remembers(server: SavedServer): Boolean = !settings.readOnly && server.rememberSignIn && secrets.lasting

    // Whether the sign-in in use outlasts this run of Octo, for the settings page.
    val remembersSignIn: Boolean
        get() = !settings.readOnly && (active?.let(::remembers) ?: secrets.lasting)

    // The sign-in page kind a kept server's saved headers make up, for the
    // form to show as saved. The store is read off the window's thread.
    suspend fun savedGateway(server: SavedServer): SavedGateway {
        val headers = (memoryHeaders[server.id] ?: withContext(keychain) { readHeaders(server) }).orEmpty().toList()
        val kind = savedGatewayKind(headers)
        return SavedGateway(kind, savedGatewayNames(headers, kind))
    }

    // The certificate a request sends: none when no address is https; the
    // file just picked; or a kept server's, when the request replaces that
    // server (whatever its addresses are now) or first goes, over https, to
    // one of that server's own https addresses (its main, else its home; the
    // host as HttpUrl names it, and the port). Plain http addresses neither
    // match nor stop a match.
    fun certificateFor(draft: CertificateDraft, replacingId: String?, url: HttpUrl?, home: HttpUrl?): CertificateChoice {
        if (url == null) return CertificateChoice.None
        val first = listOfNotNull(url, home).firstOrNull(HttpUrl::isHttps) ?: return CertificateChoice.None
        return when (draft) {
            CertificateDraft.None, is CertificateDraft.Remove -> CertificateChoice.None
            is CertificateDraft.Use -> CertificateChoice.Picked(draft.path, draft.password)
            is CertificateDraft.Keep -> {
                val kept = find(draft.serverId)
                val file = kept?.certificate
                when {
                    kept == null || file == null -> CertificateChoice.None
                    replacingId == kept.id -> CertificateChoice.Saved(kept.id, file)
                    TlsOrigin.of(first) in certificateOrigins(kept.address.toHttpUrlOrNull(), kept.home?.toHttpUrlOrNull()) -> CertificateChoice.Saved(kept.id, file)
                    else -> CertificateChoice.None
                }
            }
        }
    }

    // What the form's certificate row shows for a request: what it sends;
    // else, for a request with no https address, a file the record it lands
    // on keeps (one picked now, or the replaced server's), which only https
    // ever carries; else nothing.
    fun certificateShown(draft: CertificateDraft, replacingId: String?, url: HttpUrl?, home: HttpUrl?): CertificateShown {
        if (url == null) return CertificateShown.None
        val choice = certificateFor(draft, replacingId, url, home)
        if (choice != CertificateChoice.None) return CertificateShown.Sends(choice)
        if (url.isHttps || home?.isHttps == true) return CertificateShown.None
        return when {
            draft is CertificateDraft.Use -> CertificateShown.HttpsOnly(certificateName(draft.path))
            draft is CertificateDraft.Keep && draft.serverId == replacingId ->
                find(draft.serverId)?.certificate?.let { CertificateShown.HttpsOnly(it.name) } ?: CertificateShown.None
            else -> CertificateShown.None
        }
    }

    // Loads what a request sends, for the form's row; null for none.
    suspend fun loadChoice(choice: CertificateChoice): CertificateLoad? = when (choice) {
        CertificateChoice.None -> null
        is CertificateChoice.Picked -> opening(certificateName(choice.path), choice.password, readBytes(choice.path))
        is CertificateChoice.Saved -> loadSaved(choice.serverId, choice.file)
    }

    // Opens a file the person just chose, with `password` (the form tries an
    // empty one first): the certificate, or why not. The file is opened off
    // the caller's thread, since its key's password check takes a while.
    suspend fun openPicked(path: String, password: String): CertificateOpened {
        val name = certificateName(path)
        val bytes = readBytes(path).getOrElse { return CertificateOpened.Refused(unreadableRead(it, name).words) }
            ?: return CertificateOpened.Refused(CertificateWords.notFound(name))
        return withContext(Dispatchers.Default) { openCertificate(bytes, password, name, clock(), engineCheck) }
    }

    // A client for checking a server at these addresses (the main one
    // first, then the home one) with these headers and this client
    // certificate, built from the base with a security of its own; `seconds`
    // limits each call. With no address it carries no headers and offers no
    // certificate at all.
    fun checkClient(addresses: List<HttpUrl>, headers: Map<String, String>, certificate: LoadedCertificate?, seconds: Long? = null): CheckClient {
        val own = security.forCheck()
        addresses.firstOrNull()?.let { main -> own.configure(main, addresses.getOrNull(1), headers, certificate) }
        val builder = own.install(base())
        if (seconds != null) builder.callTimeout(seconds, TimeUnit.SECONDS)
        return CheckClient(own, builder.build())
    }

    // The headers a family invite or hand-over asks for, from the form:
    // its rows, with saved values filled in from a server kept at its
    // address, and its sign-in page fields; or what is wrong with them.
    suspend fun joinHeaders(request: SignInRequest): ResolvedHeaders {
        val main = normalizeServerUrl(request.address) ?: return ResolvedHeaders.Problem("That doesn't look like a server address.")
        return headersFor(request, main, null)
    }

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

    // A kept server's certificate item: this run's copy, else the store's.
    // A store that fails is a failure, never "no item".
    private fun certItemNow(id: String): Result<CertSecret?> {
        memoryCerts[id]?.let { return Result.success(it) }
        return try {
            val text = secrets.read(certificateAccount(id)) ?: return Result.success(null)
            Result.success(runCatching { certJson.decodeFromString(CertSecret.serializer(), text) }.getOrNull())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun certItem(id: String): Result<CertSecret?> = withContext(keychain) { certItemNow(id) }

    private fun storeUnread(e: Throwable) =
        CertificateLoad.Unreadable(CertificateWords.storeUnread(secrets.label, e.message ?: e.javaClass.simpleName), again = false)

    // A kept server's certificate: its item, then the file where the person
    // keeps it, opened with the item's password. An item that is not there,
    // or names another file, asks for the file again.
    private suspend fun loadSaved(id: String, file: CertFile): CertificateLoad {
        val item = certItem(id).getOrElse { return storeUnread(it) }
        if (item == null || item.path != file.path) return CertificateLoad.Unreadable(CertificateWords.needsPassword(file.name), again = true)
        return opening(file.name, item.password, readBytes(file.path))
    }

    // The same, waiting on this thread, for the start.
    private fun loadSavedNow(id: String, file: CertFile): CertificateLoad {
        val item = certItemNow(id).getOrElse { return storeUnread(it) }
        if (item == null || item.path != file.path) return CertificateLoad.Unreadable(CertificateWords.needsPassword(file.name), again = true)
        return openingNow(file.name, item.password, readBytesNow(file.path))
    }

    private suspend fun readBytes(path: String): Result<ByteArray?> {
        val at = certificatePath(path) ?: return Result.failure(NoSuchFileException(path))
        return try {
            Result.success(certificateReads.bytes(at))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun readBytesNow(path: String): Result<ByteArray?> {
        val at = certificatePath(path) ?: return Result.failure(NoSuchFileException(path))
        return try {
            Result.success(certificateReads.bytesNow(at))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Opens the bytes of a file named `name` with `password`: Found, or
    // Unreadable with why. No bytes is a read past the bound. Off the
    // caller's thread, since its key's password check takes a while.
    private suspend fun opening(name: String, password: String, read: Result<ByteArray?>): CertificateLoad =
        withContext(Dispatchers.Default) { openingNow(name, password, read) }

    // The same, on this thread, for the start.
    private fun openingNow(name: String, password: String, read: Result<ByteArray?>): CertificateLoad {
        val bytes = read.getOrElse { return unreadableRead(it, name) }
            ?: return CertificateLoad.Unreadable(CertificateWords.notFound(name), again = false)
        return when (val opened = openCertificate(bytes, password, name, clock(), engineCheck)) {
            is CertificateOpened.Found -> CertificateLoad.Found(opened.certificate)
            is CertificateOpened.Refused -> CertificateLoad.Unreadable(if (opened.wrongPassword) CertificateWords.needsPassword(name) else opened.words, again = true)
        }
    }

    // Writes a certificate item under `id`, after the settings: the run
    // keeps it when the store refuses. The keep note's words, if any.
    private fun writeCertItem(id: String, item: CertSecret): String? = try {
        secrets.write(certificateAccount(id), certJson.encodeToString(CertSecret.serializer(), item))
        memoryCerts.remove(id)
        if (secrets.lasting || settings.readOnly) null else CertificateWords.SESSION_ONLY
    } catch (e: Exception) {
        memoryCerts[id] = item
        CertificateWords.writeRefused(e.message ?: e.javaClass.simpleName)
    }

    // Deletes a certificate item no settings entry names any more.
    private fun deleteCertItem(id: String): String? = try {
        secrets.delete(certificateAccount(id))
        null
    } catch (e: Exception) {
        CertificateWords.deleteRefused(secrets.label, e.message ?: e.javaClass.simpleName)
    }

    // What a keep does with client certificates: the file the saved record
    // names, the certificate the connection is made with, the item written
    // under the record's id after the settings (a file picked, or a copy
    // carried from another server), the servers whose items go after that,
    // the server whose run copy goes, whether a Remove was done, and the
    // words for the keep's note when the connection is made without the
    // record's own certificate.
    private class CertKeep(
        val file: CertFile?,
        val identity: LoadedCertificate?,
        val item: CertSecret? = null,
        val deletes: List<String> = emptyList(),
        val memoryDrop: String? = null,
        val removed: Boolean = false,
        val words: String? = null,
    )

    // What the keep does with certificates, by what the form asked and the
    // record the keep lands on: the replaced server, a twin, or a new one.
    // Null when a carried certificate's item could not be read.
    private suspend fun certificatesFor(
        draft: CertificateDraft,
        reached: Attempt.Reached,
        replacing: SavedServer?,
        edited: SavedServer?,
        twin: SavedServer?,
        id: String,
        use: Boolean,
    ): CertKeep? {
        val tried = reached.tried
        return when (draft) {
            is CertificateDraft.Use -> {
                // The server replaced under a new id leaves the list, and its item goes.
                val leaves = edited?.id?.takeIf { it != id }
                CertKeep(
                    CertFile(draft.path, certificateName(draft.path)),
                    tried.certificate,
                    item = CertSecret(draft.path, draft.password),
                    deletes = listOfNotNull(leaves),
                    memoryDrop = leaves,
                    words = (tried.load as? CertificateLoad.Unreadable)?.words.takeIf { use },
                )
            }
            // Only the server the row showed loses its certificate; on any
            // other record this is None.
            is CertificateDraft.Remove ->
                if ((edited ?: twin)?.id == draft.serverId) {
                    CertKeep(null, null, deletes = listOf(draft.serverId), memoryDrop = draft.serverId, removed = true)
                } else {
                    twinsOwn(twin, reached, use)
                }
            is CertificateDraft.Keep -> keepCertificate(draft.serverId, reached, replacing, edited, twin, id, use)
            // A kept server's own form starts with Keep; an edit asked with
            // None keeps what the server had.
            CertificateDraft.None ->
                if (edited != null) keepCertificate(edited.id, reached, replacing, edited, twin, id, use) else twinsOwn(twin, reached, use)
        }
    }

    // None, or what acts like it: a new server gets no certificate, and a
    // twin keeps its own, which the connection is made with when it can be
    // read now.
    private suspend fun twinsOwn(twin: SavedServer?, reached: Attempt.Reached, use: Boolean): CertKeep {
        val file = twin?.certificate ?: return CertKeep(null, null)
        return when (val load = twinLoad(twin, reached, use)) {
            null -> CertKeep(file, null)
            is CertificateLoad.Found -> CertKeep(file, load.certificate)
            is CertificateLoad.Unreadable -> CertKeep(file, null, words = load.words)
        }
    }

    // A twin's own certificate, loaded only when the keep makes the twin the
    // one in use and the certificate can go to the addresses just reached.
    private suspend fun twinLoad(twin: SavedServer, reached: Attempt.Reached, use: Boolean): CertificateLoad? {
        val file = twin.certificate ?: return null
        if (!use || !sendsCertificate(twin, reached.main, reached.home)) return null
        return loadSaved(twin.id, file)
    }

    // Keep(k): the certificate of k, the server the form showed.
    private suspend fun keepCertificate(
        k: String,
        reached: Attempt.Reached,
        replacing: SavedServer?,
        edited: SavedServer?,
        twin: SavedServer?,
        id: String,
        use: Boolean,
    ): CertKeep? {
        val tried = reached.tried
        // k's certificate, when the try sent it.
        val sentFile = (tried.choice as? CertificateChoice.Saved)?.takeIf { it.serverId == k }?.file
        val sent = tried.certificate.takeIf { sentFile != null }
        val words = (tried.load as? CertificateLoad.Unreadable)?.words.takeIf { sentFile != null && use }
        val kFile = find(k)?.certificate ?: sentFile
        // A kept server's form names that server; one naming another acts as
        // the edited server's own.
        if (edited != null && edited.id != k) return keepCertificate(edited.id, reached, replacing, edited, twin, id, use)
        // The server the request replaces keeps its certificate, under a new
        // id too; an API key of another account takes it to the new server
        // and leaves k its own.
        if (replacing?.id == k && (edited != null || twin == null)) {
            if (kFile == null) return CertKeep(null, null)
            if (id == k) return CertKeep(kFile, sent, words = words)
            return carry(k, kFile, id, sent, leaves = edited != null, words = words)
        }
        // A new server at k's own origin gets k's certificate when the try
        // sent it.
        if (twin == null) return if (sentFile != null && kFile != null) carry(k, kFile, id, sent, leaves = false, words = null) else CertKeep(null, null)
        // k itself, found again: nothing is written, and the certificate the
        // try sent is the one in use.
        if (twin.id == k) return if (sentFile != null) CertKeep(twin.certificate, sent, words = words) else twinsOwn(twin, reached, use)
        // Another twin T, and the try sent nothing of k's: T keeps its own.
        if (sentFile == null || kFile == null) return twinsOwn(twin, reached, use)
        // T with none takes the one that just worked.
        val own = twin.certificate ?: return carry(k, kFile, id, sent, leaves = false, words = null)
        // An Add keeps T's own without reading it.
        if (!use) return CertKeep(own, null)
        return when (val load = twinLoad(twin, reached, use)) {
            null -> CertKeep(own, null)
            is CertificateLoad.Found -> CertKeep(own, load.certificate)
            // T's own needs choosing again: T takes the one that just worked.
            is CertificateLoad.Unreadable -> if (load.again) {
                carry(k, kFile, id, sent, leaves = false, words = null)
            } else {
                // T's file is only out of reach now: T keeps it, and this
                // run goes on with the one that just worked.
                CertKeep(own, sent, words = load.words)
            }
        }
    }

    // k's certificate carried to `id`: the same file, and k's item (this
    // run's copy first) copied there after the settings. An item that is
    // not there, or names another file, carries the file alone, and whatever
    // sits under `id` goes. Null when the store failed to read it.
    private suspend fun carry(k: String, file: CertFile, id: String, sent: LoadedCertificate?, leaves: Boolean, words: String?): CertKeep? {
        val item = certItem(k).getOrElse { return null }?.takeIf { it.path == file.path }
        return CertKeep(
            file,
            sent,
            item = item,
            deletes = listOfNotNull(k.takeIf { leaves }, id.takeIf { item == null }),
            memoryDrop = k.takeIf { leaves },
            words = words,
        )
    }

    // The system's store is slow at times, and can wait on the listener,
    // so it is only used off the window's thread, one call at a time so a
    // sign-out's delete cannot land after the next sign-in's write.
    private val keychain = Dispatchers.IO.limitedParallelism(1)

    private fun SignInRequest.secretFor(): String = if (mode == AuthMode.ApiKey) secret.trim() else secret

    // Tries the server the way the request asks, through a client of its
    // own that offers the certificate the request sends. A server that
    // cannot check tokens is tried again with the password itself where that
    // is safe, with the same certificate, loaded once.
    private suspend fun attempt(request: SignInRequest, editing: SavedServer? = null, mode: AuthMode = request.mode, loaded: CertificateTry? = null): Attempt {
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
        val headers = when (val resolved = headersFor(request, main, editing)) {
            is ResolvedHeaders.Problem -> return Attempt.Failed(resolved.text)
            is ResolvedHeaders.Ready -> resolved.headers.associate { it.name to it.value }
        }
        val tried = loaded ?: certificateFor(request.certificate, editing?.id, main, home).let { choice -> CertificateTry(choice, loadChoice(choice)) }
        var creds = Credentials(request.username.trim(), secret, mode)
        return checkClient(listOfNotNull(main, home), headers, tried.certificate, COMMIT_SECONDS).use { own ->
            val (reachedUrl, info) = try {
                reach(own.http, main, home, creds)
            } catch (e: SubsonicException) {
                untrusted(own, e, main, home)?.let { return Attempt.Untrusted(it) }
                // The server asked for a client certificate, or turned one
                // down; one that could not be read says why first.
                own.security.certificateWords(listOfNotNull(main, home))?.let { words ->
                    return Attempt.Failed((tried.load as? CertificateLoad.Unreadable)?.words ?: words, certificate = true, tried = tried)
                }
                return when (legacyRetry(e, mode, main)) {
                    LegacyRetry.Retry -> attempt(request, editing, AuthMode.LegacyPassword, tried)
                    LegacyRetry.Unsafe -> Attempt.Failed(SignInWords.LEGACY_UNSAFE, tried = tried)
                    LegacyRetry.None -> Attempt.Failed(e.userMessage() + homeHttpsHint(main, e), (e as? SubsonicException.NotSubsonic)?.gateway, tried = tried)
                }
            }
            // A home address with a certificate the system does not trust is
            // asked about now, while the listener is here to answer.
            if (home != null && reachedUrl != home) {
                val quick = own.http.newBuilder().callTimeout(HOME_PING_SECONDS, TimeUnit.SECONDS).build()
                val homeError = runCatching { SubsonicClient(home, creds, quick).ping() }.exceptionOrNull()
                if (homeError is SubsonicException) untrusted(own, homeError, home)?.let { return Attempt.Untrusted(it) }
            }
            val client = SubsonicClient(reachedUrl, creds, own.http)
            // With an API key the server says whose it is.
            if (request.mode == AuthMode.ApiKey) {
                val owner = runCatching { client.tokenInfo() }.getOrNull()
                if (!owner.isNullOrBlank()) creds = creds.named(owner)
            }
            val extensions = if (info.openSubsonic) runCatching { client.extensions() }.getOrDefault(emptyList()) else emptyList()
            Attempt.Reached(main, home, creds, headers, ServerFacts(info, extensions), tried)
        }
    }

    // The headers a request asks for: its rows, with saved values filled in
    // where a saved row was left empty, and the sign-in page fields; or what
    // is wrong with them (a sign-in page's headers over plain http across
    // the internet among it).
    private suspend fun headersFor(request: SignInRequest, main: HttpUrl, editing: SavedServer?): ResolvedHeaders {
        // A header row left completely empty is ignored; a half-filled or
        // malformed one is pointed out rather than dropped.
        val unusable = request.headers.any { row ->
            val name = row.name.trim()
            val value = row.value.trim()
            (name.isNotEmpty() || value.isNotEmpty()) &&
                (!isHeaderName(name) || !isHeaderValue(value) || (value.isEmpty() && !row.saved))
        }
        if (unusable) return ResolvedHeaders.Problem("Each header needs a name with no spaces and a plain text value.")
        val needsSaved = request.headers.any { it.saved } || request.gateway.saved != GatewayKind.None
        val saved = if (needsSaved) withContext(keychain) { savedHeaders(main, request.username.trim(), editing) } else emptyList()
        return resolveConnectionHeaders(request.headers, request.gateway, saved, main.isHttps, isPrivateHost(main))
    }

    // Whether these headers hold one a sign-in page reads, which can't go to
    // this address: plain http to a host outside the home network.
    private fun needsHttps(main: HttpUrl, headers: Map<String, String>): Boolean =
        headers.keys.any(GatewayNames::isGatewayName) && !gatewayAllowed(main.isHttps, isPrivateHost(main))

    // Pings the main address. When that cannot connect and there is a home
    // address, pings that instead: at home the main address may not loop back.
    private suspend fun reach(http: OkHttpClient, main: HttpUrl, home: HttpUrl?, creds: Credentials): Pair<HttpUrl, ServerInfo> =
        try {
            main to SubsonicClient(main, creds, http).ping()
        } catch (e: SubsonicException.Unreachable) {
            if (home == null || failedOnTls(e)) throw e
            val info = runCatching { SubsonicClient(home, creds, http).ping() }.getOrNull() ?: throw e
            home to info
        }

    // A failure caused by a certificate the system does not trust, as a
    // question for the listener, from the check's own client. Null for any
    // other failure.
    private fun untrusted(own: CheckClient, e: SubsonicException, vararg urls: HttpUrl?): CertificateQuestion? {
        if (e !is SubsonicException.Unreachable || !failedOnTls(e)) return null
        for (url in urls.filterNotNull()) {
            val leaf = own.security.takeRejected(url.host) ?: continue
            return CertificateQuestion(url.host, leaf)
        }
        return null
    }

    // Makes the connection: the shared client set up for the server, the
    // home address watched when there is one (through a client of its own,
    // closed with the route), and a client whose calls follow it. It is
    // stamped with the ticket of the change that made it.
    private fun activate(saved: SavedServer, secret: String, headers: Map<String, String>, generation: Long, certificate: LoadedCertificate?): Connection {
        val main = saved.address.toHttpUrlOrNull() ?: normalizeServerUrl(saved.address)!!
        val home = saved.home?.toHttpUrlOrNull()?.takeIf { it != main }
        val creds = Credentials(saved.username, secret, saved.authMode)
        route?.close()
        val chooser = home?.let { at ->
            val checks = checkClient(listOf(main, at), headers, certificate, HOME_PING_SECONDS)
            HomeRoute(main, at, { url -> answersAt(checks, url, creds) }, moved = { onRouteMoved?.invoke() }, closing = checks)
        }
        route = chooser
        // The client certificate goes to the server's own https addresses
        // only, for the app's calls and the audio engine's, before anything is
        // made with it.
        security.configure(main, home, headers, certificate)
        engineIdentity.use(certificate, certificateOrigins(main, home))
        security.onConnectionError = chooser?.let { it::connectionFailed }
        // Open connections were made for the old server; start fresh.
        http.connectionPool.evictAll()
        activeGeneration = generation
        // "Octo" as the client name, as the phone app sends: Octo then
        // serves covers of songs found online plain, without its mark.
        val client = SubsonicClient(main, creds, http, clientName = "Octo", route = { chooser?.activeUrl() ?: main })
        return Connection(client, saved, headers, generation, gatewayOrigins(main, home))
    }

    // Whether the home address answers like the server does.
    private suspend fun answersAt(checks: CheckClient, url: HttpUrl, creds: Credentials): Boolean = try {
        SubsonicClient(url, creds, checks.http).ping()
        true
    } catch (e: SubsonicException) {
        // An error from the server itself still means it is there.
        e !is SubsonicException.Unreachable && e !is SubsonicException.NotSubsonic
    }

    // The headers saved for this server, for values left unchanged: the one
    // being edited, else the same account kept at the same address, else
    // any kept there.
    private fun savedHeaders(main: HttpUrl, username: String, editing: SavedServer?): List<ServerHeader> {
        val address = main.toString()
        val saved = editing
            ?: servers.firstOrNull { it.address == address && it.username == username }
            ?: servers.firstOrNull { it.address == address }
            ?: return emptyList()
        return (memoryHeaders[saved.id] ?: readHeaders(saved)).orEmpty().map { (name, value) -> ServerHeader(name, value) }
    }

    // The saved server's header values from the store, or null when it has
    // headers and the store no longer holds them. Values an older version
    // filed by address are moved under the server's own id.
    private fun readHeaders(saved: SavedServer): Map<String, String>? {
        if (saved.headerNames.isEmpty()) return emptyMap()
        runCatching { secrets.read(headersAccount(saved)) }.getOrNull()?.let { return decodeHeaders(it) }
        val text = runCatching { secrets.read(headersAccount(saved.address)) }.getOrNull() ?: return null
        val headers = decodeHeaders(text) ?: return null
        if (runCatching { secrets.write(headersAccount(saved), text) }.isSuccess) dropAddressHeaders(saved.address)
        return headers
    }

    private fun decodeHeaders(text: String): Map<String, String>? =
        runCatching { headersJson.decodeFromString(headerList, text) }.getOrNull()?.associate { it.name to it.value }

    // Puts the header values in the store under the server's id, or takes
    // them out when there are none. A note when the store refuses; they are
    // then kept for this run.
    private fun keepHeaders(server: SavedServer, headers: Map<String, String>): String? {
        if (headers.isEmpty()) {
            runCatching { secrets.delete(headersAccount(server)) }
            return null
        }
        val list = headers.map { (name, value) -> ServerHeader(name, value) }
        return try {
            secrets.write(headersAccount(server), headersJson.encodeToString(headerList, list))
            null
        } catch (e: SecretStoreException) {
            "Octo couldn't save the server's headers (${e.message}), so it will ask for them next time."
        }
    }

    // Deletes header values an older version filed under an address, once
    // no kept server still reads them from there: each moves its own under
    // its id the first time it is read.
    private fun dropAddressHeaders(address: String) {
        val stillRead = servers.any { server ->
            server.address == address && server.headerNames.isNotEmpty() &&
                runCatching { secrets.read(headersAccount(server)) }.getOrNull() == null
        }
        if (!stillRead) runCatching { secrets.delete(headersAccount(address)) }
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

// Where a server's header values are filed in the system's store: under its
// id, so two accounts at one address each keep their own.
fun headersAccount(server: SavedServer): String = "headers@${server.id}"

// Where versions before ids filed them: by address, shared by every
// account there.
fun headersAccount(address: String): String = "headers@$address"

private val headersJson = Json { ignoreUnknownKeys = true }
private val headerList = ListSerializer(ServerHeader.serializer())
private val certJson = Json { ignoreUnknownKeys = true }

// A hint for an https address on the home network that could not be
// reached: servers at home often speak only plain http. A certificate from
// a distrusted authority did reach the server, so it gets no hint.
fun homeHttpsHint(url: HttpUrl, e: Throwable): String =
    if (e is SubsonicException.Unreachable && url.isHttps && isPrivateHost(url) && !e.isDistrusted()) " Home servers often use http://. Switch to http:// and try again." else ""

// What to tell someone when a request fails, in the phone app's words. A
// sign-in page in front of the server is named, with how to get through.
fun Throwable.userMessage(): String = when (this) {
    is SubsonicException.Unreachable ->
        if (isDistrusted()) DISTRUSTED_MESSAGE
        else SignInWords.UNREACHABLE
    is SubsonicException.NotSubsonic ->
        GatewayWords.failureFor(this)
            ?: if (serverBusy) SignInWords.RESTARTING
            else SignInWords.NOT_MUSIC
    is SubsonicException.WrongCredentials -> SignInWords.WRONG_PASSWORD
    is SubsonicException.AuthNotSupported ->
        if (code == 41) SignInWords.LEGACY_HINT
        else SignInWords.WAY_REFUSED
    is SubsonicException.NotFound -> "That isn't on the server any more."
    is SubsonicException.Server -> "The server said: $message"
    else -> SignInWords.SOMETHING_WRONG
}
