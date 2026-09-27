package app.winters.octo.discovery

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// The line saying what the plus does: shown a few times, and no more once
// a song has been added.
class AddHintTest {
    // A store held in memory, standing in for the phone's.
    private class MemoryStore : DataStore<Preferences> {
        private val current = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = current

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(current.value).also { current.value = it }
    }

    @Test
    fun itShowsTheFirstFiveTimes() {
        for (shown in 0 until ADD_HINT_SHOWINGS) assertTrue(showsAddHint(AddHintState(shown), countedThisVisit = false))
        assertFalse(showsAddHint(AddHintState(5), countedThisVisit = false))
        assertFalse(showsAddHint(AddHintState(9), countedThisVisit = false))
    }

    @Test
    fun theFifthShowingStaysForTheRestOfItsVisit() {
        assertTrue(showsAddHint(AddHintState(5), countedThisVisit = true))
    }

    @Test
    fun itStopsOnceASongIsAdded() {
        assertFalse(showsAddHint(AddHintState(0, added = true), countedThisVisit = false))
        // Even on the visit that counted it.
        assertFalse(showsAddHint(AddHintState(1, added = true), countedThisVisit = true))
    }

    @Test
    fun showingsAreCountedAndKept() = runBlocking {
        val store = MemoryStore()
        val hint = AddHint(store)
        assertEquals(AddHintState(0, added = false), hint.state.first())
        repeat(3) { hint.shown() }
        // The same store read again, as after the app restarts.
        assertEquals(AddHintState(3, added = false), AddHint(store).state.first())
        repeat(2) { hint.shown() }
        assertFalse(showsAddHint(AddHint(store).state.first(), countedThisVisit = false))
    }

    @Test
    fun anAddIsKept() = runBlocking {
        val store = MemoryStore()
        AddHint(store).added()
        val later = AddHint(store).state.first()
        assertTrue(later.added)
        assertEquals(0, later.shown)
        assertFalse(showsAddHint(later, countedThisVisit = false))
    }
}
