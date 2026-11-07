package app.winters.octo.desktop.server

import app.winters.octo.connection.Place
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    private class FakeChanges : AddressChanges {
        @Volatile var onChange: (() -> Unit)? = null
        val closed = AtomicBoolean(false)

        override fun watch(onChange: () -> Unit): AutoCloseable {
            this.onChange = onChange
            return AutoCloseable { closed.set(true) }
        }
    }

    @Test
    fun anAddressChangeNoticeChecksAgainWithoutWaitingForALook() {
        val answers = AtomicInteger()
        val network = AtomicReference(setOf("192.168.1.10"))
        val changes = FakeChanges()
        HomeRoute(main, home, { answers.incrementAndGet(); true }, network = { network.get() }, changes = changes, watchEveryMs = 60_000).use {
            waitFor { answers.get() == 1 && changes.onChange != null }
            network.set(setOf("10.20.30.40"))
            changes.onChange!!.invoke()
            waitFor { answers.get() == 2 }
            assertEquals(2, answers.get())
        }
    }

    @Test
    fun closingStopsListeningForAddressChanges() {
        val changes = FakeChanges()
        HomeRoute(main, home, { true }, network = { emptySet() }, changes = changes, watchEveryMs = 60_000).close()
        assertEquals(true, changes.closed.get())
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

    // Each move between the addresses is heard once, and a check that
    // stays put is not a move. Red when `moved` is never called, or called
    // on every check.
    @Test
    fun eachMoveIsHeardOnce() {
        val answering = AtomicBoolean(true)
        val network = AtomicReference(setOf("192.168.1.10"))
        val moves = AtomicInteger()
        val checks = AtomicInteger()
        HomeRoute(main, home, { checks.incrementAndGet(); answering.get() }, network = { network.get() }, watchEveryMs = 20, moved = { moves.incrementAndGet() }).use { route ->
            waitFor { moves.get() == 1 }
            assertEquals(Place.Home, route.place)
            val before = checks.get()
            route.recheck()
            waitFor { checks.get() > before }
            assertEquals(1, moves.get())
            answering.set(false)
            network.set(setOf("10.20.30.40"))
            waitFor { moves.get() == 2 }
            assertEquals(Place.Away, route.place)
        }
    }

    // The client the route's checks go through is closed with it. Red when
    // close leaves it open.
    @Test
    fun closingClosesTheClientItsChecksUse() {
        val closed = AtomicBoolean(false)
        HomeRoute(main, home, { true }, network = { emptySet() }, changes = FakeChanges(), watchEveryMs = 60_000, closing = AutoCloseable { closed.set(true) }).close()
        assertTrue(closed.get())
    }
}
