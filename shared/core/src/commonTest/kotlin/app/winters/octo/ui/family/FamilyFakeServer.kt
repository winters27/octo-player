package app.winters.octo.ui.family

import app.winters.octo.subsonic.Credentials
import app.winters.octo.subsonic.SubsonicClient
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

// A pretend Octo server with Family on, answering the octoFamily calls
// by endpoint. Every call is recorded.
class FamilyFakeServer : AutoCloseable {
    private val server = MockWebServer()
    private val answers = ConcurrentHashMap<String, (RecordedRequest) -> String>()
    val calls = CopyOnWriteArrayList<RecordedRequest>()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                calls += request
                val endpoint = request.url.pathSegments.lastOrNull().orEmpty()
                val body = try {
                    answers[endpoint]?.invoke(request) ?: fail(70, "Not found")
                } catch (e: IllegalStateException) {
                    // A rule that refuses: the family page's "not signed in".
                    return MockResponse.Builder().code(401).body("").build()
                }
                return MockResponse.Builder().body(body).build()
            }
        }
        server.start()
        answer("getFamily") { """"family":{"me":${me()}}""" }
        answer("getFamilyRequests") { """"familyRequests":{"request":[]}""" }
        answer("getFamilyDevices") { """"familyDevices":{"device":[]}""" }
        answer("getStarred2") { """"starred2":{}""" }
    }

    val url get() = server.url("/")

    fun client() = SubsonicClient(url, Credentials("alex", "secret"), OkHttpClient())

    fun answer(endpoint: String, payload: (RecordedRequest) -> String) {
        answers[endpoint] = { request -> ok(payload(request)) }
    }

    // Answers with this body as it is, as the family page's JSON calls do.
    fun raw(endpoint: String, body: (RecordedRequest) -> String) {
        answers[endpoint] = body
    }

    fun failWith(endpoint: String, code: Int, message: String) {
        answers[endpoint] = { fail(code, message) }
    }

    fun called(endpoint: String) = calls.filter { it.url.pathSegments.lastOrNull() == endpoint }

    override fun close() = server.close()

    companion object {
        fun ok(payload: String) =
            """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"octo","openSubsonic":true${if (payload.isEmpty()) "" else ",$payload"}}}"""

        fun fail(code: Int, message: String) =
            """{"subsonic-response":{"status":"failed","version":"1.16.1","type":"octo","openSubsonic":true,"error":{"code":$code,"message":"$message"}}}"""

        fun me(
            role: String = "Listener",
            addToLibrary: String = "Request",
            requestQuality: String = "Flac",
            weeklyLimit: Int = 10,
            used: Int = 3,
            storageUsed: Long = 5_400_000_000,
            storageLimit: Int = 10,
            offlineCopies: Boolean = false,
            approveRequests: Boolean = false,
            manageFamily: Boolean = false,
            managed: Boolean = true,
        ) = """{"username":"alex","displayName":"Alex","role":"$role","managed":$managed,
            "abilities":{"addToLibrary":"$addToLibrary","requestQuality":"$requestQuality","autoApprove":false,
            "weeklyRequestLimit":$weeklyLimit,"streamCap":192,"awayCap":128,"away":true,"devicesAtOnce":2,
            "downloadFiles":false,"offlineCopies":$offlineCopies,"share":false,"importPlaylists":true,"cleanOnly":false,
            "familyPlaylistsEdit":true,"manageFamily":$manageFamily,"approveRequests":$approveRequests,
            "storageLimitGb":$storageLimit,"instantFromFamily":true},
            "requestsThisWeek":$used,"storageUsedBytes":$storageUsed,"place":"Home","deviceId":"d_7Qm2abc"}"""

        fun request(
            id: String,
            state: String = "Pending",
            outcome: String? = null,
            title: String = "Song",
            note: String = "",
            username: String = "alex",
        ) = """{"id":"$id","username":"$username","displayName":"${username.replaceFirstChar(Char::titlecase)}","kind":"Song",
            "target":"ext-deezer-song-$id","title":"$title","artist":"Artist","album":"Album","coverArt":null,
            "quality":"Flac","state":"$state","created":"2026-10-20T18:00:00Z","decided":null,"decidedBy":null,
            "note":"$note","failure":null,"librarySongId":null,"outcome":${outcome?.let { "\"$it\"" } ?: "null"}}"""
    }
}
