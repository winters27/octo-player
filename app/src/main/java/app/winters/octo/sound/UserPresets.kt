package app.winters.octo.sound

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.presetData by preferencesDataStore("sound_presets")

// A curve the listener saved under their own name.
@Serializable
private data class SavedPreset(val name: String, val gains: List<Float>)

// The listener's own presets, listed after the built-in ones. They are
// shared by every output.
@Singleton
class UserPresets @Inject constructor(@ApplicationContext private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(SavedPreset.serializer())

    val presets: StateFlow<List<EqPreset>> = context.presetData.data
        .map { p -> read(p[PRESETS]).map { EqPreset(it.name, it.gains) } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    // Saves a curve, replacing one saved before under the same name.
    fun save(name: String, gains: List<Float>) {
        val clean = name.trim()
        if (clean.isEmpty() || gains.size != GraphicBands.size) return
        scope.launch {
            context.presetData.edit { p ->
                val others = read(p[PRESETS]).filterNot { it.name.equals(clean, ignoreCase = true) }
                p[PRESETS] = json.encodeToString(listSerializer, others + SavedPreset(clean, gains))
            }
        }
    }

    fun delete(name: String) {
        scope.launch {
            context.presetData.edit { p ->
                p[PRESETS] = json.encodeToString(listSerializer, read(p[PRESETS]).filterNot { it.name == name })
            }
        }
    }

    // Every saved preset, read fresh, for a backup.
    suspend fun saved(): List<EqPreset> = read(context.presetData.data.first()[PRESETS]).map { EqPreset(it.name, it.gains) }

    // Adds presets from a backup, each replacing one of the same name.
    suspend fun restore(presets: List<EqPreset>) {
        val clean = presets.filter { it.name.isNotBlank() && it.gains.size == GraphicBands.size }
        if (clean.isEmpty()) return
        context.presetData.edit { p ->
            val names = clean.mapTo(HashSet()) { it.name.trim().lowercase() }
            val others = read(p[PRESETS]).filterNot { it.name.trim().lowercase() in names }
            p[PRESETS] = json.encodeToString(listSerializer, others + clean.map { SavedPreset(it.name.trim(), it.gains) })
        }
    }

    private fun read(stored: String?): List<SavedPreset> =
        stored?.let { runCatching { json.decodeFromString(listSerializer, it) }.getOrNull() }.orEmpty()
            .filter { it.gains.size == GraphicBands.size }

    private companion object {
        val PRESETS = stringPreferencesKey("presets")
    }
}
