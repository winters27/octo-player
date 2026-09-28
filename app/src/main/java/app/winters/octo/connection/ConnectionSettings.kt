package app.winters.octo.connection

import app.winters.octo.subsonic.AuthMode
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

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
