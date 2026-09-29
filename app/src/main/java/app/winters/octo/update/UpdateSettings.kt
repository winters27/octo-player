package app.winters.octo.update

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.updateData by preferencesDataStore("updates")

private val CHECK_AUTOMATICALLY = booleanPreferencesKey("check_automatically")
private val INSTALL = stringPreferencesKey("install")
private val EARLY_VERSIONS = booleanPreferencesKey("early_versions")

// The updater's choices on the phone, the same three as on the desktop.
@Singleton
class UpdateSettings internal constructor(private val store: DataStore<Preferences>) {
    @Inject constructor(@ApplicationContext context: Context) : this(context.updateData)

    val prefs: Flow<UpdatePrefs> = store.data
        .map { stored ->
            UpdatePrefs(
                checkAutomatically = stored[CHECK_AUTOMATICALLY] ?: true,
                install = InstallWhen.entries.firstOrNull { it.name == stored[INSTALL] } ?: InstallWhen.Ask,
                earlyVersions = stored[EARLY_VERSIONS] ?: false,
            )
        }
        .distinctUntilChanged()

    suspend fun setCheckAutomatically(on: Boolean) = change { it[CHECK_AUTOMATICALLY] = on }

    suspend fun setInstall(choice: InstallWhen) = change { it[INSTALL] = choice.name }

    suspend fun setEarlyVersions(on: Boolean) = change { it[EARLY_VERSIONS] = on }

    private suspend fun change(update: (MutablePreferences) -> Unit) {
        try {
            store.edit { update(it) }
        } catch (e: IOException) {
            Log.w("Octo", "update settings not saved", e)
        }
    }
}
