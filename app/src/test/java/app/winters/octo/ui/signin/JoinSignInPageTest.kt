package app.winters.octo.ui.signin

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import app.winters.octo.connection.GatewayWords
import app.winters.octo.connection.ServerClients
import app.winters.octo.connection.startHome
import app.winters.octo.connection.testDevices
import app.winters.octo.data.installTestKeystore
import app.winters.octo.data.testSessions
import app.winters.octo.subsonic.FamilyHandOverLink
import app.winters.octo.subsonic.FamilyInviteLink
import app.winters.octo.subsonic.gateway.Gateway
import app.winters.octo.subsonic.gateway.GatewayKind
import app.winters.octo.subsonic.gateway.basicCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executors

// An invite's sign-up and a hand-over from another device, run through the
// sign-in view model itself, go to the family page with the form's sign-in
// page fields, and a page that still turns the app away is named. Neither
// gets as far as the switch.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class JoinSignInPageTest {
    private val server = MockWebServer()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var main: ExecutorCoroutineDispatcher
    private lateinit var address: HttpUrl
    private val appPassword = basicCredentials("alice", "app-pass")

    @Before
    fun start() {
        main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        Dispatchers.setMain(main)
        installTestKeystore()
        address = server.startHome()
        // Authentik in front of Octo, turning away every app: 401 and its page.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse.Builder().code(401)
                .addHeader("Content-Type", "text/html; charset=utf-8")
                .body("<html><body aria-label=\"authentik\"></body></html>").build()
        }
    }

    @After
    fun stop() {
        server.close()
        Dispatchers.resetMain()
        main.close()
    }

    // The view model as the app makes it. The switch is never reached on
    // these paths, so it is made without running its constructor: any call
    // into it fails the test.
    private fun form() = SignInViewModel(testSessions(), unbuilt(), ServerClients(context, testDevices()), testDevices())

    private inline fun <reified T> unbuilt(): T {
        val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, T::class.java) as T
    }

    // Waits for what the view model started to finish.
    private fun SignInViewModel.settle() = runBlocking { viewModelScope.coroutineContext.job.children.toList().joinAll() }

    private fun SignInViewModel.fillSignInPage() {
        pickGateway(GatewayKind.Password)
        gatewayFirst = "alice"
        gatewaySecond = "app-pass"
    }

    private val link: String get() = address.toString().removeSuffix("/")

    // Red when the invite's sign-up client is built without the form's
    // connection (ConnectionSettings() in its place).
    @Test
    fun anInviteSignsUpWithTheFormsSignInPageFields() {
        val vm = form()
        vm.startJoin(FamilyInviteLink(server = link, token = "invite-token"))
        vm.inviteName = "Alice"
        vm.invitePassword = "a long password"
        vm.inviteAgain = "a long password"
        vm.fillSignInPage()
        vm.join()
        vm.settle()
        assertEquals(appPassword, server.takeRequest().headers["Authorization"])
        assertEquals(GatewayWords.failure(Gateway.Authentik, true), vm.error)
        assertEquals(Gateway.Authentik, vm.gateway)
    }

    // A hand-over that meets the sign-in page opens its fields; filled in,
    // trying again sends them. Red when the hand-over's client is built
    // without the form's connection.
    @Test
    fun aHandOverTriedAgainSendsTheFormsSignInPageFields() {
        val vm = form()
        vm.startJoin(FamilyHandOverLink(base = link, token = "handover-token", key = "key", server = link))
        vm.settle()
        val first = List(server.requestCount) { server.takeRequest() }
        assertNull(first.first().headers["Authorization"])
        assertEquals(GatewayWords.failure(Gateway.Authentik, false), vm.error)
        assertTrue(vm.advancedOpen)
        vm.fillSignInPage()
        vm.receive()
        vm.settle()
        val again = List(server.requestCount - first.size) { server.takeRequest() }
        assertEquals(appPassword, again.first().headers["Authorization"])
        assertEquals(GatewayWords.failure(Gateway.Authentik, true), vm.error)
    }
}
