package app.winters.octo.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayGuardTest {
    private var replacements = 0L
    private var account: String? = "a1"
    private val guard = PlayGuard({ replacements }, { account })

    @Test
    fun aPlayStillWantedWhenNothingChanged() {
        assertTrue(guard.stillWanted(guard.mark()))
    }

    @Test
    fun anEarlierChoiceThatLandsLastIsDropped() = runTest {
        val serverAnswersA = CompletableDeferred<Unit>()
        val applied = mutableListOf<String>()
        // A is chosen first and waits on the server.
        val a = async(start = CoroutineStart.UNDISPATCHED) {
            val mark = guard.mark()
            serverAnswersA.await()
            if (guard.stillWanted(mark)) applied += "A"
        }
        // B is chosen and set while A waits on the server.
        val markB = guard.mark()
        if (guard.stillWanted(markB)) {
            applied += "B"
            replacements++
        }
        serverAnswersA.complete(Unit)
        a.await()
        assertEquals(listOf("B"), applied)
    }

    @Test
    fun anotherAccountDropsAPlay() {
        val mark = guard.mark()
        account = "a2"
        assertFalse(guard.stillWanted(mark))
    }

    @Test
    fun closeDropsAPlay() {
        val mark = guard.mark()
        replacements++
        assertFalse(guard.stillWanted(mark))
    }
}
