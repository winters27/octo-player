package app.winters.octo.family

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.winters.octo.subsonic.FamilyRequest
import app.winters.octo.ui.family.NoticeMemory
import app.winters.octo.ui.family.alreadyTold
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.familyNoticePrefs by preferencesDataStore("family_notices")

// What each account has been told about its family requests, so each
// decision is told once, kept per account (the kept server's id).
@Singleton
class FamilyNoticeStore internal constructor(private val store: DataStore<Preferences>) {
    @Inject constructor(@ApplicationContext context: Context) : this(context.familyNoticePrefs)

    private val json = Json { ignoreUnknownKeys = true }

    private fun key(account: String) = stringPreferencesKey("told@$account")

    suspend fun read(account: String): NoticeMemory =
        store.data.first()[key(account)]?.let { runCatching { json.decodeFromString(NoticeMemory.serializer(), it) }.getOrNull() } ?: NoticeMemory()

    suspend fun write(account: String, memory: NoticeMemory) {
        try {
            store.edit { it[key(account)] = json.encodeToString(NoticeMemory.serializer(), memory) }
        } catch (e: IOException) {
            Log.w("Octo", "family notices not saved", e)
        }
    }

    // A request whose answer the request sheet already showed.
    suspend fun alreadyTold(account: String, request: FamilyRequest) {
        if (account.isEmpty()) return
        write(account, read(account).alreadyTold(request))
    }
}
