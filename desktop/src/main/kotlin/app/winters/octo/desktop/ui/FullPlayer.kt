package app.winters.octo.desktop.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.SidePanel
import app.winters.octo.desktop.library.Cover
import app.winters.octo.desktop.library.lengthText
import app.winters.octo.desktop.lyrics.LyricsMenuButton
import app.winters.octo.desktop.lyrics.LyricsView
import app.winters.octo.desktop.nav.Page
import app.winters.octo.desktop.player.DesktopPlayer
import app.winters.octo.desktop.player.PlayerState
import app.winters.octo.desktop.player.RepeatMode
import app.winters.octo.desktop.player.wash.ImmersiveWash
import app.winters.octo.desktop.player.wash.WashCover
import app.winters.octo.design.IconAction
import app.winters.octo.design.LineSlider
import app.winters.octo.design.LocalReduceMotion
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.design.Txt
import app.winters.octo.player.immersive.WashTuning
import app.winters.octo.player.immersive.paceBpm
import app.winters.octo.subsonic.Song
import kotlinx.coroutines.delay

// The dark words used over a light background, as on the phone.
val DarkInk = Color(0xFF141416)

// The way in: the background dollies in over 0.8 s after 0.02 s, quick then
// settling, as on the phone.
private val DollyIn = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

// Where the song is, read every frame while it plays, so the line glides,
// and a few times a second while paused, so a seek then shows at once.
@Composable
fun rememberFramePosition(player: DesktopPlayer): State<Long> {
    val position = remember { mutableLongStateOf(player.positionMs()) }
    val state by player.state.collectAsState()
    LaunchedEffect(player, state.playing, state.current?.key) {
        position.longValue = player.positionMs()
        while (state.playing) withFrameMillis { position.longValue = player.positionMs() }
        while (state.current != null) {
            delay(200)
            position.longValue = player.positionMs()
        }
    }
    return position
}

// The player filling the window: the moving wash of the cover's colours
// behind, the cover, the song, the seek line, the transport, volume and the
// heart on the left, and the lyrics or the queue on the right. The words
// turn dark over a light wash. Escape closes it, as the collapse button does.
// The wash fills the whole window; `top` is the room the title bar keeps.
@Composable
fun FullPlayer(app: AppState, modifier: Modifier = Modifier, top: androidx.compose.ui.unit.Dp = 0.dp) {
    val state by app.player.state.collectAsState()
    val settings by app.settings.state.collectAsState()
    val song = state.current?.song ?: return
    val look = settings.appearance
    val wash = look.wash
    val tuning = WashTuning(wash.contrast, wash.saturation / 100f, wash.brightnessCap / 100f)
    // The cover made ready, keeping the last one while the next is made.
    var cover by remember { mutableStateOf<WashCover?>(null) }
    LaunchedEffect(song.coverArt, tuning, app.connection) {
        cover = app.washCovers.prepare(app.connection?.client, song.coverArt, tuning)
    }
    val ink = if (cover?.darkWords == true) DarkInk else Color.White
    val dolly = remember { Animatable(0f) }
    val calm = LocalReduceMotion.current
    // With motion reduced, the background is simply there.
    LaunchedEffect(Unit) { if (calm) dolly.snapTo(1f) else dolly.animateTo(1f, tween(800, delayMillis = 20, easing = DollyIn)) }

    // It takes every click and scroll on its background, so none reach the
    // page hidden under it.
    Box(modifier.swallowClicks().background(OctoColors.Background)) {
        ImmersiveWash(
            cover,
            paceBpm(song.bpm, wash.useBpm),
            wash.fps,
            wash.speed / 100f,
            moving = wash.moving && !LocalReduceMotion.current,
            dolly = { dolly.value },
        )
        BoxWithConstraints(Modifier.fillMaxSize().padding(start = 56.dp, end = 40.dp, top = top + 48.dp, bottom = 32.dp)) {
            val panel = app.playerPanel
            val artSide = minOf(440.dp, maxHeight - 290.dp, if (panel != null) maxWidth * 0.36f else maxWidth * 0.6f).coerceAtLeast(120.dp)
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(48.dp, Alignment.CenterHorizontally)) {
                PlayerColumn(app, song, state, ink, artSide)
                if (panel != null) {
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        when (panel) {
                            SidePanel.Lyrics -> LyricsView(app, Modifier.fillMaxSize(), textColor = ink)
                            SidePanel.Queue -> Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.28f), RoundedCornerShape(20.dp)).padding(top = 8.dp)) {
                                QueueList(app)
                            }
                        }
                    }
                }
            }
        }
        // The panel toggles and the way out.
        Row(Modifier.align(Alignment.TopEnd).padding(top = top + 4.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (app.playerPanel == SidePanel.Lyrics) LyricsMenuButton(app, tint = ink)
            IconAction(OctoIcons.Lyrics, "Lyrics", { app.togglePlayerPanel(SidePanel.Lyrics) }, size = 36.dp, iconSize = 20.dp, active = app.playerPanel == SidePanel.Lyrics, tint = ink)
            IconAction(OctoIcons.Queue, "Queue", { app.togglePlayerPanel(SidePanel.Queue) }, size = 36.dp, iconSize = 20.dp, active = app.playerPanel == SidePanel.Queue, tint = ink)
            IconAction(OctoIcons.Collapse, "Close the player", { app.fullPlayer = false }, size = 36.dp, iconSize = 20.dp, tint = ink)
        }
    }
}

// The cover large, then the song and its heart, the seek line, the
// transport and the volume.
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PlayerColumn(app: AppState, song: Song, state: PlayerState, ink: Color, side: androidx.compose.ui.unit.Dp) {
    val muted = ink.copy(alpha = 0.62f)
    Column(Modifier.width(side), horizontalAlignment = Alignment.CenterHorizontally) {
        // A touch smaller while paused, as on the phone.
        val scale = remember { Animatable(1f) }
        LaunchedEffect(state.playing) { scale.animateTo(if (state.playing) 1f else 0.94f, tween(420)) }
        Cover(
            song.coverArt,
            Modifier.size(side).graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            },
            shape = RoundedCornerShape(14.dp),
            placeholder = OctoIcons.Songs,
        )
        Spacer(Modifier.height(28.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Txt(song.title, OctoType.headline, ink, maxLines = 2)
                val artist = song.displayArtist ?: song.artist.orEmpty()
                Txt(
                    artist,
                    OctoType.body,
                    muted,
                    Modifier.clickable(enabled = song.artistId != null) {
                        song.artistId?.let { app.navigator.go(Page.Artist(it, song.artist.orEmpty())) }
                        app.fullPlayer = false
                    },
                )
            }
            val starred = app.isStarred(song)
            IconAction(
                if (starred) OctoIcons.Liked else OctoIcons.Like,
                if (starred) "Remove from favourites" else "Add to favourites",
                { app.setStarred(listOf(song), !starred) },
                size = 40.dp,
                iconSize = 22.dp,
                tint = ink,
            )
        }
        Spacer(Modifier.height(14.dp))
        val position by rememberFramePosition(app.player)
        val duration = state.durationMs
        LineSlider(
            fraction = { if (duration > 0) position.toFloat() / duration else 0f },
            onSeek = { app.player.seekTo((it * duration).toLong()) },
            modifier = Modifier.fillMaxWidth(),
            color = ink,
            trackColor = ink.copy(alpha = 0.22f),
            hoverLabel = { lengthText(((it * duration) / 1000).toInt()).ifEmpty { "0:00" } },
        )
        Row(Modifier.fillMaxWidth()) {
            Txt(lengthText((position / 1000).toInt()).ifEmpty { "0:00" }, OctoType.caption, muted, Modifier.weight(1f))
            Txt("-" + lengthText(((duration - position).coerceAtLeast(0) / 1000).toInt()).ifEmpty { "0:00" }, OctoType.caption, muted, align = TextAlign.End)
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconAction(OctoIcons.Shuffle, "Shuffle", { app.player.setShuffle(!state.shuffle) }, size = 40.dp, iconSize = 22.dp, active = state.shuffle, tint = ink)
            IconAction(OctoIcons.Previous, "Previous", app.player::previous, size = 48.dp, iconSize = 30.dp, tint = ink)
            PlayButton(state.playing, enabled = true, size = 68.dp) { app.player.togglePlay() }
            IconAction(OctoIcons.Next, "Next", app.player::next, size = 48.dp, iconSize = 30.dp, tint = ink)
            val repeat = state.repeat
            IconAction(
                if (repeat == RepeatMode.One) OctoIcons.RepeatOne else OctoIcons.Repeat,
                when (repeat) {
                    RepeatMode.Off -> "Repeat"
                    RepeatMode.All -> "Repeat all"
                    RepeatMode.One -> "Repeat one"
                },
                { app.player.setRepeat(RepeatMode.entries[(repeat.ordinal + 1) % RepeatMode.entries.size]) },
                size = 40.dp,
                iconSize = 22.dp,
                active = repeat != RepeatMode.Off,
                tint = ink,
            )
        }
        Spacer(Modifier.height(10.dp))
        // The volume: quiet on the left, loud on the right; the wheel moves it.
        val volume = state.volume
        Row(
            Modifier.fillMaxWidth().onPointerEvent(PointerEventType.Scroll) { event ->
                val dy = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                if (dy != 0f) app.setVolume(volume - dy * 0.05f)
            },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            IconAction(OctoIcons.VolumeDown, "Quieter", { app.setVolume(volume - 0.1f) }, size = 30.dp, iconSize = 18.dp, tint = muted)
            LineSlider(fraction = { volume }, onSeek = app::setVolume, modifier = Modifier.weight(1f), live = true, color = ink, trackColor = ink.copy(alpha = 0.22f))
            IconAction(OctoIcons.VolumeUp, "Louder", { app.setVolume(volume + 0.1f) }, size = 30.dp, iconSize = 18.dp, tint = muted)
        }
    }
}
