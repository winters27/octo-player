package app.winters.octo.subsonic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

const val API_VERSION = "1.16.1"

class SubsonicClient(
    // The address the server is known by. Its library is kept under this
    // address whichever one the calls go to.
    val primaryUrl: HttpUrl,
    private val credentials: Credentials,
    http: OkHttpClient,
    private val clientName: String = "Octo",
    // Headers every request to the server carries, such as a proxy's
    // access token. They go only to the server's own addresses.
    headers: Map<String, String> = emptyMap(),
    // The library folder calls are limited to, or null for all of them.
    val musicFolderId: String? = null,
    // Where calls go right now, when the server has more than one address.
    private val route: () -> HttpUrl = { primaryUrl },
) {
    // The address calls go to right now: a home address while it answers,
    // otherwise the primary one.
    val baseUrl: HttpUrl get() = route()

    val username: String get() = credentials.username

    val authMode: AuthMode get() = credentials.mode

    private val http: OkHttpClient =
        if (headers.isEmpty()) http
        else http.newBuilder()
            .addNetworkInterceptor(ServerHeaders { HeaderScope(setOf(origin(primaryUrl), origin(baseUrl)), headers) })
            .build()

    // For the one call that can take minutes: Octo builds the stations the
    // first time they are asked for after it starts.
    private val patient: OkHttpClient by lazy { this.http.newBuilder().readTimeout(3, TimeUnit.MINUTES).build() }

    // A signed address for an endpoint. Every call gets a fresh salt, so
    // never use one of these as a cache key.
    fun url(endpoint: String, params: Map<String, String> = emptyMap()): HttpUrl =
        baseUrl.newBuilder()
            .addPathSegment("rest")
            .addPathSegment(endpoint)
            .apply { credentials.authParams().forEach { (key, value) -> addQueryParameter(key, value) } }
            .addQueryParameter("v", API_VERSION)
            .addQueryParameter("c", clientName)
            .addQueryParameter("f", "json")
            .apply { params.forEach { (key, value) -> addQueryParameter(key, value) } }
            .build()

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

    // Whether the server lists an extension at this version. A server that
    // cannot say which extensions it has lists none.
    suspend fun supports(name: String, version: Int = 1): Boolean =
        try {
            extensions().lists(name, version)
        } catch (e: SubsonicException) {
            false
        }

    // The downloads Octo is doing for the signed-in user, and the ones it
    // finished lately. Only for servers that list the octoAcquisitions
    // extension; others answer with an error.
    suspend fun acquisitions(): List<Acquisition> =
        get("getAcquisitions", key = "acquisitions", serializer = Acquisitions.serializer(), default = Acquisitions()).acquisition

    // One song, by its id on the server.
    suspend fun song(id: String): Song = get("getSong", mapOf("id" to id), "song", Song.serializer())

    suspend fun user(username: String): User =
        get("getUser", mapOf("username" to username), "user", User.serializer())

    suspend fun albumList(
        type: AlbumListType,
        size: Int,
        offset: Int = 0,
        musicFolderId: String? = this.musicFolderId,
    ): List<Album> =
        get(
            "getAlbumList2",
            mapOf("type" to type.wire, "size" to size.toString(), "offset" to offset.toString()).inFolder(musicFolderId),
            "albumList2",
            AlbumList.serializer(),
            AlbumList(),
        ).album

    suspend fun album(id: String): AlbumWithSongs =
        get("getAlbum", mapOf("id" to id), "album", AlbumWithSongs.serializer())

    suspend fun artists(musicFolderId: String? = this.musicFolderId): List<ArtistIndex> =
        get(
            "getArtists",
            emptyMap<String, String>().inFolder(musicFolderId),
            "artists",
            Artists.serializer(),
            Artists(),
        ).index

    // The library folders on the server. A server with several libraries
    // lists each as a folder.
    suspend fun musicFolders(): List<MusicFolder> =
        get(
            "getMusicFolders",
            key = "musicFolders",
            serializer = MusicFolders.serializer(),
            default = MusicFolders(),
        ).musicFolder

    // The server's folders from the top, limited to one library folder when
    // given. Servers that file by tags make these folders up from the tags.
    suspend fun indexes(musicFolderId: String? = this.musicFolderId): FolderIndex {
        val wire = get(
            "getIndexes",
            emptyMap<String, String>().inFolder(musicFolderId),
            "indexes",
            IndexesWire.serializer(),
            IndexesWire(),
        )
        val loose = children(wire.child)
        return FolderIndex(wire.index.flatMap { it.artist } + loose.first, loose.second)
    }

    // One folder on the server and what it holds.
    suspend fun musicDirectory(id: String): MusicDirectory {
        val wire = get("getMusicDirectory", mapOf("id" to id), "directory", DirectoryWire.serializer())
        val (folders, songs) = children(wire.child)
        return MusicDirectory(wire.id, wire.name, folders, songs)
    }

    // Splits a folder's children into folders and songs. A folder is named
    // by its title, or its name on servers that send that instead.
    private fun children(list: List<kotlinx.serialization.json.JsonObject>): Pair<List<DirectoryRef>, List<Song>> {
        val folders = mutableListOf<DirectoryRef>()
        val songs = mutableListOf<Song>()
        for (child in list) {
            val isDir = child["isDir"]?.jsonPrimitive?.booleanOrNull ?: false
            val id = child["id"]?.jsonPrimitive?.contentOrNull ?: continue
            if (isDir) {
                val name = child["title"]?.jsonPrimitive?.contentOrNull ?: child["name"]?.jsonPrimitive?.contentOrNull
                folders += DirectoryRef(id, name.orEmpty())
            } else {
                songs += json.decodeFromJsonElement(Song.serializer(), child)
            }
        }
        return folders to songs
    }

    // Who an API key belongs to. Only servers that take API keys answer.
    suspend fun tokenInfo(): String? =
        get("tokenInfo", key = "tokenInfo", serializer = TokenInfo.serializer(), default = TokenInfo()).username

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

    suspend fun search(
        query: String,
        artists: Int = 10,
        albums: Int = 20,
        songs: Int = 30,
        musicFolderId: String? = this.musicFolderId,
    ): SearchResult =
        get(
            "search3",
            mapOf(
                "query" to query,
                "artistCount" to "$artists",
                "albumCount" to "$albums",
                "songCount" to "$songs",
            ).inFolder(musicFolderId),
            "searchResult3",
            SearchResult.serializer(),
            SearchResult(),
        )

    // One page of every song on the server, for copying the whole library.
    // An empty search matches everything on servers that allow it; others
    // answer with nothing.
    suspend fun songPage(size: Int, offset: Int, musicFolderId: String? = this.musicFolderId): List<Song> =
        get(
            "search3",
            mapOf(
                "query" to "",
                "artistCount" to "0",
                "albumCount" to "0",
                "songCount" to "$size",
                "songOffset" to "$offset",
            ).inFolder(musicFolderId),
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

    suspend fun unstarAlbums(ids: List<String>) = send("unstar", ids.map { "albumId" to it })

    // Stars whole artists, as favourites. Several go in one call.
    suspend fun starArtists(ids: List<String>) = send("star", ids.map { "artistId" to it })

    suspend fun unstarArtists(ids: List<String>) = send("unstar", ids.map { "artistId" to it })

    // Tells the server a song was played (submission) or is playing now.
    // The time is when it started, in milliseconds.
    suspend fun scrobble(id: String, time: Long, submission: Boolean) =
        send("scrobble", listOf("id" to id, "time" to "$time", "submission" to "$submission"))

    // Every set of lyrics the server has for a song. Only for servers that
    // list the songLyrics extension. Enhanced (version 2) adds word timings,
    // who sings each line, and translations.
    suspend fun lyricsBySongId(id: String, enhanced: Boolean): List<StructuredLyrics> =
        get(
            "getLyricsBySongId",
            if (enhanced) mapOf("id" to id, "enhanced" to "true") else mapOf("id" to id),
            "lyricsList",
            LyricsList.serializer(),
            LyricsList(),
        ).structuredLyrics

    // The older lyrics call, found by artist and title: plain text, or null
    // when the server has none.
    suspend fun lyrics(artist: String, title: String): String? =
        get(
            "getLyrics",
            mapOf("artist" to artist, "title" to title),
            "lyrics",
            PlainLyrics.serializer(),
            PlainLyrics(),
        ).value?.takeIf(String::isNotBlank)

    // Makes a playlist for the signed-in user with these songs in this
    // order, and answers with it as the server now has it (null from a
    // server older than API 1.14, which sends nothing back). `formPost`
    // sends the params in a form body, for a server that lists that
    // extension; otherwise a long list goes in batches.
    suspend fun createPlaylist(name: String, songIds: List<String>, formPost: Boolean = false): PlaylistWithSongs? {
        val (first, rest) = createCalls(name, songIds, formPost)
        val created = decode(sendCall(first, formPost), null, CreatedPlaylist.serializer(), null).playlist
        if (rest.isNotEmpty()) {
            val id = created?.id ?: throw SubsonicException.Server(0, "The server did not say the new playlist's id")
            addSongsCalls(id, rest).forEach { sendCall(it, formPost) }
        }
        return created
    }

    // Makes a playlist's songs exactly these, in this order: createPlaylist
    // with the playlist's id replaces its whole list.
    suspend fun replacePlaylistSongs(id: String, songIds: List<String>, formPost: Boolean = false) {
        replaceSongsCalls(id, songIds, formPost).forEach { sendCall(it, formPost) }
    }

    // Changes a playlist's details, takes songs out by their place in the
    // list (a place may repeat) and adds songs to the end. Only what is
    // given changes.
    suspend fun updatePlaylist(
        id: String,
        name: String? = null,
        comment: String? = null,
        public: Boolean? = null,
        songIdsToAdd: List<String> = emptyList(),
        songIndexesToRemove: List<Int> = emptyList(),
        formPost: Boolean = false,
    ) {
        updateCalls(id, name, comment, public, songIdsToAdd, songIndexesToRemove, formPost).forEach { sendCall(it, formPost) }
    }

    suspend fun deletePlaylist(id: String) = send("deletePlaylist", listOf("id" to id))

    // Runs one playlist call, with its params in the address or in a form
    // body, and hands back the answer once it is known to be ok.
    private suspend fun sendCall(call: PlaylistCall, formPost: Boolean): String {
        val request = if (formPost) {
            val form = FormBody.Builder().apply { call.params.forEach { (key, value) -> add(key, value) } }.build()
            Request.Builder().url(url(call.endpoint)).post(form).build()
        } else {
            val address = url(call.endpoint).newBuilder().apply { call.params.forEach { (key, value) -> addQueryParameter(key, value) } }.build()
            Request.Builder().url(address).build()
        }
        val body = try {
            val response = http.newCall(request).await()
            withContext(Dispatchers.IO) {
                response.use {
                    if (!it.isSuccessful) throw SubsonicException.NotSubsonic("HTTP ${it.code} from ${call.endpoint}")
                    it.body.string()
                }
            }
        } catch (e: IOException) {
            throw SubsonicException.Unreachable(e)
        }
        withContext(Dispatchers.Default) { decode(body, null, ServerInfo.serializer(), null) }
        return body
    }

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
                40, 44 -> SubsonicException.WrongCredentials(message)
                41, 42 -> SubsonicException.AuthNotSupported(code, message)
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

    // The params with the folder added, when there is one.
    private fun Map<String, String>.inFolder(folder: String?) = if (folder == null) this else this + ("musicFolderId" to folder)

    // The queue saved on the server, or null when none is. Only for
    // servers that list the indexBasedQueue extension.
    suspend fun playQueueByIndex(): PlayQueueByIndex? =
        get("getPlayQueueByIndex", key = "playQueueByIndex", serializer = PlayQueueByIndex.serializer(), default = PlayQueueByIndex())
            .takeIf { it.entry.isNotEmpty() }

    // The older form, where the current song is named by id.
    suspend fun playQueue(): PlayQueue? =
        get("getPlayQueue", key = "playQueue", serializer = PlayQueue.serializer(), default = PlayQueue())
            .takeIf { it.entry.isNotEmpty() }

    // Saves the queue, with the current song as its place in the list.
    suspend fun savePlayQueueByIndex(ids: List<String>, currentIndex: Int, positionMs: Long) =
        send("savePlayQueueByIndex", queueSaveParams(ids, currentIndex, positionMs, indexBased = true))

    // Saves the queue, with the current song named by id.
    suspend fun savePlayQueue(ids: List<String>, currentIndex: Int, positionMs: Long) =
        send("savePlayQueue", queueSaveParams(ids, currentIndex, positionMs, indexBased = false))

    // Makes a public link to songs or an album. Servers answer with the new
    // share; one with sharing switched off answers with an error.
    suspend fun createShare(ids: List<String>, description: String? = null, expiresAtMs: Long? = null): Share =
        getWith("createShare", shareParams(ids, description, expiresAtMs), "shares", Shares.serializer(), Shares())
            .share.firstOrNull() ?: throw SubsonicException.Server(0, "The server made no share")

    // The signed-in user's shares.
    suspend fun shares(): List<Share> =
        get("getShares", key = "shares", serializer = Shares.serializer(), default = Shares()).share

    suspend fun deleteShare(id: String) = send("deleteShare", listOf("id" to id))

    // Asks the server to read its music folders again. Needs an admin.
    suspend fun startScan(): ScanStatus =
        get("startScan", key = "scanStatus", serializer = ScanStatus.serializer(), default = ScanStatus())

    suspend fun scanStatus(): ScanStatus =
        get("getScanStatus", key = "scanStatus", serializer = ScanStatus.serializer(), default = ScanStatus())

    // What everyone on the server is playing right now.
    suspend fun nowPlaying(): List<NowPlayingEntry> =
        get("getNowPlaying", key = "nowPlaying", serializer = NowPlayingList.serializer(), default = NowPlayingList()).entry

    // The radio stations with their home pages, for editing. Patient for
    // the same reason as radioStations.
    suspend fun radioStationDetails(): List<RadioStationDetails> =
        get(
            "getInternetRadioStations",
            key = "internetRadioStations",
            serializer = RadioStationDetailsList.serializer(),
            default = RadioStationDetailsList(),
            http = patient,
        ).internetRadioStation

    suspend fun createRadioStation(streamUrl: String, name: String, homepageUrl: String? = null) =
        send("createInternetRadioStation", stationParams(streamUrl, name, homepageUrl))

    suspend fun updateRadioStation(id: String, streamUrl: String, name: String, homepageUrl: String? = null) =
        send("updateInternetRadioStation", listOf("id" to id) + stationParams(streamUrl, name, homepageUrl))

    suspend fun deleteRadioStation(id: String) = send("deleteInternetRadioStation", listOf("id" to id))

    // Like get, for params that may repeat a name.
    private suspend fun <T> getWith(
        endpoint: String,
        params: List<Pair<String, String>>,
        key: String,
        serializer: KSerializer<T>,
        default: T,
    ): T {
        val url = url(endpoint).newBuilder().apply { params.forEach { (name, value) -> addQueryParameter(name, value) } }.build()
        val body = fetch(url, endpoint)
        return withContext(Dispatchers.Default) { decode(body, key, serializer, default) }
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
