package app.winters.octo.desktop.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import app.winters.octo.design.OctoDuration
import app.winters.octo.design.OctoTooltip
import app.winters.octo.design.ProgressRing
import app.winters.octo.design.Space
import app.winters.octo.design.Txt
import app.winters.octo.design.motionScale
import app.winters.octo.design.octoTween
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.SidePanel
import app.winters.octo.ui.downloads.pillLead
import app.winters.octo.ui.downloads.pillSpoken
import app.winters.octo.ui.downloads.runningCount
import app.winters.octo.ui.downloads.stepWords
import dev.chrisbanes.haze.HazeState

// The pill's height: a little under the player's controls.
val DownloadsPillHeight = 34.dp

// The widest the pill grows; a long song name is cut before that.
val DownloadsPillMax = 300.dp

// The room beside the player the pill needs to sit level with it: its
// widest, and a gap either side.
val DownloadsPillRoom = DownloadsPillMax + Space.Page

// While songs are on their way into the library, a small glazed pill at the
// foot of the page, level with the player: the ring of the song furthest
// along, the step it is on ("Downloading 42%", "Checking"), its name, and
// how many more. A click opens the Downloads panel; nothing opens by
// itself, and the pill steps aside while that panel is open or nothing is
// on its way.
@Composable
fun DownloadsPill(app: AppState, backdrop: HazeState, modifier: Modifier = Modifier) {
    val drawer = app.downloads?.takeIf { it.supported == true } ?: return
    val rows by drawer.rows.collectAsState()
    val running = runningCount(rows)
    if (running <= 0 || app.sidePanel == SidePanel.Downloads) return
    val lead = pillLead(rows) ?: return
    val more = running - 1
    val motion = motionScale()
    OctoTooltip("Show downloads", modifier) {
        FloatingGlaze(
            backdrop,
            Modifier
                .height(DownloadsPillHeight)
                .widthIn(max = DownloadsPillMax)
                .clip(CircleShape)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(role = Role.Button) {
                    drawer.showList()
                    app.showSidePanel(SidePanel.Downloads)
                }
                .semantics(mergeDescendants = true) { contentDescription = "${pillSpoken(rows)}. Show downloads" }
                .animateContentSize(octoTween(motion, OctoDuration.Card)),
            shape = CircleShape,
            film = IslandFilm,
            frost = IslandFrost,
            saturation = IslandSaturation,
            halo = true,
            seesAll = true,
        ) {
            Row(
                Modifier.align(Alignment.Center).padding(start = Space.M, end = if (more > 0) Space.S else Space.L),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.S),
            ) {
                ProgressRing(lead.fraction, size = IconSize.Table)
                // The step changes in place, a quick fade, never a jump.
                AnimatedContent(
                    stepWords(lead),
                    transitionSpec = { fadeIn(octoTween(motion, OctoDuration.Swap)) togetherWith fadeOut(octoTween(motion, OctoDuration.Swap)) },
                    label = "step",
                ) { words -> Txt(words, DesktopType.meta, OctoColors.TextPrimary) }
                Txt(lead.title, DesktopType.meta, OctoColors.TextSecondary, Modifier.weight(1f, fill = false))
                if (more > 0) {
                    Txt("+$more", DesktopType.meta, OctoColors.TextPrimary, Modifier.clip(CircleShape).background(MoreChip).padding(horizontal = Space.S, vertical = Space.Xxs))
                }
            }
        }
    }
}

private val MoreChip = Color.White.copy(alpha = 0.12f)
