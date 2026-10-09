package app.winters.octo.subsonic

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.text.Normalizer

// Extra headers a server needs on every request, such as an access token
// for a proxy in front of it, and the addresses they may go to. `device`
// names this app install to the server, so an Octo server can tell one
// phone from another; it goes only to the same addresses.
class HeaderScope(
    val origins: Set<String>,
    val headers: Map<String, String>,
    val device: DeviceIdentity? = null,
) {
    fun covers(url: HttpUrl) = origin(url) in origins

    // Header values are secrets, so only the names are ever printed.
    override fun toString() = "HeaderScope(origins=$origins, headers=${headers.keys}, device=${device != null})"
}

// Where a request goes: scheme, host and port. Two addresses with the same
// origin reach the same server.
fun origin(url: HttpUrl): String = "${url.scheme}://${url.host.lowercase()}:${url.port}"

const val DEVICE_ID_HEADER = "X-Octo-Device-Id"
const val DEVICE_NAME_HEADER = "X-Octo-Device-Name"
const val PURPOSE_HEADER = "X-Octo-Purpose"

// This app install as its own server knows it: a stable id made once, and
// a name a person reads (the phone's model, the computer's name).
class DeviceIdentity(id: String, name: String) {
    val id: String = headerSafe(id).ifEmpty { "unknown" }
    val name: String = headerSafe(name).take(MAX_DEVICE_NAME).trim().ifEmpty { "Octo device" }

    // As headers, for code that sends its own requests to the server.
    fun headers(): Map<String, String> = mapOf(DEVICE_ID_HEADER to id, DEVICE_NAME_HEADER to name)

    override fun toString() = "DeviceIdentity(id=$id, name=$name)"
}

// What a request is for, when it is not playing. Set as the request's tag
// (Request.Builder.tag(OctoPurpose::class.java, ...)), so it is told only to
// the server's own addresses and never follows a redirect elsewhere.
enum class OctoPurpose(val wire: String) {
    // A copy kept on the device to play without a connection.
    Offline("offline"),
}

// Marks a request as being for this purpose.
fun Request.Builder.purpose(purpose: OctoPurpose): Request.Builder = tag(OctoPurpose::class.java, purpose)

// The same client, its connections and the server's headers shared, with
// every request it makes marked for this purpose, for code that cannot tag
// its own requests (a player's data source).
fun OkHttpClient.markedFor(purpose: OctoPurpose): OkHttpClient =
    newBuilder().addInterceptor { chain -> chain.proceed(chain.request().newBuilder().purpose(purpose).build()) }.build()

private const val MAX_DEVICE_NAME = 64

// A header value is plain printable ASCII, or every request fails. Accents
// are taken off, curly quotes made straight, and anything else left out:
// "Sam’s Café phone" goes as "Sam's Cafe phone".
internal fun headerSafe(text: String): String {
    val straight = text.replace('‘', '\'').replace('’', '\'').replace('“', '"').replace('”', '"')
    val plain = Normalizer.normalize(straight, Normalizer.Form.NFKD)
    return plain.filter { it in ' '..'~' }.replace(Regex(" {2,}"), " ").trim()
}

// Adds the server's headers to each request for one of its addresses.
// Installed as a network interceptor, it sees every hop of a redirect, so
// a redirect to another host never carries them along.
class ServerHeaders(private val scope: () -> HeaderScope?) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val current = scope()
        if (current == null || !current.covers(request.url)) return chain.proceed(request)
        val device = current.device
        val purpose = request.tag(OctoPurpose::class.java)
        if (current.headers.isEmpty() && device == null && purpose == null) return chain.proceed(request)
        val signed = request.newBuilder().apply {
            current.headers.forEach { (name, value) -> header(name, value) }
            if (device != null) {
                header(DEVICE_ID_HEADER, device.id)
                header(DEVICE_NAME_HEADER, device.name)
            }
            if (purpose != null) header(PURPOSE_HEADER, purpose.wire)
        }.build()
        return chain.proceed(signed)
    }
}
