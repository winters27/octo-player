package app.winters.octo.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

data class StoredSession(
    val serverUrl: String,
    val username: String,
    val passwordSealed: String,
    val serverType: String?,
    val serverVersion: String?,
    val isOcto: Boolean,
    val octoAdminReachable: Boolean,
    val extensions: Set<String>,
    // How the server is reached, as encodeConnection writes it. Its
    // secrets are sealed inside.
    val connection: String? = null,
)

private val Context.sessionData by preferencesDataStore("session")

// The signed-in server, kept between launches. The password, API key,
// header values and client certificate name are stored only in sealed form.
@Singleton
class SessionStore @Inject constructor(@ApplicationContext private val context: Context) {
    suspend fun read(): StoredSession? {
        val p = context.sessionData.data.first()
        return StoredSession(
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
    }

    suspend fun write(s: StoredSession) {
        context.sessionData.edit { p ->
            p[SERVER_URL] = s.serverUrl
            p[USERNAME] = s.username
            p[PASSWORD_SEALED] = s.passwordSealed
            if (s.serverType != null) p[SERVER_TYPE] = s.serverType else p.remove(SERVER_TYPE)
            if (s.serverVersion != null) p[SERVER_VERSION] = s.serverVersion else p.remove(SERVER_VERSION)
            p[IS_OCTO] = s.isOcto
            p[OCTO_ADMIN_REACHABLE] = s.octoAdminReachable
            p[EXTENSIONS] = s.extensions
            if (s.connection != null) p[CONNECTION] = s.connection else p.remove(CONNECTION)
        }
    }

    suspend fun clear() {
        context.sessionData.edit { it.clear() }
    }

    private companion object {
        val SERVER_URL = stringPreferencesKey("server_url")
        val USERNAME = stringPreferencesKey("username")
        val PASSWORD_SEALED = stringPreferencesKey("password_sealed")
        val SERVER_TYPE = stringPreferencesKey("server_type")
        val SERVER_VERSION = stringPreferencesKey("server_version")
        val IS_OCTO = booleanPreferencesKey("is_octo")
        val OCTO_ADMIN_REACHABLE = booleanPreferencesKey("octo_admin_reachable")
        val EXTENSIONS = stringSetPreferencesKey("extensions")
        val CONNECTION = stringPreferencesKey("connection")
    }
}
