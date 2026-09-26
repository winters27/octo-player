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
import app.winters.octo.playback.PlaybackConnection
import app.winters.octo.ui.common.Feedback
import app.winters.octo.widget.QuickPick
import app.winters.octo.widget.QuickPicks
import app.winters.octo.widget.WidgetCommand
import app.winters.octo.widget.commandIntent
import dagger.hilt.android.qualifiers.ApplicationContext
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
        return when (shortcut) {
            LauncherShortcut.ShuffleAll -> play(picks.everything(), shuffle = true, empty = "No songs in your library yet")
            LauncherShortcut.Liked -> picks.songsFor(QuickPick.Liked).let { (ids, shuffle) -> play(ids, shuffle, "No liked songs yet") }
            LauncherShortcut.RecentlyAdded -> picks.songsFor(QuickPick.RecentlyAdded).let { (ids, shuffle) ->
                play(ids, shuffle, "No songs in your library yet")
            }
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

    private fun play(ids: List<String>, shuffle: Boolean, empty: String): Boolean {
        if (ids.isEmpty()) {
            feedback.show(empty)
            return false
        }
        playback.playTracks(ids, shuffle = shuffle)
        return true
    }

    // The library song the file is, or the file on its own.
    private suspend fun open(uri: Uri): Boolean {
        val id = openedFiles.trackIdFor(uri)
        if (id == null) {
            feedback.show("Octo cannot open that file")
            return false
        }
        playback.playTracks(listOf(id))
        return true
    }
}
