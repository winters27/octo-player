package app.winters.octo.ui.home

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.winters.octo.device.Access
import app.winters.octo.home.HOME_SECTIONS_KEY
import app.winters.octo.home.HomeSectionEntry
import app.winters.octo.home.encodeHomeSections
import app.winters.octo.home.homeSectionOrder
import app.winters.octo.server.CopyOutcome
import app.winters.octo.server.CopyRunState
import app.winters.octo.server.CopyWait
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

// What Home shows besides the resume card.
data class HomeLayout(
    // The quiet card asking for the music on the phone.
    val askAccess: Boolean,
    // The shelves, drawn from the whole library, phone and server together.
    val shelves: Boolean,
    // The note for a library with no songs at all.
    val empty: Boolean,
)

// Shelves whenever the library has songs, wherever they came from, so a
// listener with only a server still gets a full Home. The phone access card
// shows only while access is missing and they have not said "Not now".
// Nulls are values not read yet: nothing shows for them, so nothing flickers.
fun homeLayout(access: Access, accessDismissed: Boolean?, librarySongs: Int?): HomeLayout =
    HomeLayout(
        askAccess = access != Access.Granted && accessDismissed == false,
        shelves = librarySongs != null && librarySongs > 0,
        empty = librarySongs == 0,
    )

// The card in Home's one copy slot.
sealed interface HomeCopyCard {
    // A first copy of the server's music on its way, `run` null while none
    // runs yet (drawn still at its first step). `big` with no music of the
    // phone's own, where it fills Home; a banner above the shelves otherwise.
    data class Coming(val run: CopyRunState?, val server: String, val big: Boolean) : HomeCopyCard

    // The music arrived. `settling` while the steps after the copy still run.
    data class Here(val token: Long, val songs: Int, val albums: Int, val server: String, val settling: Boolean, val stepsProblem: String?) : HomeCopyCard

    // The first copy stopped before its music arrived.
    data class Stopped(val outcome: CopyOutcome.Failed, val big: Boolean) : HomeCopyCard
}

// Which copy card Home shows, if any, for the account in use (`account`,
// named `server`). A first copy of this account shows while its music is
// still to come, then the done card once it is in, unless put away; a
// first copy that stopped shows while the music is still to come; a
// finished one shows the done card; with music still to come and no copy
// running yet, the card is drawn still. A copy again never shows here.
// Nothing shows until whether music is to come has been read.
fun homeCopyCard(wait: CopyWait?, run: CopyRunState?, outcome: CopyOutcome?, account: String?, server: String): HomeCopyCard? {
    if (wait == null || account == null) return null
    val big = !wait.phoneMusic
    val first = run?.takeIf { it.account == account && it.arriving }
    if (first != null) {
        if (first.swapped || !wait.awaiting) {
            return if (first.dismissed) null else HomeCopyCard.Here(first.token, first.songs, first.albums, first.server, settling = true, stepsProblem = null)
        }
        return HomeCopyCard.Coming(first, first.server, big)
    }
    if (outcome?.account == account) {
        when (outcome) {
            is CopyOutcome.Failed -> if (wait.awaiting) return HomeCopyCard.Stopped(outcome, big)
            is CopyOutcome.Arrived -> return HomeCopyCard.Here(outcome.token, outcome.songs, outcome.albums, outcome.server, settling = false, outcome.stepsProblem)
        }
    }
    return if (wait.awaiting) HomeCopyCard.Coming(null, server, big) else null
}

// Leaving Home puts away the done card it showed: Home's pause, unless the
// screen is only turning.
class DoneCardSeen {
    private var shown: Long? = null

    fun shown(token: Long?) {
        shown = token
    }

    // Answers the done card's token to put away, if any.
    fun paused(turning: Boolean): Long? = if (turning) null else shown.also { shown = null }
}

private val Context.homePrefs by preferencesDataStore("home")
private val ACCESS_DISMISSED = booleanPreferencesKey("phone_access_dismissed")
private val HOME_SECTIONS = stringPreferencesKey(HOME_SECTIONS_KEY)

// What Home remembers between runs.
@Singleton
class HomePrefs @Inject constructor(@ApplicationContext private val context: Context) {
    // The listener said "Not now" to the phone access card.
    val accessDismissed: Flow<Boolean> =
        context.homePrefs.data.map { it[ACCESS_DISMISSED] ?: false }.distinctUntilChanged()

    suspend fun dismissAccess() {
        context.homePrefs.edit { it[ACCESS_DISMISSED] = true }
    }

    // Home's shelves in their order, each shown or hidden.
    val homeSections: Flow<List<HomeSectionEntry>> =
        context.homePrefs.data.map { homeSectionOrder(it[HOME_SECTIONS]) }.distinctUntilChanged()

    suspend fun setHomeSections(entries: List<HomeSectionEntry>) {
        context.homePrefs.edit { it[HOME_SECTIONS] = encodeHomeSections(entries) }
    }

    // Every shelf back in the default order and shown. Nothing is kept, so
    // the order follows the default should it change.
    suspend fun resetHomeSections() {
        context.homePrefs.edit { it.remove(HOME_SECTIONS) }
    }
}
