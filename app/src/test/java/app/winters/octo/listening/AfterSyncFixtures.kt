package app.winters.octo.listening

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.searchKey
import app.winters.octo.catalog.titleLookup
import app.winters.octo.data.Session
import app.winters.octo.subsonic.Credentials
import app.winters.octo.subsonic.SubsonicClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import java.util.Collections

// A preferences file in memory.
internal class MemoryPrefs(start: Preferences = emptyPreferences()) : DataStore<Preferences> {
    private val state = MutableStateFlow(start)
    private val lock = Mutex()
    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
        lock.withLock { transform(state.value).also { state.value = it } }
}

// Rebuilds that are only a lock, which a test takes to stand in for one.
internal class TestRebuilds : Rebuilds {
    private val lock = Mutex()

    override suspend fun <T> between(block: suspend () -> T): T = lock.withLock { block() }
}

internal fun ok(key: String? = null, body: String = "{}") =
    if (key == null) """{"subsonic-response":{"status":"ok"}}""" else """{"subsonic-response":{"status":"ok","$key":$body}}"""

// A getStarred2 answer listing these server ids, each starred at a fixed time.
internal fun starredAnswer(songs: List<String> = emptyList(), albums: List<String> = emptyList(), artists: List<String> = emptyList()): String {
    fun list(ids: List<String>) = ids.joinToString(",") { """{"id":"$it","starred":"2026-01-02T03:04:05Z"}""" }
    return ok("starred2", """{"artist":[${list(artists)}],"album":[${list(albums)}],"song":[${list(songs)}]}""")
}

// A server that answers star, unstar, setRating and scrobble with ok (or a
// refused rating), getStarred2 with `starred`, and notes each call as
// "<endpoint> <ids...> [rating=N]".
internal class AfterSyncServer : AutoCloseable {
    val server = MockWebServer().apply { start() }
    val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())
    @Volatile var starred: String = starredAnswer()
    @Volatile var refuseRatings = false

    // Runs while the server answers a setRating, as a tap made meanwhile.
    @Volatile var whileRating: (() -> Unit)? = null

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.url
                val endpoint = url.pathSegments.last()
                val ids = listOf("id", "albumId", "artistId").flatMap { url.queryParameterValues(it) }.filterNotNull()
                asked += (listOf(endpoint) + ids + listOfNotNull(url.queryParameter("rating")?.let { "rating=$it" })).joinToString(" ")
                val body = when (endpoint) {
                    "getStarred2" -> starred
                    "setRating" -> {
                        whileRating?.invoke()
                        if (refuseRatings) """{"subsonic-response":{"status":"failed","error":{"code":0,"message":"Not now"}}}""" else ok()
                    }
                    else -> ok()
                }
                return MockResponse.Builder().body(body).build()
            }
        }
    }

    val session: Session = Session(
        SubsonicClient(server.url("/"), Credentials("winters", "secret"), OkHttpClient()),
        serverType = null,
        serverVersion = null,
        isOcto = false,
        adminReachable = false,
        extensions = emptySet(),
        id = "home",
    )

    fun calls(endpoint: String): List<String> = asked.filter { it == endpoint || it.startsWith("$endpoint ") }

    override fun close() = server.close()
}

internal fun CatalogDatabase.exec(sql: String, vararg args: Any?) = openHelper.writableDatabase.execSQL(sql, args)

// A library song.
internal fun CatalogDatabase.song(
    id: String,
    title: String = "Song $id",
    artist: String = "Artist",
    albumId: String = "lib-album",
    artistId: String = "lib-artist",
    durationMs: Long = 200_000,
    relinkKey: String = "rk-$id",
) = exec(
    """
    INSERT INTO track (id, sourceId, nativeId, title, searchKey, sortKey, artist, artistId, album, albumId, durationMs, addedAt, relinkKey,
        lookupKey, looseKey)
    VALUES (?, 'device', ?, ?, ?, ?, ?, ?, 'Album', ?, ?, 0, ?, ?, ?)
    """,
    id, id, title, searchKey(title), searchKey(title), artist, artistId, albumId, durationMs, relinkKey,
    titleLookup(title, artist).lookupKey, titleLookup(title, artist).looseKey,
)

// A source's copy of a library song (`mergedId`, empty for none).
internal fun CatalogDatabase.copy(
    sourceId: String,
    nativeId: String,
    mergedId: String,
    rating: Int? = null,
    lastPlayedAt: Long? = null,
    albumId: String = "$sourceId:album",
    artistId: String = "$sourceId:artist",
) = exec(
    """
    INSERT INTO source_track (id, sourceId, nativeId, title, searchKey, sortKey, artist, artistId, album, albumId,
        durationMs, addedAt, albumOrder, relinkKey, genre, mergedId, rating, lastPlayedAt)
    VALUES (?, ?, ?, 'Song', 'song', 'song', 'Artist', ?, 'Album', ?, 200000, 0, 0, '', '', ?, ?, ?)
    """,
    "$sourceId:$nativeId", sourceId, nativeId, artistId, albumId, mergedId, rating, lastPlayedAt,
)

internal fun CatalogDatabase.sourceAlbum(id: String, sourceId: String, artistId: String = "$sourceId:artist") = exec(
    """
    INSERT INTO source_album (id, sourceId, nativeId, title, searchKey, sortKey, artist, artistId, songCount, durationMs, addedAt)
    VALUES (?, ?, ?, 'Album', 'album', 'album', 'Artist', ?, 1, 0, 0)
    """,
    id, sourceId, id.removePrefix("$sourceId:"), artistId,
)

internal fun CatalogDatabase.sourceArtist(id: String, sourceId: String) = exec(
    "INSERT INTO source_artist (id, sourceId, name, searchKey, sortKey, albumCount, songCount) VALUES (?, ?, 'Artist', 'artist', 'artist', 1, 1)",
    id, sourceId,
)
