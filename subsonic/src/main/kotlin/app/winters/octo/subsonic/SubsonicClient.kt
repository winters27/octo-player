package app.winters.octo.subsonic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resumeWithException

const val API_VERSION = "1.16.1"

class SubsonicClient(
    val baseUrl: HttpUrl,
    private val credentials: Credentials,
    private val http: OkHttpClient,
    private val clientName: String = "Octo",
) {
    val username: String get() = credentials.username

    // A signed address for an endpoint. Every call gets a fresh salt, so
    // never use one of these as a cache key.
    fun url(endpoint: String, params: Map<String, String> = emptyMap()): HttpUrl {
        val salt = newSalt()
        return baseUrl.newBuilder()
            .addPathSegment("rest")
            .addPathSegment(endpoint)
            .addQueryParameter("u", credentials.username)
            .addQueryParameter("t", credentials.sign(salt))
            .addQueryParameter("s", salt)
            .addQueryParameter("v", API_VERSION)
            .addQueryParameter("c", clientName)
            .addQueryParameter("f", "json")
            .apply { params.forEach { (key, value) -> addQueryParameter(key, value) } }
            .build()
    }

    fun coverArtUrl(coverId: String, size: Int): HttpUrl =
        url("getCoverArt", mapOf("id" to coverId, "size" to size.toString()))

    suspend fun ping(): ServerInfo = get("ping", key = null, serializer = ServerInfo.serializer())

    suspend fun extensions(): List<Extension> =
        get(
            "getOpenSubsonicExtensions",
            key = "openSubsonicExtensions",
            serializer = ListSerializer(Extension.serializer()),
            default = emptyList(),
        )

    suspend fun user(username: String): User =
        get("getUser", mapOf("username" to username), "user", User.serializer())

    suspend fun albumList(type: AlbumListType, size: Int, offset: Int = 0): List<Album> =
        get(
            "getAlbumList2",
            mapOf("type" to type.wire, "size" to size.toString(), "offset" to offset.toString()),
            "albumList2",
            AlbumList.serializer(),
            AlbumList(),
        ).album

    suspend fun album(id: String): AlbumWithSongs =
        get("getAlbum", mapOf("id" to id), "album", AlbumWithSongs.serializer())

    suspend fun artists(): List<ArtistIndex> =
        get("getArtists", key = "artists", serializer = Artists.serializer(), default = Artists()).index

    suspend fun artist(id: String): ArtistWithAlbums =
        get("getArtist", mapOf("id" to id), "artist", ArtistWithAlbums.serializer())

    // On Octo this call also sets up per-user playlists and the radio
    // profile, so callers keep the result for the session.
    suspend fun playlists(): List<Playlist> =
        get("getPlaylists", key = "playlists", serializer = Playlists.serializer(), default = Playlists()).playlist

    suspend fun playlist(id: String): PlaylistWithSongs =
        get("getPlaylist", mapOf("id" to id), "playlist", PlaylistWithSongs.serializer())

    suspend fun search(query: String, artists: Int = 10, albums: Int = 20, songs: Int = 30): SearchResult =
        get(
            "search3",
            mapOf(
                "query" to query,
                "artistCount" to "$artists",
                "albumCount" to "$albums",
                "songCount" to "$songs",
            ),
            "searchResult3",
            SearchResult.serializer(),
            SearchResult(),
        )

    suspend fun starred(): Starred =
        get("getStarred2", key = "starred2", serializer = Starred.serializer(), default = Starred())

    // Marks songs as starred for the signed-in user. Several go in one call.
    suspend fun star(ids: List<String>) = send("star", ids.map { "id" to it })

    suspend fun unstar(ids: List<String>) = send("unstar", ids.map { "id" to it })

    // Tells the server a song was played (submission) or is playing now.
    // The time is when it started, in milliseconds.
    suspend fun scrobble(id: String, time: Long, submission: Boolean) =
        send("scrobble", listOf("id" to id, "time" to "$time", "submission" to "$submission"))

    // A call that only answers ok or an error. The params may repeat a name.
    private suspend fun send(endpoint: String, params: List<Pair<String, String>>) {
        val url = url(endpoint).newBuilder().apply { params.forEach { (key, value) -> addQueryParameter(key, value) } }.build()
        val body = fetch(url, endpoint)
        withContext(Dispatchers.Default) { decode(body, null, ServerInfo.serializer(), null) }
    }

    private suspend fun <T> get(
        endpoint: String,
        params: Map<String, String> = emptyMap(),
        key: String?,
        serializer: KSerializer<T>,
        default: T? = null,
    ): T {
        val body = fetch(url(endpoint, params), endpoint)
        // Big answers (all artists is ~260 KB) must not parse on the main thread.
        return withContext(Dispatchers.Default) { decode(body, key, serializer, default) }
    }

    private suspend fun fetch(url: HttpUrl, endpoint: String): String {
        val request = Request.Builder().url(url).build()
        return try {
            val response = http.newCall(request).await()
            withContext(Dispatchers.IO) {
                response.use {
                    if (!it.isSuccessful) {
                        throw SubsonicException.NotSubsonic("HTTP ${it.code} from $endpoint")
                    }
                    it.body.string()
                }
            }
        } catch (e: IOException) {
            throw SubsonicException.Unreachable(e)
        }
    }

    internal fun <T> decode(body: String, key: String?, serializer: KSerializer<T>, default: T?): T {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?.get("subsonic-response")?.jsonObject
            ?: throw SubsonicException.NotSubsonic("The server did not answer like a Subsonic server")
        if (root["status"]?.jsonPrimitive?.contentOrNull != "ok") {
            val error = root["error"]?.jsonObject
            val code = error?.get("code")?.jsonPrimitive?.intOrNull ?: 0
            val message = error?.get("message")?.jsonPrimitive?.contentOrNull ?: "Request failed"
            throw when (code) {
                40, 41 -> SubsonicException.WrongCredentials(message)
                70 -> SubsonicException.NotFound(message)
                else -> SubsonicException.Server(code, message)
            }
        }
        val payload = if (key == null) root else root[key]
        return when {
            payload != null -> json.decodeFromJsonElement(serializer, payload)
            default != null -> default
            else -> throw SubsonicException.Server(0, "Missing \"$key\" in the answer")
        }
    }

    private companion object {
        val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            explicitNulls = false
        }
    }
}

// Runs the call without blocking a thread, and cancels it if the caller
// stops waiting.
private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
        override fun onResponse(call: Call, response: Response) {
            cont.resume(response) { _, value, _ -> value.close() }
        }
    })
}
