package app.winters.octo.proof

import app.winters.octo.subsonic.Album
import app.winters.octo.subsonic.Artist
import app.winters.octo.subsonic.Credentials
import app.winters.octo.subsonic.Playlist
import app.winters.octo.subsonic.Song
import app.winters.octo.subsonic.SubsonicClient
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockResponseBody
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.Buffer
import okio.BufferedSink
import okio.Sink
import okio.Timeout
import okio.buffer
import java.awt.Color
import java.awt.GradientPaint
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.random.Random

// The one album the server answers for that is not in its library: found
// online, as an Octo server lists one.
const val ONLINE_ALBUM_ID = "ext-al1"
const val ONLINE_ALBUM_NAME = "Found online"

// Serves a LazyLibrary page by page, making each page when asked. Starred:
// the first `starredSongs` songs. Covers are plain tiles, one color per
// cover id. `pageDelayMs` holds each page of albums or songs back that
// long, like a slow server. `playlists` and `playQueue` are served entry by
// entry, never held; `onlineAlbumSongs` songs make the online album, which
// a search for its name finds; `extensions` are the OpenSubsonic
// extensions the server lists. Long answers are written as they are sent,
// and request bodies are not kept (only their sizes), so neither side of a
// test holds a list. Anything else answers a plain yes.
class LazySubsonic(
    private val library: LazyLibrary,
    private val starredSongs: Int,
    port: Int = 0,
    anyAddress: Boolean = false,
    private val pageDelayMs: Long = 0,
    private val playlists: List<LazyPlaylist> = emptyList(),
    private val playQueue: LazyPlayQueue? = null,
    private val onlineAlbumSongs: Int = 0,
    private val extensions: List<String> = emptyList(),
) {
    private val json = Json { encodeDefaults = false }
    private val created = AtomicInteger()
    val server = MockWebServer()

    init {
        server.bodyLimit = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val endpoint = request.url.pathSegments.last().removeSuffix(".view")
                if (endpoint == "getCoverArt") return MockResponse.Builder().addHeader("Content-Type", "image/png").body(cover(request.url.queryParameter("id").orEmpty())).build()
                val delay = if (endpoint == "search3" || endpoint == "getAlbumList2") pageDelayMs else 0
                return MockResponse.Builder().headersDelay(delay, TimeUnit.MILLISECONDS).body(answer(request)).build()
            }
        }
        if (anyAddress) server.start(InetAddress.getByName("0.0.0.0"), port) else server.start(port)
    }

    fun client(): SubsonicClient = SubsonicClient(server.url("/"), Credentials("proof", "proof"), OkHttpClient())

    fun close() = server.close()

    // The online album's songs.
    fun onlineSong(k: Int): Song = Song(
        id = "ext-s$k", title = "Found song ${k + 1}", album = ONLINE_ALBUM_NAME, albumId = ONLINE_ALBUM_ID,
        artist = "Online Artist", artistId = "ext-ar1", track = k + 1, duration = 180 + k % 120, coverArt = "ext-al-1",
        suffix = "flac", isExternal = true,
    )

    private fun onlineAlbum(): Album = Album(
        id = ONLINE_ALBUM_ID, name = ONLINE_ALBUM_NAME, artist = "Online Artist", artistId = "ext-ar1", coverArt = "ext-al-1",
        songCount = onlineAlbumSongs, year = 2026, isExternal = true,
    )

    private fun ok(key: String, body: String) = """{"subsonic-response":{"status":"ok","version":"1.16.1","$key":$body}}"""

    // A 300 px tile in a color of its own, darker toward the foot.
    private fun cover(id: String): Buffer {
        val hue = (id.hashCode() and 0xffff) / 65_535f
        val image = BufferedImage(300, 300, BufferedImage.TYPE_INT_RGB)
        val paint = image.createGraphics()
        paint.paint = GradientPaint(0f, 0f, Color.getHSBColor(hue, 0.55f, 0.85f), 300f, 300f, Color.getHSBColor((hue + 0.08f) % 1f, 0.7f, 0.35f))
        paint.fillRect(0, 0, 300, 300)
        paint.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return Buffer().write(out.toByteArray())
    }

    // An ok answer whose `key` value is an object: `head` (an object's JSON)
    // with a list of `count` items named `list` added at its end, each item
    // written as it is sent.
    private fun listed(key: String, head: String, list: String, count: Int, item: (Int) -> String): MockResponseBody = Streamed { sink ->
        sink.writeUtf8("""{"subsonic-response":{"status":"ok","version":"1.16.1","$key":""")
        val open = head.removeSuffix("}")
        sink.writeUtf8(open)
        sink.writeUtf8(if (open.length > 1) ",\"$list\":[" else "\"$list\":[")
        for (i in 0 until count) {
            if (i > 0) sink.writeByte(','.code)
            sink.writeUtf8(item(i))
        }
        sink.writeUtf8("]}}}")
    }

    private fun song(s: Song) = json.encodeToString(Song.serializer(), s)

    private fun answer(request: RecordedRequest): MockResponseBody {
        val q = request.url
        val songs = ListSerializer(Song.serializer())
        return when (q.pathSegments.last().removeSuffix(".view")) {
            "getAlbumList2" -> {
                val size = q.queryParameter("size")!!.toInt()
                val offset = q.queryParameter("offset")!!.toInt()
                val newest = q.queryParameter("type") == "newest"
                val page = (offset until minOf(offset + size, library.albumCount)).map { library.albumAt(it, newest) }
                text(ok("albumList2", """{"album":${json.encodeToString(ListSerializer(Album.serializer()), page)}}"""))
            }
            "getAlbum" -> album(q.queryParameter("id").orEmpty())
            "getArtists" -> {
                val all = (0 until library.artistCount).map(library::artist)
                text(ok("artists", """{"index":[{"name":"A","artist":${json.encodeToString(ListSerializer(Artist.serializer()), all)}}]}"""))
            }
            "search3" -> {
                val query = q.queryParameter("query").orEmpty().trim().trim('"').lowercase()
                if (onlineAlbumSongs > 0 && query.isNotEmpty() && query in ONLINE_ALBUM_NAME.lowercase()) {
                    text(ok("searchResult3", """{"album":[${json.encodeToString(Album.serializer(), onlineAlbum())}]}"""))
                } else {
                    val size = q.queryParameter("songCount")!!.toInt()
                    val offset = q.queryParameter("songOffset")?.toInt() ?: 0
                    val page = (offset until minOf(offset + size, library.songCount)).map(library::songAt)
                    text(ok("searchResult3", """{"song":${json.encodeToString(songs, page)}}"""))
                }
            }
            "getStarred2" -> {
                val count = starredSongs - library.removedBefore(starredSongs)
                listed("starred2", "{}", "song", count) { song(library.song(library.keptAt(it)).copy(starred = "2026-01-01T00:00:00Z")) }
            }
            "getScanStatus" -> text(ok("scanStatus", """{"scanning":false,"count":${library.songCount},"lastScan":"${library.lastScan}"}"""))
            "getOpenSubsonicExtensions" -> text(ok("openSubsonicExtensions", extensions.joinToString(",", "[", "]") { """{"name":"$it","versions":[1]}""" }))
            "getPlaylists" -> text(ok("playlists", """{"playlist":${json.encodeToString(ListSerializer(Playlist.serializer()), playlists.map { it.head })}}"""))
            "getPlaylist" -> {
                val list = playlists.firstOrNull { it.id == q.queryParameter("id") } ?: return notFound()
                listed("playlist", json.encodeToString(Playlist.serializer(), list.head), "entry", list.size) { song(list.entry(it)) }
            }
            // A new playlist is answered in full, as servers answer it: the
            // first configured playlist's songs under the new id.
            "createPlaylist" -> {
                val id = "created-${created.incrementAndGet()}"
                val like = playlists.firstOrNull()
                val head = Playlist(id, q.queryParameter("name").orEmpty(), owner = "proof", songCount = like?.size ?: 0)
                listed("playlist", json.encodeToString(Playlist.serializer(), head), "entry", like?.size ?: 0) { song(like!!.entry(it)) }
            }
            "getPlayQueue", "getPlayQueueByIndex" -> {
                val queue = playQueue ?: return text("""{"subsonic-response":{"status":"ok","version":"1.16.1"}}""")
                val byIndex = q.pathSegments.last().startsWith("getPlayQueueByIndex")
                // Entries first and the place after them, as Navidrome writes it.
                Streamed { sink ->
                    sink.writeUtf8("""{"subsonic-response":{"status":"ok","version":"1.16.1","${if (byIndex) "playQueueByIndex" else "playQueue"}":{"entry":[""")
                    for (i in 0 until queue.size) {
                        if (i > 0) sink.writeByte(','.code)
                        sink.writeUtf8(song(queue.entry(i)))
                    }
                    sink.writeUtf8("],")
                    sink.writeUtf8(if (byIndex) "\"currentIndex\":${queue.current}," else "\"current\":\"${queue.entry(queue.current).id}\",")
                    sink.writeUtf8(""""position":${queue.positionMs},"username":"proof","changed":"2026-11-01T12:00:00Z","changedBy":"${queue.changedBy}"}}}""")
                }
            }
            else -> text("""{"subsonic-response":{"status":"ok","version":"1.16.1"}}""")
        }
    }

    // An album of the library or the online one, its songs written as sent.
    private fun album(id: String): MockResponseBody {
        if (id == ONLINE_ALBUM_ID && onlineAlbumSongs > 0) {
            return listed("album", json.encodeToString(Album.serializer(), onlineAlbum()), "song", onlineAlbumSongs) { song(onlineSong(it)) }
        }
        val index = library.albumIndexOf(id) ?: return notFound()
        val head = library.album(index)
        // The change takes songs only from the end of an album, so its songs
        // left are its first songCount.
        val first = library.albumSongs(index).first
        return listed("album", json.encodeToString(Album.serializer(), head), "song", head.songCount) { song(library.song(first + it)) }
    }

    private fun notFound() = text("""{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":70,"message":"not found"}}}""")

    private fun text(body: String): MockResponseBody = Streamed { it.writeUtf8(body) }
}

// A body written by `write` as it is sent. Its length comes from a first
// pass of the same writer into a sink that only counts.
private class Streamed(private val write: (BufferedSink) -> Unit) : MockResponseBody {
    override val contentLength: Long by lazy {
        val counter = Counter()
        counter.buffer().use(write)
        counter.bytes
    }

    override fun writeTo(sink: BufferedSink) {
        write(sink)
        sink.flush()
    }

    private class Counter : Sink {
        var bytes = 0L

        override fun write(source: Buffer, byteCount: Long) {
            bytes += byteCount
            source.skip(byteCount)
        }

        override fun flush() = Unit

        override fun timeout(): Timeout = Timeout.NONE

        override fun close() = Unit
    }
}

// A server playlist made entry by entry, `size` entries long.
class LazyPlaylist(
    val id: String,
    val name: String,
    val size: Int,
    private val owner: String = "proof",
    private val family: Boolean = false,
    val entry: (Int) -> Song,
) {
    val head: Playlist by lazy {
        Playlist(
            id, name, owner = owner, songCount = size, duration = (0 until size).sumOf { entry(it).duration },
            created = "2026-01-01T00:00:00Z", changed = "2026-11-01T00:00:00Z", octoFamily = if (family) true else null,
        )
    }

    companion object {
        // Every kind of entry a playlist sync meets, spread evenly through
        // the list: half of it one library song again and again, 15% songs
        // found online, 10% songs the library has under other ids (matched
        // by title, artist and length) and the rest library songs. At
        // 200,000 entries: 100,000 repeats, 30,000 finds, 20,000 title
        // matches. A find's title is drawn as the library's are (one in
        // TRACK_TITLES_EVERY "Track N"), so finds share title keys with the
        // library as real ones do, by artists the library does not have.
        fun mixed(library: LazyLibrary, id: String, name: String, size: Int): LazyPlaylist = LazyPlaylist(id, name, size) { k ->
            when (k % 20) {
                in 0..9 -> library.song(1)
                in 10..12 -> {
                    val r = Random(FIND_SEED + k)
                    val title = if (r.nextInt(TRACK_TITLES_EVERY) == 0) "Track ${1 + r.nextInt(12)}" else proofTitle(r)
                    Song(
                        id = "pf$k", title = title, album = "Found online", artist = "Online Artist ${k % 1_000}", duration = 200,
                        suffix = "flac", isExternal = true,
                    )
                }
                in 13..14 -> library.song(spread(k, library.songs)).let { s ->
                    Song(id = "pt$k", title = s.title, album = s.album, artist = s.artist, duration = s.duration, suffix = "flac")
                }
                else -> library.song(spread(k + 1, library.songs))
            }
        }

        // A family playlist of library songs, a share of them in it twice or
        // more: entry k is one of the first 70% of its size, in turn.
        fun family(library: LazyLibrary, id: String, name: String, size: Int = 1_000): LazyPlaylist =
            LazyPlaylist(id, name, size, owner = "family", family = true) { k -> library.song((k % (size * 7 / 10).coerceAtLeast(1)) % library.songs) }

        private fun spread(k: Int, songs: Int) = ((k.toLong() * 7_919) % songs).toInt()

        // The seed finds' titles are drawn from, apart from the library's.
        private const val FIND_SEED = 7_000_000_000L
    }
}

// A queue saved on the server, made entry by entry: `size` songs, playing
// the one at `current`, `positionMs` into it, saved by another app.
class LazyPlayQueue(
    val size: Int,
    val current: Int,
    val positionMs: Long = 0,
    val changedBy: String = "Feishin",
    val entry: (Int) -> Song,
) {
    companion object {
        // Library songs, each once while the library is big enough.
        fun of(library: LazyLibrary, size: Int, current: Int): LazyPlayQueue =
            LazyPlayQueue(size, current, positionMs = 61_000) { k -> library.song(((k.toLong() * 7_919) % library.songs).toInt()) }
    }
}
