package app.winters.octo.admin

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.winters.octo.data.SessionRepository
import app.winters.octo.data.SessionState
import app.winters.octo.subsonic.SubsonicClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

// What Octo says about itself and the services it works with.
@Serializable
data class Health(val ok: Boolean = false, val detail: String = "", val warning: Boolean = false, val configured: Boolean = true)

@Serializable
data class AdminStatus(val octo: Health = Health(), val services: Map<String, Health> = emptyMap())

// A song Octo downloaded into the library.
@Serializable
data class DownloadRecord(
    val artist: String = "",
    val title: String = "",
    val album: String = "",
    val format: String = "",
    val source: String = "",
    val coverArtUrl: String? = null,
    val sizeBytes: Long = 0,
    val downloadedAt: String = "",
    val requestedBy: List<String> = emptyList(),
)

@Serializable
private data class Downloads(val downloads: List<DownloadRecord> = emptyList())

// How far Octo's stations have learned one listener's taste.
@Serializable
data class RadioLearning(
    val plays: Int = 0,
    val needed: Int = 0,
    val refreshing: Boolean = false,
    val lastRefreshSuccessUtc: String? = null,
    val lastRefreshError: String? = null,
)

@Serializable
data class AdminStation(
    val id: String = "",
    val name: String = "",
    val kind: String = "",
    val trackCount: Int = 0,
    val validUntilUtc: String? = null,
)

@Serializable
data class RadioState(
    val enabled: Boolean = false,
    val selectedUser: String? = null,
    val learning: RadioLearning? = null,
    val stations: List<AdminStation> = emptyList(),
)

// Where downloads land, and whether that works.
@Serializable
data class LibraryStatus(
    val effectiveDownloadPath: String = "",
    val writable: Boolean = false,
    val visibleToOcto: Boolean = false,
    val rescanAuthenticated: Boolean = false,
)

class AdminUnavailable(message: String) : IOException(message)

private val Context.adminData by preferencesDataStore("octo_admin")

// Octo's own admin pages, which answer only on the home network. The app
// finds them at the address it signed in with, at the one it last found,
// or at the address Octo gives its stations, and remembers what worked.
@Singleton
class OctoAdmin @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessions: SessionRepository,
    http: OkHttpClient,
) {
    private val quick = http.newBuilder().callTimeout(4, TimeUnit.SECONDS).build()
    private val calls = http.newBuilder().callTimeout(30, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }

    // The address last found, shown as a hint and tried first.
    val lastFound = context.adminData.data.map { it[ADDRESS]?.toHttpUrlOrNull() }

    // Where the admin pages answer right now, or null when away from home
    // or not signed in to Octo.
    suspend fun locate(): HttpUrl? {
        val client = client() ?: return null
        val tried = HashSet<HttpUrl>()
        suspend fun first(candidates: List<HttpUrl>): HttpUrl? =
            candidates.map(::root).filter(tried::add).firstOrNull { answers(it) }
        // The station addresses cost a server call, so they are only asked for last.
        val found = first(listOfNotNull(client.baseUrl, client.primaryUrl, lastFound.first()))
            ?: first(stationHosts(client))
            ?: return null
        context.adminData.edit { it[ADDRESS] = found.toString() }
        return found
    }

    suspend fun status(base: HttpUrl): AdminStatus = get(base, "status", AdminStatus.serializer())

    suspend fun downloads(base: HttpUrl): List<DownloadRecord> = get(base, "downloads", Downloads.serializer()).downloads

    suspend fun radio(base: HttpUrl): RadioState =
        get(base, "lastfm/radio", RadioState.serializer(), listOfNotNull(client()?.username?.let { "user" to it }))

    suspend fun library(base: HttpUrl): LibraryStatus = get(base, "library-status", LibraryStatus.serializer())

    // Asks Octo to build the signed-in listener's stations again.
    suspend fun refreshStations(base: HttpUrl) {
        val user = client()?.username ?: throw AdminUnavailable("Not signed in")
        send(base, "lastfm/radio/refresh", JsonObject(mapOf("user" to JsonPrimitive(user))).toString())
    }

    // The full admin pages, for everything the app does not show.
    fun pageUrl(base: HttpUrl): HttpUrl = base.newBuilder().encodedPath("/admin/").build()

    private suspend fun answers(base: HttpUrl): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            quick.newCall(Request.Builder().url(api(base, "status")).build()).execute().use { response ->
                response.isSuccessful && json.parseToJsonElement(response.body.string()).jsonObject.containsKey("octo")
            }
        }.getOrDefault(false)
    }

    // Octo builds each station's stream address from where it thinks it
    // lives, which is usually its home network address.
    private suspend fun stationHosts(client: SubsonicClient): List<HttpUrl> =
        (withTimeoutOrNull(STATIONS_WAIT_MS) { runCatching { client.radioStations() }.getOrNull() } ?: emptyList())
            .mapNotNull { it.streamUrl.toHttpUrlOrNull() }

    private fun root(url: HttpUrl): HttpUrl = url.newBuilder().encodedPath("/").query(null).build()

    private suspend fun <T> get(
        base: HttpUrl,
        path: String,
        serializer: KSerializer<T>,
        query: List<Pair<String, String>> = emptyList(),
    ): T = withContext(Dispatchers.IO) {
        val url = api(base, path).newBuilder().apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        try {
            calls.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) throw AdminUnavailable("HTTP ${response.code} from $path")
                json.decodeFromString(serializer, response.body.string())
            }
        } catch (e: IOException) {
            throw e as? AdminUnavailable ?: AdminUnavailable("Octo did not answer")
        } catch (e: SerializationException) {
            throw AdminUnavailable("Octo answered in a way the app does not understand")
        }
    }

    // A change. Octo only takes changes that carry its admin header.
    private suspend fun send(base: HttpUrl, path: String, body: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(api(base, path))
            .header("X-Octo-Admin", "1")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        try {
            calls.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw AdminUnavailable("HTTP ${response.code} from $path")
            }
        } catch (e: IOException) {
            throw e as? AdminUnavailable ?: AdminUnavailable("Octo did not answer")
        }
    }

    private fun api(base: HttpUrl, path: String): HttpUrl =
        base.newBuilder().encodedPath("/api/admin/$path").build()

    private fun client(): SubsonicClient? = (sessions.state.value as? SessionState.SignedIn)?.session?.client

    private companion object {
        val ADDRESS = stringPreferencesKey("address")

        // How long to wait for the station list when looking for Octo.
        const val STATIONS_WAIT_MS = 5_000L
    }
}
