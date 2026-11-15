package app.winters.octo.playlists

import app.winters.octo.subsonic.Credentials
import app.winters.octo.subsonic.Song
import app.winters.octo.subsonic.SubsonicClient
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockResponseBody
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import okio.BufferedSink
import java.net.URLDecoder
import java.util.Collections

// The params a request carries that say who signs in and how to answer,
// left out of what the server notes (the salt is new on every call).
private val SIGN_IN = setOf("u", "t", "s", "p", "v", "c", "f", "apiKey")

// The calls that change a playlist.
private val CHANGES = setOf("createPlaylist", "updatePlaylist", "deletePlaylist")

// A Subsonic server holding playlists of songs, for the sync's tests. It
// lists them, answers each in full, and applies every change call as a
// server does: createPlaylist makes one (or with playlistId replaces its
// songs), updatePlaylist renames, takes songs out by place in the list
// before the call or by song and copy (octoSongToRemove), and adds songs to
// the end. Every request is noted with its params, sign-in left out, from
// the address or the form body.
class PlaylistServer(
    // The songs it knows, by id; a playlist may name others, answered by id alone.
    val songs: Map<String, Song> = emptyMap(),
    val username: String = "winters",
) {
    class Held(
        val id: String,
        var name: String,
        val owner: String,
        val entries: MutableList<String>,
        var changes: Int = 0,
        val octoFamily: Boolean? = null,
        val created: String = "2026-01-01T00:00:00Z",
        var upkeep: Boolean = false,
        var octoImport: Boolean = false,
    )

    // One request as the server saw it.
    data class Call(val method: String, val endpoint: String, val params: List<Pair<String, String>>)

    private val json = Json { encodeDefaults = false }
    val playlists = LinkedHashMap<String, Held>()
    val calls: MutableList<Call> = Collections.synchronizedList(ArrayList())

    // Answers HTTP 500, changing nothing, for a change call this says yes
    // to, by the change call's number counting from 1.
    @Volatile var failChange: (Int) -> Boolean = { false }

    // Applies a change call and then closes the connection with no answer,
    // for a change call this says yes to.
    @Volatile var loseAnswer: (Int) -> Boolean = { false }

    // A playlist's answer, written as this says when set (a test's own body).
    @Volatile var playlistBody: ((Held, String) -> MockResponseBody)? = null

    // Ids this server leaves out of a change, naming them in octoSkipped, as
    // Octo does with ids it can't keep.
    val skipping: MutableSet<String> = Collections.synchronizedSet(HashSet())

    // Refuses the next replace sent with octoIfStamp as stale (octoConflict),
    // having first let `onConflict` change the playlist, as another device would.
    @Volatile var conflictNext = false
    @Volatile var onConflict: (Held) -> Unit = {}

    // Runs right after a change is applied, as another device changing the
    // playlist then would.
    @Volatile var afterChange: (Held) -> Unit = {}

    private var changeCalls = 0
    private var made = 0
    private var ticks = 0

    val server = MockWebServer()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = synchronized(this@PlaylistServer) { answer(request) }
        }
        server.start()
    }

    // A client that never sends a call twice on its own, so a call whose
    // answer is lost is seen as the sync sees it.
    fun client(http: OkHttpClient = OkHttpClient.Builder().retryOnConnectionFailure(false).build()): SubsonicClient =
        SubsonicClient(server.url("/"), Credentials(username, "secret"), http)

    fun close() = server.close()

    fun add(id: String, name: String, entries: List<String>, owner: String = username, octoFamily: Boolean? = null): Held =
        Held(id, name, owner, entries.toMutableList(), octoFamily = octoFamily).also { playlists[id] = it }

    // The change calls, in order.
    fun changeCalls(): List<Call> = calls.filter { it.endpoint in CHANGES }

    private fun stamp(held: Held) = "2026-11-01T%02d:%02d:00Z".format(held.changes / 60 % 24, held.changes % 60)

    private fun head(held: Held): String =
        buildString {
            append("""{"id":${quote(held.id)},"name":${quote(held.name)},"owner":${quote(held.owner)},"songCount":${held.entries.size}""")
            append(""","changed":"${stamp(held)}","created":"${held.created}"""")
            held.octoFamily?.let { append(""","octoFamily":$it""") }
            if (held.upkeep) append(""","octoUpkeep":true""")
            if (held.octoImport) append(""","octoImport":true,"readonly":true""")
        }

    fun songJson(id: String): String = json.encodeToString(Song.serializer(), songs[id] ?: Song(id))

    private fun full(held: Held): String = head(held) + ""","entry":[""" + held.entries.joinToString(",") { songJson(it) } + "]}"

    private fun ok(body: String = "") = MockResponse.Builder().body("""{"subsonic-response":{"status":"ok","version":"1.16.1"$body}}""").build()

    private fun answer(request: RecordedRequest): MockResponse {
        val endpoint = request.url.pathSegments.last().removeSuffix(".view")
        val params = paramsOf(request)
        calls += Call(request.method.orEmpty(), endpoint, params)
        fun one(name: String) = params.firstOrNull { it.first == name }?.second
        fun all(name: String) = params.filter { it.first == name }.map { it.second }
        if (endpoint in CHANGES) {
            val number = ++changeCalls
            if (failChange(number)) return MockResponse.Builder().code(500).body("Server error").build()
            val answer = change(endpoint, ::one, ::all)
            if (loseAnswer(number)) return MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build()
            return answer
        }
        return when (endpoint) {
            "getPlaylists" -> ok(""","playlists":{"playlist":[${playlists.values.joinToString(",") { head(it) + "}" }}]}""")
            "getPlaylist" -> {
                val held = playlists[one("id")] ?: return MockResponse.Builder()
                    .body("""{"subsonic-response":{"status":"failed","error":{"code":70,"message":"Playlist not found"}}}""").build()
                playlistBody?.let { return MockResponse.Builder().body(it(held, """{"subsonic-response":{"status":"ok","version":"1.16.1","playlist":""")).build() }
                ok(""","playlist":${full(held)}""")
            }
            "getInternetRadioStations" -> ok(""","internetRadioStations":{}""")
            else -> ok()
        }
    }

    private fun change(endpoint: String, one: (String) -> String?, all: (String) -> List<String>): MockResponse {
        when (endpoint) {
            "createPlaylist" -> {
                val target = one("playlistId")?.let(playlists::get)
                val ifStamp = one("octoIfStamp")
                if (target != null && ifStamp != null && (conflictNext || ifStamp != stamp(target))) {
                    conflictNext = false
                    onConflict(target)
                    return MockResponse.Builder()
                        .body("""{"subsonic-response":{"status":"failed","error":{"code":0,"message":"The playlist changed. Read it again.","octoConflict":true}}}""")
                        .build()
                }
                val held = target ?: Held("pl${++made}", one("name").orEmpty(), username, ArrayList()).also { playlists[it.id] = it }
                val (kept, skipped) = all("songId").partition { it !in skipping }
                held.entries.clear()
                held.entries += kept
                held.changes = ++ticks
                afterChange(held)
                return ok(""","playlist":${full(held)}""" + skippedJson(skipped))
            }
            "updatePlaylist" -> {
                val held = one("playlistId")?.let(playlists::get) ?: return ok()
                one("name")?.let { held.name = it }
                val before = held.entries.toList()
                val gone = HashSet<Int>()
                all("songIndexToRemove").mapNotNull { it.toIntOrNull() }.filter { it in before.indices }.forEach { gone += it }
                for (value in all("octoSongToRemove")) {
                    val id = value.substringBeforeLast('@')
                    val copy = value.substringAfterLast('@').toIntOrNull() ?: continue
                    before.indices.filter { before[it] == id }.getOrNull(copy - 1)?.let { gone += it }
                }
                gone.sortedDescending().forEach { held.entries.removeAt(it) }
                val (kept, skipped) = all("songIdToAdd").partition { it !in skipping }
                held.entries += kept
                held.changes = ++ticks
                afterChange(held)
                return ok(skippedJson(skipped))
            }
            "deletePlaylist" -> {
                playlists.remove(one("id"))
                return ok()
            }
        }
        return ok()
    }

    private fun paramsOf(request: RecordedRequest): List<Pair<String, String>> {
        val text = if (request.method == "POST") request.body?.utf8().orEmpty() else request.url.encodedQuery.orEmpty()
        val fromBody = if (request.method == "POST") request.url.encodedQuery.orEmpty() else ""
        return (fromBody.split('&') + text.split('&')).filter { it.isNotEmpty() }.map {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
        }.filter { it.first !in SIGN_IN }
    }

    private fun quote(text: String) = Json.encodeToString(String.serializer(), text)

    private fun skippedJson(ids: List<String>) = if (ids.isEmpty()) "" else ""","octoSkipped":[${ids.joinToString(",") { quote(it) }}]"""
}

// A body written in pieces through `write`, for answers a test shapes.
class PieceBody(private val length: Long = -1, private val write: (BufferedSink) -> Unit) : MockResponseBody {
    override val contentLength: Long get() = length

    override fun writeTo(sink: BufferedSink) = write(sink)
}
