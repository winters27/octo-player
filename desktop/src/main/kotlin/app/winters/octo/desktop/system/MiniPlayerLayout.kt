package app.winters.octo.desktop.system

import app.winters.octo.desktop.settings.WindowSpot
import app.winters.octo.desktop.window.ScreenArea

// The mini player's size the first time, and the least and most it may be.
const val MINI_WIDTH = 380f
const val MINI_HEIGHT = 124f
const val MINI_MIN_WIDTH = 300f
const val MINI_MIN_HEIGHT = 96f
const val MINI_MAX_WIDTH = 720f
const val MINI_MAX_HEIGHT = 900f

// From this tall it stops being a bar: the cover grows large, or lyrics or
// the queue open under a short header.
const val MINI_COVER_HEIGHT = 220f
const val MINI_PANEL_HEIGHT = 300f

// The sizes the size button and the lyrics and queue buttons jump to.
const val MINI_SQUARE_WIDTH = 320f
const val MINI_SQUARE_HEIGHT = 488f
const val MINI_PANEL_WIDTH = 360f
const val MINI_PANEL_OPEN_HEIGHT = 560f

// Room kept between the mini player and the screen's edge the first time.
private const val MINI_MARGIN = 24f

// How much of it must still be on a screen to open where it was.
private const val MINI_SEEN_X = 60f
private const val MINI_SEEN_Y = 40f

// What shows under the header when the window is tall enough.
enum class MiniPanel { Lyrics, Queue }

// The mini player's three shapes: a bar with a small cover, the cover
// large with the controls under it, and a short header over lyrics or
// the queue.
enum class MiniShape { Bar, Cover, Panel }

// The shape for a window this size with this panel chosen. A panel shows
// only when there is room for it; otherwise the cover takes the space.
fun miniShapeFor(height: Float, panel: MiniPanel?): MiniShape = when {
    height < MINI_COVER_HEIGHT -> MiniShape.Bar
    panel != null && height >= MINI_PANEL_HEIGHT -> MiniShape.Panel
    else -> MiniShape.Cover
}

// Where the mini player opens: where it last was when enough of it is still
// on a screen (moved inside that screen), otherwise the bottom right corner
// of the first screen. Its size stays within its limits.
fun placeMiniPlayer(saved: WindowSpot?, screens: List<ScreenArea>): WindowSpot {
    val primary = screens.firstOrNull() ?: ScreenArea(0f, 0f, 1280f, 800f)
    val width = (saved?.width ?: MINI_WIDTH).coerceIn(MINI_MIN_WIDTH, MINI_MAX_WIDTH)
    val height = (saved?.height ?: MINI_HEIGHT).coerceIn(MINI_MIN_HEIGHT, MINI_MAX_HEIGHT)
    val home = saved?.let { spot -> screenHolding(spot.x, spot.y, width, height, screens) }
    if (saved == null || home == null) {
        return WindowSpot(primary.right - width - MINI_MARGIN, primary.bottom - height - MINI_MARGIN, width, height)
    }
    return keptOn(home, saved.x, saved.y, width, height)
}

// The mini player at a new size. It grows away from the screen edge it is
// nearest, so one sitting in the bottom right corner grows up and left and
// stays in the corner; and it never grows off its screen.
fun resizeMiniPlayer(spot: WindowSpot, width: Float, height: Float, screens: List<ScreenArea>): WindowSpot {
    val w = width.coerceIn(MINI_MIN_WIDTH, MINI_MAX_WIDTH)
    val area = screenHolding(spot.x, spot.y, spot.width, spot.height, screens) ?: screens.firstOrNull()
    val h = height.coerceIn(MINI_MIN_HEIGHT, minOf(MINI_MAX_HEIGHT, area?.height ?: MINI_MAX_HEIGHT))
    if (area == null) return WindowSpot(spot.x, spot.y, w, h)
    val nearRight = area.right - (spot.x + spot.width) < spot.x - area.x
    val nearBottom = area.bottom - (spot.y + spot.height) < spot.y - area.y
    val x = if (nearRight) spot.x + spot.width - w else spot.x
    val y = if (nearBottom) spot.y + spot.height - h else spot.y
    return keptOn(area, x, y, w, h)
}

// The size the size button jumps to: a bar grows to the square with the
// cover large, anything taller folds back to the bar.
fun toggledMiniSize(spot: WindowSpot): Pair<Float, Float> =
    if (miniShapeFor(spot.height, null) == MiniShape.Bar) MINI_SQUARE_WIDTH to MINI_SQUARE_HEIGHT else MINI_WIDTH to MINI_HEIGHT

// The size for showing a panel: the window as it is when the panel already
// fits, otherwise tall enough for it (and wide enough to read lines in).
fun miniSizeForPanel(spot: WindowSpot): Pair<Float, Float> =
    if (spot.height >= MINI_PANEL_HEIGHT) {
        spot.width to spot.height
    } else {
        maxOf(spot.width, MINI_PANEL_WIDTH) to MINI_PANEL_OPEN_HEIGHT
    }

// The screen a window this size at this spot is mostly on, when enough of
// it shows there.
private fun screenHolding(x: Float, y: Float, width: Float, height: Float, screens: List<ScreenArea>): ScreenArea? =
    screens.firstOrNull { area ->
        val overlapX = minOf(x + width, area.right) - maxOf(x, area.x)
        val overlapY = minOf(y + height, area.bottom) - maxOf(y, area.y)
        overlapX >= MINI_SEEN_X && overlapY >= MINI_SEEN_Y
    }

// The spot moved just enough to sit wholly inside the screen.
private fun keptOn(area: ScreenArea, x: Float, y: Float, width: Float, height: Float): WindowSpot =
    WindowSpot(
        x.coerceIn(area.x, maxOf(area.x, area.right - width)),
        y.coerceIn(area.y, maxOf(area.y, area.bottom - height)),
        width,
        height,
    )
