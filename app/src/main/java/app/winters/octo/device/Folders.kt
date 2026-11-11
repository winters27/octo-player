package app.winters.octo.device

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.winters.octo.folders.pathNames
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

// A folder music comes from, with how many songs are in it.
data class MusicFolder(val name: String, val songs: Int, val included: Boolean)

// Folders are grouped by their top level, like "Music" or "Download", so a
// big library of artist and album folders still shows as a short list.
fun topFolder(relativePath: String?): String =
    relativePath?.trim('/')?.substringBefore('/').orEmpty()

// Whether a file is in a folder someone switched off, so it is left out.
fun switchedOff(folder: String?, excluded: Set<String>): Boolean = excluded.isNotEmpty() && topFolder(folder) in excluded

// Counts the files of a scan by top folder as they are listed, so every
// folder music was found in can be shown, switched-off ones included, so
// they can be switched back on. Holds one count per folder.
class FolderCounter {
    private val counts = LinkedHashMap<String, Int>()

    fun add(folder: String?) {
        val top = topFolder(folder)
        counts[top] = (counts[top] ?: 0) + 1
    }

    // The folders, most songs first; folders with as many songs keep the
    // order they were first seen in.
    fun folders(excluded: Set<String>): List<MusicFolder> =
        counts.map { (name, count) -> MusicFolder(name, count, name !in excluded) }.sortedByDescending { it.songs }
}

// A file's path from the top of its storage, like "Music/Kavinsky/01.mp3".
fun relativeFile(folder: String?, fileName: String): String =
    listOfNotNull(folder?.trim('/')?.takeIf(String::isNotEmpty), fileName).joinToString("/")

// The phone's main storage, as the media library names it.
private const val MAIN_STORAGE = "external_primary"

// Where a file sits, as folder names, given every storage the phone's music
// is on (`volumes`, null for the main one). The storage leads the path only
// when music is on more than one, so a phone with no SD card starts at
// "Music" rather than "Internal storage".
class PhonePaths(volumes: Collection<String?>) {
    private val all = volumes.mapTo(HashSet()) { it ?: MAIN_STORAGE }
    private val cards = (all - MAIN_STORAGE).size

    fun of(folder: String?, volume: String?): List<String> {
        val names = pathNames(folder)
        return if (all.size > 1) listOf(storageName(volume, cards)) + names else names
    }
}

// What to call a storage: the phone's own, or an SD card, told apart by its
// id when there is more than one card.
fun storageName(volume: String?, cardCount: Int): String = when {
    volume == null || volume == MAIN_STORAGE -> "Internal storage"
    cardCount <= 1 -> "SD card"
    else -> "SD card ${volume.uppercase()}"
}

private val Context.libraryPrefs by preferencesDataStore("library")
private val EXCLUDED = stringSetPreferencesKey("excluded_folders")

// Which folders are left out of the library. New folders are always in.
// Tests give their own folders in place of the kept ones.
@Singleton
open class FolderRules @Inject constructor(@ApplicationContext private val context: Context) {
    open val excluded: Flow<Set<String>> = context.libraryPrefs.data.map { it[EXCLUDED] ?: emptySet() }

    suspend fun setIncluded(folder: String, included: Boolean) {
        context.libraryPrefs.edit { prefs ->
            val now = prefs[EXCLUDED] ?: emptySet()
            prefs[EXCLUDED] = if (included) now - folder else now + folder
        }
    }

    // Replaces the whole list, as when restoring a backup.
    suspend fun setExcluded(folders: Set<String>) {
        context.libraryPrefs.edit { it[EXCLUDED] = folders }
    }
}
