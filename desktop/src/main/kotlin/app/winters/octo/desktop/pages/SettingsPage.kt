package app.winters.octo.desktop.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import app.winters.octo.animated.AnimatedArtSetting
import app.winters.octo.covers.PLAYLIST_COVERS_SETTING
import app.winters.octo.covers.PlaylistCoverStyle
import app.winters.octo.covers.playlistCoverStyleHelp
import app.winters.octo.covers.playlistCoverStyleName
import app.winters.octo.design.ControlHeight
import app.winters.octo.design.DesktopType
import app.winters.octo.design.GlazeCapsule
import app.winters.octo.design.LocalReduceMotion
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.RowHeight
import app.winters.octo.design.SettingsSize
import app.winters.octo.design.Space
import app.winters.octo.design.Txt
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.hotkeys.GlobalShortcutGroup
import app.winters.octo.desktop.lyrics.BLUETOOTH_OUTPUT_TIMING_MS
import app.winters.octo.desktop.lyrics.OUTPUT_TIMING_LIMIT_MS
import app.winters.octo.desktop.lyrics.WIRED_OUTPUT_TIMING_MS
import app.winters.octo.desktop.lyrics.outputTimingAbout
import app.winters.octo.desktop.lyrics.outputTimingOf
import app.winters.octo.desktop.lyrics.signedTiming
import app.winters.octo.desktop.lyrics.timingWords
import app.winters.octo.desktop.nav.SettingsKey
import app.winters.octo.desktop.nav.Visit
import app.winters.octo.desktop.nav.settingsPages
import app.winters.octo.desktop.nav.shortcutList
import app.winters.octo.desktop.server.Connection
import app.winters.octo.desktop.server.OCTO_LYRICS
import app.winters.octo.desktop.setAutoplay
import app.winters.octo.desktop.setLibraryOnly
import app.winters.octo.desktop.settings.AmbienceMotion
import app.winters.octo.desktop.settings.AmbienceStyle
import app.winters.octo.desktop.settings.AppSettings
import app.winters.octo.desktop.settings.Appearance
import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.desktop.settings.MaxTextScale
import app.winters.octo.desktop.settings.WashPrefs
import app.winters.octo.desktop.settings.animatedArtSetting
import app.winters.octo.desktop.system.DiscordRows
import app.winters.octo.desktop.system.MiniPlayerGroup
import app.winters.octo.desktop.system.SystemRows
import app.winters.octo.desktop.system.TaskbarAndStartupRows
import app.winters.octo.desktop.system.hasDiscord
import app.winters.octo.desktop.ui.LocalSoftwareDrawing
import app.winters.octo.discovery.LIBRARY_ONLY_HELP
import app.winters.octo.discovery.LIBRARY_ONLY_SETTING
import app.winters.octo.server.serverOffers
import kotlin.math.roundToInt

// Settings: one page at a time, picked in Settings' list in the sidebar's
// place (SettingsNav.kt), the one shown kept in the app's SettingsUi. Each
// page is a title, a line about it and its titled cards. Every change is
// saved at once.

// The Radio page's key, for a link straight to it.
const val RADIO_SECTION = SettingsKey.Radio

@Composable
fun SettingsPage(app: AppState, visit: Visit) {
    val settings by app.settings.state.collectAsState()
    val pages = settingsPages(app.os == DesktopOs.Windows, hasDiscord())
    val page = pages.firstOrNull { it.key == app.settingsUi.page } ?: pages.first()
    key(page.key) {
        SettingsPageView(app, visit, page.key, page.name, settingsLede(app, page.key)) {
            when (page.key) {
                SettingsKey.Playback -> PlaybackCards(app, settings)
                SettingsKey.Sound -> SoundCards(app)
                SettingsKey.Radio -> RadioRows(app, settings)
                SettingsKey.Lyrics -> LyricsCards(app, settings)
                SettingsKey.Servers -> ServerRows(app)
                SettingsKey.Quality -> AudioQualityRows(app, settings)
                SettingsKey.Offline -> OfflineRows(app, settings)
                SettingsKey.Listening -> ListeningCards(app, settings)
                SettingsKey.Look -> AppearanceCards(app, settings)
                SettingsKey.Home -> HomeSectionsGroup(app, settings.appearance.homeSections)
                SettingsKey.System -> {
                    SystemRows(app)
                    TaskbarAndStartupRows(app)
                    MiniPlayerGroup(app)
                }
                SettingsKey.Keys -> {
                    GlobalShortcutGroup(app)
                    KeyRows(app)
                }
                SettingsKey.Discord -> DiscordRows(app)
                SettingsKey.About -> AboutRows(app, settings)
            }
        }
    }
}

// The line under each page's title: what the page is for.
@Composable
private fun settingsLede(app: AppState, key: String): String? = when (key) {
    SettingsKey.Playback -> "What plays next, and how songs meet."
    SettingsKey.Sound -> soundLede(app)
    SettingsKey.Radio -> "Song radio from your music, and live radio on the air."
    SettingsKey.Lyrics -> "Where lyrics come from, and when they show."
    SettingsKey.Servers -> "Your servers, and what the one in use holds."
    SettingsKey.Quality -> "At home, away, and on this computer."
    SettingsKey.Offline -> "How songs start, and the ones kept here to play offline."
    SettingsKey.Listening -> "What your server hears, and your queue on every device."
    SettingsKey.Look -> "How Octo looks: colors, backgrounds and text."
    SettingsKey.Home -> "Home's shelves, in their order."
    SettingsKey.System -> if (app.os == DesktopOs.Windows) "Octo's window, how it starts, and the mini player." else "Octo's window and the mini player."
    SettingsKey.Keys -> "Keys that work from any app, and Octo's own."
    SettingsKey.Discord -> "Show what you're playing to your friends."
    SettingsKey.About -> "The version, updates and help."
    else -> null
}

// What the server brings beyond the music, in plain words.
internal fun serverOffers(connection: Connection): String? =
    serverOffers(lyrics = connection.lyricsByIdOn || connection.supports(OCTO_LYRICS), adds = connection.acquires)

// What follows the queue's end, songs found online, then, with the sound
// engine, how songs blend and how fast they play.
@Composable
private fun PlaybackCards(app: AppState, settings: AppSettings) {
    SettingsCard("When the queue ends", OctoIcons.Queue) {
        SwitchRow("Autoplay", "Similar songs keep playing, picked as Radio says.", settings.playback.autoplay) { on ->
            app.setAutoplay(on)
        }
    }
    SettingsCard("Songs found online", OctoIcons.Globe) {
        SwitchRow(LIBRARY_ONLY_SETTING, LIBRARY_ONLY_HELP, settings.libraryOnly) { on -> app.setLibraryOnly(on) }
    }
    CrossfadeAndSpeed(app)
}

// The accent and the text size, the window's background, the full player,
// playlist covers, then how much moves. The theme's card goes first, ahead
// of these. Each tuning row shows only while it has something to change;
// hidden, it hands an ask for it to the row that shows it.
@Composable
private fun AppearanceCards(app: AppState, settings: AppSettings) {
    val look = settings.appearance
    fun appearance(edit: (Appearance) -> Appearance) = app.settings.update { it.copy(appearance = edit(it.appearance)) }
    fun wash(edit: (WashPrefs) -> WashPrefs) = appearance { it.copy(wash = edit(it.wash)) }
    val chosen = ambienceOf(look)
    val showing by app.palette.collectAsState()
    SettingsCard("Theme", OctoIcons.Switches) { ThemeRows(app, settings) }
    SettingsCard("Accent and text", OctoIcons.Appearance) {
        AccentRow(look.accent, showing.theme) { accent -> appearance { it.copy(accent = accent) } }
        val system = if (app.os == DesktopOs.Windows) "Windows" else "your system"
        ChoiceRow(
            "Text size",
            "Like $system follows its own text size, up to ${(MaxTextScale * 100).roundToInt()}%.",
            TextSizes,
            look.textSize.takeIf { it in TextSizes } ?: 0,
            { size -> if (size == 0) "Like $system" else "$size%" },
        ) { size -> appearance { it.copy(textSize = size) } }
    }
    SettingsCard("Window background", OctoIcons.Backdrop) {
        PictureChoiceRow("Ambience", ambienceHelp(chosen), listOf(null, AmbienceStyle.Glow, AmbienceStyle.Immersive), chosen, ::ambienceName, { style ->
            appearance { withAmbience(it, style) }
        }) { AmbiencePicture(it) }
        if (chosen != null) {
            SliderRow("Strength", null, "${(look.glowStrength * 100).roundToInt()}%", look.glowStrength, { value ->
                appearance { it.copy(glowStrength = value) }
            })
        } else {
            HiddenRow("strength", by = "ambience")
        }
        if (chosen == AmbienceStyle.Immersive) {
            val detail = when {
                LocalSoftwareDrawing.current -> "Holds still here: this computer draws Octo without its graphics card."
                LocalReduceMotion.current -> "Holds still while motion is reduced, by Calm motion or your system."
                else -> motionHelp(look.ambienceMotion)
            }
            ChoiceRow("Movement", detail, AmbienceMotion.entries, look.ambienceMotion, ::motionName) { motion ->
                appearance { it.copy(ambienceMotion = motion) }
            }
        } else {
            HiddenRow("movement", by = "ambience")
        }
    }
    SettingsCard("Full player", OctoIcons.Enlarge) {
        val animated = look.animatedArtSetting
        val animatedHelp = when {
            animated == AnimatedArtSetting.Off -> animated.about
            LocalReduceMotion.current -> "Shows the still cover while motion is reduced, by Calm motion or your system."
            else -> animated.about
        }
        PictureChoiceRow(AnimatedArtSetting.TITLE, animatedHelp, AnimatedArtSetting.entries, animated, AnimatedArtSetting::label, { choice ->
            appearance { it.copy(animatedArt = choice.key) }
        }) { AnimatedArtPicture(it) }
        SliderRow("Brightness cap", "Lower it if bright covers make words hard to read.", "${look.wash.brightnessCap}%", (look.wash.brightnessCap - 20) / 80f, { x ->
            wash { it.copy(brightnessCap = (20 + x * 80).roundToInt()) }
        }, live = false)
    }
    SettingsCard("Covers", OctoIcons.Playlists) {
        PictureChoiceRow(PLAYLIST_COVERS_SETTING, playlistCoverStyleHelp(look.playlistCovers), PlaylistCoverStyle.entries, look.playlistCovers, ::playlistCoverStyleName, { style ->
            appearance { it.copy(playlistCovers = style) }
        }) { PlaylistCoverPicture(it) }
    }
    SettingsCard("Motion", OctoIcons.Wave) {
        SwitchRow(MOVING_BACKGROUND, "The cover's colors drift behind the full player.", look.wash.moving) { on ->
            wash { it.copy(moving = on) }
        }
        if (look.wash.moving && !look.calmMotion) {
            SliderRow("Drift speed", null, "${look.wash.speed}%", (look.wash.speed - 5) / 95f, { x ->
                wash { it.copy(speed = (5 + x * 95).roundToInt()) }
            }, live = false)
            SwitchRow("Move with the beat", "Slow songs drift slower, quick ones faster.", look.wash.useBpm) { on ->
                wash { it.copy(useBpm = on) }
            }
        } else {
            val by = settingsSlug(if (look.wash.moving) CALM_MOTION else MOVING_BACKGROUND)
            HiddenRow("drift-speed", by)
            HiddenRow("move-with-the-beat", by)
        }
        SwitchRow(CALM_MOTION, "Backgrounds hold still and lyrics move without springs.", look.calmMotion) { on ->
            appearance { it.copy(calmMotion = on) }
        }
    }
}

private const val MOVING_BACKGROUND = "Moving full player background"
private const val CALM_MOTION = "Calm motion"

// The text sizes to choose from: the system's, then Octo's own steps.
private val TextSizes = listOf(0, 100, 115, 130)

// The window's ambience as one choice, null being off.
internal fun ambienceOf(look: Appearance): AmbienceStyle? = if (look.ambientGlow) look.ambience else null

// The ambience chosen, kept as the switch and the look it had before, so
// turning it back on returns the same look.
internal fun withAmbience(look: Appearance, style: AmbienceStyle?): Appearance =
    if (style == null) look.copy(ambientGlow = false) else look.copy(ambientGlow = true, ambience = style)

// What the server hears of what plays here, and the queue between devices.
@Composable
private fun ListeningCards(app: AppState, settings: AppSettings) {
    val listening = settings.listening
    SettingsCard("Across your devices", OctoIcons.Device) {
        SwitchRow(
            "Tell the server what you play",
            "Keeps play counts and history right in every app.",
            listening.reportPlays,
        ) { on -> app.settings.update { it.copy(listening = it.listening.copy(reportPlays = on)) } }
        SwitchRow(
            "Carry the queue between devices",
            "Pick up on your phone where you left off here, and back.",
            listening.syncQueue,
        ) { on -> app.settings.update { it.copy(listening = it.listening.copy(syncQueue = on)) } }
    }
}

// Where lyrics come from and how they bloom, then their timing.
@Composable
private fun LyricsCards(app: AppState, settings: AppSettings) {
    SettingsCard("Lyrics", OctoIcons.Lyrics) {
        SwitchRow(
            "Find lyrics online",
            "Asks LRCLIB when your server has none, sending only the song's names.",
            settings.lyrics.online,
        ) { on ->
            app.settings.update { it.copy(lyrics = it.lyrics.copy(online = on)) }
            app.lyrics.state.value.song?.let { song -> app.lyrics.sources.refresh(song.id) }
        }
        SwitchRow(
            "Bloom fast singing",
            "Words held long for their line glow too. Off, only notes held a second or more.",
            settings.lyrics.bloomFast,
        ) { on -> app.settings.update { it.copy(lyrics = it.lyrics.copy(bloomFast = on)) } }
    }
    SettingsCard("Timing", OctoIcons.Timer) { OutputTimingRow(app, settings) }
}

// The lyrics timing on the output playing now: its automatic one, said so,
// until moved; Earlier and Later a twentieth of a second a click. Either way
// the row is the timing row, so an ask for it finds it.
@Composable
private fun OutputTimingRow(app: AppState, settings: AppSettings) {
    val player by app.player.state.collectAsState()
    val bluetooth by app.lyrics.bluetooth.collectAsState()
    val device = player.playingOn
    if (device == null) {
        InfoRow(
            LYRICS_TIMING,
            "Each output keeps its own. Until moved, lyrics show ${timingWords(WIRED_OUTPUT_TIMING_MS)} on speakers or a cable and ${timingWords(BLUETOOTH_OUTPUT_TIMING_MS)} on Bluetooth. Play a song to move it for the output in use.",
            "Automatic",
        )
        return
    }
    val timing = outputTimingOf(settings.lyrics.outputOffsets[device.id], bluetooth)
    SettingRow("$LYRICS_TIMING on ${device.name}", outputTimingAbout(bluetooth, timing.automatic), stable = "$LYRICS_TIMING on <device>", id = settingsSlug(LYRICS_TIMING)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S)) {
            if (!timing.automatic) RowAction("Use automatic", { app.lyrics.resetOutput(device.id) })
            GlazeCapsule(null, "Earlier", { app.lyrics.stepOutput(device.id, -1) }, enabled = timing.ms > -OUTPUT_TIMING_LIMIT_MS, height = ControlHeight.M)
            Txt(signedTiming(timing.ms), DesktopType.body.copy(fontFeatureSettings = "tnum"), OctoColors.TextSecondary, Modifier.width(SettingsSize.Reading), align = TextAlign.Center)
            GlazeCapsule(null, "Later", { app.lyrics.stepOutput(device.id, 1) }, enabled = timing.ms < OUTPUT_TIMING_LIMIT_MS, height = ControlHeight.M)
        }
    }
}

private const val LYRICS_TIMING = "Lyrics timing"

// Octo's own keys, each with its keys, in short lines.
@Composable
private fun KeyRows(app: AppState) {
    SettingsCard("Octo's keys", OctoIcons.Keyboard) {
        shortcutList(app.mac).forEach { (what, keys) ->
            Row(
                Modifier
                    .settingsRow(what, stable = "[each of Octo's keys]")
                    .fillMaxWidth()
                    .heightIn(min = RowHeight.Regular)
                    .padding(horizontal = RowInset, vertical = Space.S),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Txt(what, DesktopType.body, modifier = Modifier.weight(1f).padding(end = Space.Xl), maxLines = 2)
                Txt(keys, DesktopType.meta.copy(fontFeatureSettings = "tnum"), OctoColors.TextSecondary, maxLines = 2)
            }
        }
    }
}

// The ambience choices by name, null being off.
private fun ambienceName(style: AmbienceStyle?): String = when (style) {
    null -> "Off"
    AmbienceStyle.Glow -> "Glow"
    AmbienceStyle.Immersive -> "Immersive"
}

// When each ambience choice is the one to pick.
private fun ambienceHelp(style: AmbienceStyle?): String = when (style) {
    null -> "The plain background."
    AmbienceStyle.Glow -> "The song's colors glow across the top of the window."
    AmbienceStyle.Immersive -> "The cover's colors behind the whole window."
}

private fun motionName(motion: AmbienceMotion): String = when (motion) {
    AmbienceMotion.Still -> "Still"
    AmbienceMotion.Gentle -> "Gentle"
    AmbienceMotion.Full -> "Full"
}

// When each movement is the one to pick.
private fun motionHelp(motion: AmbienceMotion): String = when (motion) {
    AmbienceMotion.Still -> "Holds still, easiest on a battery."
    AmbienceMotion.Gentle -> "Drifts slowly while music plays."
    AmbienceMotion.Full -> "Drifts at the full player's pace."
}

// The version the build stamped, or "Development build" when run from source
// without it.
fun appVersion(): String = System.getProperty("octo.version")?.takeIf(String::isNotBlank) ?: "Development build"
