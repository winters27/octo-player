package app.winters.octo.desktop.pages

import app.winters.octo.animated.AnimatedArtSetting
import app.winters.octo.covers.PLAYLIST_COVERS_SETTING
import app.winters.octo.desktop.liveradio.FIND_STATIONS_NEAR
import app.winters.octo.desktop.liveradio.LIVE_RADIO
import app.winters.octo.desktop.nav.SettingsKey
import app.winters.octo.desktop.nav.SettingsPageInfo
import app.winters.octo.desktop.nav.settingsPages
import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.discovery.LIBRARY_ONLY_SETTING
import app.winters.octo.home.HOME_SECTIONS_TITLE
import app.winters.octo.playback.START_AFTER_SETTING
import app.winters.octo.playback.DOWNLOAD_FORMAT_HELP
import app.winters.octo.playback.DOWNLOAD_FORMAT_TITLE
import app.winters.octo.radio.SONG_RADIO
import app.winters.octo.sound.CROSSFEED_CUT_TITLE
import app.winters.octo.sound.CROSSFEED_FEED_TITLE
import app.winters.octo.sound.CROSSFEED_MONO
import app.winters.octo.ui.family.HANDOVER_TITLE
import app.winters.octo.ui.family.QUALITY_AT_HOME
import app.winters.octo.ui.family.QUALITY_AWAY
import app.winters.octo.ui.family.QUALITY_ON_THIS_DEVICE
import app.winters.octo.ui.search.rankSettings

// Every setting on Settings' pages, as Settings' search finds it: its page,
// the id of its row there (its card's id, a dot and the row's own, made from
// its title as the page makes it), its title, other words people type for
// it, a line about it, and what this computer needs for it to show. A row a
// setting keeps hidden is still listed: its page lights the row that shows
// it. Rows whose title is data (a server, a station, a key) are found by
// their card.

// What this window has, which decides the settings it shows.
data class SettingsHere(
    val os: DesktopOs,
    val tray: Boolean = false,
    val notifications: Boolean = false,
    val discord: Boolean = false,
    val engine: Boolean = false,
    val family: Boolean = false,
)

// What a setting needs to show.
enum class SettingsNeed {
    Windows,
    NotMac,
    Tray,
    Notifications,
    Discord,
    Engine,
    NoEngine,
    Family;

    fun metBy(here: SettingsHere): Boolean = when (this) {
        Windows -> here.os == DesktopOs.Windows
        NotMac -> here.os != DesktopOs.Mac
        Tray -> here.tray
        Notifications -> here.notifications
        Discord -> here.discord
        Engine -> here.engine
        NoEngine -> !here.engine
        Family -> here.family
    }
}

// One setting, a card or a page as search finds it. `row` is null for a
// page itself; `card` is the title of the card it sits in.
data class SettingsIndexEntry(
    val page: String,
    val row: String?,
    val title: String,
    val keywords: List<String> = emptyList(),
    val summary: String = "",
    val card: String? = null,
    val needs: Set<SettingsNeed> = emptySet(),
) {
    fun shows(here: SettingsHere): Boolean = needs.all { it.metBy(here) }
}

// A search result: the entry, and the name its page has in this window.
data class SettingsHit(val entry: SettingsIndexEntry, val pageName: String) {
    // Where it is, for the result's second line: the page, and its card
    // when that says more than the title; a whole page's own group in the
    // list, so its name is not said twice.
    val place: String get() = if (entry.row == null) settingsGroupOf(entry.page) else listOfNotNull(pageName, entry.card?.takeIf { it != entry.title && it != pageName }).joinToString(" · ")
}

private class PageWords(val page: String, val needs: Set<SettingsNeed>, val out: MutableList<SettingsIndexEntry>) {
    // A card: listed itself when `listed`, its rows under its id.
    fun card(
        title: String,
        id: String = settingsSlug(title),
        keywords: List<String> = emptyList(),
        summary: String = "",
        needs: Set<SettingsNeed> = emptySet(),
        listed: Boolean = true,
        rows: CardWords.() -> Unit = {},
    ) {
        val all = this.needs + needs
        if (listed) out += SettingsIndexEntry(page, id, title, keywords, summary, title, all)
        CardWords(page, id, title, all, out).rows()
    }

    // A row in no card.
    fun row(title: String, keywords: List<String> = emptyList(), summary: String = "", needs: Set<SettingsNeed> = emptySet()) {
        out += SettingsIndexEntry(page, settingsSlug(title), title, keywords, summary, null, this.needs + needs)
    }
}

private class CardWords(val page: String, val card: String, val cardTitle: String, val needs: Set<SettingsNeed>, val out: MutableList<SettingsIndexEntry>) {
    // A row of the card. `stable` is the fixed form of a title with parts
    // filled in; `id` names a row the page names on its own.
    fun row(
        title: String,
        keywords: List<String> = emptyList(),
        summary: String = "",
        needs: Set<SettingsNeed> = emptySet(),
        id: String = settingsSlug(title),
    ) {
        out += SettingsIndexEntry(page, settingsRowId(card, id), title, keywords, summary, cardTitle, this.needs + needs)
    }
}

private fun index(build: MutableList<SettingsIndexEntry>.() -> Unit): List<SettingsIndexEntry> = mutableListOf<SettingsIndexEntry>().apply(build)

private fun MutableList<SettingsIndexEntry>.page(key: String, vararg needs: SettingsNeed, words: PageWords.() -> Unit) =
    PageWords(key, needs.toSet(), this).words()

private val EngineOnly = setOf(SettingsNeed.Engine)

// Every row and card, page by page, in the order the pages show them.
val DesktopSettingsIndex: List<SettingsIndexEntry> = index {
    page(SettingsKey.Playback) {
        card("When the queue ends", listed = false) {
            row("Autoplay", listOf("similar", "continue", "endless", "radio", "queue"), "Similar songs keep playing when the queue ends")
        }
        card("Songs found online", listed = false) {
            row(LIBRARY_ONLY_SETTING, listOf("outside", "online", "found", "hide", "only", "my songs", "not in library", "discover"), "Leave songs found online out of albums, search, artists and radio")
        }
        // Between songs keeps the id the Crossfade card had, and Length the
        // id Longest blend had; the choice's own row is found by the card.
        card(
            BETWEEN_SONGS,
            id = BETWEEN_SONGS_CARD,
            keywords = listOf("crossfade", "gapless", "automix", "blend", "mix", "transition", "dj", "smart transitions", "between songs", "fade", "gap", "beat", "outro"),
            summary = "Gapless, Crossfade or Automix",
            needs = EngineOnly,
        ) {
            row("Length", listOf("fade", "seconds", "duration", "crossfade length", "longest blend", "gap"), "How many seconds a crossfade lasts", id = UnderBetweenSongs.Length.id)
            row("Crossfade curve", listOf("fade", "curve", "shape", "linear", "equal power", "s-curve", "logarithmic", "mix", "dj", "transition"), "How one song fades into the next")
            row("Filter sweeps", listOf("filter", "sweep", "bass", "eq", "dj", "automix"), "Songs thin out as they cross")
            row("Match tempo", listOf("tempo", "bpm", "beat", "beatmatch", "speed", "automix"), "The next song nudges its speed to the beat")
        }
        card("Speed and pitch", id = "speed", keywords = listOf("tempo", "faster", "slower", "rate"), needs = EngineOnly) {
            row("Speed", listOf("tempo", "pitch", "faster", "slower", "rate"), "How fast music plays")
            row("Keep the pitch", listOf("pitch", "chipmunk", "record", "tempo"), "Voices keep their pitch at any speed")
            row("Pitch", listOf("semitones", "key", "higher", "lower"), "Higher or lower, at the same speed")
            row("Speed or pitch changed", listOf("normal", "reset", "back to normal"), "Every song plays this way until it is set back")
        }
    }
    page(SettingsKey.Sound) {
        row("Nothing to shape", listOf("sound engine", "equalizer", "eq"), "The sound engine couldn't start", needs = setOf(SettingsNeed.NoEngine))
        card("Output", keywords = listOf("bluetooth", "speaker", "device", "headphones", "usb"), needs = EngineOnly, listed = false) {
            row("Each output keeps its own sound", listOf("bluetooth", "speaker", "device", "output", "headphones", "usb"), "Headphones and speakers keep their own sound")
        }
        card("Equalizer", id = "eq", keywords = listOf("eq", "bass", "treble", "presets", "bands", "filters", "curve"), summary = "Shape the sound, with presets", needs = EngineOnly) {
            row("Preset", listOf("eq", "presets", "bass boost", "save"), "Ready-made curves, and your own")
            row("Bands", listOf("eq", "graphic", "parametric", "ten bands", "free filters"), "Ten fixed bands or free filters")
            row("No filters yet", listOf("eq", "parametric", "add filter"))
            row("Filter n of m", listOf("eq", "parametric", "filter type", "peak", "shelf"), id = "filter-n-of-m")
            row("Frequency", listOf("eq", "hz", "filter"))
            row("Gain", listOf("eq", "db", "boost", "cut", "filter"))
            row("Width (Q)", listOf("eq", "q", "bandwidth", "filter"))
            row("Filters", listOf("eq", "add filter", "delete filter", "parametric"))
            row("Automatic preamp", listOf("eq", "preamp", "distortion", "clipping", "level"), "Lowers the level so the curve never distorts")
            row("Preamp", listOf("eq", "level", "gain", "distortion", "volume"))
        }
        card("Loudness", keywords = listOf("volume", "levelling", "leveling", "normalize", "replaygain", "preamp"), summary = "Even out the volume between songs", needs = EngineOnly) {
            row("Match loudness", listOf("volume", "replaygain", "normalize", "levelling", "leveling", "album", "song"), "Even out the volume between songs")
            row("Preamp", listOf("replaygain", "volume", "quiet", "level"))
            row("Songs without loudness info", listOf("replaygain", "fallback", "volume", "level"), "The level for songs never measured")
            row("Prevent clipping", listOf("clipping", "distortion", "replaygain", "boost"))
            row("Limiter", listOf("peak", "clipping", "distortion", "loud"), "Catches any peak that would distort")
        }
        card("Balance", keywords = listOf("left", "right", "channel", "mono", "pan"), summary = "Left and right", needs = EngineOnly) {
            row("Left and right", listOf("balance", "pan", "channel", "ear"), "Shift the sound toward one ear")
            row("Mono", listOf("one earbud", "channel", "stereo", "balance"), "Both ears hear the same thing")
        }
        card("Crossfeed", keywords = listOf("headphones", "stereo", "speakers", "fatigue", "bs2b", "chu moy", "meier", "blend"), summary = "Each ear hears a little of the other side, for headphones", needs = EngineOnly) {
            row(CROSSFEED_MONO, listOf("crossfeed", "mono"))
            row("Preset", listOf("crossfeed", "bs2b", "chu moy", "meier", "custom"))
            row(CROSSFEED_CUT_TITLE, listOf("crossfeed", "cutoff", "frequency", "hz"))
            row(CROSSFEED_FEED_TITLE, listOf("crossfeed", "feed", "level", "db"))
        }
        card("Headphone correction", id = "correction", keywords = listOf("headphones", "correction", "profile", "tuning", "autoeq", "parametriceq"), summary = "A correction for the headphones in use", needs = EngineOnly) {
            row("Import a correction", listOf("autoeq", "parametriceq", "file", "headphones", "import another"), "From a ParametricEQ.txt file made for your headphones", id = "import")
            row("Export my curve", listOf("parametriceq", "file", "save", "equalizer", "eq"), "Saves the equalizer as a ParametricEQ.txt file")
        }
    }
    page(SettingsKey.Radio) {
        card(SONG_RADIO, keywords = listOf("radio", "start radio", "autoplay")) {
            row("Discovery", listOf("radio", "song radio", "start radio", "autoplay", "new music", "outside", "library only", "discover"), "How much of a song radio comes from outside your library")
            row("Adventure", listOf("radio", "song radio", "start radio", "autoplay", "focused", "wander", "deep cuts", "similar"), "How far a song radio strays from the song it started from")
            row("Artist variety", listOf("radio", "song radio", "autoplay", "repeat", "spacing", "artist", "album"), "How soon an artist or album comes back")
            row("Favorites more often", listOf("radio", "song radio", "autoplay", "favorite", "hearted", "rating", "stars"), "Hearted and 4 or 5 star songs come around more")
        }
        card(LIVE_RADIO, listed = false) {
            row(FIND_STATIONS_NEAR, listOf("live radio", "radio", "stations", "fm", "am", "local", "near me", "nearby", "internet radio", "location", "place", "kept"), "Live stations on the air around a place, to play or keep")
        }
        card(STATIONS_ON_SERVER, keywords = listOf("live radio", "radio", "radio stations", "internet radio", "streams", "server"), summary = "The live stations your server keeps, for everyone on it") {
            row("No server connected", listOf("stations", "live radio"))
            row("Reading the stations", listOf("stations", "live radio"))
            row("Couldn't read the stations", listOf("stations", "live radio", "try again"))
            row("No stations yet", listOf("stations", "live radio"))
            row("Add a station", listOf("live radio", "radio", "stream", "new station", "internet radio"), "By its stream address, for everyone on your server")
            row("Adding and changing stations", listOf("admin", "stations", "live radio"))
        }
    }
    page(SettingsKey.Lyrics) {
        card("Lyrics", listed = false) {
            row("Find lyrics online", listOf("internet", "lookup", "synced", "words", "lrclib"), "Look lyrics up when your music has none")
            row("Bloom fast singing", listOf("glow", "bloom", "rap", "fast", "swell", "emphasis"), "A word held long for its line swells and glows too")
        }
        card("Timing", listed = false) {
            row("Lyrics timing", listOf("sync", "delay", "latency", "bluetooth", "earbuds", "offset", "late", "early", "behind", "output"), "Words earlier or later on this speaker or headphones")
        }
    }
    page(SettingsKey.Servers) {
        card("Your servers", keywords = listOf("servers", "switch server", "several servers", "accounts", "sign out", "remove server", "in use"), summary = "The servers kept on this computer, and the one in use") {
            row("Add a server", listOf("add server", "another server", "second server", "new server", "account", "sign in", "login"), "Keep another server here and switch to it in one click")
        }
        card("This server", keywords = listOf("server", "version", "navidrome", "octo", "subsonic", "about"), summary = "What the server is, and what it offers") {
            row("In your library", listOf("songs", "albums", "artists", "count", "library"), "How much music the server has")
            row("Signed in as", listOf("account", "username", "user", "login", "password", "change password"), "Who is signed in", id = "signed-in-as")
            row(HANDOVER_TITLE, listOf("qr", "scan", "another device", "phone", "computer", "sign in", "login"), "A QR code your phone or computer scans to sign in", needs = setOf(SettingsNeed.Family))
        }
        card("Maintenance", listed = false) {
            row("Read the library again", listOf("refresh", "reload", "sync", "update", "new music", "missing"), "When new music hasn't shown up yet")
            row("Scan the server", listOf("scan", "refresh", "new music", "server scan"), "Ask the server to look for new music")
            row("Its folders", listOf("scan", "last scan", "new music", "folders"), "When the server last looked for new music")
        }
    }
    page(SettingsKey.Quality) {
        card("Your account, on every app", needs = setOf(SettingsNeed.Family), listed = false) {
            row(QUALITY_AT_HOME, listOf("quality", "bitrate", "family", "home", "account"), "Your account's quality on your home network, for every app")
            row(QUALITY_AWAY, listOf("quality", "bitrate", "family", "away", "data saver", "account"), "Your account's quality anywhere else, for every app")
        }
        card(QUALITY_ON_THIS_DEVICE, needs = setOf(SettingsNeed.Family), listed = false) {
            row(QUALITY_ON_THIS_DEVICE, listOf("quality", "device", "family", "app", "my account", "this app"), "Your account's choice, or this app's")
        }
        card("From your server", id = "stream", listed = false) {
            row("Stream quality", listOf("quality", "bitrate", "kbps", "streaming", "original", "opus", "mp3", "lossless"), "How songs stream from the server")
        }
    }
    page(SettingsKey.Offline) {
        card("Streaming", listed = false) {
            row(START_AFTER_SETTING, listOf("buffer", "preload", "start", "slow", "wait", "stutter"), "How much of a song is ready before it starts")
        }
        card("Kept on this computer", id = "kept", listed = false) {
            row("Kept on this computer", listOf("offline", "downloads", "downloaded", "kept", "check now", "storage"), "What is kept here to play offline")
            row("Folder", listOf("offline", "downloads", "location", "where", "storage", "disk"), "Where kept songs are saved")
            row("Keep Liked songs", listOf("offline", "likes", "liked", "auto download", "favorites"), "Every song you like, as you like it")
            row("Download quality", listOf("bitrate", "kbps", "offline", "file size"), "For songs fetched from now on")
            row(DOWNLOAD_FORMAT_TITLE, listOf("opus", "aac", "mp3", "format", "codec", "offline"), DOWNLOAD_FORMAT_HELP)
        }
        card("Playlists", keywords = listOf("offline", "download playlist", "keep playlist", "kept"), summary = "Playlists kept on this computer")
        row("Remove all", listOf("delete", "downloads", "offline", "free space", "storage", "clear"), "Lets every kept song go and stops keeping any")
    }
    page(SettingsKey.Listening) {
        card("Across your devices", listed = false) {
            row("Tell the server what you play", listOf("scrobble", "report plays", "play counts", "history", "now playing"), "Keeps play counts and history right in every app")
            row("Carry the queue between devices", listOf("queue", "resume", "other device", "continue", "sync", "phone"), "Pick up on your phone where you left off here")
        }
    }
    page(SettingsKey.Look) {
        card("Theme", listed = false) {
            row(THEME_CHOICE, listOf("theme", "light mode", "dark mode", "night", "day", "colors", "colours", "palette", "appearance", "octo", "moss", "dusk", "daylight", "paper"), "Octo, Moss, Dusk, Daylight or Paper")
            row(MATCH_THE_SYSTEM, listOf("match", "system", "automatic", "day", "night", "light mode", "dark mode"), "Day and night themes follow your computer")
        }
        card("Accent and text", listed = false) {
            row(ACCENT_COLOR, listOf("accent", "color", "colour", "tint", "highlight", "dark", "personalize"), "The color of selections, sliders, switches and buttons")
            row("Text size", listOf("font", "bigger", "smaller", "zoom", "scale", "accessibility", "large text"), "Like your system, or larger")
        }
        card("Window background", listed = false) {
            row("Ambience", listOf("glow", "immersive", "background", "colours", "colors", "artwork", "cover", "dark", "plain"), "The song's colors behind the window")
            row("Strength", listOf("glow", "ambience", "background", "brightness", "intensity"), "How strongly the colors show")
            row("Movement", listOf("ambience", "immersive", "motion", "drift", "still", "gentle", "animated"), "How the immersive background moves")
        }
        card("Full player", listed = false) {
            row(AnimatedArtSetting.TITLE, listOf("animated", "cover", "artwork", "video", "loop", "apple music", "album art", "full screen", "square"), AnimatedArtSetting.ABOUT)
            row("Brightness cap", listOf("background", "dim", "white", "readable", "full player"), "How bright the background may get, so words stay readable")
        }
        card("Covers", listed = false) {
            row(PLAYLIST_COVERS_SETTING, listOf("playlist", "cover", "artwork", "mosaic", "picture", "designed"), "Designed covers, or the covers of their albums")
        }
        card("Motion", keywords = listOf("animation", "animations", "moving"), listed = false) {
            row("Moving full player background", listOf("player", "animated", "moving", "motion", "colours", "colors", "live background", "drift"), "The cover's colors drift behind the full player")
            row("Drift speed", listOf("background", "slow", "fast", "motion", "speed", "ambience"), "How fast the background moves")
            row("Move with the beat", listOf("bpm", "beats", "background", "motion", "tempo"), "Slow songs drift slower, quick ones faster")
            row("Calm motion", listOf("reduce motion", "animation", "animations", "accessibility", "still", "calm", "motion sickness", "springs"), "Backgrounds hold still and lyrics move without springs")
        }
    }
    page(SettingsKey.Home) {
        card("Shelves", keywords = listOf("home", "shelves", "sections", "order", "rearrange", "reorder", "move", "hide", "show", "layout", "customize", "edit home", "reset"), summary = "The order of Home's shelves, and which ones show") {
            row(HOME_SECTIONS_TITLE, listOf("home", "shelves", "sections", "order", "rearrange", "reorder", "hide", "show", "layout", "customize", "edit home"), "The order of Home's shelves, and which ones show")
        }
    }
    page(SettingsKey.System) {
        card("Window", keywords = listOf("system", "window"), listed = false) {
            row("Keep playing when the window closes", listOf("tray", "menu bar", "close", "background", "minimize", "hide"), "Octo stays in the tray", needs = setOf(SettingsNeed.Tray))
            row("Now playing notifications", listOf("notifications", "notify", "toast", "popup", "song change"), "While Octo's window is behind others", needs = setOf(SettingsNeed.Notifications))
            row("Use the system title bar", listOf("title bar", "frame", "window border", "native", "decorations"), "Takes effect the next time Octo opens", needs = setOf(SettingsNeed.NotMac))
            row("Media keys", listOf("keyboard", "play pause", "media controls", "headset buttons"), "Whether the media keys reach Octo")
            row("NVIDIA overlay", listOf("nvidia", "geforce", "overlay", "share", "instant replay", "memory", "game", "popup"), "Stops NVIDIA's game overlay popping up when Octo opens", needs = setOf(SettingsNeed.Windows))
        }
        card("Startup", needs = setOf(SettingsNeed.Windows), listed = false) {
            row("Start with Windows", listOf("startup", "sign in", "login", "boot", "launch", "autostart"), "Octo opens when you sign in")
            row("Start in the tray", listOf("startup", "tray", "hidden", "minimized", "background"), "Waits in the tray instead of opening its window", needs = setOf(SettingsNeed.Tray))
        }
        card("Mini player", listed = false) {
            row("Mini player", listOf("small window", "compact", "miniplayer", "widget", "ctrl+m"), "A small window in place of this one")
            row("Keep it above other windows", listOf("always on top", "on top", "mini player", "float"), "The mini player stays above other windows")
            row("Pin it in place", listOf("lock", "pin", "mini player", "move", "position"), "The mini player stays where it is")
        }
    }
    page(SettingsKey.Keys) {
        card("Global shortcuts", keywords = listOf("hotkeys", "global", "keyboard", "shortcuts", "any app", "media keys"), summary = "Keys that work from any app", needs = setOf(SettingsNeed.Windows)) {
            row("Use these keys from any app", listOf("hotkeys", "global", "shortcuts", "keyboard", "background"), "Play, skip and set the volume from any app")
            row("Use the usual keys", listOf("reset", "default keys", "hotkeys", "shortcuts"))
        }
        card("Octo's keys", keywords = listOf("keyboard", "shortcuts", "keys", "hotkeys", "cheat sheet"), summary = "Every key Octo answers to")
    }
    page(SettingsKey.Discord, SettingsNeed.Discord) {
        card("Sharing", listed = false) {
            row("Show what you're playing", listOf("discord", "rich presence", "status", "activity", "friends", "share"), "The song, its cover and the artist, in your Discord status")
            row("Include songs opened from files", listOf("discord", "files", "private", "local files"), "Off, they stay private")
        }
        card("Status", keywords = listOf("discord", "rich presence"), listed = false) {
            row("Friends' list shows", listOf("discord", "friends", "list", "name", "song", "artist"))
            row("First line", listOf("discord", "status", "text", "title", "template"), "{title}, {artist} and {album} are filled in")
            row("Second line", listOf("discord", "status", "text", "template"))
            row("Lines as they were", listOf("discord", "reset", "default"))
            row("Time", listOf("discord", "elapsed", "remaining", "timer", "clock"))
        }
        card("Pictures", keywords = listOf("discord"), listed = false) {
            row("Picture", listOf("discord", "cover", "artwork", "image", "icon"), "Found on iTunes or Deezer by name")
            row("Badge", listOf("discord", "artist photo", "small image", "icon"))
            row("Album name on the cover", listOf("discord", "album", "hover", "tooltip"))
        }
        card("More", keywords = listOf("discord"), listed = false) {
            row("Keep showing while paused", listOf("discord", "pause", "paused", "status"), "With a pause badge")
            row("Link to Last.fm", listOf("discord", "last.fm", "lastfm", "button", "link"), "Friends get an Open on Last.fm button")
        }
    }
    page(SettingsKey.About) {
        card("Updates", keywords = listOf("update", "new version", "upgrade"), listed = false) {
            row("Update ready", listOf("update", "install", "restart", "new version"), "A new version is ready to install", id = "update-ready")
            row("New versions", listOf("update", "check now", "new version", "upgrade", "github"), "Look for a new version of Octo")
            row("Check for updates automatically", listOf("update", "new version", "upgrade", "auto update"), "Looks every few hours and downloads in the background")
            row("Install updates", listOf("update", "install", "automatic", "upgrade"), "Ask me, or when I leave Octo", needs = setOf(SettingsNeed.Windows))
            row("Try early versions", listOf("beta", "pre-release", "prerelease", "update"), "New versions before they're finished")
        }
        card("Help", listed = false) {
            row("Details for a bug report", listOf("bug", "issue", "report", "logs", "version", "copy", "support"), "Version, graphics and memory, to paste into an issue")
        }
    }
}

// The pages this window lists, each found by its name and words.
private fun pageEntries(pages: List<SettingsPageInfo>): List<SettingsIndexEntry> =
    pages.map { SettingsIndexEntry(it.key, null, it.name, it.words.split(' ')) }

// What Settings' search can find in this window: every page it lists, and
// every setting there that this window shows.
fun settingsIndexFor(here: SettingsHere): List<SettingsIndexEntry> {
    val pages = settingsPages(here.os == DesktopOs.Windows, here.discord)
    val listed = pages.map { it.key }.toSet()
    return pageEntries(pages) + DesktopSettingsIndex.filter { it.page in listed && it.shows(here) }
}

// The settings matching what was typed in this window, best first: a title
// starting with it, then titles holding every word, then other words, then
// the line about it.
fun searchDesktopSettings(query: String, here: SettingsHere): List<SettingsHit> {
    val names = settingsPages(here.os == DesktopOs.Windows, here.discord).associate { it.key to it.name }
    return rankSettings(
        query,
        settingsIndexFor(here),
        title = { it.title },
        other = { (it.keywords + listOfNotNull(names[it.page], it.card)).joinToString(" ") },
        summary = { it.summary },
    ).map { SettingsHit(it, names.getValue(it.page)) }
}
