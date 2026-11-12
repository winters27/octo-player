package app.winters.octo.testing

import app.winters.octo.subsonic.Album
import app.winters.octo.subsonic.Artist
import app.winters.octo.subsonic.Credentials
import app.winters.octo.subsonic.Library
import app.winters.octo.subsonic.Song
import app.winters.octo.subsonic.Starred
import app.winters.octo.subsonic.SubsonicClient
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import java.util.Collections

// A Subsonic server holding a generated library: the album list, artists,
// songs by empty search, single albums and songs, the starred list and
// the scan status. Notes each request, so tests can see how the reads and
// the database writes interleave.
class FakeSubsonic(private val library: Library, var starred: Starred = Starred()) {
    private val json = Json { encodeDefaults = false }
    val server = MockWebServer()
    val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())

    // Answers this instead of the starred list when set: a cut-off body or
    // a failed status, for the partial-starred cases.
    @Volatile var starredBody: String? = null

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.url
                val endpoint = url.pathSegments.last().removeSuffix(".view")
                val detail = url.queryParameter("offset") ?: url.queryParameter("songOffset") ?: url.queryParameter("id")
                requests += listOfNotNull(endpoint, detail).joinToString(" ")
                return MockResponse.Builder().body(answer(endpoint, request)).build()
            }
        }
        server.start()
    }

    fun client(http: OkHttpClient = OkHttpClient()): SubsonicClient = SubsonicClient(server.url("/"), Credentials("winters", "secret"), http)

    fun close() = server.close()

    private fun ok(key: String, body: String) = """{"subsonic-response":{"status":"ok","version":"1.16.1","$key":$body}}"""

    private fun answer(endpoint: String, request: RecordedRequest): String {
        val q = request.url
        return when (endpoint) {
            "getAlbumList2" -> {
                val size = q.queryParameter("size")!!.toInt()
                val offset = q.queryParameter("offset")!!.toInt()
                ok("albumList2", """{"album":${json.encodeToString(ListSerializer(Album.serializer()), library.albums.drop(offset).take(size))}}""")
            }
            "getArtists" -> ok("artists", """{"index":[{"name":"A","artist":${json.encodeToString(ListSerializer(Artist.serializer()), library.artists)}}]}""")
            "search3" -> {
                val size = q.queryParameter("songCount")!!.toInt()
                val offset = q.queryParameter("songOffset")!!.toInt()
                ok("searchResult3", """{"song":${json.encodeToString(ListSerializer(Song.serializer()), library.songs.drop(offset).take(size))}}""")
            }
            "getAlbum" -> {
                val id = q.queryParameter("id")!!
                val album = library.albums.firstOrNull { it.id == id }
                    ?: return """{"subsonic-response":{"status":"failed","error":{"code":70,"message":"not found"}}}"""
                val songs = library.songs.filter { it.albumId == id }.distinctBy { it.id }
                val name = Json.encodeToString(String.serializer(), album.name)
                val artist = Json.encodeToString(String.serializer(), album.artist)
                ok("album", """{"id":"${album.id}","name":$name,"artist":$artist,"song":${json.encodeToString(ListSerializer(Song.serializer()), songs)}}""")
            }
            "getSong" -> {
                val id = q.queryParameter("id")!!
                val song = library.songs.firstOrNull { it.id == id }
                    ?: return """{"subsonic-response":{"status":"failed","error":{"code":70,"message":"not found"}}}"""
                ok("song", json.encodeToString(Song.serializer(), song))
            }
            "getStarred2" -> starredBody ?: ok("starred2", json.encodeToString(Starred.serializer(), starred))
            "getScanStatus" -> ok("scanStatus", """{"scanning":false,"count":${library.songs.size},"lastScan":"2026-11-01T00:00:00Z"}""")
            // star, unstar, setRating, scrobble and anything else: a plain yes.
            else -> """{"subsonic-response":{"status":"ok","version":"1.16.1"}}"""
        }
    }
}
