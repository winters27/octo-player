package app.winters.octo.desktop.ui

import androidx.compose.ui.unit.Dp
import app.winters.octo.design.FrameSize

// How the frame shares a window's width: whether the sidebar shows as its
// rail, how wide the sidebar and the side panel (when open) are, and what
// is left for the page.
data class FrameFit(val rail: Boolean, val sidebar: Dp, val panel: Dp?, val page: Dp)

// The page keeps at least FrameSize.PageMin. On a window too narrow for the
// sidebar, the side panel and that much page, the sidebar folds to its rail
// while the panel is open; if that is still not enough, the panel narrows,
// never below its least. The listener's own widths and rail choice are
// kept for when the window has room again.
fun frameFit(window: Dp, sidebar: Dp, rail: Boolean, panel: Dp?): FrameFit {
    val chosen = if (rail) FrameSize.SidebarRail else sidebar
    if (panel == null) return FrameFit(rail, chosen, null, window - chosen - FrameSize.Hairline)
    val lines = FrameSize.Hairline * 2
    val folds = !rail && window - chosen - panel - lines < FrameSize.PageMin
    val side = if (folds) FrameSize.SidebarRail else chosen
    val fitted = panel.coerceAtMost(window - side - lines - FrameSize.PageMin).coerceAtLeast(FrameSize.PanelMin)
    return FrameFit(rail || folds, side, fitted, window - side - fitted - lines)
}

// The floating player's width over a page `page` wide in a window `window`
// wide: a third of the window, but at least what its controls need, and
// never wider than the page leaves room for.
fun playerWidth(page: Dp, window: Dp): Dp {
    val room = page - FrameSize.PlayerGap * 2
    return (window / 3).coerceIn(minOf(FrameSize.PlayerMin, room), room)
}

// Whether the player is too narrow for its usual layout. Then the song
// takes the room the panel buttons had (they move into its More menu), and
// the transport sits beside the song rather than in the middle.
fun playerIsCompact(width: Dp): Boolean = width < FrameSize.PlayerMin
