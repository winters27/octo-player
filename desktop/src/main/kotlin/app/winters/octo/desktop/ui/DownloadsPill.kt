package app.winters.octo.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.winters.octo.design.DesktopType
import app.winters.octo.design.FloatingGlaze
import app.winters.octo.design.IconSize
import app.winters.octo.design.IslandFilm
import app.winters.octo.design.IslandFrost
import app.winters.octo.design.IslandSaturation
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoTooltip
import app.winters.octo.design.ProgressRing
import app.winters.octo.design.Space
import app.winters.octo.design.Txt
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.SidePanel
import app.winters.octo.ui.downloads.overallFraction
import app.winters.octo.ui.downloads.runningCount
import dev.chrisbanes.haze.HazeState

// The pill's height: a little under the player's controls.
val DownloadsPillHeight = 34.dp

// The room beside the player the pill needs to sit level with it: its
// width with the most songs it is likely to count, and a gap either side.
val DownloadsPillRoom = 180.dp

// The pill's words: how many songs are on their way.
fun downloadingText(count: Int): String = if (count == 1) "1 downloading" else "$count downloading"

// While songs are on their way into the library, a small glazed pill at the
// foot of the page, level with the player: the downloads' ring and how
// many. A click opens the Downloads panel; nothing opens by itself, and the
// pill steps aside while that panel is open or nothing is on its way.
@Composable
fun DownloadsPill(app: AppState, backdrop: HazeState, modifier: Modifier = Modifier) {
    val drawer = app.downloads?.takeIf { it.supported == true } ?: return
    val rows by drawer.rows.collectAsState()
    val running = runningCount(rows)
    if (running <= 0 || app.sidePanel == SidePanel.Downloads) return
    val words = downloadingText(running)
    OctoTooltip("Show downloads", modifier) {
        FloatingGlaze(
            backdrop,
            Modifier
                .height(DownloadsPillHeight)
                .clip(CircleShape)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(role = Role.Button) {
                    drawer.showList()
                    app.showSidePanel(SidePanel.Downloads)
                }
                .semantics(mergeDescendants = true) { contentDescription = "$words. Show downloads" },
            shape = CircleShape,
            film = IslandFilm,
            frost = IslandFrost,
            saturation = IslandSaturation,
            halo = true,
            seesAll = true,
        ) {
            Row(
                Modifier.align(Alignment.Center).padding(start = Space.M, end = Space.L),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.S),
            ) {
                ProgressRing(overallFraction(rows), size = IconSize.Table)
                Txt(words, DesktopType.meta, OctoColors.TextPrimary)
            }
        }
    }
}
