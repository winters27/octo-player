package app.winters.octo.discovery

import androidx.room.withTransaction
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.CatalogMerge
import app.winters.octo.catalog.FreeSpace
import app.winters.octo.catalog.SourceTrackEntity
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.catalog.renameLikeASave
import app.winters.octo.catalog.searchKey
import app.winters.octo.catalog.sortKey
import app.winters.octo.data.Drains
import app.winters.octo.discovery.today.todayResolve
import app.winters.octo.folders.ServerFolders
import app.winters.octo.folders.ServerName
import app.winters.octo.subsonic.Credentials
import app.winters.octo.subsonic.Song
import app.winters.octo.subsonic.SubsonicClient
import app.winters.octo.testing.testMerge
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockResponseBody
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch

// The source the fake server's library copies come from.
internal const val SERVER_SOURCE = "server:fake"

// A Subsonic server holding lists for the server list tests: playlists and
// stations (getPlaylist), albums (getAlbum), a play queue in both forms, and
// a tree of folders (getIndexes, getMusicDirectory). Each answer is written
// whole, as a server writes it; requests are noted by endpoint.
internal class ListServer(val songs: Map<String, Song> = emptyMap()) {
    private val json = Json { encodeDefaults = false }
    val lists = LinkedHashMap<String, List<String>>()
    val albums = LinkedHashMap<String, Pair<String, List<String>>>()
    var queue: List<String> = emptyList()
    var current: String? = null
    var currentIndex: Int? = null
    var position: Long = 0
    var changed: String? = "2026-11-06T10:00:00Z"

    // A folder's folders and own songs, by id; "" is the top.
    val folders = LinkedHashMap<String, Pair<List<String>, List<String>>>()
    val calls: MutableList<String> = Collections.synchronizedList(ArrayList())

    // Lists answered with code 70, as a list made anew since it was shown.
    val gone = HashSet<String>()

    // Held before an answer of this endpoint is sent, while set.
    @Volatile var hold: Pair<String, CountDownLatch>? = null

    // A list's whole answer written as this says, when set (a test's own body).
    @Volatile var listBody: ((String) -> MockResponseBody)? = null

    val server = MockWebServer()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = answer(request)
        }
        server.start()
    }

    fun client(): SubsonicClient = SubsonicClient(server.url("/"), Credentials("winters", "secret"), OkHttpClient.Builder().retryOnConnectionFailure(false).build())

    fun close() = server.close()

    fun songJson(id: String): String = json.encodeToString(Song.serializer(), songs[id] ?: Song(id, title = "Song $id"))

    private fun quote(text: String) = Json.encodeToString(String.serializer(), text)

    private fun ok(body: String) = MockResponse.Builder().body("""{"subsonic-response":{"status":"ok","version":"1.16.1"$body}}""").build()

    private fun answer(request: RecordedRequest): MockResponse {
        val endpoint = request.url.pathSegments.last().removeSuffix(".view")
        calls += endpoint
        hold?.takeIf { it.first == endpoint }?.second?.await()
        val id = request.url.queryParameter("id")
        return when (endpoint) {
            "getPlaylist" -> {
                if (id in gone || id !in lists) {
                    return MockResponse.Builder().body("""{"subsonic-response":{"status":"failed","error":{"code":70,"message":"Playlist not found"}}}""").build()
                }
                val entries = lists.getValue(id!!)
                val body = ""","playlist":{"id":${quote(id)},"name":"List $id","songCount":${entries.size},"coverArt":"c$id","entry":[${entries.joinToString(",") { songJson(it) }}]}"""
                listBody?.let { return MockResponse.Builder().body(it("""{"subsonic-response":{"status":"ok","version":"1.16.1"$body}}""")).build() }
                ok(body)
            }
            "getAlbum" -> {
                val (name, entries) = albums[id] ?: return MockResponse.Builder()
                    .body("""{"subsonic-response":{"status":"failed","error":{"code":70,"message":"Album not found"}}}""").build()
                ok(""","album":{"id":${quote(id!!)},"name":${quote(name)},"artist":"Artist","artistId":"ar1","year":2001,"songCount":${entries.size},"song":[${entries.joinToString(",") { songJson(it) }}]}""")
            }
            "getPlayQueue" -> if (queue.isEmpty()) ok("") else {
                ok(""","playQueue":{"entry":[${queue.joinToString(",") { songJson(it) }}]${current?.let { ""","current":${quote(it)}""" }.orEmpty()},"position":$position,"changed":"$changed","changedBy":"Desk"}""")
            }
            "getPlayQueueByIndex" -> if (queue.isEmpty()) ok("") else {
                ok(""","playQueueByIndex":{"entry":[${queue.joinToString(",") { songJson(it) }}]${currentIndex?.let { ""","currentIndex":$it""" }.orEmpty()},"position":$position,"changed":"$changed","changedBy":"Desk"}""")
            }
            "getIndexes" -> {
                val (subs, own) = folders[""] ?: (emptyList<String>() to emptyList())
                val children = subs.map { """{"id":${quote(it)},"isDir":true,"title":"F $it"}""" } + own.map { songJson(it) }
                ok(""","indexes":{"index":[],"child":[${children.joinToString(",")}]}""")
            }
            "getMusicDirectory" -> {
                val (subs, own) = folders[id] ?: return MockResponse.Builder()
                    .body("""{"subsonic-response":{"status":"failed","error":{"code":70,"message":"Folder not found"}}}""").build()
                val children = subs.map { """{"id":${quote(it)},"isDir":true,"title":"F $it"}""" } + own.map { songJson(it) }
                ok(""","directory":{"id":${quote(id!!)},"name":"F $id","child":[${children.joinToString(",")}]}""")
            }
            else -> ok("")
        }
    }
}

// A library song and its copy from the fake server, by id, title and artist.
internal fun libraryTrack(id: String, title: String, artist: String = "Artist", durationMs: Long = 200_000, relinkKey: String = "rk-$id") = TrackEntity(
    id = id, sourceId = SERVER_SOURCE, nativeId = id, title = title, searchKey = searchKey(title), sortKey = sortKey(title),
    artist = artist, artistId = "ar", album = "Album", albumId = "al", trackNo = null, discNo = null, year = null,
    durationMs = durationMs, addedAt = 0, mimeType = null, sizeBytes = null, artwork = null, uri = null, relinkKey = relinkKey,
)

// Puts library songs in, each with its copy on the fake server under
// `serverId(track)` (none when it answers null).
internal fun CatalogDatabase.putLibrary(tracks: List<TrackEntity>, serverId: (TrackEntity) -> String? = { it.id }) = runBlocking {
    tracks.chunked(500).forEach { dao().insertTracks(it) }
    val copies = tracks.mapNotNull { t ->
        serverId(t)?.let { native ->
            SourceTrackEntity(
                id = "copy-${t.id}", sourceId = SERVER_SOURCE, nativeId = native, title = t.title, searchKey = t.searchKey, sortKey = t.sortKey,
                artist = t.artist, artistId = t.artistId, album = t.album, albumId = t.albumId, trackNo = null, discNo = null, year = null,
                durationMs = t.durationMs, addedAt = 0, mimeType = null, sizeBytes = null, artwork = null, uri = null, albumOrder = 0,
                relinkKey = t.relinkKey, genre = "", mergedId = t.id,
            )
        }
    }
    copies.chunked(500).forEach { sourceDao().putTracks(it) }
}

// A save as a read sees one: a library song takes a new id, every row
// naming it follows (a group's rename), and the swap count moves, in one
// transaction.
internal suspend fun CatalogDatabase.renameSong(from: String, to: String) = withTransaction {
    val sql = openHelper.writableDatabase
    sql.execSQL("UPDATE track SET id = ? WHERE id = ?", arrayOf<Any?>(to, from))
    sql.execSQL("UPDATE source_track SET mergedId = ? WHERE mergedId = ?", arrayOf<Any?>(to, from))
    renameLikeASave(from, to)
}

// Server reads over a test library and the fake server, with small pages.
internal fun CatalogDatabase.serverReads(
    server: ListServer?,
    page: Int = 50,
    merge: CatalogMerge = testMerge(SERVER_SOURCE),
    space: FreeSpace = FreeSpace { Long.MAX_VALUE },
    drains: File = Files.createTempDirectory("drain").toFile(),
    owners: WorkOwners = WorkOwners(),
    indexedQueue: Boolean = false,
    // The server's folder levels, for a play of a folder's own songs.
    levels: ServerFolders? = null,
): ServerReads {
    val client = server?.client()
    return ServerReads(
        this, Drains(drains), space, owners, merge,
        server = { client?.let { ServerSide(it, SERVER_SOURCE, indexedQueue) } },
        page = page,
        levels = { levels ?: error("no server folders") },
    )
}

// The server's folder levels over a test library and the fake server: each
// page of songs resolved in place as today's resolve makes each song alone,
// its finds kept as the app keeps them.
internal fun CatalogDatabase.serverFolders(
    server: ListServer,
    owners: WorkOwners = WorkOwners(),
    page: Int = 50,
    drains: File = Files.createTempDirectory("drain").toFile(),
): ServerFolders {
    val client = server.client()
    val db = this
    return ServerFolders(
        flowOf(ServerName.None), db, Drains(drains), FreeSpace { Long.MAX_VALUE }, owners, { client },
        resolve = { songs ->
            songs.map { song ->
                todayResolve(db, SERVER_SOURCE, listOf(song)).firstOrNull()?.also { track ->
                    if (track.id.startsWith("find:")) db.onlineDao().keep(listOf(song.toFind(SERVER_SOURCE, 0)))
                }
            }
        },
        page = page,
    )
}

// The rows a read shows, as ids, in order.
internal suspend fun ServerReads.shownIds(owner: String): List<String> {
    val loader = loader(owner)
    val out = ArrayList<String>()
    var after: app.winters.octo.ui.paging.RowKey? = null
    while (true) {
        val rows = loader.after(after, 37)
        out += rows.map { it.row.id }
        if (rows.size < 37) return out
        after = rows.last().key
    }
}
