package app.winters.octo.connection

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.winters.octo.subsonic.DeviceIdentity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

// Its own store, kept out of the backup: a backup restored onto another
// phone must not bring this phone's id with it.
private val Context.devicePrefs by preferencesDataStore("device")

private val DEVICE_ID = stringPreferencesKey("id")

// This install as its own server knows it: an id made once and kept, and
// the phone's name. Read on the network's threads, the first time a request
// needs it, and kept from then on.
@Singleton
class DeviceIds internal constructor(
    private val store: DataStore<Preferences>,
    private val name: () -> String,
) {
    @Inject constructor(@ApplicationContext context: Context) : this(context.devicePrefs, { phoneName(context) })

    @Volatile private var known: DeviceIdentity? = null

    // Never on the main thread: the first call reads the store.
    fun current(): DeviceIdentity = known ?: synchronized(this) {
        known ?: DeviceIdentity(runBlocking { id() }, name()).also { known = it }
    }

    // The id, made and kept the first time it is asked for. A store that
    // cannot be written still gives an id for this run.
    internal suspend fun id(): String {
        store.data.first()[DEVICE_ID]?.let { return it }
        val made = UUID.randomUUID().toString()
        return try {
            var kept = made
            store.edit { prefs -> kept = prefs[DEVICE_ID] ?: made.also { prefs[DEVICE_ID] = it } }
            kept
        } catch (e: IOException) {
            Log.w("Octo", "device id not saved", e)
            made
        }
    }
}

// The name the owner gave the phone in its settings, or its model.
private fun phoneName(context: Context): String =
    runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()
        ?.takeIf(String::isNotBlank)
        ?: modelName(Build.MANUFACTURER, Build.MODEL)

// "Google Pixel 9" for a model that does not start with its maker's name.
internal fun modelName(manufacturer: String?, model: String?): String {
    val maker = manufacturer.orEmpty().trim().replaceFirstChar(Char::titlecase)
    val kind = model.orEmpty().trim()
    return when {
        kind.isEmpty() -> maker.ifEmpty { "Android phone" }
        maker.isEmpty() || kind.startsWith(maker, ignoreCase = true) -> kind
        else -> "$maker $kind"
    }
}
