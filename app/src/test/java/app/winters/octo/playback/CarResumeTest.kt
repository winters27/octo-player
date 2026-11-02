package app.winters.octo.playback

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class CarResumeTest {
    private var checks = 0
    private val network = MutableSharedFlow<Unit>()

    private fun TestScope.resume() = CarResume({ checks++ }, network, backgroundScope)

    @Test
    fun nothingIsLookedForUntilACarConnects() = runTest {
        val resume = resume()
        runCurrent()
        network.emit(Unit)
        resume.homeOpened()
        runCurrent()
        assertEquals(0, checks)
    }

    @Test
    fun aCarConnectingOpeningHomeOrANetworkChangeEachLookAgain() = runTest {
        val resume = resume()
        runCurrent()
        resume.carConnected()
        runCurrent()
        assertEquals(1, checks)
        resume.homeOpened()
        runCurrent()
        assertEquals(2, checks)
        network.emit(Unit)
        runCurrent()
        assertEquals(3, checks)
    }
}
