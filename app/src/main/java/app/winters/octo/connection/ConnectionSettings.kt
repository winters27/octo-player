package app.winters.octo.connection

import app.winters.octo.subsonic.AuthMode
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

// One extra header the server needs, such as a proxy's access token. The
// value is a secret: it is kept sealed and never printed.
@Serializable
data class ServerHeader(val name: String, val value: String) {
    override fun toString() = "ServerHeader(name=$name, value=${mask(value)})"
}

// The library folder calls are limited to. No folder means all of them.
@Serializable
data class FolderChoice(val id: String, val name: String)

// How the app reaches one server, beyond its address and sign-in: a home
// address to use on the home network, how it signs in, extra headers,
// certificates the user chose to trust (by host), a client certificate,
// and the library folder.
data class ConnectionSettings(
    val home: HttpUrl? = null,
    val authMode: AuthMode = AuthMode.Token,
    val headers: List<ServerHeader> = emptyList(),
    val pins: Map<String, String> = emptyMap(),
    val clientCertAlias: String? = null,
    val folder: FolderChoice? = null,
) {
    // Secrets stay out of logs: header values and the certificate name are masked.
    override fun toString() =
        "ConnectionSettings(home=$home, authMode=$authMode, headers=${headers.map { it.name }}, " +
            "pins=${pins.keys}, clientCert=${clientCertAlias?.let(::mask)}, folder=${folder?.name})"
}

// What is written to disk. The header list and the certificate name are
// sealed by the vault before they get here; the rest is not secret.
@Serializable
data class StoredConnection(
    val homeUrl: String? = null,
    val authMode: AuthMode = AuthMode.Token,
    val headersSealed: String? = null,
    val clientCertSealed: String? = null,
    val pins: Map<String, String> = emptyMap(),
    val folder: FolderChoice? = null,
)

private val json = Json { ignoreUnknownKeys = true }
private val headerList = ListSerializer(ServerHeader.serializer())

fun encodeConnection(stored: StoredConnection): String = json.encodeToString(StoredConnection.serializer(), stored)

fun decodeConnection(text: String): StoredConnection? =
    runCatching { json.decodeFromString(StoredConnection.serializer(), text) }.getOrNull()

// Seals the secret parts with the given function, for writing to disk.
fun ConnectionSettings.sealed(seal: (String) -> String) = StoredConnection(
    homeUrl = home?.toString(),
    authMode = authMode,
    headersSealed = headers.takeIf { it.isNotEmpty() }?.let { seal(json.encodeToString(headerList, it)) },
    clientCertSealed = clientCertAlias?.let(seal),
    pins = pins,
    folder = folder,
)

// Opens what was sealed. Null when a secret can no longer be opened (the
// keystore key is gone), so the caller asks the user to sign in again.
fun StoredConnection.opened(open: (String) -> String?): ConnectionSettings? {
    val headers = headersSealed?.let { sealed ->
        val plain = open(sealed) ?: return null
        runCatching { json.decodeFromString(headerList, plain) }.getOrNull() ?: return null
    }.orEmpty()
    val alias = clientCertSealed?.let { open(it) ?: return null }
    return ConnectionSettings(
        home = homeUrl?.toHttpUrlOrNull(),
        authMode = authMode,
        headers = headers,
        pins = pins,
        clientCertAlias = alias,
        folder = folder,
    )
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
