package app.winters.octo.whatsnew

import android.content.Context
import android.content.pm.PackageManager
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.winters.octo.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

// One thing that is new, in plain words.
data class WhatsNewItem(val title: String, val detail: String)

// The recent additions, newest first. Add to the top with each update.
val WhatsNewItems = listOf(
    WhatsNewItem("Shortcuts on the app icon", "Long-press Octo's icon to shuffle everything, play liked songs or recently added, or resume."),
    WhatsNewItem("Open with Octo", "Audio files from a file manager or a chat open straight into the player."),
    WhatsNewItem("Lyrics timing", "Lyrics that run early or late can be moved for that song, and the screen stays on while they show."),
    WhatsNewItem("Undo", "Removing something now offers Undo, and anything that did not work says so."),
    WhatsNewItem("Offline listening", "Download songs, and keep streamed songs to play again without a connection."),
    WhatsNewItem("Backups", "Save your settings, playlists, likes and ratings to a file, and bring them back."),
    WhatsNewItem("Speed and headphones", "Change playback speed, skip silence, and carry on when headphones reconnect."),
    WhatsNewItem("Playlist files", "Import and export playlists as M3U files."),
    WhatsNewItem("Home screen widgets", "See what is playing, or start music in one tap."),
    WhatsNewItem("Queue across phones", "Pick up where another phone running Octo left off."),
    WhatsNewItem("Server playlists", "Create, change and delete playlists on your server, kept in step both ways."),
    WhatsNewItem("Server extras", "Shared links, library scans and internet radio stations from your server."),
    WhatsNewItem("Folders", "Browse music the way it is filed, on the phone or the server."),
    WhatsNewItem("Lyrics", "Synced lyrics in the player, from your server, the song's files or online."),
    WhatsNewItem("Artists and ratings", "Top songs, biography and similar artists, and star ratings kept in step with your server."),
    WhatsNewItem("Sound", "An equalizer with presets, loudness and ReplayGain."),
)

// Whether the card about what is new should show: once after each update,
// but not on a fresh install, which has nothing to compare with.
fun whatsNewDue(lastSeen: Int?, current: Int, freshInstall: Boolean): Boolean =
    if (lastSeen == null) !freshInstall else lastSeen < current

private val Context.whatsNewData by preferencesDataStore("whats_new")

private val LAST_SEEN = intPreferencesKey("last_seen_version")

// Remembers which version's news was last put away.
@Singleton
class WhatsNewStore @Inject constructor(@ApplicationContext private val context: Context) {
    val due: Flow<Boolean> = context.whatsNewData.data.map { whatsNewDue(it[LAST_SEEN], BuildConfig.VERSION_CODE, freshInstall()) }

    // A fresh install starts having seen this version, so later updates
    // know where they stand.
    suspend fun settle() {
        val seen = context.whatsNewData.data.first()[LAST_SEEN]
        if (seen == null && freshInstall()) markSeen()
    }

    suspend fun markSeen() {
        context.whatsNewData.edit { it[LAST_SEEN] = BuildConfig.VERSION_CODE }
    }

    // Installed and never updated since.
    private fun freshInstall(): Boolean = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.firstInstallTime == info.lastUpdateTime
    } catch (e: PackageManager.NameNotFoundException) {
        true
    }
}
