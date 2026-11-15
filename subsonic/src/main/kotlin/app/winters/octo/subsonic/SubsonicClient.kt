package app.winters.octo.subsonic

import app.winters.octo.subsonic.gateway.checkStreamed
import app.winters.octo.subsonic.gateway.jsonBody
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonEncodingException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSource
import okio.buffer
import okio.sink
import okio.source
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

const val API_VERSION = "1.16.1"

// How many bytes a drain reads at a time, checking for a cancel between.
const val DRAIN_BUFFER = 64L * 1024

// How many bytes a drain writes between asks of whether there is room.
const val DRAIN_ROOM_EVERY = 4L * 1024 * 1024

// No values, for a paged call that sends none of a list.
val NO_VALUES: PagedValues = PagedValues.of(emptyList())

class SubsonicClient(
    // The address the server is known by. Its library is kept under this
    // address whichever one the calls go to.
    val primaryUrl: HttpUrl,
    credentials: Credentials,
    // The server's headers, certificates and sign-in page cookies come with
    // the client, from the security installed on it, so every route keeps
    // to the same rules.
    private val http: OkHttpClient,
    private val clientName: String = "Octo",
    // The library folder calls are limited to, or null for all of them.
    val musicFolderId: String? = null,
    // Where calls go right now, when the server has more than one address.
    private val route: () -> HttpUrl = { primaryUrl },
) {
    // The address calls go to right now: a home address while it answers,
    // otherwise the primary one.
    val baseUrl: HttpUrl get() = route()

    // Who signs in, and how. A change of the signed-in user's own password
    // here puts the new one in, so the client keeps working afterwards.
    @Volatile private var credentials: Credentials = credentials

    val username: String get() = credentials.username

    val authMode: AuthMode get() = credentials.mode

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

    // Signs an address outside /rest, such as Octo's admin pages, with the
    // same sign-in as every Subsonic call. A fresh salt each time.
    fun signed(url: HttpUrl): HttpUrl =
        url.newBuilder()
            .apply { credentials.authParams().forEach { (key, value) -> addQueryParameter(key, value) } }
            .addQueryParameter("v", API_VERSION)
            .addQueryParameter("c", clientName)
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
    // cannot say which extensions it has lists none, and so does one that
    // could not be asked; see supportsIfKnown for telling those apart.
    suspend fun supports(name: String, version: Int = 1): Boolean = supportsIfKnown(name, version) ?: false

    // Whether the server lists an extension at this version, or null when it
    // could not be asked: out of reach, a proxy answering while it restarts,
    // a sign-in page in front of the server, a page that is not the
    // server's, or the sign-in refused. Only a yes or no is the server's own
    // answer, worth keeping; a null is worth asking again. A server that
    // answers the call with an error, or has no such address (a 404), has no
    // extensions to list, which is a no.
    suspend fun supportsIfKnown(name: String, version: Int = 1): Boolean? =
        try {
            extensions().lists(name, version)
        } catch (e: SubsonicException.Unreachable) {
            null
        } catch (e: SubsonicException.NotSubsonic) {
            if (e.gateway == null && e.status != null && !e.serverBusy) false else null
        } catch (e: SubsonicException.WrongCredentials) {
            null
        } catch (e: SubsonicException) {
            false
        }

    // The downloads Octo is doing for the signed-in user, and the ones it
    // finished lately. Only for servers that list the octoAcquisitions
    // extension; others answer with an error. `keepsAnyway` is whether the
    // server lists OCTO_KEEP_ANYWAY_VERSION; only then are a row's
    // `keepAnyway` and `heldTracks` read.
    suspend fun acquisitions(keepsAnyway: Boolean = false): List<Acquisition> =
        get("getAcquisitions", key = "acquisitions", serializer = Acquisitions.serializer(), default = Acquisitions())
            .acquisition.map { it.listedBy(keepsAnyway) }

    // One download with its whole log, by the key the list gives it. Only
    // for servers that list octoAcquisitions at version 2. `keepsAnyway` as
    // for acquisitions.
    suspend fun acquisition(key: String, keepsAnyway: Boolean = false): Acquisition =
        get("getAcquisition", mapOf("key" to key), "acquisition", Acquisition.serializer()).listedBy(keepsAnyway)

    // Keeps a held download anyway, by the key the list gives it: the
    // version the signed-in user heard, or an album's held tracks, though
    // Octo could not prove them. Answers ok, or an error in the server's
    // words. Only for servers that list octoAcquisitions at
    // OCTO_KEEP_ANYWAY_VERSION.
    suspend fun keepAcquisitionAnyway(key: String) = send("keepAcquisitionAnyway", listOf("key" to key))

    // Takes the finished downloads off the signed-in user's list, or only
    // the one with this key, and answers how many left it.
    suspend fun clearAcquisitions(key: String? = null): Int =
        get("clearAcquisitions", key?.let { mapOf("key" to it) }.orEmpty(), "cleared", Cleared.serializer(), Cleared()).count

    // Starts a search for one song (a found song's id, or a library song's)
    // on the user's download sources, answered at once while it runs.
    suspend fun findSongs(id: String): FoundSongs = get("findSongs", mapOf("id" to id), "foundSongs", FoundSongs.serializer())

    // A Find songs search as it stands, by the id findSongs answered.
    suspend fun foundSongs(search: String): FoundSongs =
        get("getFoundSongs", mapOf("search" to search), "foundSongs", FoundSongs.serializer())

    // Fetches exactly this copy from a Find songs list: by its id, or on a
    // server older than copy ids, by its place on the list, which holds
    // only once the search has finished. `anyway` is sent only when not
    // null, and only to a server that lists octoAcquisitions at
    // OCTO_REPLACE_ANYWAY_VERSION: false asks first about a copy that is
    // not better than the library's, true replaces it all the same.
    suspend fun pickFoundSong(search: String, copy: FoundCandidate, anyway: Boolean? = null): PickResult {
        val which = copy.id?.let { "copy" to it }
            ?: copy.index?.let { "candidate" to it.toString() }
            ?: return PickResult(PICK_SKIPPED, "This copy cannot be picked. Search again.")
        val params = buildMap {
            put("search", search)
            put(which.first, which.second)
            anyway?.let { put("anyway", it.toString()) }
        }
        return get("pickFoundSong", params, "pick", PickResult.serializer())
    }

    // What the signed-in user may do to the library's files. Only for
    // servers that list the octoLibraryActions extension.
    suspend fun libraryActions(): LibraryActions =
        get("getLibraryActions", key = "libraryActions", serializer = LibraryActions.serializer(), default = LibraryActions())

    // Asks the server to act on one library song's file: LIBRARY_ACTION_REMOVE
    // moves it out of the library into the server's trash, and
    // LIBRARY_ACTION_UPGRADE queues a look for its FLAC and answers at once.
    // Never a rating: on some servers a low rating deletes.
    suspend fun libraryAction(id: String, action: String = LIBRARY_ACTION_REMOVE): LibraryActionResult =
        get("libraryAction", mapOf("id" to id, "action" to action), "libraryAction", LibraryActionResult.serializer())

    // A version 3 fix with what it needs: the tags to write for
    // LIBRARY_ACTION_RETAG (by SongTag name; an empty one clears it), `like`
    // for LIBRARY_ACTION_JOIN_ALBUM, `preview` for LIBRARY_ACTION_COVER.
    suspend fun libraryAction(id: String, action: String, with: Map<String, String>): LibraryActionResult =
        get("libraryAction", with + mapOf("id" to id, "action" to action), "libraryAction", LibraryActionResult.serializer())

    // The tags a download of the song would get, beside what its file says
    // now. Writes nothing. Version 3.
    suspend fun lookUpTags(id: String): SongLookup =
        get("libraryAction", mapOf("id" to id, "action" to LIBRARY_ACTION_LOOKUP), "libraryAction", SongLookup.serializer())

    // The songs in the server's trash, newest first. Version 3.
    suspend fun libraryTrash(): LibraryTrash =
        get("getLibraryTrash", key = "libraryTrash", serializer = LibraryTrash.serializer(), default = LibraryTrash())

    // The FLACs the signed-in user asked for, and how each is going. Only for
    // servers that list octoLibraryActions at version 2.
    suspend fun upgrades(): List<Upgrade> =
        get("getUpgrades", key = "upgrades", serializer = ListSerializer(Upgrade.serializer()), default = emptyList())

    // The signed-in user's imports: lists and trickle. Only for servers that
    // list octoImports.
    suspend fun imports(): ImportOverview =
        get("getImports", key = "imports", serializer = ImportOverview.serializer(), default = ImportOverview())

    // One imported list and every song in it.
    suspend fun importList(id: String): ImportListDetail =
        get("getImport", mapOf("id" to id), "import", ImportListDetail.serializer())

    // One import action (see ImportActions). Keys name songs, and may repeat.
    suspend fun importAction(
        action: String,
        params: Map<String, String> = emptyMap(),
        keys: List<String> = emptyList(),
    ): ImportAnswer =
        getWith(
            "importAction",
            listOf("action" to action) + params.toList() + keys.map { "key" to it },
            "importAction",
            ImportAnswer.serializer(),
            ImportAnswer(),
        )

    // The services a person can bring their music from. Only for servers
    // that list octoImports at OCTO_IMPORTS_FILES.
    suspend fun importServices(): ImportServices =
        get("getImportServices", key = "importServices", serializer = ImportServices.serializer(), default = ImportServices())

    // Sends a list file (.txt, .csv, .json or .zip) for the server to read
    // into lists. The answer is the server's, ok or not, in its own words.
    suspend fun importFile(name: String, bytes: ByteArray): ImportAnswer {
        val part = bytes.toRequestBody(importFileType(name))
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", name, part)
            .build()
        val request = Request.Builder().url(url("importFile", mapOf("name" to name))).post(body).build()
        val answer = exchange(request, "importFile", patient)
        return withContext(Dispatchers.Default) { decode(answer, "importAction", ImportAnswer.serializer(), ImportAnswer()) }
    }

    // A list typed or pasted as text, one song a line, for the server to
    // read. Sent in a form body, since a list can be long.
    suspend fun importText(name: String, text: String): ImportAnswer {
        val answer = postForm("importText", listOf("name" to name, "text" to text), patient)
        return withContext(Dispatchers.Default) { decode(answer, "importAction", ImportAnswer.serializer(), ImportAnswer()) }
    }

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
        get("getAlbum", mapOf("id" to id), "album", AlbumWithSongs.serializer()).let { album ->
            album.animatedArt?.let { album.copy(animatedArt = it.resolvedOn(baseUrl).takeUnless(AnimatedArt::isEmpty)) } ?: album
        }

    // An album's animated art, on a server that lists octoAnimatedArt, with
    // whole addresses. A server that never asked Apple about the album asks
    // now, for up to 10 seconds, so this call may take that long.
    suspend fun animatedArt(albumId: String): AnimatedArtAnswer =
        try {
            val art = getOptional("getAnimatedArt", mapOf("id" to albumId), "animatedArt", AnimatedArt.serializer(), patient)
                ?.resolvedOn(baseUrl)
            if (art == null || art.isEmpty) AnimatedArtAnswer.None else AnimatedArtAnswer.Found(art)
        } catch (e: SubsonicException) {
            animatedArtAnswerFor(e)
        }

    // A whole address for one of an animated art clip's, which a server
    // passing the clips on itself gives relative to its own address.
    fun animatedArtUrl(address: String): HttpUrl? = resolveArtAddress(address, baseUrl)

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
        return MusicDirectory(wire.id, wire.name, folders, songs, wire.parent?.takeIf(String::isNotEmpty))
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
                folders += DirectoryRef(
                    id,
                    name.orEmpty(),
                    coverArt = child["coverArt"]?.jsonPrimitive?.contentOrNull,
                    songCount = child["songCount"]?.jsonPrimitive?.intOrNull,
                )
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

    // An artist's most played songs, ranked, each in the library or found
    // online. `artistId` may be the artist's id from a search, which names
    // the artist more surely than the name. Only for servers that list the
    // octoTopSongs extension.
    suspend fun artistTopSongs(artistName: String, artistId: String? = null, count: Int = 20): TopSongs =
        get(
            "getArtistTopSongs",
            buildMap {
                put("artist", artistName)
                put("count", "$count")
                artistId?.let { put("id", it) }
            },
            "topSongs",
            TopSongs.serializer(),
            TopSongs(),
        )

    // A song's transition profile, or `ready = false` while the server has
    // none. Only for servers that list the octoTransitions extension.
    suspend fun transitionProfile(id: String): TransitionProfileAnswer =
        get("getTransitionProfile", mapOf("id" to id), "transitionProfile", TransitionProfileAnswer.serializer(), TransitionProfileAnswer(id))

    // The songs played most right now, ranked like an artist's top songs.
    // `chart` picks another chart (CHART_NEW_SONGS, CHART_TRENDING, a genre's
    // number) on servers that list octoTopSongs version 2; without it, the
    // overall chart. `country` reads another country's chart on servers that
    // list version 3; without it, the listener's own. Only for servers that
    // list the octoTopSongs extension.
    suspend fun topChart(count: Int = 20, chart: String? = null, country: String? = null): TopSongs =
        get(
            "getTopChart",
            buildMap {
                put("count", "$count")
                chart?.let { put("chart", it) }
                country?.let { put("country", it) }
            },
            "topSongs",
            TopSongs.serializer(),
            TopSongs(),
        )

    // The charts the listener's country has, or `country`'s on servers that
    // list octoTopSongs version 3. Only for servers that list version 2.
    suspend fun charts(country: String? = null): ChartChoices =
        get("getCharts", buildMap { country?.let { put("country", it) } }, "charts", ChartChoices.serializer(), ChartChoices())

    // Saves the listener's own chart country (CHART_COUNTRY_AUTO follows the
    // server's) and answers the charts. An answer without them is an error,
    // so a save that did not happen is never taken for one that did. Only
    // for servers that list octoTopSongs version 3.
    suspend fun setChartCountry(country: String): ChartChoices =
        get("setChartCountry", mapOf("country" to country), "charts", ChartChoices.serializer())

    // Pins a country, so the listener gets its charts as playlists too, or
    // unpins it, and answers the charts. The server adds or removes just this
    // one, so another device's pins are kept. Only for servers that list
    // octoTopSongs version 3.
    suspend fun pinChartCountry(country: String, pinned: Boolean): ChartChoices =
        get("pinChartCountry", mapOf("country" to country, "pinned" to "$pinned"), "charts", ChartChoices.serializer())

    // On Octo this call also sets up per-user playlists and the radio
    // profile, so callers keep the result for the session.
    suspend fun playlists(): List<Playlist> =
        get("getPlaylists", key = "playlists", serializer = Playlists.serializer(), default = Playlists()).playlist

    suspend fun playlist(id: String): PlaylistWithSongs =
        get("getPlaylist", mapOf("id" to id), "playlist", PlaylistWithSongs.serializer())

    // The same playlist read through `file`, for one too long to hold: the
    // whole answer is first written to the file at network speed (see
    // drain), then its songs are handed to `page` a page at a time from the
    // file, each decoded as `playlist` decodes it. Answers the playlist
    // without its songs, only once the whole answer was read and the server
    // said it went well. `room` hears how many bytes are in the file every
    // DRAIN_ROOM_EVERY and may throw to stop. The caller owns the file and
    // deletes it.
    suspend fun playlistInto(
        id: String,
        file: File,
        room: (Long) -> Unit = {},
        pageSize: Int = LISTED_PAGE,
        page: suspend (List<Song>) -> Unit,
    ): PlaylistWithSongs {
        drain(url("getPlaylist", mapOf("id" to id)), "getPlaylist", file, room)
        return readDrained(file) { readListed(it, json, "playlist", "entry", PlaylistWithSongs.serializer(), pageSize, page = page) }
    }

    // An album read the same way, its songs a page at a time; its animated
    // art as `album` gives it.
    suspend fun albumInto(
        id: String,
        file: File,
        room: (Long) -> Unit = {},
        pageSize: Int = LISTED_PAGE,
        page: suspend (List<Song>) -> Unit,
    ): AlbumWithSongs {
        drainAlbum(id, file, room)
        return readAlbum(file, pageSize, page)
    }

    // An album's whole answer written into `file` before anything reads it
    // (see drain); read it with readAlbum, as often as needed, with no
    // second request.
    suspend fun drainAlbum(id: String, file: File, room: (Long) -> Unit = {}) {
        drain(url("getAlbum", mapOf("id" to id)), "getAlbum", file, room)
    }

    // A drained album: its songs handed to `page` a page at a time, each
    // decoded as `album` decodes it, and the album without them.
    suspend fun readAlbum(file: File, pageSize: Int = LISTED_PAGE, page: suspend (List<Song>) -> Unit): AlbumWithSongs {
        val album = readDrained(file) { readListed(it, json, "album", "song", AlbumWithSongs.serializer(), pageSize, page = page) }
        return album.animatedArt?.let { album.copy(animatedArt = it.resolvedOn(baseUrl).takeUnless(AnimatedArt::isEmpty)) } ?: album
    }

    // The play queue saved on the server, written whole into `file` before
    // anything reads it (see drain); read it with readPlayQueue, as often as
    // needed, with no second request.
    suspend fun drainPlayQueue(byIndex: Boolean, file: File, room: (Long) -> Unit = {}) {
        val endpoint = if (byIndex) "getPlayQueueByIndex" else "getPlayQueue"
        drain(url(endpoint), endpoint, file, room)
    }

    // A drained play queue: its songs handed to `page` a page at a time, each
    // decoded as playQueue decodes it, and its head without them. An answer
    // with no queue is an empty one, as playQueue has it.
    suspend fun readPlayQueue(file: File, byIndex: Boolean, pageSize: Int = LISTED_PAGE, page: suspend (List<Song>) -> Unit): QueueHead =
        readDrained(file) { source ->
            if (byIndex) {
                readListed(source, json, "playQueueByIndex", "entry", PlayQueueByIndex.serializer(), pageSize, PlayQueueByIndex(), page)
                    .let { QueueHead(null, it.currentIndex, it.position, it.changed, it.changedBy) }
            } else {
                readListed(source, json, "playQueue", "entry", PlayQueue.serializer(), pageSize, PlayQueue(), page)
                    .let { QueueHead(it.current, null, it.position, it.changed, it.changedBy) }
            }
        }

    // A folder's answer written whole into `file` (see drain): the top's
    // (getIndexes) when `id` is null, otherwise that folder's.
    suspend fun drainFolder(id: String?, file: File, room: (Long) -> Unit = {}) {
        if (id == null) drain(url("getIndexes", emptyMap<String, String>().inFolder(musicFolderId)), "getIndexes", file, room)
        else drain(url("getMusicDirectory", mapOf("id" to id)), "getMusicDirectory", file, room)
    }

    // A drained folder answer: its songs handed to `song` one at a time, in
    // the order sent, each decoded as musicDirectory decodes it, and its
    // folders answered in the order indexes or musicDirectory lists them.
    suspend fun readFolder(file: File, top: Boolean, song: suspend (Song) -> Unit): List<DirectoryRef> =
        readDrained(file) { readFolderAnswer(it, json, top, song) }

    // Writes a call's whole answer into `file` before anything reads it, so
    // the server is never left waiting on a reader that writes as it goes.
    // A cancel of the caller stops the call at once, even in the middle of
    // a read, and the copy checks for one after every DRAIN_BUFFER bytes.
    // Every DRAIN_ROOM_EVERY bytes `room` hears how many are written, and
    // may throw to stop. An answer that is not a success is NotSubsonic, a
    // connection that fails Unreachable, as `fetch` has them.
    private suspend fun drain(url: HttpUrl, endpoint: String, file: File, room: (Long) -> Unit) = coroutineScope {
        val call = http.newCall(Request.Builder().url(url).build())
        val stop = launch {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            val response = try {
                call.await()
            } catch (e: IOException) {
                throw SubsonicException.Unreachable(e)
            }
            withContext(Dispatchers.IO) {
                response.use {
                    it.checkStreamed(endpoint)
                    try {
                        copyInto(it.body.source(), file, room)
                    } catch (e: IOException) {
                        ensureActive()
                        throw SubsonicException.Unreachable(e)
                    }
                }
            }
        } finally {
            stop.cancel()
        }
    }

    private suspend fun copyInto(source: BufferedSource, file: File, room: (Long) -> Unit) {
        file.parentFile?.mkdirs()
        file.sink().buffer().use { out ->
            val buffer = Buffer()
            var written = 0L
            var next = DRAIN_ROOM_EVERY
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = source.read(buffer, DRAIN_BUFFER)
                if (read == -1L) break
                out.write(buffer, read)
                written += read
                if (written >= next) {
                    room(written)
                    next += DRAIN_ROOM_EVERY
                }
            }
        }
    }

    // Reads a drained answer back from its file: an answer that is not
    // JSON is NotSubsonic, as `decode` has it, and a file cut short
    // Unreachable, as a cut connection is.
    private suspend fun <T> readDrained(file: File, read: suspend (BufferedSource) -> T): T = withContext(Dispatchers.IO) {
        try {
            file.source().buffer().use { read(it) }
        } catch (e: JsonDataException) {
            throw SubsonicException.NotSubsonic("The server did not answer like a Subsonic server")
        } catch (e: JsonEncodingException) {
            throw SubsonicException.NotSubsonic("The server did not answer like a Subsonic server")
        } catch (e: IOException) {
            throw SubsonicException.Unreachable(e)
        }
    }

    // Where a playlist's songs sit on the server, in its order, for a
    // playlist file. A server that keeps its paths to itself sends none.
    suspend fun playlistPaths(id: String): List<SongPath> =
        get("getPlaylist", mapOf("id" to id), "playlist", PlaylistPaths.serializer()).entry

    // Songs like this one, for a radio that starts from it. On Octo these
    // mix library songs with songs found online. octoMix=client asks Octo
    // for its own order of fit, since the app mixes the radio itself;
    // other servers ignore it.
    suspend fun similarSongs(id: String, count: Int = 50): List<Song> =
        get(
            "getSimilarSongs2",
            mapOf("id" to id, "count" to "$count", "octoMix" to "client"),
            "similarSongs2",
            SongList.serializer(),
            SongList(),
        ).song

    // A station's next songs on Octo (octoStations): up to `count`, none of
    // the ones in `after` (the station's songs already queued).
    suspend fun stationSongs(id: String, count: Int, after: List<String>): List<Song> =
        getWith(
            "getStationSongs",
            listOf("id" to id, "count" to "$count") + after.map { "after" to it },
            "stationSongs",
            StationSongs.serializer(),
            StationSongs(),
        ).entry

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

    // The same stars, handed to `page` a page at a time as the answer
    // arrives (see readStarred), so a big library's are never held whole.
    // Returns only once the answer was read to its end and the server said
    // it went well; a cut-off answer throws Unreachable.
    suspend fun starredInto(page: suspend (List<StarredItem>) -> Unit) {
        val request = Request.Builder().url(url("getStarred2")).build()
        val response = try {
            http.newCall(request).await()
        } catch (e: IOException) {
            throw SubsonicException.Unreachable(e)
        }
        response.use {
            withContext(Dispatchers.IO) { it.checkStreamed("getStarred2") }
            try {
                withContext(Dispatchers.IO) { readStarred(it.body.source(), page) }
            } catch (e: JsonDataException) {
                throw SubsonicException.NotSubsonic("The server did not answer like a Subsonic server")
            } catch (e: JsonEncodingException) {
                throw SubsonicException.NotSubsonic("The server did not answer like a Subsonic server")
            } catch (e: IOException) {
                throw SubsonicException.Unreachable(e)
            }
        }
    }

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

    // Every copy of a song's lyrics the server's sources hold, and what the
    // song is set to. Only for servers that list the octoLyrics extension.
    // A title or artist given searches for those instead of the song's tags.
    suspend fun lyricsCandidates(id: String, title: String? = null, artist: String? = null): LyricsCandidates =
        get("getLyricsCandidates", lyricsCandidatesParams(id, title, artist), "lyricsCandidates", LyricsCandidates.serializer())

    // Sets a song's lyrics on the server to one copy from lyricsCandidates,
    // to LYRICS_NONE to hide them, or to LYRICS_AUTO to let the server find
    // them again. It holds for every app and user on the server. Answers
    // with what the song is set to now.
    suspend fun setLyricsChoice(id: String, candidate: String): String =
        get(
            "setLyricsChoice",
            mapOf("id" to id, "candidate" to candidate),
            "lyricsChoice",
            LyricsChoiceAnswer.serializer(),
            LyricsChoiceAnswer(id, candidate),
        ).choice

    // An outside song's versions: the video it streams from and the other
    // videos it can, which the server lists from YouTube (up to ten
    // seconds). `id` is the song's id as it streams. Only for servers that
    // list STREAM_VERSIONS_EXTENSION.
    suspend fun streamVersions(id: String): StreamVersions =
        get("getStreamVersions", mapOf("id" to id), "streamVersions", StreamVersions.serializer())

    // Makes `video` (one of streamVersions' rows) the one the outside song
    // streams from for everyone on the server, from each play's next start,
    // or with STREAM_VERSION_AUTO goes back to Octo's own pick. Answers the
    // song's versions as they are now, `ids` every id the song has. A
    // refusal is the server's error in its own words (SubsonicException).
    suspend fun setStreamVersion(id: String, video: String): StreamVersions =
        get("setStreamVersion", mapOf("id" to id, "video" to video), "streamVersions", StreamVersions.serializer())

    // The outside songs whose chosen version changed after a cursor, as the
    // last answer gave it (`epoch`, `since`); both null asks for every
    // change the server keeps. Only for servers that list
    // STREAM_VERSION_CHANGES_EXTENSION.
    suspend fun streamVersionChanges(epoch: String?, since: Long?): StreamVersionChanges =
        get(
            "getStreamVersionChanges",
            buildMap {
                epoch?.let { put("epoch", it) }
                since?.let { put("since", "$it") }
            },
            "streamVersionChanges",
            StreamVersionChanges.serializer(),
        )

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
        val (first, rest) = createPaged(name, PagedValues.of(songIds), formPost)
        val created = decode(sendCall(first, formPost), null, CreatedPlaylist.serializer(), null).playlist
        if (rest.any()) {
            val id = created?.id ?: throw SubsonicException.Server(0, "The server did not say the new playlist's id")
            addSongsPaged(id, rest).forEach { sendCall(it, formPost) }
        }
        return created
    }

    // The same for songs too many to hold, sent from `songs` a page at a
    // time. The answer is read for whether it went well and for the new
    // playlist without its songs, which are skipped as they arrive.
    // `skipped` hears the ids a server listing octoPlaylistSongs says it
    // left out (octoSkipped), once per call that skipped any.
    suspend fun createPlaylist(
        name: String,
        songs: PagedValues,
        formPost: Boolean = false,
        skipped: (List<String>) -> Unit = {},
    ): PlaylistWithSongs? {
        val (first, rest) = createPaged(name, songs, formPost)
        val created = sendForHead(first, formPost, head = true, skipped = skipped)
        if (rest.any()) {
            val id = created?.id ?: throw SubsonicException.Server(0, "The server did not say the new playlist's id")
            addSongsPaged(id, rest).forEach { sendForHead(it, formPost, skipped = skipped) }
        }
        return created
    }

    // Makes a playlist's songs exactly these, in this order: createPlaylist
    // with the playlist's id replaces its whole list. `ifStamp` (the changed
    // time the server sent for it) makes Octo refuse it when the playlist
    // changed since (SubsonicException.Server with `conflict`); `ownIds`
    // says the ids are the server's own; `skipped` hears the ids it left out.
    suspend fun replacePlaylistSongs(
        id: String,
        songIds: List<String>,
        formPost: Boolean = false,
        ifStamp: String? = null,
        ownIds: Boolean = false,
        skipped: (List<String>) -> Unit = {},
    ) {
        replaceSongsPaged(id, PagedValues.of(songIds), formPost, ifStamp = ifStamp, ownIds = ownIds).forEach { call ->
            skippedInBody(sendCall(call, formPost)).takeIf { it.isNotEmpty() }?.let(skipped)
        }
    }

    // The same for songs too many to hold, sent from `songs` a page at a time.
    suspend fun replacePlaylistSongs(
        id: String,
        songs: PagedValues,
        formPost: Boolean = false,
        ifStamp: String? = null,
        ownIds: Boolean = false,
        skipped: (List<String>) -> Unit = {},
    ) {
        replaceSongsPaged(id, songs, formPost, ifStamp = ifStamp, ownIds = ownIds).forEach { sendForHead(it, formPost, skipped = skipped) }
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
        songsToRemove: List<Pair<String, Int>> = emptyList(),
        formPost: Boolean = false,
        skipped: (List<String>) -> Unit = {},
    ) {
        updatePaged(
            id, name, comment, public,
            PagedValues.of(songIdsToAdd),
            PagedValues.of(songIndexesToRemove.map { "$it" }),
            PagedValues.of(songsToRemove.map { (song, copy) -> "$song@$copy" }),
            formPost,
        ).forEach { call -> skippedInBody(sendCall(call, formPost)).takeIf { it.isNotEmpty() }?.let(skipped) }
    }

    // The same for songs too many to hold: songs to add, places to take out
    // and songs to take out by song ("id@copy"), each sent from its values a
    // page at a time.
    suspend fun updatePlaylist(
        id: String,
        name: String? = null,
        comment: String? = null,
        public: Boolean? = null,
        songIdsToAdd: PagedValues,
        songIndexesToRemove: PagedValues = NO_VALUES,
        songsToRemove: PagedValues = NO_VALUES,
        formPost: Boolean = false,
        skipped: (List<String>) -> Unit = {},
    ) {
        updatePaged(id, name, comment, public, songIdsToAdd, songIndexesToRemove, songsToRemove, formPost).forEach {
            sendForHead(it, formPost, skipped = skipped)
        }
    }

    suspend fun deletePlaylist(id: String) = send("deletePlaylist", listOf("id" to id))

    // Runs one playlist call, with its params in the address or in a form
    // body, and hands back the answer once it is known to be ok.
    private suspend fun sendCall(call: PagedCall, formPost: Boolean): String = exchange(requestOf(call, formPost), call.endpoint)

    // Runs one playlist call and reads its answer as it arrives: whether it
    // went well, as `exchange` checks it, and with `head` the playlist it
    // names without its songs (null when it names none), as createPlaylist
    // reads it.
    private suspend fun sendForHead(
        call: PagedCall,
        formPost: Boolean,
        head: Boolean = false,
        skipped: (List<String>) -> Unit = {},
    ): PlaylistWithSongs? {
        val root = try {
            val response = http.newCall(requestOf(call, formPost)).await()
            withContext(Dispatchers.IO) {
                response.use {
                    it.checkStreamed(call.endpoint)
                    try {
                        readAnswerHead(it.body.source(), json)
                    } catch (e: JsonDataException) {
                        null
                    } catch (e: JsonEncodingException) {
                        null
                    }
                }
            }
        } catch (e: IOException) {
            throw SubsonicException.Unreachable(e)
        }
        return withContext(Dispatchers.Default) {
            decodeRoot(root, null, ServerInfo.serializer(), null)
            skippedIn(root).takeIf { it.isNotEmpty() }?.let(skipped)
            if (head) decodeRoot(root, null, CreatedPlaylist.serializer(), null).playlist else null
        }
    }

    // A playlist call's request: its params in the address, or in a form
    // body streamed from its values, with only the sign-in in the address.
    private fun requestOf(call: PagedCall, formPost: Boolean): Request {
        if (formPost) return Request.Builder().url(url(call.endpoint)).post(StreamedForm(call)).build()
        val address = url(call.endpoint).newBuilder().apply { call.pairs().forEach { (key, value) -> addQueryParameter(key, value) } }.build()
        return Request.Builder().url(address).build()
    }

    // Sends the params in a form body rather than the address, and hands
    // back the answer once it is known to be ok. Only the sign-in is in the
    // address.
    private suspend fun postForm(endpoint: String, params: List<Pair<String, String>>, http: OkHttpClient = this.http): String {
        val form = FormBody.Builder().apply { params.forEach { (key, value) -> add(key, value) } }.build()
        return exchange(Request.Builder().url(url(endpoint)).post(form).build(), endpoint, http)
    }

    private suspend fun exchange(request: Request, endpoint: String, http: OkHttpClient = this.http): String {
        val body = try {
            val response = http.newCall(request).await()
            withContext(Dispatchers.IO) { response.use { it.jsonBody(endpoint) } }
        } catch (e: IOException) {
            throw SubsonicException.Unreachable(e)
        }
        withContext(Dispatchers.Default) { decode(body, null, ServerInfo.serializer(), null) }
        return body
    }

    // Gives a user a new password: the signed-in user's own, or anyone's
    // for an admin. The password goes in a form body, never in the
    // address, which proxies and servers write to their logs. When it is
    // the signed-in user's own, this client signs in with it from then on.
    // A family member's own password, which the server checks against the
    // current one. Every app then needs the new one; this client switches
    // to it at once.
    suspend fun changeFamilyPassword(current: String, next: String) {
        postForm("changeFamilyPassword", listOf("current" to current, "next" to next))
        val now = credentials
        if (now.mode != AuthMode.ApiKey) credentials = Credentials(now.username, next, now.mode)
    }

    // What this client signs in with, for sealing into a hand-over to the
    // same person's other device. Never logged or sent anywhere else.
    fun handOverSecret(): String = credentials.handOverSecret()

    suspend fun changePassword(username: String, newPassword: String) {
        postForm("changePassword", listOf("username" to username, "password" to newPassword))
        val now = credentials
        if (username == now.username && now.mode != AuthMode.ApiKey) credentials = Credentials(username, newPassword, now.mode)
    }

    // The family page's calls on this server, through this client's
    // connections and headers, each signed as every Subsonic call is.
    fun familyWeb(): FamilyWeb = FamilyWeb(baseUrl, http, ::signed)

    // The same server, headers and route signed in with another secret, for
    // checking a password without touching this client.
    fun withSecret(secret: String): SubsonicClient =
        SubsonicClient(primaryUrl, Credentials(username, secret, authMode), http, clientName, musicFolderId, route)

    // A call that only answers ok or an error. The params may repeat a name.
    internal suspend fun send(endpoint: String, params: List<Pair<String, String>>) {
        val url = url(endpoint).newBuilder().apply { params.forEach { (key, value) -> addQueryParameter(key, value) } }.build()
        val body = fetch(url, endpoint)
        withContext(Dispatchers.Default) { decode(body, null, ServerInfo.serializer(), null) }
    }

    // A call whose answer may leave its key out, or null: null then.
    internal suspend fun <T> getOptional(
        endpoint: String,
        params: Map<String, String>,
        key: String,
        serializer: KSerializer<T>,
        http: OkHttpClient = this.http,
    ): T? {
        val body = fetch(url(endpoint, params), endpoint, http)
        return withContext(Dispatchers.Default) {
            val root = decode(body, null, kotlinx.serialization.json.JsonObject.serializer(), null)
            val payload = root[key]
            if (payload == null || payload is kotlinx.serialization.json.JsonNull) null else json.decodeFromJsonElement(serializer, payload)
        }
    }

    // Params in a form body rather than the address; only ok or an error.
    internal suspend fun sendForm(endpoint: String, params: List<Pair<String, String>>) {
        postForm(endpoint, params)
    }

    internal suspend fun <T> get(
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

    // The answer's body once it is known to be JSON or empty. Anything else
    // (an error status, a sign-in page, a page that is not the server's) is
    // NotSubsonic, naming the gateway in front of the server when one
    // answered.
    private suspend fun fetch(url: HttpUrl, endpoint: String, http: OkHttpClient = this.http): String {
        val request = Request.Builder().url(url).build()
        return try {
            val response = http.newCall(request).await()
            withContext(Dispatchers.IO) { response.use { it.jsonBody(endpoint) } }
        } catch (e: IOException) {
            throw SubsonicException.Unreachable(e)
        }
    }

    // An answer of any other shape throws only SubsonicException: no
    // "subsonic-response" object is NotSubsonic, and a payload that does not
    // read as asked is Server, so callers that catch SubsonicException see
    // every way an answer can be wrong.
    internal fun <T> decode(body: String, key: String?, serializer: KSerializer<T>, default: T?): T =
        decodeRoot(runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()?.get("subsonic-response") as? JsonObject, key, serializer, default)

    // The same, for an answer's own object already read (null when the body
    // held none).
    internal fun <T> decodeRoot(answer: JsonObject?, key: String?, serializer: KSerializer<T>, default: T?): T {
        val root = answer ?: throw SubsonicException.NotSubsonic(NOT_LIKE_SUBSONIC)
        if ((root["status"] as? JsonPrimitive)?.contentOrNull != "ok") {
            val error = root["error"] as? JsonObject
            val code = (error?.get("code") as? JsonPrimitive)?.intOrNull ?: 0
            val message = (error?.get("message") as? JsonPrimitive)?.contentOrNull ?: "Request failed"
            val conflict = (error?.get("octoConflict") as? JsonPrimitive)?.booleanOrNull == true
            throw subsonicError(code, message, conflict)
        }
        val payload = if (key == null) root else root[key]
        return when {
            payload != null -> try {
                json.decodeFromJsonElement(serializer, payload)
            } catch (e: IllegalArgumentException) {
                // SerializationException is one too.
                throw SubsonicException.Server(0, if (key == null) "The answer could not be read" else "The answer's \"$key\" could not be read")
            }
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

    // Where a postal code (`code`) or a city (`name`, with `state` when
    // known) is in `country` (ISO alpha-2), or, with neither, the nearest
    // place to a position (`latitude`, `longitude`), on a server that lists
    // octoPlaces; null when the server does not know the place.
    suspend fun place(
        code: String?,
        name: String?,
        state: String?,
        country: String,
        latitude: Double? = null,
        longitude: Double? = null,
    ): Place? =
        try {
            getOptional("getPlace", placeParams(code, name, state, country, latitude, longitude), "place", Place.serializer())
        } catch (e: SubsonicException.NotFound) {
            null
        }

    // Like get, for params that may repeat a name.
    // With no `default`, an answer missing `key` is an error.
    internal suspend fun <T> getWith(
        endpoint: String,
        params: List<Pair<String, String>>,
        key: String,
        serializer: KSerializer<T>,
        default: T? = null,
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
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
        override fun onResponse(call: Call, response: Response) {
            cont.resume(response) { _, value, _ -> value.close() }
        }
    })
}

// The type a list file is sent as, by its name's ending.
internal fun importFileType(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
    "csv" -> "text/csv"
    "json" -> "application/json"
    "zip" -> "application/zip"
    "txt" -> "text/plain"
    else -> "application/octet-stream"
}.toMediaType()
