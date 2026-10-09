package app.winters.octo.family

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import app.winters.octo.subsonic.FamilyJoinLink
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.winters.octo.catalog.FIND_PREFIX
import app.winters.octo.catalog.isFind
import app.winters.octo.data.Session
import app.winters.octo.data.SessionRepository
import app.winters.octo.data.SessionState
import app.winters.octo.data.accountId
import app.winters.octo.subsonic.FamilyMe
import app.winters.octo.subsonic.OCTO_FAMILY
import app.winters.octo.subsonic.RequestQuality
import app.winters.octo.subsonic.SubsonicException
import app.winters.octo.subsonic.removeFromMyLibrary
import app.winters.octo.ui.common.Feedback
import app.winters.octo.ui.family.FamilyModel
import app.winters.octo.ui.family.OutsideActions
import app.winters.octo.ui.family.USUAL_OUTSIDE_ACTIONS
import app.winters.octo.ui.family.offersRemoveFromMyLibrary
import app.winters.octo.ui.family.outsideActions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

// Whether a session's server has Family on.
val Session.family: Boolean get() = "$OCTO_FAMILY:1" in extensions

// What the app was opened for from outside: the Family screen (a notice
// tapped), or joining a family (a pairing link or its QR code).
sealed interface FamilyOpen {
    data object Family : FamilyOpen
    data class Join(val link: FamilyJoinLink) : FamilyOpen
}

// A song found online to ask a copy of, for the request sheet.
data class CopyAsk(val id: String, val title: String)

// Family for the server in use: the shared model the Family screen shows,
// the account's abilities that song menus and offline copies follow, and
// the request sheet that is open. Started with the app; another account in
// use starts it over.
@Singleton
class FamilyHub @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessions: SessionRepository,
    private val notices: FamilyNoticeStore,
    private val feedback: Feedback,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun session(): Session? = (sessions.state.value as? SessionState.SignedIn)?.session

    val model = FamilyModel(
        client = { session()?.client },
        supported = { session()?.family == true },
        scope = scope,
        answered = { request -> scope.launch { notices.alreadyTold(session()?.id.orEmpty(), request) } },
    )

    private val _opens = MutableStateFlow<FamilyOpen?>(null)

    // Waiting to be opened by the app's screens, once.
    val opens: StateFlow<FamilyOpen?> = _opens

    fun open(what: FamilyOpen) {
        _opens.value = what
    }

    fun opened() {
        _opens.value = null
    }

    // The song the request sheet is open for, or null while it is shut.
    var asking by mutableStateOf<CopyAsk?>(null)
        private set

    // Outside songs saved here, shown before the server's lists catch up.
    var savedHere by mutableStateOf<Set<String>>(emptySet())
        private set

    fun start() {
        scope.launch {
            sessions.state.filter { it !is SessionState.Loading }.map { it.accountId }.distinctUntilChanged().collect {
                model.forget()
                savedHere = emptySet()
                asking = null
                // Requests are checked in the background only for a server
                // with Family on.
                if (session()?.family == true) FamilyNoticeWorker.schedule(context) else FamilyNoticeWorker.cancel(context)
                model.plan()
            }
        }
    }

    val me: FamilyMe? get() = model.me

    // The abilities, read now if they are not yet, for code that must
    // follow them (offline copies). Null without a family.
    suspend fun plan(): FamilyMe? = model.plan()

    // What a song found online offers this account.
    val outside: OutsideActions get() = if (session()?.family == true) outsideActions(model.me) else USUAL_OUTSIDE_ACTIONS

    val removesFromMine: Boolean get() = session()?.family == true && offersRemoveFromMyLibrary(model.me)

    fun ask(trackId: String, title: String) {
        if (model.me == null) return
        asking = CopyAsk(trackId.removePrefix(FIND_PREFIX), title)
    }

    fun closeSheet() {
        asking = null
    }

    fun isSaved(trackId: String): Boolean = trackId in savedHere || model.saved.songs.any { it.id == trackId.removePrefix(FIND_PREFIX) }

    // Saves a song found online, or takes it off Saved: it plays from the
    // internet, and a copy can be asked for from Family.
    fun toggleSaved(trackId: String, title: String) {
        if (!isFind(trackId)) return
        val client = session()?.client ?: return
        val saving = !isSaved(trackId)
        scope.launch {
            try {
                if (saving) client.star(listOf(trackId.removePrefix(FIND_PREFIX))) else client.unstar(listOf(trackId.removePrefix(FIND_PREFIX)))
                savedHere = if (saving) savedHere + trackId else savedHere - trackId
                feedback.show(if (saving) "Saved $title. Find it in Family, under Saved." else "Took $title off Saved")
            } catch (e: SubsonicException) {
                feedback.show(e.message ?: "The server did not save it.")
            }
            model.refresh()
        }
    }

    // Sends the request from the sheet, and says what became of it.
    fun request(ask: CopyAsk, quality: RequestQuality) {
        asking = null
        scope.launch {
            model.request(ask.id, quality)
            model.said?.let { feedback.show(it) }
        }
    }

    // Takes library songs out of this member's own library.
    fun removeFromMyLibrary(serverId: String, title: String) {
        val client = session()?.client ?: return
        scope.launch {
            try {
                client.removeFromMyLibrary(serverId)
                feedback.done("Removed $title from your library")
            } catch (e: SubsonicException) {
                feedback.show(e.message ?: "The server kept $title.")
            }
        }
    }
}
