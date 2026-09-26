package app.winters.octo.subsonic

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

// Extra headers a server needs on every request, such as an access token
// for a proxy in front of it, and the addresses they may go to.
class HeaderScope(val origins: Set<String>, val headers: Map<String, String>) {
    fun covers(url: HttpUrl) = origin(url) in origins

    // Header values are secrets, so only the names are ever printed.
    override fun toString() = "HeaderScope(origins=$origins, headers=${headers.keys})"
}

// Where a request goes: scheme, host and port. Two addresses with the same
// origin reach the same server.
fun origin(url: HttpUrl): String = "${url.scheme}://${url.host.lowercase()}:${url.port}"

// Adds the server's headers to each request for one of its addresses.
// Installed as a network interceptor, it sees every hop of a redirect, so
// a redirect to another host never carries them along.
class ServerHeaders(private val scope: () -> HeaderScope?) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val current = scope()
        if (current == null || current.headers.isEmpty() || !current.covers(request.url)) {
            return chain.proceed(request)
        }
        val signed = request.newBuilder().apply { current.headers.forEach { (name, value) -> header(name, value) } }.build()
        return chain.proceed(signed)
    }
}
