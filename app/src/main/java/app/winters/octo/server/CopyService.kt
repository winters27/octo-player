package app.winters.octo.server

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.app.ServiceCompat
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

// What the copy's service follows: the copy running, and whether any of the
// app's screens is in front.
interface CopySource {
    val copyRun: StateFlow<CopyRunState?>
    val inFront: StateFlow<Boolean>
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface CopyServiceEntry {
    fun serverSync(): ServerSync
}

// Keeps a whole copy going while the app is away, with a notice saying how
// far it is. It follows the copy and stops itself once the app is in front
// again or no whole copy runs. Only it stops itself, always with the id of
// its latest start, and only once it is in the foreground, as the system
// asks of a service started to be one.
class CopyService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var copy: CopySource
    private var lastStartId = 0
    private var shown: NoticeKey? = null
    private var timedOut = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        up = true
        copy = source(applicationContext)
        CopyNotices.channel(this)
        enterForeground()
        scope.launch {
            combine(copy.copyRun, copy.inFront, ::Pair).collect { (run, front) ->
                if (run == null || serviceAction(run, front) == ServiceAction.Stop) {
                    stop(lastStartId)
                } else {
                    show(run)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        stopping = false
        enterForeground()
        // Past the system's limit, a start only ends it.
        if (timedOut || serviceAction(copy.copyRun.value, copy.inFront.value) == ServiceAction.Stop) stop(startId)
        return START_NOT_STICKY
    }

    // The system's limit on a data sync ran out (Android 15 and later): the
    // copy goes on while the app lives, and the notice says to open it.
    override fun onTimeout(startId: Int, fgsType: Int) {
        timedOut = true
        CopyNotices.cutOff(this)
        stop(lastStartId)
    }

    override fun onDestroy() {
        up = false
        stopping = false
        scope.cancel()
        super.onDestroy()
    }

    // Asks to stop with the latest start's id; a newer start keeps it.
    private fun stop(startId: Int) {
        stopping = true
        stopSelf(startId)
    }

    // Puts the service in the foreground with the copy's notice as it is now,
    // as every start asks, even one that only ends it.
    private fun enterForeground() {
        val run = copy.copyRun.value
        val notice = run?.takeIf { it.whole }?.let { CopyNotices.copy(this, copyNotice(it)) } ?: CopyNotices.starting(this)
        try {
            ServiceCompat.startForeground(this, CopyNotices.COPY_NOTICE, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            shown = run?.let(::noticeKey)
        } catch (e: IllegalStateException) {
            // Not allowed now (the time limit spent, say): the copy goes on without it.
            Log.w("Octo", "copy notice not shown: ${e.javaClass.simpleName}")
            stop(lastStartId)
        }
    }

    // Posts the notice again when it says something new, unless it was
    // swiped away.
    private fun show(run: CopyRunState) {
        val key = noticeKey(run)
        if (timedOut || CopyNotices.dismissed || key == shown) return
        shown = key
        CopyNotices.post(this, CopyNotices.COPY_NOTICE, CopyNotices.copy(this, copyNotice(run)))
    }

    companion object {
        // The service is running, from its start to its end, and whether it
        // asked to stop since its latest start.
        @Volatile var up = false
            private set

        @Volatile private var stopping = false

        // A service is up and staying, showing the copy's notice.
        val keeping: Boolean get() = up && !stopping

        // Where the service reads the copy and the app's place; tests give their own.
        @VisibleForTesting
        internal var source: (Context) -> CopySource = { EntryPointAccessors.fromApplication(it, CopyServiceEntry::class.java).serverSync() }
    }
}
