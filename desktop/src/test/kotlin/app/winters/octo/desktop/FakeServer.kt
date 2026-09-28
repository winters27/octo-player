package app.winters.octo.desktop

import app.winters.octo.desktop.server.Connection
import app.winters.octo.desktop.settings.SavedServer
import app.winters.octo.subsonic.Credentials
import app.winters.octo.subsonic.SubsonicClient
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.SSLSocketFactory

// A pretend Subsonic server for tests: each endpoint answers with the body
// given for it, wrapped as a Subsonic answer, and every call is recorded.
// Nothing here ever reaches a real server. With `tls` it speaks https,
// with whatever certificate that holds.
class FakeServer(tls: SSLSocketFactory? = null) : AutoCloseable {
    private val server = MockWebServer()
    private val answers = HashMap<String, String>()
    private val rules = HashMap<String, (RecordedRequest) -> String>()
    private val files = HashMap<String, ByteArray>()
    val calls = CopyOnWriteArrayList<RecordedRequest>()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                calls += request
                val endpoint = request.url.pathSegments.lastOrNull().orEmpty()
                synchronized(files) { files[endpoint] }?.let { bytes ->
                    return MockResponse.Builder().body(okio.Buffer().write(bytes)).build()
                }
                synchronized(rules) { rules[endpoint] }?.let { rule -> return MockResponse.Builder().body(rule(request)).build() }
                val body = synchronized(answers) { answers[endpoint] }
                    ?: return MockResponse.Builder().body(error(70, "not found")).build()
                return MockResponse.Builder().body(body).build()
            }
        }
        if (tls != null) server.useHttps(tls)
        server.start()
    }

    val address: String get() = server.url("/").toString()

    // Answers `endpoint` with this payload inside an ok answer.
    fun answer(endpoint: String, payload: String = "", type: String = "navidrome", openSubsonic: Boolean = true) {
        val extra = if (payload.isEmpty()) "" else ",$payload"
        synchronized(answers) {
            answers[endpoint] = """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"$type","serverVersion":"0.58.0","openSubsonic":$openSubsonic$extra}}"""
        }
    }

    // Answers `endpoint` with these bytes as they are, like a song file.
    fun file(endpoint: String, bytes: ByteArray) {
        synchronized(files) { files[endpoint] = bytes }
    }

    // Answers `endpoint` by looking at each request: `ok` for an answer
    // like `answer` gives, `failed` for an error.
    fun answerBy(endpoint: String, rule: (RecordedRequest) -> String) {
        synchronized(rules) { rules[endpoint] = rule }
    }

    fun ok(payload: String = "", type: String = "navidrome"): String {
        val extra = if (payload.isEmpty()) "" else ",$payload"
        return """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"$type","serverVersion":"0.58.0","openSubsonic":true$extra}}"""
    }

    fun failed(code: Int, message: String) = error(code, message)

    fun fail(endpoint: String, code: Int, message: String) {
        synchronized(answers) { answers[endpoint] = error(code, message) }
    }

    fun endpoints(): List<String> = calls.map { it.url.pathSegments.last() }

    fun client(password: String = "secret"): SubsonicClient = SubsonicClient(server.url("/"), Credentials("winters", password), OkHttpClient())

    fun connection(extensions: List<String> = emptyList()): Connection =
        Connection(client(), SavedServer(address, "winters", extensions = extensions))

    override fun close() = server.close()

    private fun error(code: Int, message: String) =
        """{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":$code,"message":"$message"}}}"""
}

// A song as a server lists it, in JSON.
fun songJson(id: String, title: String, artist: String = "Artist", album: String = "Album", albumId: String = "al-$album", duration: Int = 200) =
    """{"id":"$id","title":"$title","artist":"$artist","album":"$album","albumId":"$albumId","duration":$duration}"""
