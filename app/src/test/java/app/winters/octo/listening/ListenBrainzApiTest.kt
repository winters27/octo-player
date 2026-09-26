package app.winters.octo.listening

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException

// Talking to ListenBrainz, always against a local stand-in, never the real one.
class ListenBrainzApiTest {
    private val server = MockWebServer()
    private val token = "3fa85f64-5717-4562-b3fc-2c963f66afa6"
    private val play = Listen(1_700_000_000, "Roads", "Portishead", "Dummy", 305_000, 3)

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun api() = ListenBrainzApi(OkHttpClient(), server.url("/"))

    private fun answer(code: Int = 200, body: String = """{"status":"ok"}""", headers: List<Pair<String, String>> = emptyList()) {
        val response = MockResponse.Builder().code(code).body(body)
        headers.forEach { (name, value) -> response.addHeader(name, value) }
        server.enqueue(response.build())
    }

    @Test
    fun aGoodTokenSaysWhoseItIs() = runTest {
        answer(body = """{"code":200,"message":"Token valid.","valid":true,"user_name":"winters"}""")

        assertEquals(TokenCheck.Valid("winters"), api().validate(token))
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/1/validate-token", request.url.encodedPath)
        assertEquals("Token $token", request.headers["Authorization"])
        // The token never goes in the address.
        assertFalse(request.url.toString().contains(token))
    }

    @Test
    fun aTokenListenBrainzDoesNotKnowIsInvalid() = runTest {
        answer(body = """{"code":200,"message":"Token invalid.","valid":false}""")
        answer(code = 401, body = """{"code":401,"error":"Invalid authorization token."}""")

        assertEquals(TokenCheck.Invalid, api().validate(token))
        assertEquals(TokenCheck.Invalid, api().validate(token))
    }

    @Test
    fun textThatIsNotATokenIsNeverSent() = runTest {
        assertEquals(TokenCheck.Invalid, api().validate("not a token"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun noAnswerMeansUnreachable() = runTest {
        val url = server.url("/")
        server.close()
        assertEquals(TokenCheck.Unreachable, ListenBrainzApi(OkHttpClient(), url).validate(token))
    }

    @Test
    fun onePlayGoesAsSingle() = runTest {
        answer()

        assertEquals(SubmitResult.Sent, api().submit(token, listOf(play), "0.1.0"))
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/1/submit-listens", request.url.encodedPath)
        assertEquals("Token $token", request.headers["Authorization"])
        assertTrue(request.headers["Content-Type"]!!.startsWith("application/json"))
        val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals("single", body.getValue("listen_type").jsonPrimitive.content)
        val sent = body.getValue("payload").jsonArray.single().jsonObject
        assertEquals("1700000000", sent.getValue("listened_at").jsonPrimitive.content)
    }

    @Test
    fun severalPlaysGoAsOneImport() = runTest {
        answer()
        val plays = (0 until MAX_LISTENS_PER_REQUEST).map { play.copy(listenedAt = play.listenedAt + it) }

        assertEquals(SubmitResult.Sent, api().submit(token, plays, "0.1.0"))
        val body = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals("import", body.getValue("listen_type").jsonPrimitive.content)
        assertEquals(MAX_LISTENS_PER_REQUEST, body.getValue("payload").jsonArray.size)
    }

    @Test
    fun moreThanOneRequestHoldsIsNeverSent() = runTest {
        val plays = (0..MAX_LISTENS_PER_REQUEST).map { play.copy(listenedAt = play.listenedAt + it) }
        try {
            api().submit(token, plays, "0.1.0")
            fail("A batch over the limit went out")
        } catch (e: IllegalArgumentException) {
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun answersSayWhatToDoNext() = runTest {
        answer(code = 401)
        answer(code = 400)
        answer(code = 503)
        val api = api()

        assertEquals(SubmitResult.Unauthorized, api.submit(token, listOf(play), "1"))
        assertEquals(SubmitResult.Rejected, api.submit(token, listOf(play), "1"))
        assertEquals(SubmitResult.Failed, api.submit(token, listOf(play), "1"))
    }

    @Test
    fun tooManyCallsWaitsAsLongAsListenBrainzSays() = runTest {
        answer(429, """{"code":429,"error":"Too many requests"}""", listOf("X-RateLimit-Reset-In" to "7", "X-RateLimit-Remaining" to "0"))
        val api = api()

        assertEquals(SubmitResult.RateLimited(7_000), api.submit(token, listOf(play), "1"))
        // Now playing is skipped rather than held back while no calls are left.
        assertTrue(api.playingNow(token, play, "1") is SubmitResult.RateLimited)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun anEmptyWindowIsWaitedOutBeforeTheNextCall() = runTest {
        answer(headers = listOf("X-RateLimit-Remaining" to "0", "X-RateLimit-Reset-In" to "5"))
        answer()
        val api = api()

        api.submit(token, listOf(play), "1")
        val before = testScheduler.currentTime
        assertEquals(SubmitResult.Sent, api.submit(token, listOf(play), "1"))
        assertTrue(testScheduler.currentTime - before >= 4_000)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun theListenBrainzClientDropsEverythingAddedForTheServer() {
        val shared = OkHttpClient.Builder()
            .addInterceptor { it.proceed(it.request().newBuilder().header("X-Server-Secret", "s1").build()) }
            .addNetworkInterceptor { it.proceed(it.request().newBuilder().header("CF-Access-Client-Id", "s2").build()) }
            .build()
        val client = listenBrainzClient(shared, "Octo/test", allowed = server.url("/"))
        assertEquals(1, client.interceptors.size)
        assertTrue(client.networkInterceptors.isEmpty())
        assertFalse(client.followRedirects)

        answer()
        client.newCall(Request.Builder().url(server.url("/1/validate-token")).build()).execute().close()
        val request = server.takeRequest()
        assertNull(request.headers["X-Server-Secret"])
        assertNull(request.headers["CF-Access-Client-Id"])
        assertEquals("Octo/test", request.headers["User-Agent"])
    }

    @Test
    fun theListenBrainzClientOnlyTalksToListenBrainzOverHttps() {
        val client = listenBrainzClient(OkHttpClient(), "Octo/test")
        try {
            client.newCall(Request.Builder().url(server.url("/1/submit-listens")).build()).execute().close()
            fail("A request went somewhere other than ListenBrainz")
        } catch (e: IOException) {
            assertEquals(0, server.requestCount)
        }
        assertEquals("https", LISTENBRAINZ_API.scheme)
        assertEquals("api.listenbrainz.org", LISTENBRAINZ_API.host)
    }
}
