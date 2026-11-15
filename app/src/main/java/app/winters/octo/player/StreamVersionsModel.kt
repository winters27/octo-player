package app.winters.octo.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.winters.octo.catalog.FIND_PREFIX
import app.winters.octo.catalog.isFind
import app.winters.octo.data.SessionRepository
import app.winters.octo.data.SessionState
import app.winters.octo.playback.PlaybackConnection
import app.winters.octo.playback.StreamVersionFeed
import app.winters.octo.playback.VersionDropper
import app.winters.octo.playback.VersionDrops
import app.winters.octo.subsonic.STREAM_VERSION_AUTO
import app.winters.octo.subsonic.StreamVersions
import app.winters.octo.subsonic.SubsonicClient
import app.winters.octo.subsonic.SubsonicException
import app.winters.octo.versions.StreamVersionWords
import app.winters.octo.versions.StreamVersionsEvent
import app.winters.octo.versions.StreamVersionsSheetState
import app.winters.octo.versions.VersionsSong
import app.winters.octo.versions.on
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

// The entry the player is on, and its song's id as it streams when the
// song is one found online.
data class PlayingSong(val entryId: String?, val songId: String?)

// What the versions sheet needs of the server and the player, so its rules
// are tried without either.
interface VersionsPort {
    val playing: StateFlow<PlayingSong?>

    suspend fun list(songId: String): StreamVersions

    suspend fun choose(songId: String, video: String): StreamVersions

    // Lets every saved copy of these songs go at once, with their kept
    // transition profiles and their lyrics timing.
    suspend fun dropNow(ids: List<String>)

    // Plays the entry on again from its start as a new play.
    fun restart(entryId: String)

    // Reads the server's list of songs whose version changed.
    suspend fun readFeed()
}

// The sheet's work: every change goes through the shared reducer, so a
// pick waits on one row at a time, a pick for a song no longer playing is
// refused, and the sheet closes when the player's entry changes. Once the
// server took a pick (or a going back), every saved copy of each of the
// song's ids goes and their lyrics timing with them, whatever the sheet
// shows by then; the entry plays again from its start only while it is
// still the one on.
class StreamVersionsSheet(private val scope: CoroutineScope, private val port: VersionsPort) {
    private val _state = MutableStateFlow<StreamVersionsSheetState>(StreamVersionsSheetState.Closed)
    val state: StateFlow<StreamVersionsSheetState> = _state

    // The last state that was not closed, drawn while the sheet fades away.
    private val _shown = MutableStateFlow<StreamVersionsSheetState>(StreamVersionsSheetState.Closed)
    val shown: StateFlow<StreamVersionsSheetState> = _shown

    init {
        scope.launch {
            port.playing.map { it?.entryId }.distinctUntilChanged().collect { send(StreamVersionsEvent.EntryChanged(it)) }
        }
    }

    fun open(song: VersionsSong) {
        send(StreamVersionsEvent.Open(song))
        list(song)
    }

    fun retry() {
        val song = (_state.value as? StreamVersionsSheetState.CouldNotReach)?.song ?: return
        if (send(StreamVersionsEvent.Retry)) list(song)
    }

    fun pick(videoId: String) {
        val now = port.playing.value
        if (send(StreamVersionsEvent.Pick(videoId, now?.entryId, now?.songId))) choose(videoId)
    }

    fun undo() {
        val now = port.playing.value
        if (send(StreamVersionsEvent.Undo(now?.entryId, now?.songId))) choose(STREAM_VERSION_AUTO)
    }

    fun close() {
        send(StreamVersionsEvent.Close)
    }

    // True when the event changed the sheet.
    private fun send(event: StreamVersionsEvent): Boolean {
        val before = _state.value
        val after = before.on(event)
        if (after === before) return false
        _state.value = after
        if (after !is StreamVersionsSheetState.Closed) _shown.value = after
        return true
    }

    private fun list(song: VersionsSong) {
        scope.launch {
            val event = try {
                StreamVersionsEvent.Listed(port.list(song.songId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: SubsonicException.Unreachable) {
                StreamVersionsEvent.ListFailed(null)
            } catch (e: SubsonicException.NotSubsonic) {
                // A proxy or sign-in page answered in the server's place:
                // worth asking again, so Try again, not the server's words.
                StreamVersionsEvent.ListFailed(null)
            } catch (e: SubsonicException) {
                StreamVersionsEvent.ListFailed(e.message ?: StreamVersionWords.SERVER_UNREACHABLE)
            } catch (e: Exception) {
                StreamVersionsEvent.ListFailed(null)
            }
            send(event)
        }
    }

    private fun choose(video: String) {
        val song = (_state.value as? StreamVersionsSheetState.Shown)?.song ?: return
        scope.launch {
            val answer = try {
                port.choose(song.songId, video)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SubsonicException.Unreachable) {
                send(StreamVersionsEvent.PickFailed(StreamVersionWords.PICK_UNREACHABLE))
                return@launch
            } catch (e: SubsonicException.NotSubsonic) {
                send(StreamVersionsEvent.PickFailed(StreamVersionWords.PICK_UNREACHABLE))
                return@launch
            } catch (e: SubsonicException) {
                send(StreamVersionsEvent.PickFailed(e.message ?: StreamVersionWords.PICK_UNREACHABLE))
                return@launch
            } catch (e: Exception) {
                send(StreamVersionsEvent.PickFailed(StreamVersionWords.PICK_UNREACHABLE))
                return@launch
            }
            send(StreamVersionsEvent.Picked(answer))
            try {
                port.dropNow(answer.ids.ifEmpty { listOf(song.songId) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A copy that could not go is caught by the next play's video check.
            }
            if (port.playing.value?.entryId == song.entryId) port.restart(song.entryId)
            port.readFeed()
        }
    }
}

// The server and the player as the sheet reaches them in the app.
internal class AppVersionsPort(
    private val sessions: SessionRepository,
    private val playback: PlaybackConnection,
    private val drops: VersionDropper,
    private val feed: StreamVersionFeed,
    scope: CoroutineScope,
) : VersionsPort {
    override val playing: StateFlow<PlayingSong?> = playback.now
        .map { now -> now.trackId?.let { PlayingSong(now.entryId, it.takeIf(::isFind)?.removePrefix(FIND_PREFIX)) } }
        .stateIn(scope, SharingStarted.Eagerly, null)

    private fun signedIn() = (sessions.state.value as? SessionState.SignedIn)?.session

    private fun client(): SubsonicClient = signedIn()?.client ?: throw SubsonicException.Unreachable(IOException("No server signed in"))

    override suspend fun list(songId: String): StreamVersions = client().streamVersions(songId)

    override suspend fun choose(songId: String, video: String): StreamVersions = client().setStreamVersion(songId, video)

    override suspend fun dropNow(ids: List<String>) {
        val source = signedIn()?.sourceId ?: return
        drops.drop(source, ids, now = true)
    }

    override fun restart(entryId: String) {
        playback.restartFresh(entryId)
    }

    override suspend fun readFeed() = feed.refresh()
}

@HiltViewModel
class StreamVersionsViewModel @Inject constructor(
    sessions: SessionRepository,
    playback: PlaybackConnection,
    drops: VersionDrops,
    feed: StreamVersionFeed,
) : ViewModel() {
    private val sheet = StreamVersionsSheet(viewModelScope, AppVersionsPort(sessions, playback, drops, feed, viewModelScope))

    val state: StateFlow<StreamVersionsSheetState> = sheet.state
    val shown: StateFlow<StreamVersionsSheetState> = sheet.shown

    fun open(song: VersionsSong) = sheet.open(song)

    fun retry() = sheet.retry()

    fun pick(videoId: String) = sheet.pick(videoId)

    fun undo() = sheet.undo()

    fun close() = sheet.close()
}
