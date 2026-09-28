package app.winters.octo.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import app.winters.octo.design.ControlHeight
import app.winters.octo.design.Corner
import app.winters.octo.design.DesktopType
import app.winters.octo.design.FloatingGlaze
import app.winters.octo.design.FrameSize
import app.winters.octo.design.Glaze
import app.winters.octo.design.GlazeLight
import app.winters.octo.design.Glyph
import app.winters.octo.design.IconAction
import app.winters.octo.design.IconSize
import app.winters.octo.design.LineSlider
import app.winters.octo.design.MenuRow
import app.winters.octo.design.MenuSeparator
import app.winters.octo.design.MenuTitle
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.design.PauseGlyph
import app.winters.octo.design.Scrubber
import app.winters.octo.design.Space
import app.winters.octo.design.Spinner
import app.winters.octo.design.Txt
import app.winters.octo.design.chromeFilm
import app.winters.octo.design.hoverLift
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.SidePanel
import app.winters.octo.desktop.library.Cover
import app.winters.octo.desktop.library.lengthText
import app.winters.octo.desktop.nav.Page
import app.winters.octo.desktop.player.DesktopPlayer
import app.winters.octo.desktop.player.PlayerState
import app.winters.octo.desktop.player.RepeatMode
import app.winters.octo.desktop.player.formatLabel
import app.winters.octo.desktop.system.MiniPlayerButton
import app.winters.octo.playback.SLEEP_EXTENSIONS
import app.winters.octo.playback.SLEEP_MINUTES
import app.winters.octo.playback.SLEEP_SONG_COUNTS
import app.winters.octo.playback.SleepState
import app.winters.octo.playback.sleepSummary
import app.winters.octo.subsonic.Song
import dev.chrisbanes.haze.HazeState
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

// Where the song is, read from the player a few times a second, for the
// time and the progress line. It is read while paused too, so a seek then
// shows at once.
@Composable
fun rememberPosition(player: DesktopPlayer): State<Long> {
    val position = remember { mutableLongStateOf(player.positionMs()) }
    val playing by player.state.collectAsState()
    LaunchedEffect(player, playing) {
        position.longValue = player.positionMs()
        while (playing.current != null) {
            delay(200)
            position.longValue = player.positionMs()
        }
    }
    return position
}

// The player, docked along the foot of the frame, in three columns: the
// song on the left, the transport and progress in the middle, and the
// panels, output and volume on the right. The side columns share the width
// evenly, so the transport sits at the window's true centre whatever the
// song's title.
@Composable
fun PlayerBar(app: AppState, backdrop: HazeState, modifier: Modifier = Modifier) {
    val state by app.player.state.collectAsState()
    val song = state.current?.song
    // Songs dragged here go to the end of the queue; the bar lights while
    // they are held over it.
    val over = isDropOver(PlayerDrop)
    BoxWithConstraints(
        modifier
            .chromeFilm(backdrop)
            .dropTarget(PlayerDrop, "Add to the queue") { app.addToQueue(it) }
            .then(if (over) Modifier.background(DropLit) else Modifier),
    ) {
        val middle = minOf(FrameSize.TransportMax, maxWidth * 0.42f)
        Row(Modifier.fillMaxSize().padding(horizontal = Space.L), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { SongZone(app, song) }
            Box(Modifier.width(middle), contentAlignment = Alignment.Center) { TransportZone(app, state) }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { UtilityZone(app, state) }
        }
    }
}

private const val PlayerDrop = "player"

// The song: its cover (which opens the full player), its title (which
// opens its album), the artist and album as links, and the heart.
@Composable
private fun SongZone(app: AppState, song: Song?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.L)) {
        Cover(
            song?.coverArt,
            Modifier.size(FrameSize.PlayerCover).clickable(enabled = song != null) { app.fullPlayer = true },
            shape = Corner.ArtMShape,
            placeholder = OctoIcons.Songs,
        )
        Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(Space.Xxs)) {
            if (song == null) {
                Txt("Nothing playing", DesktopType.emphasis, OctoColors.TextMuted)
            } else {
                val album = song.albumId?.takeIf(String::isNotBlank)
                Txt(
                    song.title,
                    DesktopType.emphasis,
                    OctoColors.TextPrimary,
                    if (album != null) Modifier.pointerHoverIcon(PointerIcon.Hand).clickable { app.navigator.go(Page.Album(album)) } else Modifier,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinkText(song.displayArtist ?: song.artist.orEmpty(), song.artistId) { app.navigator.go(Page.Artist(it, song.artist.orEmpty())) }
                    if (!song.album.isNullOrBlank()) {
                        Txt(" · ", DesktopType.meta, OctoColors.TextMuted)
                        LinkText(song.album.orEmpty(), song.albumId) { app.navigator.go(Page.Album(it)) }
                    }
                }
            }
        }
        if (song != null) {
            val starred = app.isStarred(song)
            IconAction(if (starred) OctoIcons.Liked else OctoIcons.Like, if (starred) "Remove from favourites" else "Add to favourites", { app.setStarred(listOf(song), !starred) }, size = ControlHeight.M, iconSize = IconSize.Toolbar)
        }
    }
}

// The transport over the progress line, with the time played on the left
// and, on the right, the time left (or the song's length; a click swaps).
@Composable
private fun TransportZone(app: AppState, state: PlayerState) {
    val song = state.current?.song
    val position by rememberPosition(app.player)
    val settings by app.settings.state.collectAsState()
    val left = settings.frame.showTimeLeft
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.Xxs)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S)) {
            IconAction(OctoIcons.Shuffle, "Shuffle", { app.player.setShuffle(!state.shuffle) }, size = ControlHeight.M, iconSize = IconSize.Toolbar, active = state.shuffle)
            IconAction(OctoIcons.Previous, "Previous", app.player::previous, size = ControlHeight.L, iconSize = IconSize.Transport, enabled = song != null)
            PlayButton(state.playing, enabled = song != null, size = FrameSize.PlayButton, waiting = state.buffering) { app.player.togglePlay() }
            IconAction(OctoIcons.Next, "Next", app.player::next, size = ControlHeight.L, iconSize = IconSize.Transport, enabled = song != null)
            val repeat = state.repeat
            IconAction(
                if (repeat == RepeatMode.One) OctoIcons.RepeatOne else OctoIcons.Repeat,
                when (repeat) {
                    RepeatMode.Off -> "Repeat"
                    RepeatMode.All -> "Repeat all"
                    RepeatMode.One -> "Repeat one"
                },
                { app.player.setRepeat(RepeatMode.entries[(repeat.ordinal + 1) % RepeatMode.entries.size]) },
                size = ControlHeight.M,
                iconSize = IconSize.Toolbar,
                active = repeat != RepeatMode.Off,
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.M)) {
            val duration = state.durationMs
            // Where a drag would land, shown in place of the time while dragging.
            var scrubbing by remember { mutableStateOf<Float?>(null) }
            val shownMs = scrubbing?.let { (it * duration).toLong() } ?: position
            Txt(lengthText((shownMs / 1000).toInt()).ifEmpty { "0:00" }, TimeStyle, OctoColors.TextMuted, Modifier.width(TimeWidth), align = TextAlign.End)
            Scrubber(
                fraction = { if (duration > 0) position.toFloat() / duration else 0f },
                onSeek = { app.player.seekTo((it * duration).toLong()) },
                modifier = Modifier.weight(1f),
                onScrub = { scrubbing = it },
            )
            val end = if (left && duration > 0) "-" + lengthText(((duration - shownMs).coerceAtLeast(0) / 1000).toInt()).ifEmpty { "0:00" } else lengthText((duration / 1000).toInt()).ifEmpty { "0:00" }
            Txt(
                end,
                TimeStyle,
                OctoColors.TextMuted,
                Modifier.width(TimeWidth).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { app.updateFrame { it.copy(showTimeLeft = !left) } },
            )
        }
    }
}

// Times in figures of one width, so the line never shifts as they change.
private val TimeStyle = DesktopType.meta.copy(fontFeatureSettings = "tnum")
private val TimeWidth = Space.Wide + Space.S

// The song's format as a quiet line (it opens the Info tab), the panels,
// the output, the volume, More, and the full player.
@Composable
private fun UtilityZone(app: AppState, state: PlayerState) {
    val song = state.current?.song
    Row(horizontalArrangement = Arrangement.spacedBy(Space.Xxs), verticalAlignment = Alignment.CenterVertically) {
        formatLabel(state.format)?.let { line ->
            Txt(
                line,
                DesktopType.meta.copy(fontFeatureSettings = "tnum"),
                OctoColors.TextMuted,
                Modifier.padding(end = Space.S).pointerHoverIcon(PointerIcon.Hand).clickable { app.showInfo(null) },
            )
        }
        IconAction(OctoIcons.Lyrics, "Lyrics", { app.toggleSidePanel(SidePanel.Lyrics) }, size = ControlHeight.M, iconSize = IconSize.Toolbar, active = app.sidePanel == SidePanel.Lyrics)
        IconAction(OctoIcons.Queue, "Queue", { app.toggleSidePanel(SidePanel.Queue) }, size = ControlHeight.M, iconSize = IconSize.Toolbar, active = app.sidePanel == SidePanel.Queue)
        OutputButton(app)
        VolumeControl(app, state.volume)
        MoreButton(app, state)
        IconAction(OctoIcons.Expand, "Open the player", { app.fullPlayer = !app.fullPlayer }, size = ControlHeight.M, iconSize = IconSize.Toolbar, enabled = song != null, active = app.fullPlayer)
    }
}

// What is used now and then: the sleep timer, stopping after this song,
// the song's details and the mini player. Lit while a timer is set.
@Composable
private fun MoreButton(app: AppState, state: PlayerState) {
    var anchor by remember { mutableStateOf(IntRect.Zero) }
    val sleep by app.sleep.state.collectAsState()
    val timing = sleep != SleepState.Off
    Box(Modifier.onGloballyPositioned { anchor = it.windowRect() }) {
        IconAction(OctoIcons.More, if (timing) "More (sleep timer: ${sleepSummary(sleep)})" else "More", {
            app.popups.showUnder(anchor, width = FrameSize.Menu) { close -> MoreMenu(app, close) }
        }, size = ControlHeight.M, iconSize = IconSize.Toolbar, active = timing || state.stopAfterCurrent)
    }
}

private enum class MorePage { Main, Sleep }

@Composable
private fun ColumnScope.MoreMenu(app: AppState, close: () -> Unit) {
    var page by remember { mutableStateOf(MorePage.Main) }
    val now by app.player.state.collectAsState()
    val sleep by app.sleep.state.collectAsState()
    when (page) {
        MorePage.Main -> {
            MenuRow("Sleep timer", { page = MorePage.Sleep }, OctoIcons.SleepTimer, more = true, detail = if (sleep != SleepState.Off) sleepSummary(sleep) else null)
            MenuRow(
                "Stop after this song",
                { app.player.setStopAfterCurrent(!now.stopAfterCurrent); close() },
                if (now.stopAfterCurrent) OctoIcons.Check else OctoIcons.Pause,
                enabled = now.current != null,
            )
            MenuSeparator()
            MenuRow("Song details", { app.showInfo(null); close() }, OctoIcons.Info, enabled = now.current != null)
            app.toggleMiniPlayer?.let { toggle -> MenuRow("Mini player", { toggle(); close() }, OctoIcons.Expand) }
        }
        MorePage.Sleep -> SleepMenu(app, sleep, back = { page = MorePage.Main }, close = close)
    }
}

// The phone's sleep choices: a length, the end of this song, or after a
// few songs; and while one runs, more time or off.
@Composable
private fun ColumnScope.SleepMenu(app: AppState, sleep: SleepState, back: () -> Unit, close: () -> Unit) {
    MenuRow("Back", back, OctoIcons.Back)
    MenuSeparator()
    if (sleep != SleepState.Off) {
        MenuTitle("Stopping: ${sleepSummary(sleep)}")
        if (sleep is SleepState.Counting) {
            SLEEP_EXTENSIONS.forEach { minutes -> MenuRow("Add $minutes minutes", { app.sleep.extend(minutes); close() }, OctoIcons.SleepTimer) }
        }
        MenuRow("Turn off", { app.sleep.cancel(); close() }, OctoIcons.Close)
        MenuSeparator()
    }
    SLEEP_MINUTES.forEach { minutes ->
        MenuRow(if (minutes == 60) "1 hour" else "$minutes minutes", { app.sleep.start(minutes); close() })
    }
    MenuRow("At the end of this song", { app.sleep.endOfSong(); close() }, enabled = app.player.state.value.current != null)
    SLEEP_SONG_COUNTS.filter { it > 1 }.forEach { count ->
        MenuRow("After $count songs", { app.sleep.afterSongs(count); close() }, enabled = app.player.state.value.current != null)
    }
}

// Play and pause: the one control in the glaze, a lit glass circle. While
// waiting for sound, a spinner turns inside it.
@Composable
fun PlayButton(playing: Boolean, enabled: Boolean, size: Dp = FrameSize.PlayButtonLarge, waiting: Boolean = false, onClick: () -> Unit) {
    Glaze(
        Modifier.size(size).hoverLift(CircleShape, clickable = enabled).clickable(enabled = enabled, onClick = onClick),
        light = GlazeLight.Lifted,
    ) {
        when {
            waiting && playing -> Spinner(size = size * 0.5f)
            playing -> PauseGlyph(OctoColors.TextPrimary, size = size * 0.42f)
            else -> Glyph(OctoIcons.Play, size = size * 0.55f)
        }
    }
}

// Where the sound goes: a glass menu of the system's default (followed as
// it changes, so plugging in headphones moves the sound there) and every
// device the engine can play to. The chosen one is ticked.
@Composable
private fun OutputButton(app: AppState) {
    var anchor by remember { mutableStateOf(IntRect.Zero) }
    val state by app.player.state.collectAsState()
    Box(Modifier.onGloballyPositioned { anchor = it.windowRect() }) {
        IconAction(outputIcon(state.playingOn?.name), "Output: ${state.playingOn?.name ?: "system default"}", {
            app.popups.showUnder(anchor, width = FrameSize.Menu) { close ->
                val now = app.player.state.value
                MenuTitle("Play on")
                now.outputs.forEachIndexed { index, device ->
                    if (index == 1) MenuSeparator()
                    MenuRow(
                        device.name,
                        {
                            app.selectOutput(device.id)
                            close()
                        },
                        if (device.id == now.output?.id) OctoIcons.Check else outputIcon(device.name),
                        detail = if (index == 0) "Follows the system" else null,
                    )
                }
            }
        }, size = ControlHeight.M, iconSize = IconSize.Toolbar)
    }
}

// Headphones for a device named like a pair, a speaker otherwise.
private fun outputIcon(name: String?): ImageVector {
    val lower = name.orEmpty().lowercase()
    val pair = listOf("headphone", "headset", "earbud", "airpods", "buds", "earphone").any { it in lower }
    return if (pair) OctoIcons.Headphones else OctoIcons.Speaker
}

// The volume: a speaker that mutes and unmutes, and a line to drag. The
// wheel anywhere over either turns it up or down.
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun VolumeControl(app: AppState, volume: Float) {
    var before by remember { mutableFloatStateOf(0.8f) }
    Row(
        Modifier.onPointerEvent(PointerEventType.Scroll) { event ->
            val dy = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
            if (dy != 0f) app.setVolume(volume - dy * 0.05f)
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
            size = ControlHeight.M,
            iconSize = IconSize.Toolbar,
        )
        LineSlider(fraction = { volume }, onSeek = app::setVolume, modifier = Modifier.width(FrameSize.Volume), live = true, hoverLabel = { "${(it * 100).toInt()}%" })
    }
}

// A laid-out thing's bounds in the window, for opening a pop-up under it.
fun androidx.compose.ui.layout.LayoutCoordinates.windowRect(): IntRect {
    val r = boundsInWindow()
    return IntRect(r.left.roundToInt(), r.top.roundToInt(), r.right.roundToInt(), r.bottom.roundToInt())
}
