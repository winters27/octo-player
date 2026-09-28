package app.winters.octo.desktop.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.setAutoplay
import app.winters.octo.desktop.nav.Page
import app.winters.octo.desktop.nav.Visit
import app.winters.octo.desktop.nav.shortcutList
import app.winters.octo.desktop.settings.AmbienceMotion
import app.winters.octo.desktop.settings.AmbienceStyle
import app.winters.octo.desktop.settings.Appearance
import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.desktop.settings.WashPrefs
import app.winters.octo.desktop.system.SystemSettingsCard
import app.winters.octo.desktop.ui.LocalBottomRoom
import app.winters.octo.desktop.ui.LocalSoftwareDrawing
import app.winters.octo.desktop.ui.PageTitle
import app.winters.octo.desktop.ui.pagePadding
import app.winters.octo.desktop.ui.rememberListState
import app.winters.octo.design.GlazeCapsule
import app.winters.octo.design.LocalReduceMotion
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.design.Txt
import kotlin.math.roundToInt

// Settings: the server, how the app looks, playback, and the keyboard
// shortcuts. Every change is saved at once to the settings file.
@Composable
fun SettingsPage(app: AppState, visit: Visit) {
    val settings by app.settings.state.collectAsState()
    val list = rememberListState(app.navigator, visit)
    LazyColumn(state = list, contentPadding = pagePadding(LocalBottomRoom.current)) {
        item(key = "title") { PageTitle("Settings") }
        item(key = "server") {
            SettingsCard("Server") {
                val connection = app.connection
                val server = connection?.server
                if (server != null) {
                    InfoLine("Address", server.address)
                    InfoLine("Signed in as", server.username)
                    InfoLine("Server", listOfNotNull(server.serverType?.replaceFirstChar { it.uppercase() }, server.serverVersion).joinToString(" ").ifEmpty { "Subsonic" })
                    InfoLine("Extensions", server.extensions.map { it.substringBefore(':') }.distinct().joinToString(", ").ifEmpty { "None listed" })
                    InfoLine("Password kept in", app.accounts.storeLabel)
                }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlazeCapsule(null, "Read the library again", { app.library?.load(); app.refreshPlaylists() })
                    GlazeCapsule(null, "Sign out", app::signOut)
                }
            }
        }
        item(key = "look") {
            SettingsCard("Appearance") {
                val look = settings.appearance
                fun appearance(edit: (Appearance) -> Appearance) = app.settings.update { it.copy(appearance = edit(it.appearance)) }
                // Off, or one of the two looks; null stands for off.
                val chosen = if (look.ambientGlow) look.ambience else null
                ChoiceLine("Ambience", ambienceHelp(chosen), listOf(null, AmbienceStyle.Glow, AmbienceStyle.Immersive), chosen, ::ambienceName) { style ->
                    appearance { if (style == null) it.copy(ambientGlow = false) else it.copy(ambientGlow = true, ambience = style) }
                }
                if (look.ambientGlow) {
                    SliderLine("Strength", "${(look.glowStrength * 100).roundToInt()}%", look.glowStrength, { value ->
                        appearance { it.copy(glowStrength = value) }
                    })
                }
                if (chosen == AmbienceStyle.Immersive) {
                    val detail = when {
                        LocalSoftwareDrawing.current -> "Holds still here: this computer draws Octo without its graphics card."
                        LocalReduceMotion.current -> "Holds still while motion is reduced, by Calm motion or your system."
                        else -> motionHelp(look.ambienceMotion)
                    }
                    ChoiceLine("Movement", detail, AmbienceMotion.entries, look.ambienceMotion, ::motionName) { motion ->
                        appearance { it.copy(ambienceMotion = motion) }
                    }
                }
                fun wash(edit: (WashPrefs) -> WashPrefs) = app.settings.update { it.copy(appearance = it.appearance.copy(wash = edit(it.appearance.wash))) }
                SwitchLine("Calm motion", "The player's background and the window's colours hold still, and lyrics move without springs or blooms.", look.calmMotion) { on ->
                    app.settings.update { it.copy(appearance = it.appearance.copy(calmMotion = on)) }
                }
                SwitchLine("Moving player background", "The cover's colours drift slowly behind the full player.", look.wash.moving) { on -> wash { it.copy(moving = on) } }
                if (look.wash.moving && !look.calmMotion) {
                    SliderLine("Drift speed", "${look.wash.speed}%", (look.wash.speed - 5) / 95f, { x -> wash { it.copy(speed = (5 + x * 95).roundToInt()) } }, live = false)
                    SwitchLine("Follow the song's tempo", "Faster songs drift a little faster, when the server knows their tempo.", look.wash.useBpm) { on -> wash { it.copy(useBpm = on) } }
                }
                SliderLine("Background brightness", "${look.wash.brightnessCap}%", (look.wash.brightnessCap - 20) / 80f, { x -> wash { it.copy(brightnessCap = (20 + x * 80).roundToInt()) } }, live = false)
                if (app.os != DesktopOs.Mac) {
                    SwitchLine("Use the system title bar", "The window's own frame instead of Octo's glass one. Takes effect the next time Octo opens.", settings.systemTitleBar) { on ->
                        app.settings.update { it.copy(systemTitleBar = on) }
                    }
                }
            }
        }
        item(key = "lyrics") {
            SettingsCard("Lyrics") {
                SwitchLine(
                    "Look lyrics up online",
                    "When your server and the song file have none, ask LRCLIB, sending only the song's title, artist, album and length.",
                    settings.lyrics.online,
                ) { on ->
                    app.settings.update { it.copy(lyrics = it.lyrics.copy(online = on)) }
                    app.lyrics.state.value.song?.let { song -> app.lyrics.sources.refresh(song.id) }
                }
            }
        }
        item(key = "listening") {
            SettingsCard("Listening") {
                SwitchLine(
                    "Tell the server what you play",
                    "Plays count on your server, so play counts and recently played stay right in every app that uses it. Leave on unless another app already reports this computer's plays.",
                    settings.listening.reportPlays,
                ) { on -> app.settings.update { it.copy(listening = it.listening.copy(reportPlays = on)) } }
                SwitchLine(
                    "Carry the queue between devices",
                    "Keeps what you're listening to on your server, so Octo on your phone can pick it up, and Home here offers the phone's.",
                    settings.listening.syncQueue,
                ) { on -> app.settings.update { it.copy(listening = it.listening.copy(syncQueue = on)) } }
            }
        }
        item(key = "playback") {
            SettingsCard("Playback") {
                SwitchLine(
                    "Autoplay",
                    "When the queue ends, similar songs keep playing: songs like it from your server, or by the same artist or in the same genre.",
                    settings.playback.autoplay,
                ) { on -> app.setAutoplay(on) }
                Txt("The equalizer, loudness, crossfade and speed are on the Sound page.", OctoType.bodySmall, OctoColors.TextSecondary, Modifier.padding(top = 12.dp), maxLines = 2)
                Row(Modifier.padding(top = 8.dp)) {
                    GlazeCapsule(OctoIcons.Sound, "Open Sound", { app.navigator.go(Page.Sound) })
                }
            }
        }
        item(key = "system") { SystemSettingsCard(app) }
        item(key = "about") {
            SettingsCard("About") {
                InfoLine("Version", appVersion())
            }
        }
        item(key = "keys") {
            SettingsCard("Keyboard shortcuts") {
                shortcutList(app.mac).forEach { (what, keys) ->
                    Row(Modifier.fillMaxWidth().cardLine().padding(vertical = 10.dp)) {
                        Txt(what, OctoType.bodySmall, modifier = Modifier.weight(1f))
                        Txt(keys, OctoType.bodySmall, OctoColors.TextSecondary)
                    }
                }
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

// What each ambience choice looks like, for the line under its name.
private fun ambienceHelp(style: AmbienceStyle?): String = when (style) {
    null -> "The plain dark background."
    AmbienceStyle.Glow -> "A soft glow of the playing song's colours across the top of the window."
    AmbienceStyle.Immersive -> "The full player's wash of the cover's colours, behind the whole window."
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
