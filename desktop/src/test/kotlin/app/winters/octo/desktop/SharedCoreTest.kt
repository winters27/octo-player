package app.winters.octo.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// The window's readings, without a window: the shared core answers on the
// desktop JVM as it does on the phone.
class SharedCoreTest {
    @Test
    fun theSharedCoreAnswersOnTheDesktop() {
        val (identity, spring) = sharedCoreReadings()
        assertTrue(identity, identity.contains(" are Same "))
        val value = spring.substringAfterLast(": ").toDouble()
        assertTrue(spring, value > 0.0 && value < 100.0)
        assertEquals(2, sharedCoreReadings().size)
    }
}
