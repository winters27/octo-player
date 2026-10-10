package app.winters.octo.connection

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

// The phone's id is made once and kept; its name reads like the phone.
class DeviceIdsTest {
    private class MemoryStore : DataStore<Preferences> {
        private val current = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = current

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(current.value).also { current.value = it }
    }

    @Test
    fun theIdIsMadeOnceAndKept() {
        val store = MemoryStore()
        val first = DeviceIds(store) { "Pixel 9" }.current()
        assertEquals(36, first.id.length)
        assertEquals("Pixel 9", first.name)
        // Another start of the app reads the same id.
        assertEquals(first.id, DeviceIds(store) { "Pixel 9" }.current().id)
        // Another install makes its own.
        assertNotEquals(first.id, DeviceIds(MemoryStore()) { "Pixel 9" }.current().id)
    }

    @Test
    fun itIsReadOnceAndKeptInMemory() {
        val ids = DeviceIds(MemoryStore()) { "Pixel 9" }
        assertSame(ids.current(), ids.current())
    }

    @Test
    fun aModelNameReadsLikeThePhone() {
        assertEquals("Google Pixel 9", modelName("Google", "Pixel 9"))
        assertEquals("OnePlus CPH2841", modelName("OnePlus", "CPH2841"))
        assertEquals("Samsung SM-S928B", modelName("samsung", "SM-S928B"))
        assertEquals("Pixel 9", modelName("", "Pixel 9"))
        assertEquals("Nothing Phone (2)", modelName("Nothing", "Nothing Phone (2)"))
        assertEquals("Android phone", modelName(null, null))
    }
}
