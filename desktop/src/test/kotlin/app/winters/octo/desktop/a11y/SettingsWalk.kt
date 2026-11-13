package app.winters.octo.desktop.a11y

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import app.winters.octo.design.ArrowKeys
import app.winters.octo.design.FocusVisibility
import app.winters.octo.design.LocalTyping
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.ProvideWindowLook
import app.winters.octo.design.TypingState
import app.winters.octo.audio.AutomixSettings
import app.winters.octo.audio.DspSettings
import app.winters.octo.audio.EqSettings
import app.winters.octo.audio.ReplayGainSettings
import app.winters.octo.desktop.FakeServer
import app.winters.octo.desktop.audio.SoundTarget
import app.winters.octo.desktop.hotkeys.HotkeyAction
import app.winters.octo.desktop.nav.SettingsKey
import app.winters.octo.desktop.nav.settingsPages
import app.winters.octo.desktop.pages.CardIcon
import app.winters.octo.desktop.pages.CardIconInfo
import app.winters.octo.desktop.pages.LocalSettingsAnchors
import app.winters.octo.desktop.pages.RowId
import app.winters.octo.desktop.pages.RowTitle
import app.winters.octo.desktop.pages.RowTitleInfo
import app.winters.octo.desktop.pages.SettingsAnchors
import app.winters.octo.desktop.pages.SettingsCard
import app.winters.octo.desktop.pages.SettingsHere
import app.winters.octo.desktop.player.DesktopPlayer
import app.winters.octo.desktop.player.OutputDevice
import app.winters.octo.desktop.player.PlayerState
import app.winters.octo.desktop.player.SilentPlayer
import app.winters.octo.desktop.settings.AmbienceStyle
import app.winters.octo.desktop.settings.AppSettings
import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.desktop.settings.SHARED_SOUND
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.desktop.system.NVIDIA_OVERLAY_TITLE
import app.winters.octo.desktop.system.NvidiaOverlayRow
import app.winters.octo.desktop.system.NvidiaRow
import app.winters.octo.desktop.system.StartupRows
import app.winters.octo.desktop.system.SystemPrefs
import app.winters.octo.desktop.update.DesktopUpdates
import app.winters.octo.desktop.update.FakeReleases
import app.winters.octo.desktop.update.UpdaterAvailability
import app.winters.octo.desktop.update.installerFor
import app.winters.octo.playback.BetweenSongs
import app.winters.octo.sound.Crossfeed
import app.winters.octo.sound.CrossfeedPreset
import app.winters.octo.sound.EqFilter
import app.winters.octo.sound.EqMode
import app.winters.octo.sound.HeadphoneCorrection
import app.winters.octo.sound.ParametricEqFile
import app.winters.octo.sound.ReplayGainMode
import app.winters.octo.sound.SoundSettings
import app.winters.octo.update.PlayerApp
import app.winters.octo.update.PlayerUpdater
import app.winters.octo.update.PlayerVersion
import app.winters.octo.ui.family.QUALITY_AT_HOME
import app.winters.octo.ui.family.QUALITY_ON_THIS_DEVICE
import app.winters.octo.update.ReleaseFeed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

// Every Settings page drawn in the real window, on each computer and build,
// with every row a setting can hide shown, and the rows only some states
// draw: a station list or none, an admin or not, an update ready, a filter
// and a correction loaded, an output playing, no sound engine. Walked once
// a run and kept, so the tests that read it share one walk.
internal object SettingsWalk {
    // One row as drawn: its page, its title, and the id it is found by.
    class Drawn(val page: String, val title: RowTitleInfo, val id: String?)

    // One card's title line as drawn: its page, its title and icon, and the
    // card's id (a card whose title changes with the window keeps it).
    class DrawnCard(val page: String, val card: CardIconInfo, val id: String?)

    // One window walked: what it has, every row drawn in it, and every card
    // title drawn with an icon.
    class Window(val name: String, val here: SettingsHere, val rows: List<Drawn>, val cards: List<DrawnCard> = emptyList())

    val windows: List<Window> by lazy { walk() }

    // The engine's sound settings reach nothing; enough for Settings to
    // offer what the engine shapes. With `on`, songs play on that output.
    class ShapingPlayer(private val inner: DesktopPlayer = SilentPlayer(), on: OutputDevice? = null) : DesktopPlayer by inner, SoundTarget {
        override val deviceKey = MutableStateFlow<String?>("spk")
        override val state: StateFlow<PlayerState> = if (on == null) inner.state else MutableStateFlow(inner.state.value.copy(playingOn = on))
        override fun shape(eq: EqSettings, replayGain: ReplayGainSettings, dsp: DspSettings) = Unit
        override fun setCrossfade(ms: Int) = Unit
        override fun setAutomix(settings: AutomixSettings) = Unit
        override fun setSpeed(speed: Float, pitch: Float) = Unit
    }

    // Every row a setting can hide, shown.
    val everyRowShown: (AppSettings) -> AppSettings = { it.withSound(SoundSettings(mode = EqMode.Parametric, autoPreamp = false, replayGain = ReplayGainMode.Track, mono = true, crossfeed = Crossfeed(on = true, preset = CrossfeedPreset.Custom))) }

    // As above, with a filter on the curve and a headphone correction loaded.
    private val withFilterAndCorrection: (AppSettings) -> AppSettings = {
        everyRowShown(it).withSound(
            SoundSettings(
                mode = EqMode.Parametric,
                autoPreamp = false,
                replayGain = ReplayGainMode.Track,
                mono = true,
                crossfeed = Crossfeed(on = true, preset = CrossfeedPreset.Custom),
                filters = listOf(EqFilter(frequency = 1_000f, gainDb = 3f, q = 1f)),
                correction = HeadphoneCorrection("Studio cans", ParametricEqFile.IMPORTED, -3f, listOf(EqFilter(frequency = 120f, gainDb = -2f, q = 0.7f))),
            ),
        )
    }

    private fun AppSettings.withSound(sound: SoundSettings): AppSettings = copy(
        appearance = appearance.copy(ambientGlow = true, ambience = AmbienceStyle.Immersive, calmMotion = false, wash = appearance.wash.copy(moving = true)),
        discord = discord.copy(on = true, firstLine = "{title} by {artist}"),
        hotkeys = hotkeys.copy(on = true, keys = mapOf(HotkeyAction.Like.id to "Ctrl+Alt+L")),
        // Crossfade, so its length and curve show; the windows as set by
        // default show Automix's rows.
        playback = playback.copy(betweenSongs = BetweenSongs.Crossfade, crossfadeLength = 6, speed = 1.25f),
        sound = this.sound.copy(profiles = mapOf(SHARED_SOUND to sound)),
    )

    // The pretend server's answers for an admin, or for someone who isn't.
    private fun FakeServer.user(admin: Boolean) = answer("getUser", """"user":{"username":"winters","adminRole":$admin}""")

    private fun FakeServer.scanned() = answer("getScanStatus", """"scanStatus":{"scanning":false,"count":120,"folderCount":3,"lastScan":"2026-11-01T10:00:00Z"}""")

    // A family plan that limits nothing, and this computer among its
    // devices, its quality left to the account.
    fun FakeServer.family() {
        answer(
            "getFamily",
            """"family":{"me":{"username":"winters","displayName":"Jordan","role":"Owner","managed":false,
            "abilities":{"addToLibrary":"Direct","requestQuality":"Best","autoApprove":true,"weeklyRequestLimit":0,"streamCap":0,"awayCap":0,
            "away":true,"devicesAtOnce":0,"downloadFiles":true,"offlineCopies":true,"share":true,"importPlaylists":true,"cleanOnly":false,
            "familyPlaylistsEdit":true,"manageFamily":true,"approveRequests":true,"storageLimitGb":0,"instantFromFamily":true},
            "requestsThisWeek":0,"storageUsedBytes":0,"place":"Home","deviceId":"d_1",
            "quality":{"home":"Original","away":"Standard","familyLimitKbps":0,"familyAwayLimitKbps":0}}}""",
            type = "octo",
        )
        answer(
            "getFamilyDevices",
            """"familyDevices":{"device":[{"id":"d_1","username":"winters","name":"Studio PC","kind":"OctoApp","app":"Octo (Windows)","created":"","lastSeen":"","place":"Home","playing":null,"current":true,"quality":"Account"}]}""",
            type = "octo",
        )
    }

    private fun FakeServer.stations(vararg names: String) = answer(
        "getInternetRadioStations",
        """"internetRadioStations":{"internetRadioStation":[${names.mapIndexed { i, name -> """{"id":"st$i","name":"$name","streamUrl":"https://radio.example/$i"}""" }.joinToString(",")}]}""",
    )

    // An updater that has found and fetched version 1.2.0 from a pretend
    // GitHub on this computer.
    fun readyUpdates(github: FakeReleases, folder: File): (SettingsStore) -> DesktopUpdates = { settings ->
        github.publish("1.2.0", "- Octo keeps itself up to date.\n- Lyrics find their place faster after a seek.")
        val http = OkHttpClient()
        val running = PlayerVersion.parse("1.1.0")!!
        val downloads = File(folder, "updates").apply { mkdirs() }
        val updater = PlayerUpdater(
            PlayerApp.Desktop, running,
            ReleaseFeed(http, File(downloads, "releases.json"), "Octo test", apiBase = github.api),
            http, downloads, listOf(github.publicKey),
            pick = { installerFor(it, DesktopOs.Windows, "x64", null) },
        )
        DesktopUpdates(settings, UpdaterAvailability.On(running), DesktopOs.Windows, downloads, updater, start = { error("nothing installs in a test") }).also { updates ->
            runBlocking { updates.check() }
            check(updates.ready != null) { "the update is ready" }
        }
    }

    // Where a row is found: its own id, or the card holding a title's switch.
    private fun SemanticsNode.placeId(): String? {
        var at: SemanticsNode? = this
        while (at != null) {
            at.config.getOrNull(RowId)?.let { return it }
            at = at.parent
        }
        return null
    }

    private fun walk(): List<Window> {
        val root = Files.createTempDirectory("octo-settings-walk").toFile()
        // A build that carries a Discord application, so its page shows.
        System.setProperty("octo.discordAppId", "1234567890123456789")
        try {
            val windows = mutableListOf<Window>()
            FakeReleases().use { github ->
                windows += window(
                    "Windows, every row shown, a family server, an admin with a station, an update ready, a filter and a correction",
                    File(root, "w1"), DesktopOs.Windows, withFilterAndCorrection, family = true,
                    serve = { it.user(admin = true); it.scanned(); it.stations("Night Owl FM"); it.family() },
                    updates = readyUpdates(github, File(root, "w1-updates")),
                    before = { s -> s.app.offline.keepLiked(true) },
                    waits = mapOf(
                        SettingsKey.Radio to "[each station]",
                        SettingsKey.Servers to "Scan the server",
                        SettingsKey.Offline to "Remove all",
                        SettingsKey.Quality to QUALITY_ON_THIS_DEVICE,
                    ),
                )
            }
            windows += window(
                "Windows as set by default, playing on its speakers, its stations unread",
                File(root, "w2"), DesktopOs.Windows, { it }, family = false,
                player = ShapingPlayer(on = OutputDevice("spk", "Speakers")),
                waits = mapOf(SettingsKey.Radio to "Couldn't read the stations"),
            )
            windows += window(
                "macOS, every row shown, someone not an admin, no stations",
                File(root, "w3"), DesktopOs.Mac, everyRowShown, family = false,
                serve = { it.user(admin = false); it.scanned(); it.stations() },
                waits = mapOf(SettingsKey.Radio to "No stations yet", SettingsKey.Servers to "Its folders"),
            )
            val held = CountDownLatch(1)
            windows += window(
                "Linux, every row shown, a family server, its stations still on their way",
                File(root, "w4"), DesktopOs.Linux, everyRowShown, family = true,
                serve = { server ->
                    server.family()
                    server.answerBy("getInternetRadioStations") { held.await(10, TimeUnit.SECONDS); server.ok() }
                },
                waits = mapOf(SettingsKey.Radio to "Reading the stations", SettingsKey.Quality to QUALITY_AT_HOME),
                // The held answer goes before the server closes.
                after = { held.countDown() },
            )
            windows += window(
                "Windows without the sound engine",
                File(root, "w5"), DesktopOs.Windows, { it }, family = false,
                player = SilentPlayer(),
                only = setOf(SettingsKey.Sound, SettingsKey.Playback),
            )
            windows += installedStartup()
            windows += nvidiaOverlay()
            return windows
        } finally {
            System.clearProperty("octo.discordAppId")
            root.deleteRecursively()
        }
    }

    // The startup card as the installed app draws it, starting with Windows
    // and so offering the tray: a build run from source has no program for
    // Windows to start, so its window shows the card's one note instead.
    @OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
    private fun installedStartup(): Window {
        lateinit var scene: ImageComposeScene
        SwingUtilities.invokeAndWait {
            scene = ImageComposeScene(900, 400, Density(1f)) {
                ProvideWindowLook(reduceMotion = true, focus = FocusVisibility(keyboard = false), arrows = ArrowKeys()) {
                    CompositionLocalProvider(LocalTyping provides TypingState(), LocalSettingsAnchors provides SettingsAnchors()) {
                        Column { StartupRows(SystemPrefs(startWithWindows = true), works = true, turnedOff = false, tray = true) {} }
                    }
                }
            }
        }
        val rows = mutableListOf<Drawn>()
        try {
            repeat(3) { SwingUtilities.invokeAndWait { scene.render() } }
            SwingUtilities.invokeAndWait {
                fun walk(node: SemanticsNode) {
                    node.config.getOrNull(RowTitle)?.let { rows += Drawn(SettingsKey.System, it, node.placeId()) }
                    node.children.forEach(::walk)
                }
                scene.semanticsOwners.forEach { walk(it.unmergedRootSemanticsNode) }
            }
        } finally {
            SwingUtilities.invokeAndWait { scene.close() }
        }
        check(rows.any { it.title.stable == "Start in the tray" }) { "the installed startup card draws Start in the tray" }
        return Window("Windows, the installed app's startup card", SettingsHere(DesktopOs.Windows, tray = true, notifications = true, discord = false, engine = true, family = false), rows)
    }

    // The NVIDIA overlay row as an installed app on an NVIDIA computer draws
    // it, offered and kept off: the walk's own scenes run from source, where
    // the row stays out.
    @OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
    private fun nvidiaOverlay(): Window {
        lateinit var scene: ImageComposeScene
        SwingUtilities.invokeAndWait {
            scene = ImageComposeScene(900, 400, Density(1f)) {
                ProvideWindowLook(reduceMotion = true, focus = FocusVisibility(keyboard = false), arrows = ArrowKeys()) {
                    CompositionLocalProvider(LocalTyping provides TypingState(), LocalSettingsAnchors provides SettingsAnchors()) {
                        // In its card, as Settings > System draws it.
                        SettingsCard("Window", OctoIcons.System) {
                            NvidiaOverlayRow(NvidiaRow.Offer(null)) {}
                            NvidiaOverlayRow(NvidiaRow.Kept(justNow = true)) {}
                        }
                    }
                }
            }
        }
        val rows = mutableListOf<Drawn>()
        try {
            repeat(3) { SwingUtilities.invokeAndWait { scene.render() } }
            SwingUtilities.invokeAndWait {
                fun walk(node: SemanticsNode) {
                    node.config.getOrNull(RowTitle)?.let { rows += Drawn(SettingsKey.System, it, node.placeId()) }
                    node.children.forEach(::walk)
                }
                scene.semanticsOwners.forEach { walk(it.unmergedRootSemanticsNode) }
            }
        } finally {
            SwingUtilities.invokeAndWait { scene.close() }
        }
        check(rows.count { it.title.stable == NVIDIA_OVERLAY_TITLE } == 2) { "both NVIDIA rows are drawn: $rows" }
        return Window("Windows with NVIDIA, the installed app's overlay row", SettingsHere(DesktopOs.Windows, tray = true, notifications = true, discord = false, engine = true, family = false), rows)
    }

    private fun window(
        name: String,
        folder: File,
        os: DesktopOs,
        look: (AppSettings) -> AppSettings,
        family: Boolean,
        player: DesktopPlayer = ShapingPlayer(),
        serve: (FakeServer) -> Unit = {},
        updates: ((SettingsStore) -> DesktopUpdates)? = null,
        before: (A11yScene) -> Unit = {},
        // A row title each page is drawn until it shows, for states a
        // server's answer brings.
        waits: Map<String, String> = emptyMap(),
        only: Set<String>? = null,
        after: () -> Unit = {},
    ): Window {
        folder.mkdirs()
        return A11yScene(
            folder,
            reduceMotion = true,
            look = look,
            extensions = if (family) listOf("octoFamily:1") else listOf(),
            player = player,
            os = os,
            withSystem = true,
            serve = serve,
            updates = updates,
            saved = true,
        ).use { s ->
            s.onUi { before(s) }
            val system = s.system!!
            val here = SettingsHere(
                os,
                tray = system.trayAvailable,
                notifications = system.notificationsAvailable,
                discord = system.discord != null,
                engine = s.app.sound != null,
                family = s.app.connection?.family == true,
            )
            val rows = mutableListOf<Drawn>()
            val cards = mutableListOf<DrawnCard>()
            for (page in settingsPages(os == DesktopOs.Windows, here.discord)) {
                if (only != null && page.key !in only) continue
                s.onUi { s.app.openSettings(page.key) }
                s.render(4)
                fun titles() = s.nodes().mapNotNull { node -> node.config.getOrNull(RowTitle)?.let { node to it } }
                waits[page.key]?.let { wanted ->
                    val end = System.currentTimeMillis() + 8_000
                    while (titles().none { it.second.stable == wanted }) {
                        check(System.currentTimeMillis() < end) { "$name: ${page.key} never drew \"$wanted\"" }
                        s.render(2)
                    }
                }
                titles().forEach { (node, title) -> rows += Drawn(page.key, title, node.placeId()) }
                s.nodes().forEach { node -> node.config.getOrNull(CardIcon)?.let { cards += DrawnCard(page.key, it, node.placeId()) } }
            }
            after()
            // Let the held answer reach the app before the server closes.
            s.render(2)
            Window(name, here, rows, cards)
        }
    }
}
