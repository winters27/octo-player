package app.winters.octo.subsonic

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

// Turns whatever someone typed into the server's base address.
// "192.168.50.21:5274", "https://music.example.com/" and ".../rest" all work.
fun normalizeServerUrl(input: String): HttpUrl? {
    var text = input.trim()
    if (text.isEmpty()) return null
    if (!text.contains("://")) text = "http://$text"
    val url = text.toHttpUrlOrNull() ?: return null
    val segments = url.pathSegments.filter { it.isNotEmpty() }.toMutableList()
    // A pasted API path or web player path is not part of the base.
    if (segments.lastOrNull() in setOf("rest", "app")) segments.removeAt(segments.lastIndex)
    return url.newBuilder()
        // A name and password pasted into the address are never kept with it.
        .username("")
        .password("")
        .encodedPath("/")
        .apply { segments.forEach { addPathSegment(it) } }
        .query(null)
        .fragment(null)
        .build()
}

// True when the address stays inside a home network, going only by the
// address itself (no lookups).
fun isPrivateHost(url: HttpUrl): Boolean {
    val host = url.host.lowercase()
    if (host == "localhost" || host.endsWith(".local") || host.endsWith(".lan") ||
        host.endsWith(".home.arpa")
    ) return true
    if (host.contains(':')) {
        return host == "::1" || host.startsWith("fc") || host.startsWith("fd") ||
            host.startsWith("fe80")
    }
    val parts = host.split('.').mapNotNull { it.toIntOrNull() }
    if (parts.size != 4) return false
    val (a, b) = parts
    return a == 10 || a == 127 || (a == 172 && b in 16..31) ||
        (a == 192 && b == 168) || (a == 169 && b == 254)
}
