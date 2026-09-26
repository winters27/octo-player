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
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

const val API_VERSION = "1.16.1"

class SubsonicClient(
    val baseUrl: HttpUrl,
    private val credentials: Credentials,
    private val http: OkHttpClient,
    private val clientName: String = "Octo",
) {
    val username: String get() = credentials.username

    // For the one call that can take minutes: Octo builds the stations the
    // first time they are asked for after it starts.
    private val patient: OkHttpClient by lazy { http.newBuilder().readTimeout(3, TimeUnit.MINUTES).build() }

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

    // An artist's biography, pictures and artists like them. The id may also
    // be an album's or a song's, for their artist.
    suspend fun artistInfo(id: String, similar: Int = 20): ArtistInfo =
        get("getArtistInfo2", mapOf("id" to id, "count" to "$similar"), "artistInfo2", ArtistInfo.serializer(), ArtistInfo())

    // An artist's most played songs. Servers look them up by name; one that
    // lists the "topSongsByArtistId" extension can take the artist's id too,
    // which is surer when two artists share a name.
    suspend fun topSongs(artistName: String, count: Int = 10, artistId: String? = null): List<Song> =
        get(
            "getTopSongs",
            buildMap {
                put("artist", artistName)
                put("count", "$count")
                artistId?.let { put("id", it) }
            },
            "topSongs",
            SongList.serializer(),
            SongList(),
        ).song

    // On Octo this call also sets up per-user playlists and the radio
    // profile, so callers keep the result for the session.
    suspend fun playlists(): List<Playlist> =
        get("getPlaylists", key = "playlists", serializer = Playlists.serializer(), default = Playlists()).playlist

    suspend fun playlist(id: String): PlaylistWithSongs =
        get("getPlaylist", mapOf("id" to id), "playlist", PlaylistWithSongs.serializer())

    // Songs like this one, for a radio that starts from it. On Octo these
    // mix library songs with songs found online.
    suspend fun similarSongs(id: String, count: Int = 50): List<Song> =
        get(
            "getSimilarSongs2",
            mapOf("id" to id, "count" to "$count"),
            "similarSongs2",
            SongList.serializer(),
            SongList(),
        ).song

    // Streams the server runs. On Octo each is also a read-only playlist
    // with the same id, holding the songs it will play.
    suspend fun radioStations(): List<RadioStation> =
        get(
            "getInternetRadioStations",
            key = "internetRadioStations",
            serializer = RadioStations.serializer(),
            default = RadioStations(),
            http = patient,
        ).internetRadioStation

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

    // One page of every song on the server, for copying the whole library.
    // An empty search matches everything on servers that allow it; others
    // answer with nothing.
    suspend fun songPage(size: Int, offset: Int): List<Song> =
        get(
            "search3",
            mapOf(
                "query" to "",
                "artistCount" to "0",
                "albumCount" to "0",
                "songCount" to "$size",
                "songOffset" to "$offset",
            ),
            "searchResult3",
            SearchResult.serializer(),
            SearchResult(),
        ).song

    suspend fun starred(): Starred =
        get("getStarred2", key = "starred2", serializer = Starred.serializer(), default = Starred())

    // Marks songs as starred for the signed-in user. Several go in one call.
    suspend fun star(ids: List<String>) = send("star", ids.map { "id" to it })

    suspend fun unstar(ids: List<String>) = send("unstar", ids.map { "id" to it })

    // Rates a song for the signed-in user, 1 to 5 stars; 0 takes the rating off.
    suspend fun setRating(id: String, rating: Int) =
        send("setRating", listOf("id" to id, "rating" to "${rating.coerceIn(0, 5)}"))

    // Stars whole albums. On Octo an album found online is downloaded.
    suspend fun starAlbums(ids: List<String>) = send("star", ids.map { "albumId" to it })

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
        http: OkHttpClient = this.http,
    ): T {
        val body = fetch(url(endpoint, params), endpoint, http)
        // Big answers (all artists is ~260 KB) must not parse on the main thread.
        return withContext(Dispatchers.Default) { decode(body, key, serializer, default) }
    }

    private suspend fun fetch(url: HttpUrl, endpoint: String, http: OkHttpClient = this.http): String {
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
