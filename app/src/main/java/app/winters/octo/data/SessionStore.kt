package app.winters.octo.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.winters.octo.livelists.accountKey
import app.winters.octo.server.serverName
import app.winters.octo.server.settleServers
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

// One server kept on the phone. Its password (or API key) and the secret
// parts of its connection are stored only sealed; a server signed out of
// keeps no password.
@Serializable
data class StoredServer(
    val serverUrl: String,
    val username: String,
    val passwordSealed: String = "",
    val serverType: String? = null,
    val serverVersion: String? = null,
    val isOcto: Boolean = false,
    val octoAdminReachable: Boolean = false,
    val extensions: Set<String> = emptySet(),
    // How the server is reached, as encodeConnection writes it. Its
    // secrets are sealed inside.
    val connection: String? = null,
    // Which server this is, for everything the phone keeps for it: the
    // account's key from when it was first kept (the name older versions
    // gave its live lists), kept when the address is edited.
    val id: String = "",
    // The name the listener gave it; none shows its host.
    val label: String = "",
    // Signed out: still in the list, its password forgotten.
    val signedOut: Boolean = false,
) {
    val name: String get() = serverName(label, serverUrl)

    // The id, or the one it would get when kept by an older version.
    val key: String get() = id.ifEmpty { accountKey(username, serverUrl) }

    fun sameAccount(other: StoredServer) = username == other.username && serverUrl == other.serverUrl
}

// Every kept server in order, and the one in use, if any.
data class KeptServers(val servers: List<StoredServer> = emptyList(), val active: String? = null) {
    val inUse: StoredServer? get() = active?.let(::find)

    fun find(id: String?): StoredServer? = id?.let { wanted -> servers.firstOrNull { it.id == wanted } }

    fun replacing(server: StoredServer): KeptServers =
        copy(servers = if (servers.any { it.id == server.id }) servers.map { if (it.id == server.id) server else it } else servers + server)
}

private val Context.sessionData by preferencesDataStore("session")

private val json = Json { ignoreUnknownKeys = true }
private val serverList = ListSerializer(StoredServer.serializer())

// The servers kept between launches. The list is kept under `servers`. The
// one in use is written where older versions of Octo keep their one server
// (`server_url` and the rest), which says which one it is: an older version
// opened later is still signed in to it, and read back, those have the last
// word (see settleServers). A store from before the list
// becomes a list of one, still in use: nothing is signed out and nothing
// is lost. The change is written with the next change.
@Singleton
class SessionStore internal constructor(private val data: DataStore<Preferences>) {
    @Inject constructor(@ApplicationContext context: Context) : this(context.sessionData)

    suspend fun read(): KeptServers = settle(data.data.first())

    // Changes the kept servers in one step, and answers them as written.
    suspend fun update(change: (KeptServers) -> KeptServers): KeptServers {
        var written = KeptServers()
        data.edit { p ->
            written = change(settle(p))
            write(p, written)
        }
        return written
    }

    private fun settle(p: Preferences): KeptServers {
        val listed = p[SERVERS]?.let { text -> runCatching { json.decodeFromString(serverList, text) }.getOrNull() }.orEmpty()
        val (servers, inUse) = settleServers(
            listed.map { it.withId() },
            legacyOf(p)?.withId(),
            id = { it.id },
            same = { a, b -> a.sameAccount(b) },
            adopt = { legacy, match ->
                if (match == null) legacy else legacy.copy(id = match.id, label = legacy.label.ifEmpty { match.label }, signedOut = false)
            },
        )
        return KeptServers(servers, inUse?.id)
    }

    private fun write(p: MutablePreferences, kept: KeptServers) {
        p[SERVERS] = json.encodeToString(serverList, kept.servers)
        val inUse = kept.inUse?.takeUnless { it.signedOut || it.passwordSealed.isEmpty() }
        if (inUse == null) {
            LEGACY.forEach { p.remove(it) }
            return
        }
        p[SERVER_URL] = inUse.serverUrl
        p[USERNAME] = inUse.username
        p[PASSWORD_SEALED] = inUse.passwordSealed
        if (inUse.serverType != null) p[SERVER_TYPE] = inUse.serverType else p.remove(SERVER_TYPE)
        if (inUse.serverVersion != null) p[SERVER_VERSION] = inUse.serverVersion else p.remove(SERVER_VERSION)
        p[IS_OCTO] = inUse.isOcto
        p[OCTO_ADMIN_REACHABLE] = inUse.octoAdminReachable
        p[EXTENSIONS] = inUse.extensions
        if (inUse.connection != null) p[CONNECTION] = inUse.connection else p.remove(CONNECTION)
    }

    // The one server as an older version keeps it, when it is signed in.
    private fun legacyOf(p: Preferences): StoredServer? = StoredServer(
        serverUrl = p[SERVER_URL] ?: return null,
        username = p[USERNAME] ?: return null,
        passwordSealed = p[PASSWORD_SEALED] ?: return null,
        serverType = p[SERVER_TYPE],
        serverVersion = p[SERVER_VERSION],
        isOcto = p[IS_OCTO] ?: false,
        octoAdminReachable = p[OCTO_ADMIN_REACHABLE] ?: false,
        extensions = p[EXTENSIONS] ?: emptySet(),
        connection = p[CONNECTION],
    )

    private fun StoredServer.withId(): StoredServer = if (id.isEmpty()) copy(id = key) else this

    private companion object {
        val SERVERS = stringPreferencesKey("servers")

        // The server in use, where older versions keep their one server.
        val SERVER_URL = stringPreferencesKey("server_url")
        val USERNAME = stringPreferencesKey("username")
        val PASSWORD_SEALED = stringPreferencesKey("password_sealed")
        val SERVER_TYPE = stringPreferencesKey("server_type")
        val SERVER_VERSION = stringPreferencesKey("server_version")
        val IS_OCTO = booleanPreferencesKey("is_octo")
        val OCTO_ADMIN_REACHABLE = booleanPreferencesKey("octo_admin_reachable")
        val EXTENSIONS = stringSetPreferencesKey("extensions")
        val CONNECTION = stringPreferencesKey("connection")
        val LEGACY = listOf(SERVER_URL, USERNAME, PASSWORD_SEALED, SERVER_TYPE, SERVER_VERSION, IS_OCTO, OCTO_ADMIN_REACHABLE, EXTENSIONS, CONNECTION)
    }
}
