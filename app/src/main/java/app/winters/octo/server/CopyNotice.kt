package app.winters.octo.server

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import app.winters.octo.design.OctoIcons
import app.winters.octo.subsonic.LIBRARY_PAGE
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

// What the copy's notice says, and its bar out of NOTICE_MAX.
data class NoticeContent(val title: String, val text: String, val bar: Boolean, val progress: Int, val indeterminate: Boolean)

const val NOTICE_MAX = 1_000

// The notice for the copy running: its title, what it is doing with its
// count, and a bar of the whole copy, moving on its own while how far
// cannot be told, the library saves before its groups are planned, or the
// steps after a copy run.
fun copyNotice(run: CopyRunState): NoticeContent {
    val fraction = run.fraction
    val moving = fraction == null || (run.step == CopyStep.Save && run.of == null) || run.step == CopyStep.Settle
    return NoticeContent(
        title = copyTitle(run.arriving),
        text = copyDescription(run),
        bar = run.whole,
        progress = if (fraction == null) 0 else (fraction * NOTICE_MAX).toInt(),
        indeterminate = moving,
    )
}

// What a notice shows that is worth posting it again for: the step, the
// whole percent, and, while no total is known, every tenth page.
data class NoticeKey(val token: Long, val pass: Int, val step: CopyStep, val arriving: Boolean, val percent: Int?, val pages: Long)

fun noticeKey(run: CopyRunState): NoticeKey = NoticeKey(
    run.token,
    run.pass,
    run.step,
    run.arriving,
    run.fraction?.let(::percent),
    if (run.of == null) run.done / (PAGES_PER_NOTICE * LIBRARY_PAGE) else -1,
)

private const val PAGES_PER_NOTICE = 10L

// Whether the copy's service stays: while a whole copy runs and the app is
// not in front.
enum class ServiceAction { Keep, Stop }

fun serviceAction(run: CopyRunState?, inFront: Boolean): ServiceAction =
    if (run != null && run.whole && !inFront) ServiceAction.Keep else ServiceAction.Stop

// The copy's notices: the one the service keeps up while the app is away
// (COPY_NOTICE), and on their own id the ones saying how a copy ended, or
// the copy's notice posted plainly when the system would not start the
// service (COPY_CLOSING).
object CopyNotices {
    const val CHANNEL = "library_copy"
    const val COPY_NOTICE = 4_201
    const val COPY_CLOSING = 4_202

    // Notifications may show: on Android 13 and later only once allowed,
    // and on any version while not blocked for the app.
    fun canPost(context: Context): Boolean =
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    // A quiet channel: it never sounds or buzzes.
    fun channel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Library copy", NotificationManager.IMPORTANCE_LOW).apply {
                description = "How far bringing in your server's music is, while Octo is closed"
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    // The copy's notice was swiped away (Android 14 allows it): it is not
    // posted again until the app next leaves the front.
    @Volatile var dismissed = false

    // The copy's notice, which says when it is swiped away.
    fun copy(context: Context, content: NoticeContent): Notification =
        base(context)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply { if (content.bar) setProgress(NOTICE_MAX, content.progress, content.indeterminate) }
            .setDeleteIntent(dismissed(context))
            .build()

    // The service's notice before the copy is known.
    fun starting(context: Context): Notification =
        base(context).setContentTitle(copyTitle(arriving = true)).setOnlyAlertOnce(true).setProgress(0, 0, true).build()

    // How a first copy ended, said while the app is away.
    fun closing(context: Context, outcome: CopyOutcome) {
        val (title, text) = when (outcome) {
            is CopyOutcome.Arrived -> COPY_DONE_TITLE to copyDoneText(outcome.songs, outcome.albums, outcome.server)
            is CopyOutcome.Failed -> COPY_FAILED_TITLE to outcome.reason
        }
        post(context, COPY_CLOSING, base(context).setContentTitle(title).setContentText(text).setAutoCancel(true).build())
    }

    // The system's time limit stopped the service before the copy ended.
    fun cutOff(context: Context) {
        post(context, COPY_CLOSING, base(context).setContentTitle(copyTitle(arriving = true)).setContentText(COPY_OPEN_TO_FINISH).setAutoCancel(true).build())
    }

    fun post(context: Context, id: Int, notification: Notification) {
        if (!canPost(context)) return
        channel(context)
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
            // Taken back between the check and the post.
        }
    }

    fun cancel(context: Context, id: Int) = NotificationManagerCompat.from(context).cancel(id)

    private fun base(context: Context): NotificationCompat.Builder =
        NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(OctoIcons.Sync)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open(context))

    // Opens the app where it was.
    private fun open(context: Context): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        return PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun dismissed(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, CopyNoticeDismissed::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}

// Hears the copy's notice swiped away.
class CopyNoticeDismissed : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        CopyNotices.dismissed = true
    }
}

private val Context.copyNoticeData by preferencesDataStore("copy_notice")
private val ASKED = booleanPreferencesKey("asked")

// Whether the listener was asked once to allow notifications for a copy.
@Singleton
class CopyNoticePrefs @Inject constructor(@ApplicationContext private val context: Context) {
    val asked: Flow<Boolean> = context.copyNoticeData.data.map { it[ASKED] ?: false }.distinctUntilChanged()

    suspend fun markAsked() {
        context.copyNoticeData.edit { it[ASKED] = true }
    }
}

// Whether to offer a notification while a copy runs: only while they would
// not show and the listener was not asked yet (null: not read yet).
fun noticeAsk(enabled: Boolean, asked: Boolean?): Boolean = !enabled && asked == false

// How Allow asks: the system's own ask on Android 13 and later while
// notifications are only not allowed yet; the app's notification settings
// when they are blocked otherwise, or on an older Android.
enum class NoticeAskWay { System, Settings }

fun noticeAskWay(sdk: Int, permissionGranted: Boolean): NoticeAskWay =
    if (sdk >= Build.VERSION_CODES.TIRAMISU && !permissionGranted) NoticeAskWay.System else NoticeAskWay.Settings

// After the system's ask: whether no dialog showed (the listener refused
// for good before), so the settings page opens instead. The system says
// so by showing no reason to ask either before the ask or after it.
fun askShowedNothing(granted: Boolean, rationaleBefore: Boolean, rationaleAfter: Boolean): Boolean =
    !granted && !rationaleBefore && !rationaleAfter
