package app.winters.octo.subsonic

import okhttp3.HttpUrl

// How the sign-in page reaches a server: encrypted or plain.
enum class Scheme(val prefix: String) {
    Https("https://"),
    Http("http://"),
}

// A typed or pasted address, split into the scheme it starts with (null
// when it names none) and the rest. "HTTP://music.lan" gives Http and
// "music.lan".
fun splitScheme(typed: String): Pair<Scheme?, String> {
    val text = typed.trimStart()
    for (scheme in Scheme.entries) {
        if (text.startsWith(scheme.prefix, ignoreCase = true)) return scheme to text.substring(scheme.prefix.length)
    }
    return null to typed
}

// The scheme for an address nobody picked one for: plain http on the home
// network, where servers seldom have a certificate, and https anywhere else.
fun automaticScheme(rest: String): Scheme {
    val url = normalizeServerUrl(rest.trim()) ?: return Scheme.Https
    return if (isPrivateHost(url)) Scheme.Http else Scheme.Https
}

// The server's address from the scheme and the rest, or null when the rest
// is not an address.
fun serverUrl(scheme: Scheme, rest: String): HttpUrl? {
    val text = rest.trim()
    if (text.isEmpty() || text.contains("://")) return null
    return normalizeServerUrl(scheme.prefix + text)
}

// An address as people read it: no slash at the end.
fun shownAddress(url: HttpUrl): String = url.toString().removeSuffix("/")
