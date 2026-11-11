package app.winters.octo.ui.album

import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.server.serverSourceId
import app.winters.octo.subsonic.Credentials
import app.winters.octo.subsonic.Song
import app.winters.octo.subsonic.SubsonicClient
import app.winters.octo.ui.paging.ListLoader
import app.winters.octo.ui.paging.RowKey
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient

// Song `i` of a library album, in album order, on disc `disc`.
internal fun albumTrack(i: Int, disc: Int?, albumId: String = "al", id: String = "t%05d".format(i), title: String = "Song %05d".format(i)) = TrackEntity(
    id = id, sourceId = "device", nativeId = id, title = title, searchKey = title.lowercase(), sortKey = title.lowercase(),
    artist = "Band", artistId = "ar", album = "Big", albumId = albumId, trackNo = i % 100 + 1, discNo = disc, year = null,
    durationMs = 200_000, addedAt = 0, mimeType = null, sizeBytes = null, artwork = null, uri = null, albumOrder = i,
    relinkKey = "band|${title.lowercase()}", artistSortKey = "band", albumSortKey = "big",
)

// A library album "al" of `n` songs in album order, song `i` on disc
// `disc(i)`; with a `sourceId`, each a copy of the server's song "s<i>" of
// its album "srv", so a vote finds that album and its listing links them.
internal suspend fun CatalogDatabase.albumOnServer(n: Int, sourceId: String?, disc: (Int) -> Int?) {
    dao().insertTracks((0 until n).map { albumTrack(it, disc(it)) })
    if (sourceId == null) return
    val sql = openHelper.writableDatabase
    sql.beginTransaction()
    try {
        sql.execSQL(
            "INSERT INTO source_album (id, sourceId, nativeId, title, searchKey, sortKey, artist, artistId, songCount, durationMs, addedAt) " +
                "VALUES (?, ?, 'srv', 'Big', 'big', 'big', 'Band', '', ?, 0, 0)",
            arrayOf<Any?>("$sourceId:srv", sourceId, n),
        )
        val copy = sql.compileStatement(
            "INSERT INTO source_track (id, sourceId, nativeId, title, searchKey, sortKey, artist, artistId, album, albumId, durationMs, addedAt, " +
                "albumOrder, relinkKey, genre, mergedId) VALUES (?, ?, ?, ?, ?, ?, 'Band', '', 'Big', ?, 200000, 0, ?, '', '', ?)",
        )
        for (i in 0 until n) {
            val title = "Song %05d".format(i)
            copy.clearBindings()
            copy.bindString(1, "$sourceId:s$i")
            copy.bindString(2, sourceId)
            copy.bindString(3, "s$i")
            copy.bindString(4, title)
            copy.bindString(5, title.lowercase())
            copy.bindString(6, title.lowercase())
            copy.bindString(7, "$sourceId:srv")
            copy.bindLong(8, i.toLong())
            copy.bindString(9, "t%05d".format(i))
            copy.executeInsert()
        }
        sql.setTransactionSuccessful()
    } finally {
        sql.endTransaction()
    }
}

// The server's listing of song `i` of album "srv", linked to the library.
internal fun listed(i: Int, disc: Int? = 1) = Song(id = "s$i", title = "Song %05d".format(i), album = "Big", albumId = "srv", artist = "Band", track = i % 100 + 1, discNumber = disc, duration = 200)

// A song Octo found online for album "srv", outside the library.
internal fun found(k: Int, disc: Int? = 1) =
    Song(id = "f$k", title = "Found $k", album = "Big", albumId = "srv", artist = "Band", track = k % 100 + 1, discNumber = disc, duration = 200, isExternal = true)

// An Octo server answering getAlbum for album "srv" with `listing`, in
// that order, and a plain yes to anything else.
internal class AlbumServer(@Volatile var listing: List<Song> = emptyList()) : AutoCloseable {
    private val json = Json { encodeDefaults = false }
    val server = MockWebServer()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val endpoint = request.url.pathSegments.last().removeSuffix(".view")
                val body = if (endpoint == "getAlbum") {
                    val songs = json.encodeToString(ListSerializer(Song.serializer()), listing)
                    """{"subsonic-response":{"status":"ok","version":"1.16.1","album":{"id":"srv","name":"Big","artist":"Band","song":$songs}}}"""
                } else {
                    """{"subsonic-response":{"status":"ok","version":"1.16.1"}}"""
                }
                return MockResponse.Builder().body(body).build()
            }
        }
        server.start()
    }

    val client: SubsonicClient by lazy { SubsonicClient(server.url("/"), Credentials("winters", "secret"), OkHttpClient()) }

    val sourceId: String get() = serverSourceId(server.url("/"))

    override fun close() = server.close()
}

// A loader's rows' songs, walked a page at a time.
internal suspend fun <T : Any> ListLoader<T>.walkedIds(page: Int = 500): List<String> {
    val out = ArrayList<String>()
    var after: RowKey? = null
    while (true) {
        val rows = after(after, page)
        out += rows.mapNotNull { trackIdOf(it.row) }
        if (rows.size < page) return out
        after = rows.last().key
    }
}
