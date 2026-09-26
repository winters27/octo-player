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

// One part of the app and what it gained, a short sentence each.
data class WhatsNewGroup(val title: String, val lines: List<String>)

// The one line the card on Home shows. Kept short.
const val WhatsNewSummary = "A new equalizer, sorting, favourites, downloads and much more"

// What this update brings, part by part. Rewrite it with each update.
val WhatsNewGroups = listOf(
    WhatsNewGroup(
        "Sound",
        listOf(
            "An equalizer with a live curve, presets, and room for your own.",
            "Loudness evens out the volume from song to song.",
            "Headphone correction, and balance between left and right.",
            "Headphones and speakers can each keep their own sound.",
        ),
    ),
    WhatsNewGroup(
        "Library",
        listOf(
            "Sort any list, and each list remembers its order.",
            "Favourite albums and artists, with their own page and a shelf on Home.",
            "Pin albums, artists and playlists to the top of Home.",
            "Browse your music by folder, on the phone or your server.",
            "History shows what you played lately and what you play most.",
            "Rate songs with stars.",
            "Play and Shuffle at the top of your lists, and a random album.",
            "Pull down on a list to look for new music.",
        ),
    ),
    WhatsNewGroup(
        "Playback",
        listOf(
            "Play next and Add to queue keep their place while shuffling.",
            "The queue shows what already played, and can clear itself, save as a playlist, or stop after a song.",
            "Autoplay keeps similar songs coming when the queue runs out.",
            "Change the speed and pitch, and skip the quiet parts.",
            "The sleep timer can add a few minutes, or stop after a few songs.",
            "Songs can fade into each other, and music can carry on when headphones reconnect.",
            "Swipe the artwork to skip, and turn the phone sideways for a wider player.",
            "Play in the car with Android Auto, or ask your phone's assistant to play something.",
        ),
    ),
    WhatsNewGroup(
        "Server",
        listOf(
            "Discover finds songs, albums and artists beyond your library, and plays them.",
            "Stations on Home, and a radio from a song or an artist.",
            "Play your server's internet radio stations.",
            "Playlists stay the same on the phone and your server, both ways.",
            "Share links to songs and albums.",
            "Pick up the queue where another device left off.",
            "Artist pages show top songs, a biography and similar artists.",
            "Scan your server for new music, and see who else is listening.",
            "If your server is Octo, Settings shows what it is doing.",
        ),
    ),
    WhatsNewGroup(
        "Lyrics",
        listOf(
            "Lyrics in the player, from your server, the song's files or online.",
            "Lyrics that follow the song light up as they are sung. Tap a line to play from there.",
            "Move a song's lyrics earlier or later when they are out of time.",
            "The screen stays on while lyrics show.",
        ),
    ),
    WhatsNewGroup(
        "Offline",
        listOf(
            "Download songs, albums and playlists.",
            "Keep Liked songs and chosen playlists downloaded as they change.",
            "Songs you stream are kept for a while, so they play again without a connection.",
            "The next few songs load ahead while one plays.",
            "With no connection, songs that cannot play are dimmed.",
        ),
    ),
    WhatsNewGroup(
        "Everyday touches",
        listOf(
            "Undo when you remove something, and a short note when something does not work.",
            "Pick several songs at once to play, queue, add to a playlist, download or like.",
            "Song info shows everything known about a song.",
            "Swipe a song to the right to play it next.",
            "Home screen widgets for what is playing and for quick play.",
            "Long-press the app icon to shuffle everything, play Liked songs or recently added, or resume.",
            "Audio files from other apps open straight in Octo.",
            "Share a song's file, set it as a ringtone, or delete it from the phone.",
            "Search remembers your recent searches, and can show only songs, albums, artists or playlists.",
            "Bring in playlists from files, and save them out again.",
        ),
    ),
    WhatsNewGroup(
        "Settings",
        listOf(
            "Settings has a page for each topic, and a search for any setting.",
            "The artwork's colours can glow softly behind the app.",
            "Send your plays to ListenBrainz, with or without a server.",
            "Back up your settings, playlists, likes, favourites and more to a file, and bring them back.",
        ),
    ),
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
