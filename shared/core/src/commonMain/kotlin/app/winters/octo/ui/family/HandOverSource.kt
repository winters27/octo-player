package app.winters.octo.ui.family

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.winters.octo.subsonic.FamilySignInPending
import app.winters.octo.subsonic.FamilySignInStart
import app.winters.octo.subsonic.SubsonicClient
import app.winters.octo.subsonic.SubsonicException
import app.winters.octo.subsonic.familySignInUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// The "Sign in on another device" QR code while it shows: its token and
// key, how renewing it goes, a device asking to sign in, and how handing
// the sign-in over went. Each one opened has its own `id`, so an answer
// for one already closed is dropped.
data class HandOverSheet(
    val id: Int = 0,
    val start: FamilySignInStart? = null,
    // Made on this device; it is only ever in the QR code.
    val key: String = "",
    val openedAt: Long = 0,
    val loading: Boolean = false,
    val error: String? = null,
    // Open ten minutes: renewing stopped, and the sheet asks.
    val stale: Boolean = false,
    val refreshFailed: Boolean = false,
    val retryAt: Long = 0,
    // How many times the code was renewed, for the "New code" notice.
    val renewed: Int = 0,
    // A device that scanned the code and waits for Allow or Deny.
    val asking: FamilySignInPending? = null,
    val sending: Boolean = false,
    // How it ended: handed over, or why not.
    val done: String? = null,
    val failed: String? = null,
) {
    val waiting: Boolean get() = start == null && error == null

    // The QR code's link both ways: through the outside address and the
    // home one. The choice picks where it is redeemed and which address the
    // new device signs in to; the home address always goes along.
    fun options(awayAllowed: Boolean): LinkOptions? {
        val begun = start ?: return null
        val anywhere = begun.links.anywhere?.let(::baseOf)
        val home = begun.links.home?.let(::baseOf)
        fun link(base: String) = familySignInUrl(base, begun.token, key, base, home)
        return LinkOptions(anywhere?.let(::link), home?.let(::link), begun.anywhereAvailable && anywhere != null, awayAllowed)
    }

    // Never print the token or the key.
    override fun toString() = "HandOverSheet(id=$id, loading=$loading, asking=${asking != null}, done=${done != null})"
}

// A server's address from what the server answered, a bare address or a
// link on it.
private fun baseOf(text: String): String = (linkServer(text) ?: text).removeSuffix("/")

// The name and platform of the device asking, as the prompt shows them.
fun askingTitle(asking: FamilySignInPending): String = "Sign in on ${asking.deviceName.ifBlank { "a new device" }}?"

fun askingLine(asking: FamilySignInPending): String =
    listOf(asking.platform, "It gets your sign-in for this server. Allow it only if it's yours.").filter(String::isNotBlank).joinToString(" · ")

const val HANDOVER_TITLE = "Sign in on another device"
const val HANDOVER_CAPTION = "Scan with Octo on the new device, or with its camera"
const val HANDOVER_FAILED = "Couldn't make a code. Try again."
const val HANDOVER_SEND_FAILED = "Couldn't send your sign-in. Make a new code and try again."
const val ALLOW = "Allow"
const val DENY = "Deny"

// How long before expiry a code is renewed, how often a failed renewal is
// tried again, and how long the sheet renews at most.
const val HANDOVER_LEAD_MS = 5_000L
const val HANDOVER_RETRY_MS = 10_000L
const val HANDOVER_FOR_MS = 10 * 60 * 1000L

// The signed-in device's side of a hand-over. While the sheet shows and
// the app is on screen: the code renews a few seconds before it expires
// (with a new key), and the server is asked every couple of seconds
// whether a device redeemed it. Nothing is sent until the person allows
// that device; then the sign-in is sealed with the key and handed over.
@Stable
class HandOverSource(
    private val client: () -> SubsonicClient?,
    private val scope: CoroutineScope,
    // The account's name as the family knows it, sent along.
    private val displayName: () -> String = { "" },
    private val clock: () -> Long = System::currentTimeMillis,
    private val tickMs: Long = 250,
    private val askEveryMs: Long = 2_000,
    private val newKey: () -> String = HandOverBox::newKey,
) {
    var sheet by mutableStateOf<HandOverSheet?>(null)
        private set

    var onScreen by mutableStateOf(true)
        private set

    private var opened = 0
    private var running: Job? = null

    // Opens the sheet with a fresh code.
    fun open() {
        opened += 1
        sheet = HandOverSheet(id = opened, openedAt = clock(), key = newKey())
        val id = opened
        scope.launch { begin(id) }
    }

    fun close() {
        running?.cancel()
        running = null
        sheet = null
    }

    fun sheetOnScreen(visible: Boolean) {
        onScreen = visible
    }

    // After ten minutes, or after a failure: a fresh code, renewing again.
    fun newCode() {
        val open = sheet ?: return
        sheet = open.copy(openedAt = clock(), stale = false, refreshFailed = false, error = null, failed = null, done = null, asking = null)
        scope.launch { begin(open.id) }
    }

    fun retry() = newCode()

    // The device asking is the person's own: tell the server, then seal this
    // device's sign-in with the key and hand it over.
    fun allow() {
        val open = sheet ?: return
        val asking = open.asking ?: return
        val start = open.start ?: return
        val client = client() ?: return
        running?.cancel()
        sheet = open.copy(sending = true)
        scope.launch {
            val sent = runCatching {
                val web = client.familyWeb()
                web.decideSignIn(start.token, allow = true)
                web.putSignInBox(start.token, HandOverBox.seal(signInOf(client, open), open.key))
            }
            val now = sheet?.takeIf { it.id == open.id } ?: return@launch
            sheet = if (sent.isSuccess) {
                now.copy(sending = false, asking = null, done = "Sent to ${asking.deviceName.ifBlank { "your other device" }}. It's signing in now.")
            } else {
                now.copy(sending = false, asking = null, failed = HANDOVER_SEND_FAILED)
            }
        }
    }

    // Not the person's device: tell the server no, and show a fresh code,
    // since this one is used up.
    fun deny() {
        val open = sheet ?: return
        val start = open.start ?: return
        val client = client() ?: return
        sheet = open.copy(asking = null, loading = true)
        scope.launch {
            runCatching { client.familyWeb().decideSignIn(start.token, allow = false) }
            renew(open.id)
        }
    }

    // What travels in the box: this app's own sign-in for the server.
    private fun signInOf(client: SubsonicClient, open: HandOverSheet): HandOverSignIn {
        val links = open.start?.links
        val anywhere = links?.anywhere?.let(::baseOf)
        val home = links?.home?.let(::baseOf)
        return HandOverSignIn(
            username = client.username,
            secret = client.handOverSecret(),
            mode = client.authMode.name,
            server = anywhere ?: client.primaryUrl.toString().removeSuffix("/"),
            home = home,
            displayName = displayName(),
        )
    }

    private suspend fun begin(id: Int) {
        running?.cancel()
        val open = sheet?.takeIf { it.id == id } ?: return
        sheet = open.copy(loading = true, error = null)
        val started = startCode()
        val now = sheet?.takeIf { it.id == id } ?: return
        if (started == null) {
            sheet = now.copy(loading = false, error = HANDOVER_FAILED)
            return
        }
        sheet = now.copy(start = started.first, key = started.second, loading = false)
        running = scope.launch { watch(id) }
    }

    // A new token, and a new key to go with it.
    private suspend fun startCode(): Pair<FamilySignInStart, String>? {
        val client = client() ?: return null
        return try {
            client.familyWeb().startSignIn() to newKey()
        } catch (e: SubsonicException) {
            null
        }
    }

    private suspend fun renew(id: Int) {
        val open = sheet?.takeIf { it.id == id } ?: return
        sheet = open.copy(loading = true)
        val started = startCode()
        val now = sheet?.takeIf { it.id == id } ?: return
        sheet = if (started != null) {
            now.copy(start = started.first, key = started.second, loading = false, refreshFailed = false, renewed = now.renewed + 1)
        } else {
            now.copy(loading = false, refreshFailed = true, retryAt = clock() + HANDOVER_RETRY_MS)
        }
        if (running?.isActive != true) running = scope.launch { watch(id) }
    }

    private suspend fun watch(id: Int) {
        // Counted in ticks, not by the clock, which only says when codes expire.
        var sinceAsk = askEveryMs
        while (true) {
            val open = sheet?.takeIf { it.id == id } ?: return
            val start = open.start
            val busy = open.loading || open.sending || open.asking != null || open.done != null || open.failed != null || open.stale
            if (onScreen && !busy && start != null) {
                val due = if (open.refreshFailed) open.retryAt else expiresAtMs(start.expires)?.minus(HANDOVER_LEAD_MS)
                if (due != null && clock() >= due) {
                    if (clock() - open.openedAt >= HANDOVER_FOR_MS) {
                        sheet = open.copy(stale = true, refreshFailed = false)
                        return
                    }
                    renew(id)
                } else if (!open.refreshFailed && sinceAsk >= askEveryMs) {
                    sinceAsk = 0
                    val asking = runCatching { client()?.familyWeb()?.signInPending(start.token) }.getOrNull()
                    if (asking != null) sheet = sheet?.takeIf { it.id == id && it.start?.token == start.token }?.copy(asking = asking)
                }
            }
            delay(tickMs)
            sinceAsk += tickMs
        }
    }
}
