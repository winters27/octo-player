package app.winters.octo.desktop.livelists

import app.winters.octo.desktop.queue.replace
import app.winters.octo.livelists.LiveList
import app.winters.octo.livelists.LiveListsJson
import app.winters.octo.livelists.duplicating
import app.winters.octo.livelists.removing
import app.winters.octo.livelists.saving
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

// The signed-in account's live lists, kept in its folder beside its plays
// and queue as live-lists.json. Changes show at once and are written a
// moment later, one write at a time, each writing the lists as they are
// then. With no file (signed out, or the screenshot tests) the lists live
// only while the app runs.
@OptIn(ExperimentalCoroutinesApi::class)
class LiveListStore(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _lists = MutableStateFlow<List<LiveList>>(emptyList())
    val lists: StateFlow<List<LiveList>> = _lists

    @Volatile private var file: File? = null

    // Reads an account's lists from `file`, or starts empty with none. The
    // file is a few kilobytes, so it is read straight away.
    fun open(file: File?) {
        this.file = file
        _lists.value = file?.takeIf(File::exists)?.let { runCatching { LiveListsJson.decode(it.readText()) }.getOrNull() }.orEmpty()
    }

    fun byId(id: String): LiveList? = _lists.value.firstOrNull { it.id == id }

    // Saves a new list or a changed one, stamped as changed now.
    fun save(list: LiveList): LiveList {
        val stamped = list.copy(name = list.name.trim(), changed = clock(), created = list.created.takeIf { it != 0L } ?: clock())
        change { it.saving(stamped) }
        return stamped
    }

    fun remove(id: String) = change { it.removing(id) }

    fun rename(id: String, name: String) {
        val list = byId(id) ?: return
        if (name.isBlank() || name.trim() == list.name) return
        save(list.copy(name = name))
    }

    // A copy just after the list, named "<name> (copy)".
    fun duplicate(list: LiveList): LiveList {
        var made: LiveList? = null
        change { lists -> lists.duplicating(list, clock()).also { made = it.second }.first }
        return made!!
    }

    // Writes the lists now, on the caller's thread, for quitting.
    fun saveNow() {
        runCatching { write() }
    }

    private fun change(edit: (List<LiveList>) -> List<LiveList>) {
        _lists.value = edit(_lists.value)
        if (file != null) scope.launch(io) { runCatching { write() } }
    }

    @Synchronized
    private fun write() {
        val target = file ?: return
        replace(target, LiveListsJson.encode(_lists.value))
    }

    companion object {
        const val FILE_NAME = "live-lists.json"
    }
}
