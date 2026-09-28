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
import app.winters.octo.desktop.nav.Page
import app.winters.octo.desktop.nav.Visit
import app.winters.octo.desktop.nav.shortcutList
import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.desktop.settings.WashPrefs
import app.winters.octo.desktop.system.SystemSettingsCard
import app.winters.octo.desktop.ui.LocalBottomRoom
import app.winters.octo.desktop.ui.PageTitle
import app.winters.octo.desktop.ui.pagePadding
import app.winters.octo.desktop.ui.rememberListState
import app.winters.octo.design.GlazeCapsule
import app.winters.octo.design.LineSlider
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.design.Separator
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
                SwitchLine("Ambient glow", "A soft wash of the playing song's colours behind the window.", settings.appearance.ambientGlow) { on ->
                    app.settings.update { it.copy(appearance = it.appearance.copy(ambientGlow = on)) }
                }
                if (settings.appearance.ambientGlow) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Txt("Strength", OctoType.bodySmall, modifier = Modifier.width(140.dp))
                        LineSlider(
                            fraction = { settings.appearance.glowStrength },
                            onSeek = { value -> app.settings.update { it.copy(appearance = it.appearance.copy(glowStrength = value)) } },
                            modifier = Modifier.weight(1f),
                            live = true,
                        )
                        Txt("${(settings.appearance.glowStrength * 100).roundToInt()}%", OctoType.caption, OctoColors.TextMuted, Modifier.width(44.dp))
                    }
                }
                val look = settings.appearance
                fun wash(edit: (WashPrefs) -> WashPrefs) = app.settings.update { it.copy(appearance = it.appearance.copy(wash = edit(it.appearance.wash))) }
                SwitchLine("Calm motion", "The player's background holds still, and lyrics move without springs or blooms.", look.calmMotion) { on ->
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
        item(key = "playback") {
            SettingsCard("Playback") {
                Txt("The equalizer, loudness, crossfade and speed are on the Sound page.", OctoType.bodySmall, OctoColors.TextSecondary, maxLines = 2)
                Row(Modifier.padding(top = 8.dp)) {
                    GlazeCapsule(OctoIcons.Sound, "Open Sound", { app.navigator.go(Page.Sound) })
                }
            }
        }
        item(key = "system") { SystemSettingsCard(app) }
        item(key = "keys") {
            SettingsCard("Keyboard shortcuts") {
                shortcutList(app.mac).forEachIndexed { index, (what, keys) ->
                    if (index > 0) Separator()
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Txt(what, OctoType.bodySmall, modifier = Modifier.weight(1f))
                        Txt(keys, OctoType.bodySmall, OctoColors.TextSecondary)
                    }
                }
            }
        }
    }
}

