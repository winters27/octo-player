package app.winters.octo.connection

import kotlinx.serialization.Serializable

// One extra header the server needs, such as a proxy's access token. The
// value is a secret: it is kept sealed and never printed.
@Serializable
data class ServerHeader(val name: String, val value: String) {
    override fun toString() = "ServerHeader(name=$name, value=${mask(value)})"
}

// A secret as it may be shown: always the same few dots, whatever its
// length, so nothing about it shows.
fun mask(secret: String): String = if (secret.isEmpty()) "" else "•".repeat(8)

// The headers worth keeping from what was typed: named, with a value, and
// each name once (the last one typed wins).
fun cleanHeaders(headers: List<ServerHeader>): List<ServerHeader> =
    headers.map { ServerHeader(it.name.trim(), it.value.trim()) }
        .filter { isHeaderName(it.name) && it.value.isNotEmpty() && isHeaderValue(it.value) }
        .associateBy { it.name.lowercase() }
        .values
        .toList()

// A header name is plain letters, digits and a few marks, with no spaces.
fun isHeaderName(name: String): Boolean =
    name.isNotEmpty() && name.all { (it.isLetterOrDigit() && it.code < 128) || it in "!#$%&'*+-.^_`|~" }

// A header value is printable plain text. Anything else would make every
// request to the server fail.
fun isHeaderValue(value: String): Boolean = value.all { it == '\t' || it in ' '..'~' }
