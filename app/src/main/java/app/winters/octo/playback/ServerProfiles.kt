package app.winters.octo.playback

import app.winters.octo.data.Session
import app.winters.octo.subsonic.OCTO_TRANSITIONS
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Songs' transition profiles for the Crossfader, from the server that holds
// the songs, each song named by the address the queue plays it from.
interface ProfileSource {
    // The profile of the song at `uri`, when it is already here.
    fun cached(uri: String?): TransitionProfile?

    // Asks for the profile of the song at `uri` and hands it, or null when
    // the server has none, to `done` on the main thread. False when there is
    // nothing to ask: not a song from the server in use, or a server that
    // makes no profiles.
    fun request(uri: String?, done: (TransitionProfile?) -> Unit): Boolean
}

// The server song an item plays: its id there, when it is from the server
// `sourceId` names.
fun serverSongOf(uri: String?, sourceId: String): String? {
    val ref = parseStreamUri(uri ?: return null) ?: return null
    return ref.serverId.takeIf { ref.sourceId == null || ref.sourceId == sourceId }
}

// Asks the signed-in server, when it lists octoTransitions, and keeps what
// it says for the server in use. Answers are handed back on `main`.
class ServerProfiles(
    private val scope: CoroutineScope,
    private val main: CoroutineDispatcher = Dispatchers.Main,
    private val session: () -> Session?,
) : ProfileSource {
    // The server the kept profiles are from, and whether it makes them.
    private var server: String? = null
    private var offers: Boolean? = null
    private var profiles: TransitionProfiles? = null

    // The kept profiles for the server in use, new ones when it changed.
    private fun forSession(session: Session): TransitionProfiles = synchronized(this) {
        if (server != session.sourceId) {
            server = session.sourceId
            offers = if ("$OCTO_TRANSITIONS:1" in session.extensions) true else null
            profiles = TransitionProfiles(fetch = { id -> ask(session, id) })
        }
        profiles!!
    }

    private suspend fun ask(session: Session, id: String) = withContext(Dispatchers.IO) {
        val known = synchronized(this@ServerProfiles) { offers }
        val yes = known ?: session.client.supportsIfKnown(OCTO_TRANSITIONS).also { said ->
            if (said != null) synchronized(this@ServerProfiles) { offers = said }
        }
        if (yes == true) session.client.transitionProfile(id) else null
    }

    override fun cached(uri: String?): TransitionProfile? {
        val session = session()?.takeIf { it.isOcto } ?: return null
        val id = serverSongOf(uri, session.sourceId) ?: return null
        return forSession(session).cached(id)
    }

    override fun request(uri: String?, done: (TransitionProfile?) -> Unit): Boolean {
        val session = session()?.takeIf { it.isOcto } ?: return false
        val id = serverSongOf(uri, session.sourceId) ?: return false
        val profiles = forSession(session)
        if (synchronized(this) { offers } == false) return false
        scope.launch {
            val profile = profiles.profile(id)
            withContext(main) { done(profile) }
        }
        return true
    }
}
