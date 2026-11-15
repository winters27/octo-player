package app.winters.octo.subsonic

import app.winters.octo.subsonic.gateway.GatewayCookies
import app.winters.octo.subsonic.gateway.GatewayNames
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

// Extra headers a server needs on every request, such as an access token
// for a proxy in front of it, and the addresses they may go to. The three
// names a sign-in page in front of the server uses (GatewayNames) go only
// to `gatewayOrigins`, whatever row they came from. `cookies` holds that
// sign-in page's session for this configuration alone. `device` names this
// app install to the server, so an Octo server can tell one phone from
// another; it goes only to the same addresses.
class HeaderScope(
    val origins: Set<String>,
    val headers: Map<String, String>,
    val gatewayOrigins: Set<String>,
    val cookies: GatewayCookies,
    val device: DeviceIdentity? = null,
) {
    fun covers(url: HttpUrl) = origin(url) in origins

    // The headers for a request to this address: every one at the server's
    // addresses, a sign-in page's only where they may go.
    fun headersFor(url: HttpUrl): Map<String, String> {
        if (!covers(url)) return emptyMap()
        val gateway = origin(url) in gatewayOrigins
        return headers.filter { (name, _) -> gateway || !GatewayNames.isGatewayName(name) }
    }

    // Header values are secrets, so only the names are ever printed.
    override fun toString() =
        "HeaderScope(origins=$origins, gatewayOrigins=$gatewayOrigins, headers=${headers.keys}, device=${device != null})"
}

// Where a request goes: scheme, host and port. Two addresses with the same
// origin reach the same server.
fun origin(url: HttpUrl): String = "${url.scheme}://${url.host.lowercase()}:${url.port}"

// The same host over https on its usual port: where a server at a plain
// http address sends its visitors when it moves them up to https. It is
// the same server, reached over TLS. Null for an https address.
fun httpsTwin(url: HttpUrl): String? =
    if (url.isHttps) null else origin(url.newBuilder().scheme("https").port(443).build())

// The origins of a server at these addresses: each address's own, and the
// https twin of each plain http one, so the server's headers and its
// sign-in page's cookies follow a move up to https on the same host.
fun serverOrigins(main: HttpUrl, home: HttpUrl?): Set<String> =
    listOfNotNull(main, home).flatMapTo(HashSet()) { listOfNotNull(origin(it), httpsTwin(it)) }

// The addresses a sign-in page's headers may go to: the main address and
// its https twin, and the home address only over https (an https home
// address, or the https twin of an http one). None at all when the main
// address is plain http to a host outside the home network, where anyone
// on the way could read them.
fun gatewayOrigins(main: HttpUrl, home: HttpUrl?): Set<String> {
    if (!main.isHttps && !isPrivateHost(main)) return emptySet()
    return setOfNotNull(origin(main), httpsTwin(main), home?.let { if (it.isHttps) origin(it) else httpsTwin(it) })
}

const val DEVICE_ID_HEADER = "X-Octo-Device-Id"
const val DEVICE_NAME_HEADER = "X-Octo-Device-Name"
const val PURPOSE_HEADER = "X-Octo-Purpose"

// This install as its own server knows it: a stable id made once, and
// a name a person reads (the phone's name, the computer's name), kept as
// typed, at most 64 characters.
class DeviceIdentity(id: String, name: String) {
    val id: String = id.filter { it in '!'..'~' }.ifEmpty { "unknown" }
    val name: String = clipName(name.trim()).ifEmpty { "Octo device" }

    // The name as it travels in a header: UTF-8, percent-encoded, so any
    // name goes, "Sam’s Café" too.
    val headerName: String get() = encodeComponent(name)

    // As headers, for code that sends its own requests to the server.
    fun headers(): Map<String, String> = mapOf(DEVICE_ID_HEADER to id, DEVICE_NAME_HEADER to headerName)

    override fun toString() = "DeviceIdentity(id=$id, name=$name)"
}

// What a request is for, when it is not playing. Set as the request's tag
// (Request.Builder.tag(OctoPurpose::class.java, ...)), so it is told only to
// the server's own addresses and never follows a redirect elsewhere.
enum class OctoPurpose(val wire: String) {
    // A copy kept on the device to play without a connection.
    Offline("offline"),

    // A read that analyzes a song the player plays or will play (its
    // first or last seconds, its length), never a play of its own.
    Scout("scout"),
}

// Marks a request as being for this purpose.
fun Request.Builder.purpose(purpose: OctoPurpose): Request.Builder = tag(OctoPurpose::class.java, purpose)

// The same client, its connections and the server's headers shared, with
// every request it makes marked for this purpose, for code that cannot tag
// its own requests (a player's data source).
fun OkHttpClient.markedFor(purpose: OctoPurpose): OkHttpClient =
    newBuilder().addInterceptor { chain -> chain.proceed(chain.request().newBuilder().purpose(purpose).build()) }.build()

private const val MAX_DEVICE_NAME = 64

// At most 64 characters, never cutting one in two, with no control
// characters.
private fun clipName(text: String): String {
    val clean = text.filterNot { it.isISOControl() }
    if (clean.codePointCount(0, clean.length) <= MAX_DEVICE_NAME) return clean
    return clean.substring(0, clean.offsetByCodePoints(0, MAX_DEVICE_NAME)).trim()
}

// Puts the scope in force when a request starts on it, as the request's
// tag, so every hop of that request and what it answers belong to the same
// configuration, even when the server in use changes on the way.
class ServerScopeAtStart(private val scope: () -> HeaderScope?) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val current = scope() ?: return chain.proceed(request)
        return chain.proceed(request.newBuilder().tag(HeaderScope::class, current).build())
    }
}

// Adds the server's headers and its sign-in page's cookies to each hop for
// one of its addresses, by the scope the request started under, and keeps
// the sign-in page's cookies each such hop answers with. Installed as a
// network interceptor, it sees every hop of a redirect, so a redirect to
// another host never carries them along.
class ServerHeaders : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val current = request.tag(HeaderScope::class)
        if (current == null || !current.covers(request.url)) return chain.proceed(request)
        val device = current.device
        val purpose = request.tag(OctoPurpose::class.java)
        val cookie = current.cookies.cookieHeader(request.url)
        val signed = request.newBuilder().apply {
            current.headersFor(request.url).forEach { (name, value) -> header(name, value) }
            if (device != null) {
                header(DEVICE_ID_HEADER, device.id)
                header(DEVICE_NAME_HEADER, device.headerName)
            }
            if (purpose != null) header(PURPOSE_HEADER, purpose.wire)
            if (cookie != null) header("Cookie", request.header("Cookie")?.let { "$it; $cookie" } ?: cookie)
        }.build()
        val response = chain.proceed(signed)
        current.cookies.keep(request.url, response.headers("Set-Cookie"))
        return response
    }
}

// Sets a client up to carry the server's headers: the scope in force when
// each request starts, and that scope's headers and cookies on every hop.
// Installed once per client.
fun OkHttpClient.Builder.serverHeaders(scope: () -> HeaderScope?): OkHttpClient.Builder =
    addInterceptor(ServerScopeAtStart(scope)).addNetworkInterceptor(ServerHeaders())
