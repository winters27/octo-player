package app.winters.octo.player

import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

// The player's settings in memory, starting with `start`: a file-backed
// store cannot replace its file on a Windows JVM, which the phone never is.
class MemoryPrefs(start: Preferences = mutablePreferencesOf()) : DataStore<Preferences> {
    private val state = MutableStateFlow(start)
    private val lock = Mutex()
    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
        lock.withLock { transform(state.value).also { state.value = it } }
}

// Keys by name as an older app stored them: true and false, whole numbers
// and names.
fun storedPrefs(keys: Map<String, Any>): Preferences = mutablePreferencesOf().apply { keys.forEach { (name, value) -> put(name, value) } }

private fun MutablePreferences.put(name: String, value: Any) {
    when (value) {
        is Boolean -> this[booleanPreferencesKey(name)] = value
        is Int -> this[intPreferencesKey(name)] = value
        is String -> this[stringPreferencesKey(name)] = value
        else -> error("$name: $value")
    }
}

// Every key the store holds, by name.
suspend fun DataStore<Preferences>.keys(): Map<String, Any> = data.first().asMap().mapKeys { it.key.name }

// The player's settings file on a phone that ran an older app, which left
// `old` in it: the first read runs the app's own migrations over them, as
// the phone's first start of this app does. The file is written once, at
// that read, as on the phone.
fun phoneThatRanAnOlderApp(dir: File, scope: CoroutineScope, old: Map<String, Any>): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(
        migrations = listOf(LeftByAnOlderApp(old)) + playerMigrations,
        scope = scope,
        produceFile = { File(dir, "player.preferences_pb") },
    )

// Puts back what an older app left in a settings file, before this app's
// own migrations see it.
private class LeftByAnOlderApp(private val old: Map<String, Any>) : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences) = currentData.asMap().isEmpty() && old.isNotEmpty()

    override suspend fun migrate(currentData: Preferences): Preferences =
        currentData.toMutablePreferences().apply { old.forEach { (name, value) -> put(name, value) } }.toPreferences()

    override suspend fun cleanUp() = Unit
}

// What 1.12 and older apps stored for an install that never touched
// Crossfade but wrote every key, as a backup restore does.
val OLD_DEFAULTS: Map<String, Any> = mapOf(
    "crossfade" to false,
    "crossfade_seconds" to 8,
    "smart_transitions" to true,
    "filter_sweeps" to true,
    "match_tempo" to false,
    "crossfade_curve" to "EqualPower",
)
