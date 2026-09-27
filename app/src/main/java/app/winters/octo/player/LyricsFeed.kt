package app.winters.octo.player

import androidx.lifecycle.ViewModel
import app.winters.octo.lyrics.LyricsAnswer
import app.winters.octo.lyrics.LyricsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import javax.inject.Inject

// What the lyrics view is asked to show: the song on now while lyrics are
// open, whether the listener hid its lyrics, and anything that means they
// must be fetched again when it changes (the listener's pick, a retry).
data class LyricsWanted(val id: String, val hidden: Boolean = false, val version: Any? = null)

// How many times, and how far apart, a failed lookup is asked again before
// it shows as failed.
const val QUIET_RETRIES = 2
const val QUIET_RETRY_MS = 4_000L

// What the lyrics view shows, following the song while lyrics are open.
// Lyrics are only looked up while the view is on screen (`watched`). A song
// that changes while it is off screen shows as loading, never as the last
// song's lyrics or as "none", and is looked up the moment the view is back.
// The first lookup each time the view comes back is `resumed`, so a "none"
// found while it was away is asked again. A lookup that fails shows as
// Failed and is tried once more when the network comes back
// (`networkBack` returns); coming back on screen tries again too.
@OptIn(ExperimentalCoroutinesApi::class)
fun lyricsStates(
    wanted: Flow<LyricsWanted?>,
    watched: Flow<Boolean>,
    lookUp: suspend (id: String, resumed: Boolean) -> LyricsAnswer,
    networkBack: suspend () -> Unit,
): Flow<LyricsState> = flow {
    // What the view shows now, and what it was asked for then.
    var shown: LyricsState = LyricsState.Hidden
    var shownFor: LyricsWanted? = null
    // Counts each time the view comes on screen, and the last one a lookup
    // was made in.
    var turn = 0
    var lookedIn = -1
    val turns = watched.distinctUntilChanged().map { on -> if (on) ++turn else null }

    suspend fun safeLookUp(id: String, resumed: Boolean): LyricsAnswer = try {
        lookUp(id, resumed)
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
        LyricsAnswer.Failed
    } catch (_: Exception) {
        LyricsAnswer.Failed
    }

    emitAll(
        combine(wanted, turns) { song, on -> song to on }
            .distinctUntilChanged()
            .transformLatest { (song, on) ->
                suspend fun show(state: LyricsState) {
                    shown = state
                    shownFor = song
                    emit(state)
                }
                when {
                    song == null -> show(LyricsState.Hidden)
                    song.hidden -> show(LyricsState.HiddenForSong)
                    // Off screen: nothing is looked up, and another song waits
                    // as loading.
                    on == null -> if (shownFor != song) show(LyricsState.Loading)
                    else -> {
                        val resumed = on != lookedIn
                        lookedIn = on
                        // Lyrics or "none" for this song stay while they are checked.
                        if (shownFor != song || (shown !is LyricsState.Found && shown != LyricsState.None)) {
                            show(LyricsState.Loading)
                        }
                        var answer = safeLookUp(song.id, resumed)
                        // The server may still be looking (it keeps going after telling
                        // us "not yet"), so ask again quietly a couple of times before
                        // saying anything failed.
                        var quietTries = 0
                        while (answer == LyricsAnswer.Failed && quietTries < QUIET_RETRIES) {
                            quietTries++
                            delay(QUIET_RETRY_MS)
                            answer = safeLookUp(song.id, false)
                        }
                        if (answer == LyricsAnswer.Failed) {
                            show(LyricsState.Failed(song.id))
                            networkBack()
                            show(LyricsState.Loading)
                            answer = safeLookUp(song.id, false)
                        }
                        show(
                            when (answer) {
                                is LyricsAnswer.Found -> LyricsState.Found(song.id, answer.lyrics)
                                LyricsAnswer.None -> LyricsState.None
                                LyricsAnswer.Failed -> LyricsState.Failed(song.id)
                            },
                        )
                    }
                }
            },
    )
}

// Lets the lyrics view ask again after a lookup failed.
@HiltViewModel
class LyricsRetryViewModel @Inject constructor(private val repository: LyricsRepository) : ViewModel() {
    fun retry(trackId: String) = repository.retry(trackId)
}
