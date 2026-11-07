package app.winters.octo.subsonic.gateway

import okhttp3.Cookie
import okhttp3.HttpUrl

private const val MAX_COOKIES = 16

// A sign-in page's own session cookies: Authentik's, Authelia's and
// Cloudflare Access's. Octo's own (its admin and family pages') are not
// among them, so keeping these never changes how Octo sees the app.
fun isGatewayCookie(name: String): Boolean =
    name.startsWith("authentik_proxy") || name == "authelia_session" || name == "CF_Authorization"

// The session cookies a sign-in page in front of the server hands the
// app, for one configuration only: in memory, never on disk, gone with the
// configuration. At most 16; the one that expires first makes room.
class GatewayCookies(private val now: () -> Long = System::currentTimeMillis) {
    private val kept = mutableListOf<Cookie>()

    // Takes the gateway cookies from an answer's Set-Cookie headers, in the
    // order they came. One that has expired removes the cookie of the same
    // name, domain and path.
    @Synchronized
    fun keep(url: HttpUrl, setCookies: List<String>) {
        for (line in setCookies) {
            val cookie = Cookie.parse(url, line) ?: continue
            if (!isGatewayCookie(cookie.name)) continue
            kept.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
            if (cookie.expiresAt > now()) kept += cookie
        }
        while (kept.size > MAX_COOKIES) kept.remove(kept.minBy { it.expiresAt })
    }

    // "a=1; b=2" for the cookies this address may have that have not
    // expired, or null when there are none.
    @Synchronized
    fun cookieHeader(url: HttpUrl): String? {
        val time = now()
        kept.removeAll { it.expiresAt <= time }
        return kept.filter { it.matches(url) }.takeIf { it.isNotEmpty() }?.joinToString("; ") { "${it.name}=${it.value}" }
    }

    @Synchronized
    fun isEmpty(): Boolean {
        val time = now()
        return kept.none { it.expiresAt > time }
    }

    // Cookie values are secrets, so only how many are kept is ever printed.
    override fun toString() = "GatewayCookies(${synchronized(this) { kept.size }} kept)"
}
