package app.winters.octo.subsonic

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

// A pair code is six digits; anything else is not one.
fun isFamilyCode(code: String): Boolean = code.length == 6 && code.all(Char::isDigit)

// What a family link carries: a server and either a pair code for a
// member's new device, or an invite for a new member. Both forms are read:
// the https one every QR code and shared link uses,
//   <server>/family/join#u=<username>&c=<6 digits>
//   <server>/family/join#invite=<token>
// and the app's own,
//   octo://join?server=<address>&username=<name>&code=<6 digits>
//   octo://join?server=<address>&invite=<token>
// Secrets ride in the https link's # part, which browsers never send.
sealed interface FamilyLink {
    val server: String
}

data class FamilyJoinLink(override val server: String, val username: String, val code: String) : FamilyLink {
    // Never print the code, even by accident in a log.
    override fun toString() = "FamilyJoinLink(server=$server, username=$username)"
}

data class FamilyInviteLink(override val server: String, val token: String) : FamilyLink {
    override fun toString() = "FamilyInviteLink(server=$server)"
}

// Reads a family link, or null when the text is not one. Spaces around it
// are ignored, so a pasted link works.
fun parseFamilyLink(text: String): FamilyLink? {
    val trimmed = text.trim()
    return when {
        trimmed.startsWith("octo://join", ignoreCase = true) -> parseAppLink(trimmed)
        trimmed.startsWith("https://", ignoreCase = true) || trimmed.startsWith("http://", ignoreCase = true) -> parseWebLink(trimmed)
        else -> null
    }
}

// A pairing link only, for places that pair and nothing else.
fun parseFamilyJoinLink(text: String): FamilyJoinLink? = parseFamilyLink(text) as? FamilyJoinLink

private fun parseAppLink(text: String): FamilyLink? {
    val query = text.substringAfter('?', "")
    val params = readParams(query)
    val server = params["server"]?.trim()?.takeIf(String::isNotEmpty) ?: return null
    params["invite"]?.trim()?.takeIf(String::isNotEmpty)?.let { return FamilyInviteLink(server, it) }
    val username = params["username"]?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val code = params["code"]?.trim()?.takeIf(::isFamilyCode) ?: return null
    return FamilyJoinLink(server, username, code)
}

private fun parseWebLink(text: String): FamilyLink? {
    val fragment = text.substringAfter('#', "")
    if (fragment.isEmpty()) return null
    val url = text.substringBefore('#').toHttpUrlOrNull() ?: return null
    val segments = url.pathSegments.filter(String::isNotEmpty)
    if (segments.size < 2 || segments[segments.size - 2] != "family" || segments.last() != "join") return null
    val base = url.newBuilder().encodedPath("/").apply { segments.dropLast(2).forEach { addPathSegment(it) } }.query(null).build()
    val server = base.toString().removeSuffix("/")
    val params = readParams(fragment)
    params["invite"]?.trim()?.takeIf(String::isNotEmpty)?.let { return FamilyInviteLink(server, it) }
    val username = params["u"]?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val code = params["c"]?.trim()?.takeIf(::isFamilyCode) ?: return null
    return FamilyJoinLink(server, username, code)
}

private fun readParams(text: String): Map<String, String> = text.split('&').mapNotNull { part ->
    val at = part.indexOf('=')
    if (at <= 0) null else part.substring(0, at) to decodeComponent(part.substring(at + 1))
}.toMap()

// The https link for a pair code: what a QR code shows and what is copied.
// Any camera or browser opens it; the server's page hands it on to Octo.
fun familyJoinUrl(server: String, username: String, code: String): String =
    "${server.trim().removeSuffix("/")}/family/join#u=${encodeComponent(username)}&c=${encodeComponent(code)}"

// The https link for an invite.
fun familyInviteUrl(server: String, token: String): String =
    "${server.trim().removeSuffix("/")}/family/join#invite=${encodeComponent(token)}"

// The app's own form of a link, for opening Octo directly.
fun familyAppLink(link: FamilyLink): String = when (link) {
    is FamilyJoinLink -> "octo://join?server=${encodeComponent(link.server)}&username=${encodeComponent(link.username)}&code=${encodeComponent(link.code)}"
    is FamilyInviteLink -> "octo://join?server=${encodeComponent(link.server)}&invite=${encodeComponent(link.token)}"
}

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
