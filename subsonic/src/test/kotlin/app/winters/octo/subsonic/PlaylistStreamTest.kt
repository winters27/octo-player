package app.winters.octo.subsonic

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockResponseBody
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okio.Buffer
import okio.BufferedSink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

// The playlist calls for lists too long to hold: the paged send forms make
// exactly the requests today's list forms and builders make (the same
// addresses, and form bodies byte for byte), a playlist read through a
// drained file hands on every song as the whole read decodes it and fails
// as it fails, a cancelled read stops reading at once, and a list that
// fails while its body is sent fails the call without losing OkHttp's thread.
class PlaylistStreamTest {
    private val server = MockWebServer()
    private val json = Json { encodeDefaults = false }
    private val dir = Files.createTempDirectory("drain").toFile()

    @Before
    fun start() = server.start()

    @After
    fun stop() {
        server.close()
        dir.deleteRecursively()
    }

    private fun client(http: OkHttpClient = OkHttpClient()) = SubsonicClient(server.url("/"), Credentials("winters", "secret"), http)

    private fun ok(extra: String = "") = MockResponse.Builder().body("""{"subsonic-response":{"status":"ok","version":"1.16.1"$extra}}""").build()

    // A request's params with the sign-in left out, from the address and the body.
    private fun params(request: RecordedRequest): List<Pair<String, String>> {
        val text = (request.url.encodedQuery.orEmpty().split('&') + request.body?.utf8().orEmpty().split('&')).filter { it.isNotEmpty() }
        return text.map { java.net.URLDecoder.decode(it.substringBefore('='), "UTF-8") to java.net.URLDecoder.decode(it.substringAfter('=', ""), "UTF-8") }
            .filter { it.first !in setOf("u", "t", "s", "p", "v", "c", "f") }
    }

    private fun ids(n: Int) = (1..n).map { "s$it😀" }

    private fun takeAll(): List<RecordedRequest> = generateSequence { server.takeRequest(10, java.util.concurrent.TimeUnit.MILLISECONDS) }.toList()

    // Today's builders' calls as requests: each call's params, and with a
    // form body the bytes one FormBody of them writes.
    private fun expected(calls: List<PlaylistCall>, formPost: Boolean) = calls.map { call ->
        val body = if (formPost) Buffer().also { FormBody.Builder().apply { call.params.forEach { (k, v) -> add(k, v) } }.build().writeTo(it) }.readUtf8() else null
        Triple(call.endpoint, call.params, body)
    }

    private fun actual(requests: List<RecordedRequest>, formPost: Boolean) = requests.map {
        Triple(it.url.pathSegments.last(), params(it), if (formPost) it.body?.utf8() else null)
    }

    @Test
    fun pagedSendsMakeTodaysRequests() = runBlocking {
        server.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: RecordedRequest) = ok(""","playlist":{"id":"pl9","name":"Drive"}""")
        }
        for (n in listOf(0, 150, 151, 2_000)) {
            for (formPost in listOf(false, true)) {
                val songs = ids(n)
                client().replacePlaylistSongs("pl1", PagedValues.of(songs), formPost)
                assertEquals("replace $n $formPost", expected(replaceSongsCalls("pl1", songs, formPost), formPost), actual(takeAll(), formPost))
                client().replacePlaylistSongs("pl1", songs, formPost)
                assertEquals("list replace $n $formPost", expected(replaceSongsCalls("pl1", songs, formPost), formPost), actual(takeAll(), formPost))

                val made = client().createPlaylist("Drive", PagedValues.of(songs), formPost)
                assertEquals("pl9", made?.id)
                val (first, rest) = createCalls("Drive", songs, formPost)
                assertEquals("create $n $formPost", expected(listOf(first) + addSongsCalls("pl9", rest), formPost), actual(takeAll(), formPost))

                val removals = (0 until minOf(n, 150)).toList()
                val byId = removals.map { songs[it] to 1 }
                client().updatePlaylist(
                    "pl1", name = "N", songIdsToAdd = PagedValues.of(songs), songIndexesToRemove = PagedValues.of(removals.map { "$it" }),
                    songsToRemove = PagedValues.of(byId.map { (id, copy) -> "$id@$copy" }), formPost = formPost,
                )
                assertEquals(
                    "update $n $formPost",
                    expected(updateCalls("pl1", "N", null, null, songs, removals, byId, formPost), formPost),
                    actual(takeAll(), formPost),
                )
            }
        }
    }

    // The same form body length is written each time it is asked for, as
    // OkHttp may write a body twice.
    @Test
    fun aStreamedFormCanBeWrittenAgain() {
        val form = StreamedForm(PagedCall("createPlaylist", listOf("name" to "A b"), listOf("songId" to PagedValues.of(ids(1_201)))))
        val one = Buffer().also { form.writeTo(it) }.readUtf8()
        val two = Buffer().also { form.writeTo(it) }.readUtf8()
        assertEquals(one, two)
        assertEquals(one.toByteArray().size.toLong(), form.contentLength())
        assertTrue(!form.isOneShot())
    }

    @Test
    fun aListThatFailsWhileSentFailsTheCall() = runBlocking {
        val broken = object : PagedValues {
            override val size = 600
            override fun pages(pageSize: Int): Sequence<List<String>> = sequence {
                yield(List(pageSize) { "s$it" })
                throw IllegalStateException("the table went away")
            }
        }
        server.enqueue(ok())
        try {
            client().replacePlaylistSongs("pl1", broken, formPost = true)
            fail("sent a body that could not be read")
        } catch (_: SubsonicException.Unreachable) {
        }
        // OkHttp still sends calls.
        server.enqueue(ok())
        client().replacePlaylistSongs("pl1", PagedValues.of(ids(3)), formPost = true)
    }

    private fun song(i: Int) = Song("s$i", "Title $i 😀", artist = "A", duration = i, isExternal = i % 3 == 0)

    private fun playlistAnswer(songs: List<Song>, head: String = """"id":"pl1","name":"Mix","owner":"winters"""", status: String = "ok", tail: String = "") =
        """{"subsonic-response":{"status":"$status","version":"1.16.1","playlist":{$head,"entry":${json.encodeToString(ListSerializer(Song.serializer()), songs)}}$tail}}"""

    private suspend fun read(file: File = File(dir, "plsync-1.json"), pageSize: Int = 7): Pair<PlaylistWithSongs, List<List<Song>>> {
        val pages = ArrayList<List<Song>>()
        val head = client().playlistInto("pl1", file, pageSize = pageSize) { pages += it }
        return head to pages
    }

    @Test
    fun aDrainedReadHandsOnEverySongAsAWholeReadDecodesIt() = runBlocking {
        val songs = (0 until 50).map(::song)
        repeat(2) { server.enqueue(MockResponse.Builder().body(playlistAnswer(songs)).build()) }
        val whole = client().playlist("pl1")
        val (head, pages) = read()
        assertEquals(whole.copy(entry = emptyList()), head)
        assertEquals(whole.entry, pages.flatten())
        assertTrue(pages.all { it.size <= 7 })
        // The head may come after the songs, and a null list is none.
        server.enqueue(MockResponse.Builder().body("""{"subsonic-response":{"playlist":{"entry":null,"id":"pl2"},"status":"ok"}}""").build())
        val (late, none) = read()
        assertEquals("pl2", late.id)
        assertEquals(emptyList<Song>(), none.flatten())
    }

    @Test
    fun aDrainedReadFailsAsAWholeReadFails() = runBlocking {
        suspend fun failure(body: String): Throwable? {
            server.enqueue(MockResponse.Builder().body(body).build())
            return runCatching { read() }.exceptionOrNull()
        }
        val songs = (0 until 20).map(::song)
        assertTrue(failure(playlistAnswer(songs, status = "failed", tail = ""","error":{"code":70,"message":"Playlist not found"}""")) is SubsonicException.NotFound)
        assertTrue(failure("""{"subsonic-response":{"status":"ok"}}""").let { it is SubsonicException.Server && it.message!!.contains("Missing") })
        assertTrue(failure("""{"other":{}}""") is SubsonicException.NotSubsonic)
        assertTrue(failure("<html>Sign in</html>") is SubsonicException.NotSubsonic)
        // Cut off part way: as a cut connection.
        val whole = playlistAnswer(songs)
        server.enqueue(
            MockResponse.Builder().body(
                object : MockResponseBody {
                    override val contentLength = whole.length.toLong()

                    override fun writeTo(sink: BufferedSink) {
                        sink.writeUtf8(whole.take(whole.length / 2))
                        sink.flush()
                        throw IOException("cut")
                    }
                },
            ).build(),
        )
        assertTrue(runCatching { read() }.exceptionOrNull() is SubsonicException.Unreachable)
        // An answer that ends part way through is not a Subsonic answer.
        assertTrue(failure(whole.dropLast(40)).let { it is SubsonicException.NotSubsonic || it is SubsonicException.Unreachable })
        // The songs given twice cannot be taken back.
        assertTrue(failure("""{"subsonic-response":{"status":"ok","playlist":{"id":"p","entry":[],"entry":[]}}}""") is SubsonicException.NotSubsonic)
        server.enqueue(MockResponse.Builder().code(503).body("busy").build())
        assertTrue(runCatching { read() }.exceptionOrNull().let { it is SubsonicException.NotSubsonic && it.status == 503 })
    }

    // A cancelled read stops reading at once, even while the server is
    // still sending: the server sees the connection closed.
    @Test
    fun aCancelledDrainClosesTheConnection() = runBlocking {
        val sending = CompletableDeferred<Unit>()
        val closed = AtomicBoolean(false)
        server.enqueue(
            MockResponse.Builder().body(
                object : MockResponseBody {
                    override val contentLength = -1L

                    override fun writeTo(sink: BufferedSink) {
                        sink.writeUtf8("""{"subsonic-response":{"status":"ok","playlist":{"id":"p","entry":[""")
                        try {
                            while (true) {
                                sink.writeUtf8(json.encodeToString(Song.serializer(), song(1)) + ",")
                                sink.flush()
                                sending.complete(Unit)
                                Thread.sleep(5)
                            }
                        } catch (e: IOException) {
                            closed.set(true)
                            throw e
                        }
                    }
                },
            ).build(),
        )
        val reading = async(Dispatchers.IO) { read() }
        sending.await()
        withTimeout(5_000) { reading.cancelAndJoin() }
        withContext(Dispatchers.IO) {
            val until = System.currentTimeMillis() + 5_000
            while (!closed.get() && System.currentTimeMillis() < until) Thread.sleep(10)
        }
        assertTrue("the server saw the read stop", closed.get())
    }

    // An album read the same way: its songs a page at a time, its head as
    // the whole read has it.
    @Test
    fun anAlbumIsReadThroughItsFile() = runBlocking {
        val songs = (0 until 30).map(::song)
        val answer = """{"subsonic-response":{"status":"ok","version":"1.16.1","album":{"id":"al1","name":"Big","artist":"A","songCount":30,""" +
            """"song":${json.encodeToString(ListSerializer(Song.serializer()), songs)}}}}"""
        repeat(2) { server.enqueue(MockResponse.Builder().body(answer).build()) }
        val whole = client().album("al1")
        val pages = ArrayList<List<Song>>()
        val head = client().albumInto("al1", File(dir, "album-1.json"), pageSize = 7) { pages += it }
        assertEquals(whole.copy(song = emptyList()), head)
        assertEquals(whole.song, pages.flatten())
    }

    // A playlist call's answer with every song in it is read for its head only.
    @Test
    fun aCreatedPlaylistsAnswerIsReadForItsHead() = runBlocking {
        server.enqueue(MockResponse.Builder().body(playlistAnswer((0 until 3_000).map(::song))).build())
        val made = client().createPlaylist("Mix", PagedValues.of(ids(2)), formPost = true)
        assertEquals("pl1", made?.id)
        assertEquals(emptyList<Song>(), made?.entry)
    }
}
