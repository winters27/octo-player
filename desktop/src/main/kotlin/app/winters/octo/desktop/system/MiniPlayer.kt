package app.winters.octo.desktop.system

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowScope
import androidx.compose.ui.window.rememberWindowState
import app.winters.octo.desktop.library.Cover
import app.winters.octo.desktop.library.LocalCovers
import app.winters.octo.desktop.library.lengthText
import app.winters.octo.desktop.player.DesktopPlayer
import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.desktop.settings.WindowSpot
import app.winters.octo.desktop.ui.PlayButton
import app.winters.octo.desktop.ui.rememberPosition
import app.winters.octo.desktop.window.Frame
import app.winters.octo.desktop.window.ResizeEdges
import app.winters.octo.desktop.window.ScreenArea
import app.winters.octo.desktop.window.roundWindowsCorners
import app.winters.octo.design.IconAction
import app.winters.octo.design.LineSlider
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.design.Txt
import app.winters.octo.subsonic.SubsonicClient
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import java.awt.Dimension

// The mini player's size the first time, and the least and most it may be.
const val MINI_WIDTH = 380f
const val MINI_HEIGHT = 124f
const val MINI_MIN_WIDTH = 300f
const val MINI_MIN_HEIGHT = 96f
const val MINI_MAX_WIDTH = 720f
const val MINI_MAX_HEIGHT = 320f

// Room kept between the mini player and the screen's edge the first time.
private const val MINI_MARGIN = 24f

// Where the mini player opens: where it last was when enough of it is still
// on a screen (moved inside that screen), otherwise the bottom right corner
// of the first screen. Its size stays within its limits.
fun placeMiniPlayer(saved: WindowSpot?, screens: List<ScreenArea>): WindowSpot {
    val primary = screens.firstOrNull() ?: ScreenArea(0f, 0f, 1280f, 800f)
    val width = (saved?.width ?: MINI_WIDTH).coerceIn(MINI_MIN_WIDTH, MINI_MAX_WIDTH)
    val height = (saved?.height ?: MINI_HEIGHT).coerceIn(MINI_MIN_HEIGHT, MINI_MAX_HEIGHT)
    val home = saved?.let { spot ->
        screens.firstOrNull { area ->
            val overlapX = minOf(spot.x + width, area.right) - maxOf(spot.x, area.x)
            val overlapY = minOf(spot.y + height, area.bottom) - maxOf(spot.y, area.y)
            overlapX >= 60f && overlapY >= 40f
        }
    }
    if (saved == null || home == null) {
        return WindowSpot(primary.right - width - MINI_MARGIN, primary.bottom - height - MINI_MARGIN, width, height)
    }
    val x = saved.x.coerceIn(home.x, maxOf(home.x, home.right - width))
    val y = saved.y.coerceIn(home.y, maxOf(home.y, home.bottom - height))
    return WindowSpot(x, y, width, height)
}

private val MiniShape = RoundedCornerShape(16.dp)

// A small window that stays above the others: the cover, the song, the
// transport and progress, over the cover's own colours blurred behind
// glass. Drag it anywhere, resize it by its edges.
@OptIn(FlowPreview::class)
@Composable
fun MiniPlayerWindow(
    player: DesktopPlayer,
    covers: SubsonicClient?,
    spot: WindowSpot,
    os: DesktopOs,
    icon: Painter?,
    onMoved: (WindowSpot) -> Unit,
    onOpenOcto: () -> Unit,
    onClose: () -> Unit,
) {
    val windowState = rememberWindowState(position = WindowPosition(spot.x.dp, spot.y.dp), size = DpSize(spot.width.dp, spot.height.dp))
    Window(
        onCloseRequest = onClose,
        state = windowState,
        title = "Octo mini player",
        icon = icon,
        undecorated = true,
        // See-through corners where the system draws them well; Windows 11
        // rounds the frameless window itself.
        transparent = os == DesktopOs.Mac,
        alwaysOnTop = true,
        resizable = true,
    ) {
        val frame = remember { Frame(window, windowState) }
        LaunchedEffect(Unit) {
            window.minimumSize = Dimension(MINI_MIN_WIDTH.toInt(), MINI_MIN_HEIGHT.toInt())
            window.maximumSize = Dimension(MINI_MAX_WIDTH.toInt(), MINI_MAX_HEIGHT.toInt())
            if (os == DesktopOs.Windows) roundWindowsCorners(window)
        }
        LaunchedEffect(Unit) {
            snapshotFlow { windowState.position to windowState.size }
                .debounce(500)
                .collect { (position, size) ->
                    if (position is WindowPosition.Absolute) onMoved(WindowSpot(position.x.value, position.y.value, size.width.value, size.height.value))
                }
        }
        CompositionLocalProvider(LocalCovers provides covers) {
            Box(Modifier.fillMaxSize().clip(MiniShape).background(OctoColors.Background)) {
                MiniPlayerContent(player, onOpenOcto, onClose)
                ResizeEdges(frame, thickness = 4.dp)
            }
        }
    }
}

@Composable
private fun WindowScope.MiniPlayerContent(player: DesktopPlayer, onOpenOcto: () -> Unit, onClose: () -> Unit) {
    val state by player.state.collectAsState()
    val song = state.current?.song
    val position by rememberPosition(player)
    Box(Modifier.fillMaxSize()) {
        // The glass: the cover's colours, blurred and dimmed.
        Cover(song?.coverArt, Modifier.fillMaxSize().blur(48.dp, BlurredEdgeTreatment.Rectangle).alpha(0.55f), shape = RoundedCornerShape(0.dp))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.35f), Color.Black.copy(alpha = 0.6f)))))
        // The whole background moves the window.
        WindowDraggableArea(Modifier.fillMaxSize()) {}
        BoxWithConstraints(Modifier.fillMaxSize().padding(10.dp)) {
            val tall = maxHeight >= 150.dp
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(song?.coverArt, Modifier.fillMaxHeight().aspectRatio(1f), shape = RoundedCornerShape(10.dp), placeholder = OctoIcons.Songs)
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Txt(song?.title ?: "Nothing playing", if (tall) OctoType.headline else OctoType.label, if (song != null) OctoColors.TextPrimary else OctoColors.TextMuted)
                            if (song != null) Txt((song.displayArtist ?: song.artist).orEmpty(), OctoType.caption, OctoColors.TextSecondary)
                        }
                        IconAction(OctoIcons.Expand, "Open Octo", onOpenOcto, size = 24.dp, iconSize = 14.dp, tint = OctoColors.TextSecondary)
                        IconAction(OctoIcons.Close, "Close the mini player", onClose, size = 24.dp, iconSize = 14.dp, tint = OctoColors.TextSecondary)
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconAction(OctoIcons.Previous, "Previous", player::previous, size = 30.dp, iconSize = 18.dp, enabled = song != null)
                        PlayButton(state.playing, enabled = song != null, size = 34.dp) { player.togglePlay() }
                        IconAction(OctoIcons.Next, "Next", player::next, size = 30.dp, iconSize = 18.dp, enabled = song != null)
                        val duration = state.durationMs
                        Txt(lengthText((position / 1000).toInt()).ifEmpty { "0:00" }, OctoType.caption, OctoColors.TextMuted, Modifier.width(36.dp).padding(start = 4.dp), align = TextAlign.End)
                        LineSlider(
                            fraction = { if (duration > 0) position.toFloat() / duration else 0f },
                            onSeek = { player.seekTo((it * duration).toLong()) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}
