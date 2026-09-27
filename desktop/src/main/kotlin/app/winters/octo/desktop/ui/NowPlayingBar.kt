package app.winters.octo.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.SidePanel
import app.winters.octo.desktop.library.Cover
import app.winters.octo.desktop.library.lengthText
import app.winters.octo.desktop.nav.Page
import app.winters.octo.desktop.player.DesktopPlayer
import app.winters.octo.desktop.player.RepeatMode
import app.winters.octo.design.FloatingGlaze
import app.winters.octo.design.Glaze
import app.winters.octo.design.GlazeLight
import app.winters.octo.design.IconAction
import app.winters.octo.design.LineSlider
import app.winters.octo.design.MenuRow
import app.winters.octo.design.MenuTitle
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.design.PauseGlyph
import app.winters.octo.design.Glyph
import app.winters.octo.design.Txt
import app.winters.octo.design.hoverLift
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

val BarHeight = 84.dp
val BarShape = RoundedCornerShape(22.dp)

// Where the song is, read from the player a few times a second while it
// plays, for the time and the progress line.
@Composable
fun rememberPosition(player: DesktopPlayer): State<Long> {
    val position = remember { mutableLongStateOf(player.positionMs()) }
    val playing by player.state.collectAsState()
    LaunchedEffect(player, playing) {
        position.longValue = player.positionMs()
        while (playing.playing) {
            delay(200)
            position.longValue = player.positionMs()
        }
    }
    return position
}

// The glass bar along the foot of the window: the song on the left, the
// transport and progress in the middle, and on the right lyrics, the queue,
// the output, volume and the full player.
@Composable
fun NowPlayingBar(app: AppState, backdrop: HazeState, modifier: Modifier = Modifier) {
    val state by app.player.state.collectAsState()
    val song = state.current?.song
    val position by rememberPosition(app.player)
    FloatingGlaze(backdrop, modifier, shape = BarShape) {
        Row(Modifier.fillMaxWidth().fillMaxHeight().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            // The song.
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Cover(
                    song?.coverArt,
                    Modifier.size(56.dp).clickable(enabled = song != null) { app.fullPlayer = true },
                    shape = RoundedCornerShape(8.dp),
                    placeholder = OctoIcons.Songs,
                )
                Column(Modifier.weight(1f, fill = false)) {
                    Txt(
                        song?.title ?: "Nothing playing",
                        OctoType.label,
                        if (song != null) OctoColors.TextPrimary else OctoColors.TextMuted,
                        Modifier.clickable(enabled = song?.albumId != null) { song?.albumId?.let { app.navigator.go(Page.Album(it)) } },
                    )
                    if (song != null) {
                        LinkText(song.displayArtist ?: song.artist.orEmpty(), song.artistId) { app.navigator.go(Page.Artist(it, song.artist.orEmpty())) }
                    }
                }
                if (song != null) {
                    val starred = app.isStarred(song)
                    IconAction(if (starred) OctoIcons.Liked else OctoIcons.Like, if (starred) "Remove from favourites" else "Add to favourites", { app.setStarred(listOf(song), !starred) }, size = 32.dp, iconSize = 18.dp)
                }
            }
            // Transport and progress.
            Column(Modifier.widthIn(min = 360.dp, max = 560.dp).weight(1.3f), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    IconAction(OctoIcons.Shuffle, "Shuffle", { app.player.setShuffle(!state.shuffle) }, size = 32.dp, iconSize = 18.dp, active = state.shuffle)
                    IconAction(OctoIcons.Previous, "Previous", app.player::previous, enabled = song != null)
                    PlayButton(state.playing, enabled = song != null) { app.player.togglePlay() }
                    IconAction(OctoIcons.Next, "Next", app.player::next, enabled = song != null)
                    val repeat = state.repeat
                    IconAction(
                        if (repeat == RepeatMode.One) OctoIcons.RepeatOne else OctoIcons.Repeat,
                        when (repeat) {
                            RepeatMode.Off -> "Repeat"
                            RepeatMode.All -> "Repeat all"
                            RepeatMode.One -> "Repeat one"
                        },
                        { app.player.setRepeat(RepeatMode.entries[(repeat.ordinal + 1) % RepeatMode.entries.size]) },
                        size = 32.dp,
                        iconSize = 18.dp,
                        active = repeat != RepeatMode.Off,
                    )
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Txt(lengthText((position / 1000).toInt()).ifEmpty { "0:00" }, OctoType.caption, OctoColors.TextMuted, Modifier.width(44.dp), align = TextAlign.End)
                    val duration = state.durationMs
                    LineSlider(
                        fraction = { if (duration > 0) position.toFloat() / duration else 0f },
                        onSeek = { app.player.seekTo((it * duration).toLong()) },
                        modifier = Modifier.weight(1f),
                    )
                    Txt(lengthText((duration / 1000).toInt()).ifEmpty { "0:00" }, OctoType.caption, OctoColors.TextMuted, Modifier.width(44.dp))
                }
            }
            // Panels, output, volume and the full player.
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                IconAction(OctoIcons.Lyrics, "Lyrics", { app.toggleSidePanel(SidePanel.Lyrics) }, size = 34.dp, iconSize = 19.dp, active = app.sidePanel == SidePanel.Lyrics)
                IconAction(OctoIcons.Queue, "Queue", { app.toggleSidePanel(SidePanel.Queue) }, size = 34.dp, iconSize = 19.dp, active = app.sidePanel == SidePanel.Queue)
                OutputButton(app)
                VolumeControl(app, state.volume)
                IconAction(OctoIcons.Expand, "Open the player", { app.fullPlayer = !app.fullPlayer }, size = 34.dp, iconSize = 20.dp, enabled = song != null, active = app.fullPlayer)
            }
        }
    }
}

// Play and pause: the one control in the glaze, a lit glass circle.
@Composable
fun PlayButton(playing: Boolean, enabled: Boolean, size: androidx.compose.ui.unit.Dp = 44.dp, onClick: () -> Unit) {
    Glaze(
        Modifier.size(size).hoverLift(androidx.compose.foundation.shape.CircleShape, clickable = enabled).clickable(enabled = enabled, onClick = onClick),
        light = GlazeLight.Lifted,
    ) {
        if (playing) PauseGlyph(OctoColors.TextPrimary, size = size * 0.42f)
        else Glyph(OctoIcons.Play, size = size * 0.55f)
    }
}

// Where the sound goes. The placeholder player has only the system's
// default; the engine lists the real outputs here.
@Composable
private fun OutputButton(app: AppState) {
    var anchor by remember { mutableStateOf(IntRect.Zero) }
    Box(Modifier.onGloballyPositioned { anchor = it.windowRect() }) {
        IconAction(OctoIcons.Speaker, "Output", {
            app.popups.showUnder(anchor) { close ->
                val state = app.player.state.value
                MenuTitle("Play on")
                state.outputs.forEach { device ->
                    MenuRow(device.name, { app.selectOutput(device.id); close() }, OctoIcons.Speaker, detail = if (device.id == state.output?.id) "In use" else null)
                }
            }
        }, size = 34.dp, iconSize = 19.dp)
    }
}

// The volume: a speaker that mutes and unmutes, and a line to drag or
// scroll.
@Composable
private fun VolumeControl(app: AppState, volume: Float) {
    var before by remember { mutableFloatStateOf(0.8f) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconAction(
            if (volume <= 0.001f) OctoIcons.VolumeDown else OctoIcons.VolumeUp,
            if (volume <= 0.001f) "Unmute" else "Mute",
            {
                if (volume > 0.001f) {
                    before = volume
                    app.setVolume(0f)
                } else {
                    app.setVolume(before.takeIf { it > 0.01f } ?: 0.8f)
                }
            },
            size = 34.dp,
            iconSize = 19.dp,
        )
        LineSlider(fraction = { volume }, onSeek = app::setVolume, modifier = Modifier.width(96.dp), live = true, wheelStep = 0.05f)
    }
}

// A laid-out thing's bounds in the window, for opening a pop-up under it.
fun androidx.compose.ui.layout.LayoutCoordinates.windowRect(): IntRect {
    val r = boundsInWindow()
    return IntRect(r.left.roundToInt(), r.top.roundToInt(), r.right.roundToInt(), r.bottom.roundToInt())
}
