package app.winters.octo.system

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import app.winters.octo.playback.ListSpec
import app.winters.octo.playback.PlaybackConnection
import app.winters.octo.server.COPY_LIKES_ON_ITS_WAY
import app.winters.octo.server.COPY_STILL_ON_ITS_WAY
import app.winters.octo.server.ServerSync
import app.winters.octo.ui.common.Feedback
import app.winters.octo.widget.QuickPick
import app.winters.octo.widget.QuickPicks
import app.winters.octo.widget.WidgetCommand
import app.winters.octo.widget.commandIntent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

// Starts the music that the launcher's shortcuts and "Open with Octo" ask
// for, with the same song lists the home screen widgets use.
class SystemEntries @Inject constructor(
    @ApplicationContext private val context: Context,
    private val picks: QuickPicks,
    private val playback: PlaybackConnection,
    private val openedFiles: OpenedFiles,
    private val feedback: Feedback,
    private val sync: ServerSync,
) {
    // Plays what the intent asks for once the screen is up, then opens the
    // player. Coming back from recents hands over the old intent again,
    // which is not a new request.
    fun handle(activity: ComponentActivity, intent: Intent, openPlayer: () -> Unit) {
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val shortcut = shortcutOf(intent)
        val file = intent.data?.takeIf { intent.action == ACTION_OPEN_FILE }
        if (shortcut == null && file == null) return
        activity.lifecycleScope.launch {
            // On screen, the app may start the playback service itself.
            activity.withResumed { }
            val started = if (shortcut != null) start(shortcut) else file?.let { open(it) } == true
            if (started) openPlayer()
        }
    }

    private suspend fun start(shortcut: LauncherShortcut): Boolean {
        ShortcutManagerCompat.reportShortcutUsed(context, shortcut.id)
        // While the server's first copy is still to come, the music is on its
        // way. A shortcut that opens the app cold waits for that first read.
        val coming = sync.copyWait.filterNotNull().first().awaiting
        val noSongs = if (coming) COPY_STILL_ON_ITS_WAY else "No songs in your library yet"
        return when (shortcut) {
            LauncherShortcut.ShuffleAll -> play(picks.everything().takeIf { picks.hasSongs() }, shuffle = true, empty = noSongs)
            LauncherShortcut.Liked -> picks.specFor(QuickPick.Liked).let { pick ->
                play(pick?.first?.takeIf { picks.hasLiked() }, pick?.second ?: true, if (coming) COPY_LIKES_ON_ITS_WAY else "No liked songs yet")
            }
            LauncherShortcut.RecentlyAdded -> picks.specFor(QuickPick.RecentlyAdded).let { pick -> play(pick?.first, pick?.second ?: false, noSongs) }
            // Exactly the widget's Resume: carry on with the queue, or with
            // none, shuffle everything.
            LauncherShortcut.Resume -> try {
                commandIntent(context, WidgetCommand.Play).send()
                true
            } catch (e: PendingIntent.CanceledException) {
                Log.w("Octo", "resume shortcut refused")
                false
            }
        }
    }

    private fun play(spec: ListSpec?, shuffle: Boolean, empty: String): Boolean {
        if (spec == null) {
            feedback.show(empty)
            return false
        }
        playback.playList(spec, null, shuffle)
        return true
    }

    // The library song the file is, or the file on its own.
    private suspend fun open(uri: Uri): Boolean {
        val id = openedFiles.trackIdFor(uri)
        if (id == null) {
            feedback.show("Octo cannot open that file")
            return false
        }
        playback.playList(ListSpec.Ids(listOf(id)))
        return true
    }
}
