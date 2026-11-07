package app.winters.octo.admin

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.winters.octo.connection.GatewayWords
import app.winters.octo.data.Session
import app.winters.octo.data.SessionRepository
import app.winters.octo.data.SessionState
import app.winters.octo.data.accountId
import app.winters.octo.serverupdate.AdminPlace
import app.winters.octo.subsonic.SubsonicClient
import app.winters.octo.subsonic.SubsonicException
import app.winters.octo.subsonic.gateway.Gateway
import app.winters.octo.subsonic.gateway.isHtml
import app.winters.octo.subsonic.gateway.jsonBody
import app.winters.octo.subsonic.gateway.notSubsonic
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
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
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

// Whether an admin call may carry the sign-in: only to the server's own
// addresses, or to one Octo itself announced for its stations. A saved
// address from another network could belong to anything.
internal fun mayCarrySignIn(target: HttpUrl, trusted: Set<HttpUrl>): Boolean =
    target.newBuilder().encodedPath("/").query(null).build() in trusted

// Octo's full admin pages at `base`, at the card `section` names, as in
// "/admin/#spotiflac", or at the top with none.
internal fun adminPageUrl(base: HttpUrl, section: String? = null): HttpUrl =
    base.newBuilder().encodedPath("/admin/").fragment(section).build()

// What Octo says about itself and the services it works with. `action` is
// what Octo offers to do about it on its full admin pages, such as "pass"
// for Mornye's check.
@Serializable
data class Health(
    val ok: Boolean = false,
    val detail: String = "",
    val warning: Boolean = false,
    val configured: Boolean = true,
    val action: String? = null,
)

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
    // Where Octo serves the cover (from the song's file, else the address
    // saved with it). Older servers name none.
    val cover: String? = null,
)

// A download as the app shows it: its cover from the address Octo serves it
// at, on the admin pages' own address and signed by `sign`. A server that
// names none, or names another host, keeps the cover address the log saved.
internal fun withServedCover(record: DownloadRecord, base: HttpUrl, sign: (HttpUrl) -> HttpUrl): DownloadRecord {
    val path = record.cover?.takeIf { it.startsWith("/") && !it.startsWith("//") } ?: return record
    val served = base.resolve(path) ?: return record
    return record.copy(coverArtUrl = sign(served).toString())
}

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

// Octo's admin pages could not be used: not signed in, out of reach, or
// something else answered. `gateway` names a sign-in page in front of the
// server, when that is what answered.
class AdminUnavailable(message: String, val gateway: Gateway? = null, val loginSent: Boolean = false) : IOException(message)

// Octo's answer to an admin call, read as `serializer` through the answer
// reader. Anything that is not Octo's JSON (an error, a sign-in page in
// front of the server) is AdminUnavailable.
internal fun <T> readAdminAnswer(response: Response, path: String, json: Json, serializer: KSerializer<T>): T {
    val text = try {
        response.jsonBody(path)
    } catch (e: SubsonicException.NotSubsonic) {
        throw adminRefusal(e, path)
    }
    return try {
        json.decodeFromString(serializer, text)
    } catch (e: SerializationException) {
        throw AdminUnavailable(NOT_UNDERSTOOD)
    }
}

// Why an admin call's answer is not Octo's: a sign-in page in front of the
// server (named, with how to get through), a Navidrome account that is not
// an admin, the status, or a page Octo would not send.
internal fun adminRefusal(answer: SubsonicException.NotSubsonic, path: String): AdminUnavailable {
    val gateway = answer.gateway
    val status = answer.status
    return when {
        gateway != null -> AdminUnavailable(GatewayWords.failure(gateway, answer.loginSent), gateway, answer.loginSent)
        status == 403 -> AdminUnavailable("Needs a Navidrome admin account")
        status != null -> AdminUnavailable("HTTP $status from $path")
        else -> AdminUnavailable(NOT_UNDERSTOOD)
    }
}

private const val NOT_UNDERSTOOD = "Octo answered in a way the app does not understand"

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

    // The addresses the app trusts with its sign-in, from the last locate(),
    // and the kept server they were found for: only that server's sign-in
    // ever goes to them.
    private class Trust(val server: String, val urls: Set<HttpUrl>)

    @Volatile private var trusted: Trust? = null

    // The kept server in use, by its id, as it changes; null with none.
    val server: Flow<String?> = sessions.state.map { it.accountId }.distinctUntilChanged()

    // Where the admin pages answer right now, or null when away from home
    // or not signed in to Octo.
    suspend fun locate(): HttpUrl? {
        val session = session() ?: return null
        val client = session.client
        val tried = HashSet<HttpUrl>()
        suspend fun first(candidates: List<HttpUrl>): HttpUrl? =
            candidates.map(::root).filter(tried::add).firstOrNull { answers(it) }
        // The station addresses cost a server call, so they are only asked for last.
        val own = listOf(client.baseUrl, client.primaryUrl).map(::root).toSet()
        trusted = Trust(session.id, own)
        val found = first(listOfNotNull(client.baseUrl, client.primaryUrl, lastFound(session.id)))
            ?: stationHosts(client).map(::root).let { announced ->
                trusted = Trust(session.id, own + announced)
                first(announced)
            }
            ?: return null
        context.adminData.edit {
            it[addressKey(session.id)] = found.toString()
            it.remove(ADDRESS)
        }
        return found
    }

    // Where the admin pages answered last, and for which kept server.
    @Volatile private var lastPlace: Pair<String, HttpUrl>? = null

    // Where to ask about updating the server, and how to sign the call.
    // With `reuse`, the address found last is asked again without looking
    // for it, as while the server restarts, when nothing answers anywhere.
    suspend fun updatePlace(reuse: Boolean): AdminPlace? {
        val session = session() ?: return null
        val kept = lastPlace?.takeIf { it.first == session.id && trusted?.server == session.id }?.second
        val base = if (reuse && kept != null) kept else locate()?.also { lastPlace = session.id to it } ?: return null
        return AdminPlace(base, ::sign)
    }

    // Forgets where a server taken off the list had its admin pages.
    suspend fun forget(server: String) {
        context.adminData.edit { it.remove(addressKey(server)) }
    }

    // The address last found for a kept server, tried first. An older
    // version kept one for its only server, which stays that server's while
    // it is the only one kept.
    private suspend fun lastFound(server: String): HttpUrl? {
        val saved = context.adminData.data.first()
        val older = saved[ADDRESS].takeIf { sessions.servers.value.servers.size == 1 }
        return (saved[addressKey(server)] ?: older)?.toHttpUrlOrNull()
    }

    suspend fun status(base: HttpUrl): AdminStatus = get(base, "status", AdminStatus.serializer())

    suspend fun downloads(base: HttpUrl): List<DownloadRecord> =
        get(base, "downloads", Downloads.serializer()).downloads.map { withServedCover(it, base, ::sign) }

    suspend fun radio(base: HttpUrl): RadioState =
        get(base, "lastfm/radio", RadioState.serializer(), listOfNotNull(client()?.username?.let { "user" to it }))

    suspend fun library(base: HttpUrl): LibraryStatus = get(base, "library-status", LibraryStatus.serializer())

    // Asks Octo to build the signed-in listener's stations again.
    suspend fun refreshStations(base: HttpUrl) {
        val user = client()?.username ?: throw AdminUnavailable("Not signed in")
        send(base, "lastfm/radio/refresh", JsonObject(mapOf("user" to JsonPrimitive(user))).toString())
    }

    // The full admin pages, for everything the app does not show, opened at
    // one card when `section` names it.
    fun pageUrl(base: HttpUrl, section: String? = null): HttpUrl = adminPageUrl(base, section)

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
        val url = sign(api(base, path).newBuilder().apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build())
        try {
            calls.newCall(Request.Builder().url(url).build()).execute().use { response -> readAdminAnswer(response, path, json, serializer) }
        } catch (e: IOException) {
            throw e as? AdminUnavailable ?: AdminUnavailable("Octo did not answer")
        }
    }

    // A change. Octo only takes changes that carry its admin header.
    private suspend fun send(base: HttpUrl, path: String, body: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(sign(api(base, path)))
            .header("X-Octo-Admin", "1")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        try {
            calls.newCall(request).execute().use { response ->
                // A sign-in page answering in place of Octo has not taken the change.
                if (!response.isSuccessful || isHtml(response.header("Content-Type"))) throw adminRefusal(response.notSubsonic(path), path)
            }
        } catch (e: IOException) {
            throw e as? AdminUnavailable ?: AdminUnavailable("Octo did not answer")
        }
    }

    private fun api(base: HttpUrl, path: String): HttpUrl =
        base.newBuilder().encodedPath("/api/admin/$path").build()

    // Octo's admin pages ask who is calling; the app answers with its own
    // sign-in, but only to an address it already trusts with it. The status
    // probe in answers() stays unsigned, since it asks addresses that may not be Octo.
    // Another kept server's sign-in never goes to addresses found for the
    // one before it.
    private fun sign(url: HttpUrl): HttpUrl {
        val session = session() ?: return url
        val trust = trusted?.takeIf { it.server == session.id } ?: return url
        return if (mayCarrySignIn(url, trust.urls)) session.client.signed(url) else url
    }

    private fun session(): Session? = (sessions.state.value as? SessionState.SignedIn)?.session

    private fun client(): SubsonicClient? = session()?.client

    private companion object {
        // An older version's one address, for its only server.
        val ADDRESS = stringPreferencesKey("address")

        fun addressKey(server: String) = stringPreferencesKey("address@$server")

        // How long to wait for the station list when looking for Octo.
        const val STATIONS_WAIT_MS = 5_000L
    }
}
