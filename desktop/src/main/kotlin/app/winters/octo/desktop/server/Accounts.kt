package app.winters.octo.desktop.server

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
import app.winters.octo.subsonic.normalizeServerUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

// The OpenSubsonic extensions the desktop app looks for.
const val SONG_LYRICS = "songLyrics"
const val OCTO_LYRICS = "octoLyrics"

// The signed-in server: the client that talks to it, and what it said
// about itself when signing in.
class Connection(val client: SubsonicClient, val server: SavedServer) {
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

sealed interface SignInOutcome {
    // Signed in. `remembered` says whether the password went into the
    // system's store; when it could not, `note` says why in plain words.
    class Done(val connection: Connection, val remembered: Boolean, val note: String? = null) : SignInOutcome

    class Failed(val message: String) : SignInOutcome
}

sealed interface TestOutcome {
    class Reached(val facts: ServerFacts) : TestOutcome
    class Failed(val message: String) : TestOutcome
}

// Signing in to a server, remembering it, and signing out. The address,
// username and what the server said are kept in the settings file; the
// password goes only to the system's password store.
class Accounts(
    private val settings: SettingsStore,
    private val secrets: SecretStore,
    private val http: OkHttpClient,
) {
    // Asks the server who it is and which extensions it lists, without
    // keeping anything.
    suspend fun test(address: String, username: String, password: String, mode: AuthMode = AuthMode.Token): TestOutcome {
        val url = normalizeServerUrl(address) ?: return TestOutcome.Failed("That doesn't look like a server address.")
        if (username.isBlank() || password.isEmpty()) return TestOutcome.Failed("Enter a username and password.")
        val client = SubsonicClient(url, Credentials(username.trim(), password, mode), http)
        return try {
            val info = client.ping()
            val extensions = if (info.openSubsonic) runCatching { client.extensions() }.getOrDefault(emptyList()) else emptyList()
            TestOutcome.Reached(ServerFacts(info, extensions))
        } catch (e: SubsonicException) {
            TestOutcome.Failed(e.userMessage())
        }
    }

    // Tests the connection, then keeps it: the password in the system's
    // store and the rest in the settings. A store that refuses does not stop
    // the sign-in; the password is then asked for again next time.
    suspend fun signIn(address: String, username: String, password: String, mode: AuthMode = AuthMode.Token): SignInOutcome {
        val facts = when (val tested = test(address, username, password, mode)) {
            is TestOutcome.Failed -> return SignInOutcome.Failed(tested.message)
            is TestOutcome.Reached -> tested.facts
        }
        val url = normalizeServerUrl(address)!!
        val saved = SavedServer(
            address = url.toString(),
            username = username.trim(),
            authMode = mode,
            serverType = facts.info.type,
            serverVersion = facts.info.serverVersion,
            openSubsonic = facts.info.openSubsonic,
            extensions = facts.extensionKeys,
        )
        val old = settings.current.server
        val note = withContext(keychain) {
            // A different server or user replaces the old one, whose password goes.
            if (old != null && (old.address != saved.address || old.username != saved.username)) {
                runCatching { secrets.delete(secretAccount(old.username, old.address)) }
            }
            try {
                secrets.write(secretAccount(saved.username, saved.address), password)
                if (secrets.lasting) null else "This computer has no password store, so Octo will ask for the password each time it opens."
            } catch (e: SecretStoreException) {
                "Octo couldn't save the password (${e.message}), so it will ask for it next time."
            }
        }
        settings.update { it.copy(server = saved) }
        memory = password
        return SignInOutcome.Done(Connection(clientFor(saved, password), saved), remembered = note == null, note = note)
    }

    // The saved server, ready to use, or null when there is none or its
    // password is not in the store. Nothing is asked of the server here.
    // The store can wait on the listener (a locked keyring asks to be
    // unlocked), so this is called before the window opens, not on it.
    fun restore(): Connection? {
        val saved = settings.current.server ?: return null
        val password = memory ?: runCatching { secrets.read(secretAccount(saved.username, saved.address)) }.getOrNull() ?: return null
        return Connection(clientFor(saved, password), saved)
    }

    // The server signed in to last, for filling in the sign-in page.
    val last: SavedServer? get() = settings.current.server

    // Forgets the server at once, and the password off the window's thread.
    suspend fun signOut() {
        val old = settings.current.server
        memory = null
        settings.update { it.copy(server = null) }
        if (old != null) withContext(keychain) { runCatching { secrets.delete(secretAccount(old.username, old.address)) } }
    }

    val storeLabel: String get() = secrets.label

    // The password of this run, for a store that cannot keep it.
    @Volatile private var memory: String? = null

    // The system's store is slow at times, and can wait on the listener,
    // so it is only used off the window's thread, one call at a time so a
    // sign-out's delete cannot land after the next sign-in's write.
    private val keychain = Dispatchers.IO.limitedParallelism(1)

    private fun clientFor(saved: SavedServer, password: String): SubsonicClient {
        val url = saved.address.toHttpUrlOrNull() ?: normalizeServerUrl(saved.address)!!
        // "Octo" as the client name, as the phone app sends: Octo then
        // serves covers of songs found online plain, without its mark.
        return SubsonicClient(url, Credentials(saved.username, password, saved.authMode), http, clientName = "Octo")
    }
}

// What to tell someone when a request fails, in the phone app's words.
fun Throwable.userMessage(): String = when (this) {
    is SubsonicException.Unreachable ->
        "Couldn't reach the server. Check the address, and that you're on the right network."
    is SubsonicException.NotSubsonic ->
        "That address answered, but not like a music server. Check the port."
    is SubsonicException.WrongCredentials -> "Wrong username or password."
    is SubsonicException.AuthNotSupported ->
        if (code == 41) "This account can't sign in with a token. Turn on \"Send the password itself\"."
        else "The server doesn't take this way of signing in."
    is SubsonicException.NotFound -> "That isn't on the server any more."
    is SubsonicException.Server -> "The server said: $message"
    else -> "Something went wrong."
}
