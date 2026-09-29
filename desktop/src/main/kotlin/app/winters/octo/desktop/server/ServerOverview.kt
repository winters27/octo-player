package app.winters.octo.desktop.server

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.winters.octo.desktop.settings.SavedServer
import app.winters.octo.server.answerWords
import app.winters.octo.server.libraryCounts
import app.winters.octo.server.scanWords
import app.winters.octo.server.serverKind
import app.winters.octo.server.serverOffers
import app.winters.octo.subsonic.AuthMode
import app.winters.octo.subsonic.ScanStatus
import app.winters.octo.subsonic.SubsonicException
import app.winters.octo.subsonic.User
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// How often a running scan is asked how far it is.
private const val SCAN_POLL_MS = 2_000L

// What the Servers section says about the server in use, in words.
data class ServerOverview(
    // "Octo 0.9.3".
    val kind: String,
    // What it brings beyond the music, or null.
    val offers: String?,
    // "1,204 songs, 96 albums, 41 artists and 12 playlists", once read.
    val counts: String?,
    // Whether it is reading its folders now, or when it last did.
    val scan: String?,
    val scanning: Boolean,
    // Whether the listener may ask it to read its folders (admins only).
    val canScan: Boolean,
    // "Answered in 42 ms", once asked.
    val answer: String?,
    // Whether a password can be changed here, and why not when it cannot.
    val canChangePassword: Boolean,
    val passwordNote: String?,
)

// The overview of the server in use from what is known of it so far. A
// count, the scan or the user not read yet is left out rather than guessed.
fun overviewOf(
    server: SavedServer,
    lyrics: Boolean,
    adds: Boolean,
    songs: Int?,
    albums: Int?,
    artists: Int?,
    playlists: Int?,
    scan: ScanStatus?,
    user: User?,
    answerMs: Long?,
    now: Long,
): ServerOverview {
    val key = server.authMode == AuthMode.ApiKey
    val mayChange = user?.settingsRole != false
    return ServerOverview(
        kind = serverKind(server.serverType, server.serverVersion),
        offers = serverOffers(lyrics, adds),
        counts = libraryCounts(songs, albums, artists, playlists),
        scan = scanWords(scan, now),
        scanning = scan?.scanning == true,
        canScan = user?.adminRole == true,
        answer = answerMs?.let(::answerWords),
        canChangePassword = !key && mayChange,
        passwordNote = when {
            key -> "You signed in with an API key, so there's no password here to change."
            !mayChange -> "Your server doesn't let this account change its password."
            else -> null
        },
    )
}

// Who is signed in where, for a server's row: "winters at music.example.com".
fun accountLine(server: SavedServer): String {
    val where = server.address.substringAfter("://").removeSuffix("/")
    return if (server.username.isBlank()) where else "${server.username} at $where"
}

// A server's quiet status in its row: what it is, and how it answered.
// The one in use shows only its kind here; the rest is said below the list.
fun statusLine(server: SavedServer, check: ServerCheck?, switching: Boolean = false, inUse: Boolean = false): String {
    val kind = serverKind(server.serverType, server.serverVersion)
    return when {
        switching -> "Switching to it now"
        server.signedOut -> "Signed out. Sign in again to use it."
        inUse || check == null || check.reach == Reach.Unknown -> kind
        check.reach == Reach.Answers -> listOfNotNull(kind, check.ms?.let(::answerWords)).joinToString(" · ")
        check.reach == Reach.WrongPassword -> "$kind · Didn't take the saved password"
        else -> "$kind · Out of reach right now"
    }
}

// What is known about the kept servers while the Servers section is open:
// how each answered, and the scan and user of the one in use.
@Stable
class ServerFactsModel(
    private val accounts: Accounts,
    private val connection: () -> Connection?,
    private val scope: CoroutineScope,
) {
    val checks = mutableStateMapOf<String, ServerCheck>()

    var scan by mutableStateOf<ScanStatus?>(null)
        private set

    var user by mutableStateOf<User?>(null)
        private set

    // Why a scan could not start, in plain words, until the next try.
    var scanProblem by mutableStateOf<String?>(null)
        private set

    private var following: Job? = null

    // Asks every kept server how it is, and the one in use what it is
    // doing. Servers signed out of are not asked.
    suspend fun refresh() {
        val inUse = connection()
        coroutineScope {
            accounts.servers.forEach { server -> launch { checks[server.id] = accounts.check(server.id, inUse) } }
            if (inUse != null) {
                launch { scan = runCatching { inUse.client.scanStatus() }.getOrNull() }
                launch { user = runCatching { inUse.client.user(inUse.client.username) }.getOrNull() }
            }
        }
        if (scan?.scanning == true) follow(inUse, onDone = {})
    }

    // Everything known belonged to the last server in use.
    fun forget() {
        following?.cancel()
        checks.clear()
        scan = null
        user = null
        scanProblem = null
    }

    // Asks the server to read its folders again, then follows it until it
    // is done, when `onDone` reads the library again.
    fun startScan(onDone: () -> Unit) {
        val inUse = connection() ?: return
        if (scan?.scanning == true) return
        scanProblem = null
        scope.launch {
            try {
                scan = inUse.client.startScan().let { it.copy(scanning = true) }
                follow(inUse, onDone)
            } catch (e: SubsonicException) {
                scanProblem = if (e is SubsonicException.Server && e.code == 50) "Only an admin can scan this server." else "The scan couldn't start just now. " + e.userMessage()
            }
        }
    }

    // Asks how the scan is going every 2 seconds until it has stopped. Some
    // servers answer before a scan has begun, so "not scanning" counts only
    // once it was seen running, or when said twice.
    private fun follow(inUse: Connection?, onDone: () -> Unit) {
        inUse ?: return
        following?.cancel()
        following = scope.launch {
            var seen = false
            var quiet = 0
            while (true) {
                delay(SCAN_POLL_MS)
                if (connection() !== inUse) return@launch
                val now = runCatching { inUse.client.scanStatus() }.getOrNull() ?: continue
                if (now.scanning) {
                    seen = true
                    quiet = 0
                    scan = now
                } else if (seen || ++quiet >= 2) {
                    scan = now
                    onDone()
                    return@launch
                }
            }
        }
    }
}
