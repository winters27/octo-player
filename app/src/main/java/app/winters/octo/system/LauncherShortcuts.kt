package app.winters.octo.system

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import app.winters.octo.MainActivity
import app.winters.octo.R

// Asks the main screen to start one of the launcher's shortcuts.
const val ACTION_SHORTCUT = "app.winters.octo.SHORTCUT"
private const val EXTRA_SHORTCUT = "app.winters.octo.shortcut"

// The shortcuts shown on a long press of Octo's icon, in the order shown.
enum class LauncherShortcut(val id: String, @StringRes val label: Int, @DrawableRes val icon: Int) {
    ShuffleAll("shuffle_all", R.string.shortcut_shuffle_all, R.drawable.shortcut_shuffle),
    Liked("liked", R.string.widget_quick_liked, R.drawable.shortcut_liked),
    RecentlyAdded("recently_added", R.string.widget_quick_recent, R.drawable.shortcut_recent),
    Resume("resume", R.string.widget_quick_resume, R.drawable.shortcut_resume),
}

// The shortcut a launch came from, or null for any other launch.
fun shortcutOf(action: String?, id: String?): LauncherShortcut? =
    if (action == ACTION_SHORTCUT) LauncherShortcut.entries.firstOrNull { it.id == id } else null

fun shortcutOf(intent: Intent): LauncherShortcut? = shortcutOf(intent.action, intent.getStringExtra(EXTRA_SHORTCUT))

// Hands the shortcuts to the launcher. Called each time the app opens, so
// their names follow the phone's language.
fun publishShortcuts(context: Context) {
    val shortcuts = LauncherShortcut.entries.mapIndexed { rank, shortcut ->
        val label = context.getString(shortcut.label)
        ShortcutInfoCompat.Builder(context, shortcut.id)
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(IconCompat.createWithResource(context, shortcut.icon))
            .setRank(rank)
            .setIntent(
                Intent(context, MainActivity::class.java)
                    .setAction(ACTION_SHORTCUT)
                    .putExtra(EXTRA_SHORTCUT, shortcut.id),
            )
            .build()
    }
    try {
        ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
    } catch (e: IllegalStateException) {
        // Asked too often or while in the background: the old ones stay.
        Log.w("Octo", "could not publish shortcuts")
    }
}
