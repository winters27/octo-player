package app.winters.octo.lyrics

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

// Wrong lyrics are dealt with by the listener, one song at a time, in two
// ways. The lyrics menu can choose other lyrics: every copy the app can
// find for the song (the server's, the song file's, a .lrc file's, the
// online library's match and its search results, or a search typed by
// hand for a song whose tags are wrong), and the one picked is used for
// that song from then on, ahead of the usual order. Or it can hide the
// song's lyrics, for a song where no copy is right. Both are kept here, by
// song, and both can be undone from the same menu.

private val Context.lyricsChoiceData by preferencesDataStore("lyrics_choices")

private const val PICK_PREFIX = "pick:"
private const val HIDDEN_PREFIX = "hidden:"

private fun pickKey(trackId: String) = stringPreferencesKey("$PICK_PREFIX$trackId")
private fun hiddenKey(trackId: String) = booleanPreferencesKey("$HIDDEN_PREFIX$trackId")

// Lyrics the listener picked for a song: one of the song's own sources,
// one copy in the online library, by its number, or a choice the server
// keeps for every app (see ServerChoices.kt). This phone never keeps a
// server choice as its own pick: the server keeps it.
sealed interface LyricsPick {
    data class Own(val source: LyricsSource) : LyricsPick
    data class Online(val id: Long) : LyricsPick

    // "auto", or the id of one copy the server holds ("kugou:123").
    data class OnServer(val choice: String) : LyricsPick
}

// How a pick is written: "own:Server", "online:12345" or "server:auto".
fun LyricsPick.encoded(): String = when (this) {
    is LyricsPick.Own -> "own:${source.name}"
    is LyricsPick.Online -> "online:$id"
    is LyricsPick.OnServer -> "server:$choice"
}

// A kept pick, or null for one this app cannot read.
fun decodePick(text: String?): LyricsPick? {
    val kind = text?.substringBefore(':') ?: return null
    val value = text.substringAfter(':', "")
    return when (kind) {
        "own" -> LyricsSource.entries.firstOrNull { it.name == value && it != LyricsSource.Online }?.let(LyricsPick::Own)
        "online" -> value.toLongOrNull()?.let(LyricsPick::Online)
        "server" -> value.takeIf(String::isNotEmpty)?.let(LyricsPick::OnServer)
        else -> null
    }
}

// Which pick these lyrics are, or null when that cannot be told: online
// lyrics saved before the library's number was kept.
fun pickOf(lyrics: Lyrics): LyricsPick? = when (lyrics.source) {
    LyricsSource.Online -> lyrics.onlineId?.let(LyricsPick::Online)
    else -> LyricsPick.Own(lyrics.source)
}

// What the listener said about one song's lyrics: the ones picked, if
// any, and whether they are hidden.
data class LyricsChoice(val pick: LyricsPick? = null, val hidden: Boolean = false)

// Each song's lyrics choice, kept only for songs the listener changed.
@Singleton
class LyricsChoices internal constructor(private val store: DataStore<Preferences>) {
    @Inject constructor(@ApplicationContext context: Context) : this(context.lyricsChoiceData)

    fun choiceFor(trackId: String): Flow<LyricsChoice> =
        store.data.map { read(it, trackId) }.distinctUntilChanged()

    suspend fun current(trackId: String): LyricsChoice = read(store.data.first(), trackId)

    // Uses these lyrics for the song from now on. Picking lyrics for a
    // hidden song shows it again.
    suspend fun pick(trackId: String, pick: LyricsPick) {
        store.edit {
            it[pickKey(trackId)] = pick.encoded()
            it.remove(hiddenKey(trackId))
        }
    }

    // The song shows no lyrics until they are shown again. The pick, if
    // any, is kept for then.
    suspend fun hide(trackId: String) {
        store.edit { it[hiddenKey(trackId)] = true }
    }

    suspend fun show(trackId: String) {
        store.edit { it.remove(hiddenKey(trackId)) }
    }

    // Forgets what this phone said about the song, so the server's choice
    // stands alone. `hidden` keeps the song hidden here, as the server now
    // has it.
    suspend fun clear(trackId: String, hidden: Boolean = false) {
        store.edit {
            it.remove(pickKey(trackId))
            if (hidden) it[hiddenKey(trackId)] = true else it.remove(hiddenKey(trackId))
        }
    }

    private fun read(prefs: Preferences, trackId: String) =
        LyricsChoice(decodePick(prefs[pickKey(trackId)]), prefs[hiddenKey(trackId)] == true)
}
