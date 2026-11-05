package app.winters.octo.proof

import app.winters.octo.subsonic.Album
import app.winters.octo.subsonic.Artist
import app.winters.octo.subsonic.Credentials
import app.winters.octo.subsonic.Song
import app.winters.octo.subsonic.SubsonicClient
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.Buffer
import java.awt.Color
import java.awt.GradientPaint
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

// Serves a LazyLibrary page by page, making each page when asked. Starred:
// the first `starredSongs` songs. Covers are plain tiles, one color per
// cover id. `pageDelayMs` holds each page of albums or songs back that
// long, like a slow server. Anything else answers a plain yes.
class LazySubsonic(
    private val library: LazyLibrary,
    private val starredSongs: Int,
    port: Int = 0,
    anyAddress: Boolean = false,
    private val pageDelayMs: Long = 0,
) {
    private val json = Json { encodeDefaults = false }
    val server = MockWebServer()

    init {
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

    private fun answer(request: RecordedRequest): String {
        val q = request.url
        val songs = ListSerializer(Song.serializer())
        return when (q.pathSegments.last().removeSuffix(".view")) {
            "getAlbumList2" -> {
                val size = q.queryParameter("size")!!.toInt()
                val offset = q.queryParameter("offset")!!.toInt()
                val page = (offset until minOf(offset + size, library.albums)).map(library::album)
                ok("albumList2", """{"album":${json.encodeToString(ListSerializer(Album.serializer()), page)}}""")
            }
            "getArtists" -> {
                val all = (0 until library.artists).map(library::artist)
                ok("artists", """{"index":[{"name":"A","artist":${json.encodeToString(ListSerializer(Artist.serializer()), all)}}]}""")
            }
            "search3" -> {
                val size = q.queryParameter("songCount")!!.toInt()
                val offset = q.queryParameter("songOffset")!!.toInt()
                val page = (offset until minOf(offset + size, library.songs)).map(library::song)
                ok("searchResult3", """{"song":${json.encodeToString(songs, page)}}""")
            }
            "getStarred2" -> {
                // Written out in pieces, so the server never holds the list either.
                val body = StringBuilder()
                body.append("""{"subsonic-response":{"status":"ok","version":"1.16.1","starred2":{"song":[""")
                for (i in 0 until starredSongs) {
                    if (i > 0) body.append(',')
                    body.append(json.encodeToString(Song.serializer(), library.song(i).copy(starred = "2026-01-01T00:00:00Z")))
                }
                body.append("]}}}")
                body.toString()
            }
            "getScanStatus" -> ok("scanStatus", """{"scanning":false,"count":${library.songs},"lastScan":"2026-11-01T00:00:00Z"}""")
            else -> """{"subsonic-response":{"status":"ok","version":"1.16.1"}}"""
        }
    }
}
