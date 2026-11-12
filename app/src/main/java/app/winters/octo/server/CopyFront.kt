package app.winters.octo.server

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import java.util.concurrent.atomic.AtomicInteger

// Where the app is, as the copy's notice needs it: in front, or away (it
// left and has not come back; a process that never showed a screen, started
// for the car say, is never away), and the starts of the notice's service
// that follow. `start` starts the service; `run` is the copy running. No
// start waits on whether a service is up: one that is may be stopping.
internal class CopyFront(private val run: () -> CopyRunState?, private val start: () -> Unit) {
    private val _inFront = MutableStateFlow(false)
    val inFront: StateFlow<Boolean> = _inFront

    @Volatile var away = false
        private set

    fun came() {
        away = false
        _inFront.value = true
    }

    // The last screen stopped; `turning` when it only comes back at once in
    // another shape (a turn, a resize), which is not leaving. Leaving during a
    // whole copy starts the service even while one is up: one told to stop
    // as the app came back may still be stopping, and a new start keeps it.
    // A notice swiped away is shown again from a new leave on.
    fun left(turning: Boolean) {
        if (turning) return
        away = true
        _inFront.value = false
        CopyNotices.dismissed = false
        if (leaveStartsNotice(run())) start()
    }

    // A whole copy planned while the app is away starts the service; one
    // running on is kept by it.
    fun planned(whole: Boolean) {
        if (plannedStartsNotice(whole, away)) start()
    }
}

// Hears the app's screens start and stop: `came` when the first starts, and
// `left` when the last stops, with whether it stopped only to come back at
// once in another shape (a turn, a resize).
internal class ForegroundCallbacks(private val came: () -> Unit, private val left: (turning: Boolean) -> Unit) : Application.ActivityLifecycleCallbacks {
    private val started = AtomicInteger(0)

    override fun onActivityStarted(activity: Activity) {
        if (started.incrementAndGet() == 1) came()
    }

    override fun onActivityStopped(activity: Activity) {
        if (started.decrementAndGet() <= 0) {
            started.set(0)
            left(activity.isChangingConfigurations)
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}

// No whole copy of an address was ever taken in, as the database says
// (`copyOf`) and an older version's record not moved in yet (`older`).
internal fun arrivingFrom(copyOf: String?, older: String?): Boolean = copyOf == null && older == null

// Whether the server in use's music is still to come, from the source in
// use (`source`: null while the sign-in is read, "" signed out), the
// database's record of whose whole copy its rows are with the phone's own
// music (`state`), and an older version's record (`older`), read again
// whenever `again` changes (each copy that starts). Null only until read.
// A whole copy once in stays in: while the older record moves into the
// database the two reads can for a moment both say none. A read that fails
// counts as nothing to come, so Home stays as it was before this was kept,
// until the next read.
@OptIn(ExperimentalCoroutinesApi::class)
internal fun copyWaitOf(
    source: Flow<String?>,
    again: Flow<Any?>,
    state: (String) -> Flow<CopyStateRow>,
    older: (String) -> Flow<String?>,
): Flow<CopyWait?> =
    combine(source.distinctUntilChanged(), again.distinctUntilChanged(), ::Pair)
        .flatMapLatest { (sourceId, _) ->
            when (sourceId) {
                null -> flowOf(null)
                "" -> flowOf(CopyWait(awaiting = false, phoneMusic = false))
                else -> {
                    var arrived = false
                    combine(state(sourceId), older(sourceId).distinctUntilChanged()) { row, old ->
                        arrived = arrived || !arrivingFrom(row.copyOf, old)
                        CopyWait(awaiting = !arrived, phoneMusic = row.phoneMusic, sourceId = sourceId)
                    }.catch { e ->
                        Log.w("Octo", "copy state unread: ${e.javaClass.simpleName}")
                        emit(CopyWait(awaiting = false, phoneMusic = false, sourceId = sourceId))
                    }
                }
            }
        }
        .distinctUntilChanged()

// A pull while a whole copy for the account in use runs waits for that one
// instead of asking for another.
internal fun pullWaits(run: CopyRunState?, account: String?): Boolean = run != null && run.whole && run.account == account

// `read`'s answer, or `otherwise` when it fails (a database error, say).
internal suspend fun <T> readOr(otherwise: T, read: suspend () -> T): T =
    try {
        read()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        otherwise
    }
