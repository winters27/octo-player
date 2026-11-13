package app.winters.octo.desktop.nav

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntRect
import app.winters.octo.ui.family.AUDIO_QUALITY

// The keys of Settings' pages, which links and the command search open.
object SettingsKey {
    const val Playback = "playback"
    const val Sound = "sound"
    const val Radio = "radio"
    const val Lyrics = "lyrics"
    const val Servers = "servers"
    const val Quality = "quality"
    const val Offline = "offline"
    const val Listening = "listening"
    const val Look = "look"
    const val Home = "home"
    const val System = "system"
    const val Keys = "keys"
    const val Discord = "discord"
    const val About = "about"
}

// One page of Settings: its key, its name, and other words the command
// search finds it by.
data class SettingsPageInfo(val key: String, val name: String, val words: String = "")

// Settings' pages in the order they are listed. The window page has no
// startup card off Windows, so it is named for the window alone there; the
// Discord page is only in builds with Discord.
fun settingsPages(windows: Boolean, discord: Boolean): List<SettingsPageInfo> = listOfNotNull(
    SettingsPageInfo(SettingsKey.Playback, "Playback", "autoplay queue ends library only songs found online between songs crossfade speed pitch tempo blend transitions automix"),
    SettingsPageInfo(SettingsKey.Sound, "Sound", "equalizer eq loudness volume balance crossfeed headphone correction output"),
    SettingsPageInfo(SettingsKey.Radio, "Radio", "song radio live radio stations on your server discovery adventure variety tuning"),
    SettingsPageInfo(SettingsKey.Lyrics, "Lyrics", "lyrics online lrclib timing bloom"),
    SettingsPageInfo(SettingsKey.Servers, "Servers", "server account update scan read again sign out"),
    SettingsPageInfo(SettingsKey.Quality, AUDIO_QUALITY, "stream quality bitrate at home away"),
    SettingsPageInfo(SettingsKey.Offline, "Streaming and downloads", "offline kept on this computer downloads start playing after buffer"),
    SettingsPageInfo(SettingsKey.Listening, "Plays and sync", "listening report plays history scrobble carry the queue devices"),
    SettingsPageInfo(SettingsKey.Look, "Appearance", "look theme accent text size window background ambience full player covers motion"),
    SettingsPageInfo(SettingsKey.Home, "Home", "home shelves sections edit home"),
    SettingsPageInfo(SettingsKey.System, if (windows) "Window and startup" else "Window", "system window title bar tray startup mini player"),
    SettingsPageInfo(SettingsKey.Keys, "Keyboard", "keys keyboard shortcuts hotkeys global"),
    if (discord) SettingsPageInfo(SettingsKey.Discord, "Discord", "discord rich presence status friends") else null,
    SettingsPageInfo(SettingsKey.About, "About Octo", "about version updates help"),
)

// The page Settings opens on before any other was shown.
const val FIRST_SETTINGS_PAGE = SettingsKey.Playback

// Where in Settings something is: a page, and the row on it to bring into
// view, if any.
data class SettingsPlace(val page: String, val row: String? = null)

// The sections the Sound page had of its own, now rows of Settings' Sound
// page; its crossfade and speed are on Playback.
private val SoundSections = setOf("output", "eq", "loudness", "balance", "crossfeed", "correction")

// Where a section of the old Sound page is in Settings now.
fun soundSectionPlace(key: String): SettingsPlace = when (key) {
    "playback" -> SettingsPlace(SettingsKey.Playback, "crossfade")
    in SoundSections -> SettingsPlace(SettingsKey.Sound, key)
    else -> SettingsPlace(SettingsKey.Sound)
}

// A row to bring into view; `nonce` is new each time, so the same row
// asked for again is brought into view again.
data class SettingsTarget(val row: String, val nonce: Long)

// What Settings shows: its page, the row asked for, and what its search
// field holds. The app keeps it for the window's life; the search is
// cleared and the row let go on leaving Settings.
@Stable
class SettingsUi {
    var page by mutableStateOf(FIRST_SETTINGS_PAGE)
    var target by mutableStateOf<SettingsTarget?>(null)
        private set
    var query by mutableStateOf("")

    // Whether the keyboard is in Settings' search field; the field sets it.
    var searchHasKeyboard by mutableStateOf(false)

    // Where the page shown sits in the window, which a question asked
    // from it is centered in; set by the page as it is laid out.
    var column by mutableStateOf<IntRect?>(null)

    private var nonce = 0L

    // Asks for a row to come into view, or with null for none.
    fun aim(row: String?) {
        target = row?.let { SettingsTarget(it, ++nonce) }
    }
}
