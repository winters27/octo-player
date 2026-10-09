package app.winters.octo.subsonic

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

// What a family link carries. Three kinds are read, each in the https form
// a QR code or a shared link uses and the app's own octo:// form:
//
// An invite, for signing up:
//   <server>/family/join#invite=<token>
//   octo://join?server=<address>&invite=<token>
// A sign-in to fill in, for a member who has a login:
//   octo://signin?server=<address>&home=<address>&username=<name>
// A sign-in handed over from the same person's other device:
//   <server>/family/signin#t=<token>&k=<key>&s=<address>&h=<address>
//   octo://signin#t=<token>&k=<key>&s=<address>&h=<address>
//
// Secrets ride in the part after #, which browsers never send to a server.
// `server` is the address that works from anywhere, which may carry a path
// (https://example.com/octo); `home` is the server's address on its home
// network, tried when `server` can't be reached and kept as the account's
// home address.
sealed interface FamilyLink {
    val server: String
    val home: String?
}

data class FamilyInviteLink(override val server: String, val token: String, override val home: String? = null) : FamilyLink {
    override fun toString() = "FamilyInviteLink(server=$server, home=$home)"
}

// The server and username to fill into the sign-in form; the person types
// the password.
data class FamilySignInPrefill(override val server: String, val username: String = "", override val home: String? = null) : FamilyLink

// A hand-over from another device: the token to redeem, on `base` (the
// address the link was opened on), and the key that opens what the other
// device sends. The key never leaves this device.
data class FamilyHandOverLink(
    val base: String,
    val token: String,
    val key: String,
    override val server: String,
    override val home: String? = null,
) : FamilyLink {
    // Never print the token or the key, even by accident in a log.
    override fun toString() = "FamilyHandOverLink(base=$base, server=$server, home=$home)"
}

// Reads a family link, or null when the text is not one. Spaces around it
// are ignored, so a pasted link works.
fun parseFamilyLink(text: String): FamilyLink? {
    val trimmed = text.trim()
    return when {
        trimmed.startsWith("octo://join", ignoreCase = true) -> parseAppInvite(trimmed)
        trimmed.startsWith("octo://signin", ignoreCase = true) -> parseAppSignIn(trimmed)
        trimmed.startsWith("https://", ignoreCase = true) || trimmed.startsWith("http://", ignoreCase = true) -> parseWebLink(trimmed)
        else -> null
    }
}

private fun parseAppInvite(text: String): FamilyLink? {
    val params = readParams(text.substringAfter('?', "").substringBefore('#'))
    val server = params["server"]?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val token = params["invite"]?.trim()?.takeIf(String::isNotEmpty) ?: return null
    return FamilyInviteLink(server, token, homeIn(params, "home"))
}

private fun parseAppSignIn(text: String): FamilyLink? {
    val fragment = text.substringAfter('#', "")
    if (fragment.isNotEmpty()) return handOverIn(readParams(fragment), base = null)
    val params = readParams(text.substringAfter('?', ""))
    val server = params["server"]?.trim()?.takeIf(String::isNotEmpty)?.let(::addressIn) ?: return null
    return FamilySignInPrefill(server, params["username"]?.trim().orEmpty(), homeIn(params, "home"))
}

private fun parseWebLink(text: String): FamilyLink? {
    val fragment = text.substringAfter('#', "")
    if (fragment.isEmpty()) return null
    val url = text.substringBefore('#').toHttpUrlOrNull() ?: return null
    val segments = url.pathSegments.filter(String::isNotEmpty)
    if (segments.size < 2 || segments[segments.size - 2] != "family") return null
    val base = url.newBuilder().encodedPath("/").apply { segments.dropLast(2).forEach { addPathSegment(it) } }.query(null).build().toString().removeSuffix("/")
    val params = readParams(fragment)
    return when (segments.last()) {
        "join" -> params["invite"]?.trim()?.takeIf(String::isNotEmpty)?.let { FamilyInviteLink(base, it, homeIn(params, "home")) }
        "signin" -> handOverIn(params, base)
        else -> null
    }
}

// A hand-over's token, key and addresses; the token is redeemed on `base`,
// or on the server address when the link names none.
private fun handOverIn(params: Map<String, String>, base: String?): FamilyHandOverLink? {
    val token = params["t"]?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val key = params["k"]?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val server = params["s"]?.trim()?.takeIf(String::isNotEmpty)?.let(::addressIn) ?: base ?: return null
    return FamilyHandOverLink(base ?: server, token, key, server, homeIn(params, "h"))
}

// An address a link names, when it is an address at all.
private fun addressIn(text: String): String? = normalizeServerUrl(text)?.toString()?.removeSuffix("/")

private fun homeIn(params: Map<String, String>, name: String): String? =
    params[name]?.trim()?.takeIf(String::isNotEmpty)?.let(::addressIn)

private fun readParams(text: String): Map<String, String> = text.split('&').mapNotNull { part ->
    val at = part.indexOf('=')
    if (at <= 0) null else part.substring(0, at) to decodeComponent(part.substring(at + 1))
}.toMap()

// The https link for an invite.
fun familyInviteUrl(server: String, token: String, home: String? = null): String =
    "${server.trim().removeSuffix("/")}/family/join#invite=${encodeComponent(token)}${homePart(home)}"

// The https link for a hand-over, opened on `base`: the token, the key, and
// the addresses the new device signs in to.
fun familySignInUrl(base: String, token: String, key: String, server: String, home: String? = null): String =
    "${base.trim().removeSuffix("/")}/family/signin#t=${encodeComponent(token)}&k=${encodeComponent(key)}&s=${encodeComponent(server.trim().removeSuffix("/"))}" +
        (home?.trim()?.takeIf(String::isNotEmpty)?.let { "&h=${encodeComponent(it.removeSuffix("/"))}" }.orEmpty())

// The app's own form of a link, for opening Octo directly.
fun familyAppLink(link: FamilyLink): String = when (link) {
    is FamilyInviteLink -> "octo://join?server=${encodeComponent(link.server)}&invite=${encodeComponent(link.token)}${homePart(link.home)}"
    is FamilySignInPrefill -> "octo://signin?server=${encodeComponent(link.server)}${homePart(link.home)}" +
        (link.username.takeIf(String::isNotBlank)?.let { "&username=${encodeComponent(it)}" }.orEmpty())
    is FamilyHandOverLink -> "octo://signin#" + familySignInUrl(link.base, link.token, link.key, link.server, link.home).substringAfter('#')
}

private fun homePart(home: String?): String = home?.trim()?.takeIf(String::isNotEmpty)?.let { "&home=${encodeComponent(it)}" }.orEmpty()

// Percent-encodes text the way RFC 3986 asks: letters, digits and - . _ ~
// stay; every other character goes as its UTF-8 bytes, %XX each.
fun encodeComponent(text: String): String {
    val out = StringBuilder()
    for (byte in text.toByteArray(Charsets.UTF_8)) {
        val c = byte.toInt() and 0xFF
        if (c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code || c == '-'.code || c == '.'.code || c == '_'.code || c == '~'.code) {
            out.append(c.toChar())
        } else {
            out.append('%').append(HEX[c ushr 4]).append(HEX[c and 0x0F])
        }
    }
    return out.toString()
}

// Reads percent-encoded text; a plus is read as a space, as forms send it.
fun decodeComponent(text: String): String =
    runCatching { java.net.URLDecoder.decode(text, Charsets.UTF_8) }.getOrDefault(text)

private val HEX = "0123456789ABCDEF".toCharArray()
