package app.winters.octo.desktop.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.nav.Visit
import app.winters.octo.desktop.nav.shortcutList
import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.desktop.ui.LocalBottomRoom
import app.winters.octo.desktop.ui.PageTitle
import app.winters.octo.desktop.ui.pagePadding
import app.winters.octo.desktop.ui.rememberListState
import app.winters.octo.design.GlazeCapsule
import app.winters.octo.design.GlazeSegments
import app.winters.octo.design.LineSlider
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoSwitch
import app.winters.octo.design.OctoType
import app.winters.octo.design.Separator
import app.winters.octo.design.Txt
import app.winters.octo.design.glassPanel
import kotlin.math.roundToInt

private val CardShape = RoundedCornerShape(16.dp)

// Settings: the server, how the app looks, playback, and the keyboard
// shortcuts. Every change is saved at once to the settings file.
@Composable
fun SettingsPage(app: AppState, visit: Visit) {
    val settings by app.settings.state.collectAsState()
    val list = rememberListState(app.navigator, visit)
    LazyColumn(state = list, contentPadding = pagePadding(LocalBottomRoom.current)) {
        item(key = "title") { PageTitle("Settings") }
        item(key = "server") {
            Card("Server") {
                val connection = app.connection
                val server = connection?.server
                if (server != null) {
                    Line("Address", server.address)
                    Line("Signed in as", server.username)
                    Line("Server", listOfNotNull(server.serverType?.replaceFirstChar { it.uppercase() }, server.serverVersion).joinToString(" ").ifEmpty { "Subsonic" })
                    Line("Extensions", server.extensions.map { it.substringBefore(':') }.distinct().joinToString(", ").ifEmpty { "None listed" })
                    Line("Password kept in", app.accounts.storeLabel)
                }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlazeCapsule(null, "Read the library again", { app.library?.load(); app.refreshPlaylists() })
                    GlazeCapsule(null, "Sign out", app::signOut)
                }
            }
        }
        item(key = "look") {
            Card("Appearance") {
                Toggle("Ambient glow", "A soft wash of the playing song's colours behind the window.", settings.appearance.ambientGlow) { on ->
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
                if (app.os != DesktopOs.Mac) {
                    Toggle("Use the system title bar", "The window's own frame instead of Octo's glass one. Takes effect the next time Octo opens.", settings.systemTitleBar) { on ->
                        app.settings.update { it.copy(systemTitleBar = on) }
                    }
                }
            }
        }
        item(key = "playback") {
            Card("Playback") {
                Txt("This build plays no sound yet. These are saved now and used once it does.", OctoType.caption, OctoColors.TextMuted, maxLines = 2)
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Txt("Crossfade", OctoType.bodySmall, modifier = Modifier.width(140.dp))
                    LineSlider(
                        fraction = { settings.playback.crossfadeSeconds / 12f },
                        onSeek = { value -> app.settings.update { it.copy(playback = it.playback.copy(crossfadeSeconds = (value * 12).roundToInt())) } },
                        modifier = Modifier.weight(1f),
                        live = true,
                    )
                    Txt(if (settings.playback.crossfadeSeconds == 0) "Off" else "${settings.playback.crossfadeSeconds} s", OctoType.caption, OctoColors.TextMuted, Modifier.width(44.dp))
                }
                Toggle("Gapless", "Songs that run into each other play without a pause.", settings.playback.gapless) { on ->
                    app.settings.update { it.copy(playback = it.playback.copy(gapless = on)) }
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Txt("Even out loudness", OctoType.bodySmall)
                        Txt("Uses the loudness your server measured for each song or album.", OctoType.caption, OctoColors.TextMuted, maxLines = 2)
                    }
                    GlazeSegments(listOf("off", "track", "album"), settings.playback.replayGain, { gainLabel(it) }, { mode ->
                        app.settings.update { it.copy(playback = it.playback.copy(replayGain = mode)) }
                    })
                }
            }
        }
        item(key = "keys") {
            Card("Keyboard shortcuts") {
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

private fun gainLabel(mode: String) = when (mode) {
    "off" -> "Off"
    "album" -> "By album"
    else -> "By song"
}

// A group of settings on a glass card.
@Composable
private fun Card(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(bottom = 18.dp).glassPanel(CardShape).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Txt(title, OctoType.section, modifier = Modifier.padding(bottom = 6.dp))
        content()
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Txt(label, OctoType.bodySmall, OctoColors.TextMuted, Modifier.width(160.dp))
        Txt(value, OctoType.bodySmall, maxLines = 2, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Toggle(title: String, detail: String, on: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f)) {
            Txt(title, OctoType.bodySmall)
            Txt(detail, OctoType.caption, OctoColors.TextMuted, maxLines = 2)
        }
        OctoSwitch(on, change)
    }
}
