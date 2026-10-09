package app.winters.octo.family

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.winters.octo.MainActivity
import app.winters.octo.data.SessionRepository
import app.winters.octo.data.SessionState
import app.winters.octo.design.OctoIcons
import app.winters.octo.subsonic.FamilyRequestState
import app.winters.octo.subsonic.SubsonicClient
import app.winters.octo.subsonic.SubsonicException
import app.winters.octo.subsonic.family
import app.winters.octo.subsonic.familyRequests
import app.winters.octo.ui.family.FAMILY
import app.winters.octo.ui.family.FamilyNotice
import app.winters.octo.ui.family.familyNotices
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

// One look at the family's requests: what to tell this account, and what
// it has been told from now on. A manager who approves also hears how many
// requests wait.
class FamilyCheck(private val store: FamilyNoticeStore) {
    suspend fun run(account: String, client: SubsonicClient): List<FamilyNotice> {
        val me = client.family().me
        val mine = client.familyRequests()
        val waiting = if (me.abilities.approveRequests) client.familyRequests(all = true, state = FamilyRequestState.Pending).size else null
        val (told, memory) = familyNotices(store.read(account), mine, waiting)
        store.write(account, memory)
        return told
    }
}

// The notices on the phone: their own quiet channel, each request's news
// replacing its last, opening the app at Family when tapped.
object FamilyNotifier {
    private const val CHANNEL = "family"

    // Opened from a notice: the Family screen.
    const val OPEN_FAMILY = "app.winters.octo.OPEN_FAMILY"

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun post(context: Context, notices: List<FamilyNotice>) {
        if (notices.isEmpty() || !canPost(context)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, FAMILY, NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Requests approved, declined or added, and requests waiting for you"
                },
            )
        }
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).setAction(OPEN_FAMILY).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val compat = NotificationManagerCompat.from(context)
        for (notice in notices) {
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(OctoIcons.Listeners)
                .setContentTitle(notice.title)
                .setContentText(notice.text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(notice.text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            try {
                compat.notify(notice.key, 0, notification)
            } catch (e: SecurityException) {
                return
            }
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface FamilyWorkEntry {
    fun sessions(): SessionRepository
    fun notices(): FamilyNoticeStore
}

// Checks the family's requests in the background: when the app starts and
// every 30 minutes, on any network.
class FamilyNoticeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val entry = EntryPointAccessors.fromApplication(applicationContext, FamilyWorkEntry::class.java)
        val state = withTimeoutOrNull(SIGN_IN_WAIT_MS) { entry.sessions().state.first { it !is SessionState.Loading } }
        val session = (state as? SessionState.SignedIn)?.session ?: return Result.success()
        if (!session.family) return Result.success()
        return try {
            FamilyNotifier.post(applicationContext, FamilyCheck(entry.notices()).run(session.id, session.client))
            Result.success()
        } catch (e: SubsonicException.Unreachable) {
            Result.retry()
        } catch (e: SubsonicException) {
            Result.success()
        }
    }

    companion object {
        private const val PERIODIC = "family-notices"
        private const val NOW = "family-notices-now"
        private const val SIGN_IN_WAIT_MS = 20_000L

        // Starts the checks for a server with Family on: one now, then
        // every 30 minutes.
        fun schedule(context: Context) {
            val work = WorkManager.getInstance(context)
            val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            work.enqueueUniquePeriodicWork(
                PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<FamilyNoticeWorker>(30, TimeUnit.MINUTES).setConstraints(online).build(),
            )
            work.enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<FamilyNoticeWorker>().setConstraints(online).build())
        }

        // A server without Family, or none: no checks.
        fun cancel(context: Context) {
            val work = WorkManager.getInstance(context)
            work.cancelUniqueWork(PERIODIC)
            work.cancelUniqueWork(NOW)
        }
    }
}
