package app.winters.octo.playback

import app.winters.octo.server.RemoteQueue
import app.winters.octo.server.ResumeOffer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarInputsTest {
    @Test
    fun eachKindIsHeardWhenItsValueChangesAndNeverForItsFirst() = runTest {
        val flows = CarInput.entries.associateWith { MutableStateFlow(0) }
        val heard = mutableListOf<CarInput>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { carInputs(flows.toList()).collect { heard += it } }
        assertTrue(heard.isEmpty())
        CarInput.entries.forEach { flows.getValue(it).value = 1 }
        assertEquals(CarInput.entries, heard)
        // The same value again is no change.
        flows.getValue(CarInput.Sorts).value = 1
        assertEquals(CarInput.entries.size, heard.size)
    }

    @Test
    fun aCopyOfTheLibraryEndingAloneIsALibraryChange() = runTest {
        val ready = MutableStateFlow(true)
        val lastCopy = MutableStateFlow<Any?>(1L)
        val songs = MutableStateFlow(10)
        val heard = mutableListOf<CarInput>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            carInputs(listOf(CarInput.Library to carLibraryInput(ready, lastCopy, songs))).collect { heard += it }
        }
        lastCopy.value = 2L
        assertEquals(listOf(CarInput.Library), heard)
        songs.value = 11
        assertEquals(listOf(CarInput.Library, CarInput.Library), heard)
    }

    @Test
    fun anOfferCheckedAgainAndFoundTheSameIsNoChange() {
        fun offer(at: Long?) = ResumeOffer("Desk", "Song", "Artist", RemoteQueue(emptyList(), 0, 0, at, null))
        assertEquals(resumeOfferKey(offer(5)), resumeOfferKey(offer(5)))
        assertNotEquals(resumeOfferKey(offer(5)), resumeOfferKey(offer(6)))
        assertNull(resumeOfferKey(null))
    }
}
