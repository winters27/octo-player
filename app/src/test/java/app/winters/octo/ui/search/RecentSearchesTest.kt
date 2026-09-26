package app.winters.octo.ui.search

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class RecentSearchesTest {
    @Test
    fun theNewestSearchComesFirst() {
        val recent = withRecent(withRecent(emptyList(), "drake"), "sza")
        assertEquals(listOf("sza", "drake"), recent)
    }

    @Test
    fun searchingAgainMovesItToTheTopOnce() {
        val recent = withRecent(listOf("sza", "drake", "adele"), "Drake")
        // Kept as typed the last time.
        assertEquals(listOf("Drake", "sza", "adele"), recent)
    }

    @Test
    fun onlyTheNewestTenAreKept() {
        val recent = (1..12).fold(emptyList<String>()) { list, n -> withRecent(list, "q$n") }
        assertEquals(RECENT_SEARCHES, recent.size)
        assertEquals("q12", recent.first())
        assertEquals("q3", recent.last())
    }

    @Test
    fun spacesAreTidiedAndBlankSearchesIgnored() {
        assertEquals(listOf("frank ocean"), withRecent(emptyList(), "  frank   ocean "))
        assertEquals(listOf("a b"), withRecent(listOf("a b"), "   "))
        assertEquals(listOf("frank ocean"), withRecent(listOf("frank ocean"), "frank  ocean"))
    }

    @Test
    fun oneCanBeForgotten() {
        assertEquals(listOf("sza", "adele"), withoutRecent(listOf("sza", "drake", "adele"), "DRAKE"))
    }

    @Test
    fun theyReadBackInTheSameOrder() {
        val recent = listOf("sza", "drake", "a  b")
        assertEquals(recent, decodeRecent(encodeRecent(recent)))
        assertEquals(emptyList<String>(), decodeRecent(null))
        assertEquals(emptyList<String>(), decodeRecent(""))
    }

    // A store held in memory, standing in for the phone's.
    private class MemoryStore : DataStore<Preferences> {
        private val current = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = current

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(current.value).also { current.value = it }
    }

    @Test
    fun theStoreAddsForgetsAndClears() = runBlocking {
        val store = RecentSearches(MemoryStore())
        store.add("drake")
        store.add("sza")
        store.add("drake")
        assertEquals(listOf("drake", "sza"), store.all.first())
        store.remove("sza")
        assertEquals(listOf("drake"), store.all.first())
        store.clear()
        assertEquals(emptyList<String>(), store.all.first())
    }
}
