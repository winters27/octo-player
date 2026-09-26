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

// Leaves out files in folders someone switched off.
fun List<DeviceFile>.withoutFolders(excluded: Set<String>): List<DeviceFile> =
    if (excluded.isEmpty()) this else filterNot { topFolder(it.folder) in excluded }

// Lists every folder music was found in, including switched-off ones, so
// they can be switched back on.
fun List<DeviceFile>.folders(excluded: Set<String>): List<MusicFolder> =
    groupingBy { topFolder(it.folder) }.eachCount()
        .map { (name, count) -> MusicFolder(name, count, name !in excluded) }
        .sortedByDescending { it.songs }

// A file's path from the top of its storage, like "Music/Kavinsky/01.mp3".
fun relativeFile(folder: String?, fileName: String): String =
    listOfNotNull(folder?.trim('/')?.takeIf(String::isNotEmpty), fileName).joinToString("/")

// The phone's main storage, as the media library names it.
private const val MAIN_STORAGE = "external_primary"

// Where each file sits, as folder names, by media id. The storage it is on
// leads the path only when music is on more than one, so a phone with no
// SD card starts at "Music" rather than "Internal storage".
fun List<DeviceFile>.folderPaths(): Map<Long, List<String>> {
    val volumes = mapTo(HashSet()) { it.volume ?: MAIN_STORAGE }
    val cards = volumes - MAIN_STORAGE
    return associate { file ->
        val names = pathNames(file.folder)
        file.id to if (volumes.size > 1) listOf(storageName(file.volume, cards.size)) + names else names
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
@Singleton
class FolderRules @Inject constructor(@ApplicationContext private val context: Context) {
    val excluded: Flow<Set<String>> = context.libraryPrefs.data.map { it[EXCLUDED] ?: emptySet() }

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
