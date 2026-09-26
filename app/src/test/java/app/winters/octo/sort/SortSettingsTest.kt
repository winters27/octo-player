package app.winters.octo.sort

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesFileSerializer
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class SortSettingsTest {
    // The bytes of the settings file, written in the phone's own preferences
    // file format. (A real file store cannot be used here: on Windows the
    // JVM file store fails to replace a file that already exists.)
    private class SavedFile {
        var bytes: ByteArray? = null
    }

    // A store over the saved file: it reads the file when opened and writes
    // every change back in full, as the phone's store does.
    private class FileBackedStore(private val file: SavedFile, start: Preferences) : DataStore<Preferences> {
        private val current = MutableStateFlow(start)
        private val writing = Mutex()
        override val data: Flow<Preferences> = current

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = writing.withLock {
            val next = transform(current.value)
            val out = ByteArrayOutputStream()
            PreferencesFileSerializer.writeTo(next, out)
            file.bytes = out.toByteArray()
            current.value = next
            next
        }
    }

    // Opening the store again on the same file stands in for the app starting again.
    private suspend fun open(file: SavedFile): SortSettings = SortSettings(store(file))

    private suspend fun store(file: SavedFile): DataStore<Preferences> {
        val saved = file.bytes
        val start = if (saved == null) PreferencesFileSerializer.defaultValue else PreferencesFileSerializer.readFrom(ByteArrayInputStream(saved))
        return FileBackedStore(file, start)
    }

    @Test
    fun aFreshInstallGetsEachListsDefault() = runBlocking {
        val settings = open(SavedFile())
        SortList.entries.forEach { list -> assertEquals(list.name, list.default, settings.order(list).first()) }
    }

    @Test
    fun eachListKeepsItsOwnOrderAcrossARestart() = runBlocking {
        val file = SavedFile()
        val first = open(file)
        first.set(SortList.Songs, SortOrder(SongSort.RecentlyAdded, descending = true))
        first.set(SortList.Albums, SortOrder(AlbumSort.Year, descending = false))
        first.set(SortList.Liked, SortOrder(SongSort.Artist, descending = true))
        first.set(SortList.FolderSongs, SortOrder(SongSort.FolderOrder, descending = true))

        val again = open(file)
        assertEquals(SortOrder(SongSort.RecentlyAdded, descending = true), again.order(SortList.Songs).first())
        assertEquals(SortOrder(AlbumSort.Year, descending = false), again.order(SortList.Albums).first())
        assertEquals(SortOrder(SongSort.Artist, descending = true), again.order(SortList.Liked).first())
        assertEquals(SortOrder(SongSort.FolderOrder, descending = true), again.order(SortList.FolderSongs).first())
        // Lists nobody changed are still at their defaults.
        assertEquals(SortList.GenreSongs.default, again.order(SortList.GenreSongs).first())
        assertEquals(SortList.Artists.default, again.order(SortList.Artists).first())
    }

    @Test
    fun everyListAndOrderSurvivesARestart() = runBlocking {
        SortList.entries.forEach { list ->
            list.options.forEach { option ->
                listOf(false, true).forEach { descending ->
                    val file = SavedFile()
                    open(file).set(list, SortOrder(option, descending))
                    assertEquals("${list.name} ${option.id}", SortOrder(option, descending), open(file).order(list).first())
                }
            }
        }
    }

    @Test
    fun theLastChoiceWins() = runBlocking {
        val file = SavedFile()
        val settings = open(file)
        settings.set(SortList.Artists, SortOrder(ArtistSort.SongCount, descending = true))
        settings.set(SortList.Artists, SortOrder(ArtistSort.SongCount, descending = false))
        assertEquals(SortOrder(ArtistSort.SongCount, descending = false), settings.order(SortList.Artists).first())
        assertEquals(SortOrder(ArtistSort.SongCount, descending = false), open(file).order(SortList.Artists).first())
    }

    @Test
    fun somethingUnreadableSavedReadsAsTheDefault() = runBlocking {
        val file = SavedFile()
        store(file).edit { it[stringPreferencesKey(SortList.Songs.key)] = "Shuffle:sideways" }
        assertEquals(SortList.Songs.default, open(file).order(SortList.Songs).first())
    }
}
