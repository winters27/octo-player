package app.winters.octo.versions

import app.winters.octo.playback.TransitionProfiles
import app.winters.octo.subsonic.TransitionProfileAnswer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// A song whose version changed has its kept transition profile forgotten,
// and nothing else.
class StreamVersionProfilesTest {
    private fun answer(id: String): TransitionProfileAnswer {
        val text = javaClass.classLoader!!.getResource("transition-profile.json")!!.readText()
        return TransitionProfileAnswer.parse(text).copy(id = id, ready = true)
    }

    @Test
    fun aForgottenSongIsAskedForAgainAndTheOthersKept() = runTest {
        val asked = mutableListOf<String>()
        val profiles = TransitionProfiles({ id -> asked += id; answer(id) })
        assertNotNull(profiles.profile("X"))
        assertNotNull(profiles.profile("Y"))
        profiles.forget(listOf("X"))
        assertNull(profiles.cached("X"))
        assertNotNull(profiles.cached("Y"))
        assertNotNull(profiles.profile("X"))
        assertEquals(listOf("X", "Y", "X"), asked)
    }

    @Test
    fun aSongThatHadNoneIsAskedForAgainAtOnce() = runTest {
        val asked = mutableListOf<String>()
        val profiles = TransitionProfiles({ id -> asked += id; null }, now = { 0L })
        assertNull(profiles.profile("X"))
        assertNull(profiles.profile("X"))
        profiles.forget(listOf("X"))
        assertNull(profiles.profile("X"))
        assertEquals(listOf("X", "X"), asked)
    }

    // An answer asked for before a forget (or a clear) is never kept.
    @Test
    fun anAnswerInFlightDuringAForgetIsNotKept() = runTest {
        val gate = CompletableDeferred<Unit>()
        val profiles = TransitionProfiles({ id -> gate.await(); answer(id) })
        val first = async { profiles.profile("X") }
        runCurrent()
        profiles.forget(listOf("X"))
        gate.complete(Unit)
        assertNull(first.await())
        assertNull(profiles.cached("X"))
        assertNotNull(profiles.profile("X"))
        val second = CompletableDeferred<Unit>()
        val again = TransitionProfiles({ id -> second.await(); answer(id) })
        val waiting = async { again.profile("Y") }
        runCurrent()
        again.clear()
        second.complete(Unit)
        assertNull(waiting.await())
        assertNull(again.cached("Y"))
    }
}
