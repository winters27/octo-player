package app.winters.octo.connection

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

// The sign-in page's session cookie lives with one configuration of the
// connection: it goes back while nothing about the sign-in changes, and any
// change, or signing out, starts with none.
@RunWith(RobolectricTestRunner::class)
class GatewayJarTest {
    private val server = MockWebServer()
    private val security = testSecurity()
    private lateinit var main: HttpUrl
    private lateinit var http: OkHttpClient

    private val signIn = ServerHeader("Authorization", "Basic YWxpY2U6YXBwLXBhc3M=")
    private val otherSignIn = ServerHeader("Authorization", "Basic Ym9iOmFwcC1wYXNz")

    @Before
    fun start() {
        main = server.startHome()
        http = security.install(OkHttpClient.Builder()).build()
    }

    @After
    fun stop() = server.close()

    private fun signInWith(header: ServerHeader, pins: Map<String, String> = emptyMap(), cert: String? = null) =
        security.configure(main, ConnectionSettings(headers = listOf(header), pins = pins, clientCertAlias = cert))

    private fun call(path: String = "/rest/ping") =
        http.newCall(Request.Builder().url(main.resolve(path)!!).build()).execute().close()

    // An answer that sets this cookie.
    private fun answerSetting(setCookie: String) {
        server.enqueue(MockResponse.Builder().addHeader("Set-Cookie", setCookie).body("{}").build())
        call()
        server.takeRequest()
    }

    // The Cookie header the next request carries, or null.
    private fun nextCookie(): String? {
        server.enqueue(MockResponse.Builder().body("{}").build())
        call()
        return server.takeRequest().headers["Cookie"]
    }

    // Red when configure always starts a new jar.
    @Test
    fun theSessionGoesBackWhileNothingAboutTheSignInChanged() {
        signInWith(signIn)
        answerSetting("authentik_proxy_s=one; Path=/")
        assertEquals("authentik_proxy_s=one", nextCookie())
        signInWith(signIn)
        assertEquals("authentik_proxy_s=one", nextCookie())
    }

    // Red when the jar is kept while the origins match but a header differs.
    @Test
    fun anotherHeaderValueStartsWithNoSession() {
        signInWith(signIn)
        answerSetting("authentik_proxy_s=one; Path=/")
        signInWith(otherSignIn)
        assertNull(nextCookie())
    }

    // Red when the snapshot's equality leaves out the pins.
    @Test
    fun anotherTrustedCertificateStartsWithNoSession() {
        signInWith(signIn)
        answerSetting("authentik_proxy_s=one; Path=/")
        signInWith(signIn, pins = mapOf("music.example.com" to "ab12cd34"))
        assertNull(nextCookie())
    }

    // Red when the snapshot's equality leaves out the client certificate.
    @Test
    fun anotherClientCertificateStartsWithNoSession() {
        signInWith(signIn)
        answerSetting("authentik_proxy_s=one; Path=/")
        signInWith(signIn, cert = "octo-phone")
        assertNull(nextCookie())
    }

    // Red when clear() leaves the jar to the next configuration.
    @Test
    fun signingOutStartsWithNoSession() {
        signInWith(signIn)
        answerSetting("authentik_proxy_s=one; Path=/")
        security.clear()
        signInWith(signIn)
        assertNull(nextCookie())
    }

    // Red when the jar keeps any cookie, not only the gateway names.
    @Test
    fun theServersOwnCookiesAreNeverSentBack() {
        signInWith(signIn)
        answerSetting("octo_admin=1; Path=/")
        assertNull(nextCookie())
    }

    // Red when the configuration is read when the answer comes (no request
    // tag): the late cookie would land in the new configuration's jar.
    @Test
    fun anAnswerThatStartedBeforeASwitchNeverReachesTheNewConfiguration() {
        signInWith(signIn)
        val arrived = CountDownLatch(1)
        val go = CountDownLatch(1)
        val cookies = CopyOnWriteArrayList<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.url.encodedPath == "/slow") {
                    arrived.countDown()
                    go.await(5, TimeUnit.SECONDS)
                    return MockResponse.Builder().addHeader("Set-Cookie", "authentik_proxy_s=late; Path=/").body("{}").build()
                }
                cookies += request.headers["Cookie"].orEmpty()
                return MockResponse.Builder().body("{}").build()
            }
        }
        val slow = thread { call("/slow") }
        assertTrue(arrived.await(5, TimeUnit.SECONDS))
        signInWith(otherSignIn)
        go.countDown()
        slow.join(5_000)
        call()
        // Back to the first sign-in is a new configuration too: the late
        // cookie went into a jar nobody reads.
        signInWith(signIn)
        call()
        assertEquals(listOf("", ""), cookies.toList())
    }
}
