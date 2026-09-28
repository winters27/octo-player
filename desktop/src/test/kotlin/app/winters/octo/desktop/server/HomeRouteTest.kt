package app.winters.octo.desktop.server

import app.winters.octo.connection.Place
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class HomeRouteTest {
    private val main = "https://music.example.com/".toHttpUrl()
    private val home = "http://192.168.1.20:4533/".toHttpUrl()

    private fun waitFor(what: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!what() && System.currentTimeMillis() < deadline) Thread.sleep(10)
    }

    @Test
    fun theHomeAddressIsUsedWhileItAnswers() {
        HomeRoute(main, home, { true }).use { route ->
            waitFor { route.place == Place.Home }
            assertEquals(home, route.activeUrl())
        }
        HomeRoute(main, home, { false }).use { route ->
            Thread.sleep(200)
            assertEquals(Place.Away, route.place)
            assertEquals(main, route.activeUrl())
        }
    }

    @Test
    fun joiningAnotherNetworkChecksAgain() {
        val answering = AtomicBoolean(true)
        val network = AtomicReference(setOf("192.168.1.10"))
        HomeRoute(main, home, { answering.get() }, network = { network.get() }, watchEveryMs = 20).use { route ->
            waitFor { route.place == Place.Home }
            // Out of the house: the home address stops answering.
            answering.set(false)
            network.set(setOf("10.20.30.40"))
            waitFor { route.place == Place.Away }
            assertEquals(main, route.activeUrl())
        }
    }

    @Test
    fun aFailedRequestChecksAgainButNotInABurst() {
        val asks = AtomicInteger()
        HomeRoute(main, home, { asks.incrementAndGet(); true }, network = { emptySet() }, watchEveryMs = 60_000).use { route ->
            waitFor { asks.get() == 1 }
            repeat(20) { route.connectionFailed() }
            waitFor { asks.get() == 2 }
            Thread.sleep(200)
            assertEquals(2, asks.get())
        }
    }
}
