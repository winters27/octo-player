package app.winters.octo.playlists

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.edit
import app.winters.octo.covers.PlaylistCoverStyle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Test

// The phone's Playlist covers setting: designed at first, the choice kept,
// and anything unknown read as designed.
class PlaylistArtSettingsTest {
    // The store's values in memory: the file-backed store cannot replace
    // its file on a Windows JVM, which the phone never is.
    private class MemoryPrefs : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        private val lock = Mutex()
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            lock.withLock { transform(state.value).also { state.value = it } }
    }

    @Test
    fun theChoiceIsKept() = runBlocking {
        val data = MemoryPrefs()
        val settings = PlaylistArtSettings(data)
        assertEquals(PlaylistCoverStyle.Designed, settings.style.first())
        settings.setStyle(PlaylistCoverStyle.Mosaic)
        // Read again, as after a restart.
        assertEquals(PlaylistCoverStyle.Mosaic, PlaylistArtSettings(data).style.first())
        settings.setStyle(PlaylistCoverStyle.Designed)
        assertEquals(PlaylistCoverStyle.Designed, PlaylistArtSettings(data).style.first())
    }

    @Test
    fun somethingUnknownReadsAsDesigned() = runBlocking {
        val data = MemoryPrefs()
        data.edit { it[stringPreferencesKey("playlist_covers")] = "Sparkles" }
        assertEquals(PlaylistCoverStyle.Designed, PlaylistArtSettings(data).style.first())
    }
}
