package app.winters.octo.desktop.pages

import app.winters.octo.desktop.settings.MaxTextScale
import app.winters.octo.desktop.settings.DesktopOs
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.winters.octo.design.DesktopType
import app.winters.octo.design.LocalReduceMotion
import app.winters.octo.design.OctoColors
import app.winters.octo.design.RowHeight
import app.winters.octo.design.Space
import app.winters.octo.design.Txt
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.nav.Page
import app.winters.octo.desktop.nav.Visit
import app.winters.octo.desktop.nav.shortcutList
import app.winters.octo.desktop.server.Connection
import app.winters.octo.desktop.server.OCTO_LYRICS
import app.winters.octo.server.serverOffers
import app.winters.octo.desktop.setAutoplay
import app.winters.octo.desktop.settings.AmbienceMotion
import app.winters.octo.covers.PLAYLIST_COVERS_SETTING
import app.winters.octo.covers.PlaylistCoverStyle
import app.winters.octo.covers.playlistCoverStyleHelp
import app.winters.octo.covers.playlistCoverStyleName
import app.winters.octo.desktop.settings.AmbienceStyle
import app.winters.octo.desktop.settings.AppSettings
import app.winters.octo.desktop.settings.Appearance
import app.winters.octo.desktop.settings.WashPrefs
import app.winters.octo.desktop.system.SystemRows
import app.winters.octo.desktop.system.TaskbarAndStartupRows
import app.winters.octo.desktop.system.MiniPlayerGroup
import app.winters.octo.desktop.system.DiscordGroup
import app.winters.octo.desktop.hotkeys.GlobalShortcutGroup
import app.winters.octo.desktop.ui.LocalSoftwareDrawing
import kotlin.math.roundToInt

// Settings: the servers, how Octo looks, playback, listening, lyrics, how
// it fits into the system, and the keyboard shortcuts, each a section in
// the list beside the page. Every change is saved at once.
@Composable
fun SettingsPage(app: AppState, visit: Visit) {
    val settings by app.settings.state.collectAsState()
    SectionedPage(
        app,
        visit,
        "Settings",
        listOf(
            PageSection("servers", "Servers") { ServerRows(app) },
            PageSection("look", "Appearance") { AppearanceGroups(app, settings) },
            PageSection("playback", "Playback") { PlaybackRows(app, settings) },
            PageSection("listening", "Listening") { ListeningRows(app, settings) },
            PageSection("lyrics", "Lyrics") { LyricsRows(app, settings) },
            PageSection("system", "System") {
                SystemRows(app)
                TaskbarAndStartupRows(app)
                MiniPlayerGroup(app)
                DiscordGroup(app)
            },
            PageSection("keys", "Keyboard") {
                KeyRows(app)
                GlobalShortcutGroup(app)
            },
            PageSection("about", "About") { Rows { InfoRow("Version", null, appVersion()) } },
        ),
    )
}

// What the server brings beyond the music, in plain words.
internal fun serverOffers(connection: Connection): String? =
    serverOffers(lyrics = connection.lyricsByIdOn || connection.supports(OCTO_LYRICS), adds = connection.acquires)

// The window's background, then the full player's, then how much moves.
// Each tuning line shows only while it has something to change.
@Composable
private fun AppearanceGroups(app: AppState, settings: AppSettings) {
    val look = settings.appearance
    fun appearance(edit: (Appearance) -> Appearance) = app.settings.update { it.copy(appearance = edit(it.appearance)) }
    fun wash(edit: (WashPrefs) -> WashPrefs) = appearance { it.copy(wash = edit(it.wash)) }
    val chosen = ambienceOf(look)
    Group("Window background") {
        ChoiceRow("Ambience", ambienceHelp(chosen), listOf(null, AmbienceStyle.Glow, AmbienceStyle.Immersive), chosen, ::ambienceName) { style ->
            appearance { withAmbience(it, style) }
        }
        if (chosen != null) {
            SliderRow("Strength", "Turn it down if the colours pull your eye from the page.", "${(look.glowStrength * 100).roundToInt()}%", look.glowStrength, { value ->
                appearance { it.copy(glowStrength = value) }
            })
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
        }
    }
    Group("Full player") {
        SwitchRow("Moving background", "The cover's colours drift behind the full player. Off holds them still, easier on a laptop's battery.", look.wash.moving) { on ->
            wash { it.copy(moving = on) }
        }
        if (look.wash.moving && !look.calmMotion) {
            SliderRow("Drift speed", "How fast the colours move.", "${look.wash.speed}%", (look.wash.speed - 5) / 95f, { x ->
                wash { it.copy(speed = (5 + x * 95).roundToInt()) }
            }, live = false)
            SwitchRow("Move with the beat", "Slow songs drift slower and quick ones faster, when the server knows their tempo.", look.wash.useBpm) { on ->
                wash { it.copy(useBpm = on) }
            }
        }
        SliderRow("Brightness cap", "Turn it down if bright covers make the words hard to read.", "${look.wash.brightnessCap}%", (look.wash.brightnessCap - 20) / 80f, { x ->
            wash { it.copy(brightnessCap = (20 + x * 80).roundToInt()) }
        }, live = false)
    }
    Group("Playlists") {
        ChoiceRow(PLAYLIST_COVERS_SETTING, playlistCoverStyleHelp(look.playlistCovers), PlaylistCoverStyle.entries, look.playlistCovers, ::playlistCoverStyleName) { style ->
            appearance { it.copy(playlistCovers = style) }
        }
    }
    Group("Motion") {
        SwitchRow("Calm motion", "For less movement: backgrounds hold still, and lyrics move without springs or blooms.", look.calmMotion) { on ->
            appearance { it.copy(calmMotion = on) }
        }
    }
    Group("Text") {
        val system = if (app.os == DesktopOs.Windows) "Windows" else "your system"
        ChoiceRow(
            "Text size",
            "Bigger words everywhere in Octo. Like $system follows its own text size setting, up to ${(MaxTextScale * 100).roundToInt()}%.",
            TextSizes,
            look.textSize.takeIf { it in TextSizes } ?: 0,
            { size -> if (size == 0) "Like $system" else "$size%" },
        ) { size -> appearance { it.copy(textSize = size) } }
    }
}

// The text sizes to choose from: the system's, then Octo's own steps.
private val TextSizes = listOf(0, 100, 115, 130)

// The window's ambience as one choice, null being off.
internal fun ambienceOf(look: Appearance): AmbienceStyle? = if (look.ambientGlow) look.ambience else null

// The ambience chosen, kept as the switch and the look it had before, so
// turning it back on returns the same look.
internal fun withAmbience(look: Appearance, style: AmbienceStyle?): Appearance =
    if (style == null) look.copy(ambientGlow = false) else look.copy(ambientGlow = true, ambience = style)

@Composable
private fun PlaybackRows(app: AppState, settings: AppSettings) {
    Rows {
        SwitchRow("Autoplay", "When the queue ends, similar songs keep playing: from your server, or by the same artist or in the same genre.", settings.playback.autoplay) { on ->
            app.setAutoplay(on)
        }
        ActionRow("Equalizer, loudness, crossfade and speed", "On the Sound page.", "Open Sound", { app.navigator.go(Page.Sound) })
    }
}

@Composable
private fun ListeningRows(app: AppState, settings: AppSettings) {
    val listening = settings.listening
    Rows {
        SwitchRow(
            "Tell the server what you play",
            "Keeps play counts and recently played right in every app. Turn off if another app already reports this computer's plays.",
            listening.reportPlays,
        ) { on -> app.settings.update { it.copy(listening = it.listening.copy(reportPlays = on)) } }
        SwitchRow(
            "Carry the queue between devices",
            "Pick up on your phone where you left off here, and here where you left off on your phone.",
            listening.syncQueue,
        ) { on -> app.settings.update { it.copy(listening = it.listening.copy(syncQueue = on)) } }
    }
}

@Composable
private fun LyricsRows(app: AppState, settings: AppSettings) {
    Rows {
        SwitchRow(
            "Find lyrics online",
            "When your server and the song have none, ask LRCLIB, sending only the title, artist, album and length.",
            settings.lyrics.online,
        ) { on ->
            app.settings.update { it.copy(lyrics = it.lyrics.copy(online = on)) }
            app.lyrics.state.value.song?.let { song -> app.lyrics.sources.refresh(song.id) }
        }
    }
}

// Each shortcut and its keys, in short lines.
@Composable
private fun KeyRows(app: AppState) {
    Rows {
        shortcutList(app.mac).forEach { (what, keys) ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = RowHeight.Regular).padding(horizontal = Space.M, vertical = Space.S),
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
    null -> "The plain dark background, for the fewest distractions."
    AmbienceStyle.Glow -> "A soft glow of the playing song's colours across the top of the window."
    AmbienceStyle.Immersive -> "The cover's colours behind the whole window, as in the full player."
}

private fun motionName(motion: AmbienceMotion): String = when (motion) {
    AmbienceMotion.Still -> "Still"
    AmbienceMotion.Gentle -> "Gentle"
    AmbienceMotion.Full -> "Full"
}

// When each movement is the one to pick.
private fun motionHelp(motion: AmbienceMotion): String = when (motion) {
    AmbienceMotion.Still -> "Holds still. Easiest on a laptop's battery or an older computer."
    AmbienceMotion.Gentle -> "Drifts slowly while music plays, and rests when it stops."
    AmbienceMotion.Full -> "Drifts at the full player's pace while music plays."
}

// The version the build stamped, or "Development build" when run from source
// without it.
fun appVersion(): String = System.getProperty("octo.version")?.takeIf(String::isNotBlank) ?: "Development build"
