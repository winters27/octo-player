package app.winters.octo.desktop.window

import app.winters.octo.desktop.settings.WindowSpot

// The smallest the window may be, and its roomy size the first time (or
// when what was saved can't be trusted), at most this share of the screen.
const val MIN_WIDTH = 960f
const val MIN_HEIGHT = 600f
const val FIRST_WIDTH = 1600f
const val FIRST_HEIGHT = 1000f
const val FIRST_SHARE = 0.9f

// The part of a screen a window may use (without the taskbar or menu bar),
// in the same units as WindowSpot.
data class ScreenArea(val x: Float, val y: Float, val width: Float, val height: Float) {
    val right get() = x + width
    val bottom get() = y + height
}

// Where the window opens: where it was last time, if enough of its top
// edge is still on a screen to grab it by; otherwise the size it had,
// centred on the first screen, which is the main one (screenAreas() puts
// it first). Never smaller than the minimum, never larger than the screen
// it lands on.
fun placeWindow(
    saved: WindowSpot?,
    screens: List<ScreenArea>,
    minWidth: Float = MIN_WIDTH,
    minHeight: Float = MIN_HEIGHT,
): WindowSpot {
    val primary = screens.firstOrNull() ?: ScreenArea(0f, 0f, FIRST_WIDTH, FIRST_HEIGHT)
    fun fit(width: Float, height: Float, area: ScreenArea) =
        maxOf(minWidth, minOf(width, area.width)) to maxOf(minHeight, minOf(height, area.height))
    fun centred(width: Float, height: Float, maximized: Boolean): WindowSpot {
        val (w, h) = fit(width, height, primary)
        return WindowSpot(primary.x + (primary.width - w) / 2, primary.y + (primary.height - h) / 2, w, h, maximized)
    }
    // Roomy and centred the first time, and when the saved size is only the
    // minimum (what a minimized window used to be saved as), keeping
    // whether it filled the screen.
    val roomyWidth = minOf(FIRST_WIDTH, primary.width * FIRST_SHARE)
    val roomyHeight = minOf(FIRST_HEIGHT, primary.height * FIRST_SHARE)
    if (saved == null) return centred(roomyWidth, roomyHeight, maximized = false)
    if (saved.width <= minWidth && saved.height <= minHeight) return centred(roomyWidth, roomyHeight, saved.maximized)
    // The title strip: the top 40 units of the window.
    val home = screens.firstOrNull { area ->
        val overlapX = minOf(saved.x + saved.width, area.right) - maxOf(saved.x, area.x)
        val overlapY = minOf(saved.y + 40f, area.bottom) - maxOf(saved.y, area.y)
        overlapX >= 100f && overlapY >= 10f
    } ?: return centred(saved.width, saved.height, saved.maximized)
    val (w, h) = fit(saved.width, saved.height, home)
    // Kept inside the screen it is on.
    val x = saved.x.coerceIn(home.x, maxOf(home.x, home.right - w))
    val y = saved.y.coerceIn(home.y, maxOf(home.y, home.bottom - h))
    return WindowSpot(x, y, w, h, saved.maximized)
}

// Where a window that filled `here` goes back to: where it was before, while
// most of that is still on a screen; otherwise (its screen was unplugged or
// shrank) the same size placed on `here`, the screen it is on now.
fun restoreSpot(
    before: WindowSpot,
    screens: List<ScreenArea>,
    here: ScreenArea,
    minWidth: Float = MIN_WIDTH,
    minHeight: Float = MIN_HEIGHT,
): WindowSpot {
    val seen = screens.sumOf { area ->
        val overlapX = minOf(before.x + before.width, area.right) - maxOf(before.x, area.x)
        val overlapY = minOf(before.y + before.height, area.bottom) - maxOf(before.y, area.y)
        if (overlapX > 0f && overlapY > 0f) (overlapX * overlapY).toDouble() else 0.0
    }
    if (seen * 2 >= before.width.toDouble() * before.height) return before
    return placeWindow(before, listOf(here), minWidth, minHeight)
}

// The same screens with the main one first, so the fallback lands there.
fun <T> mainFirst(screens: List<T>, main: T?): List<T> =
    if (main == null || main !in screens) screens else listOf(main) + (screens - main)

// Whether the window's place is worth keeping for next time: not while it
// fills the screen or is minimized (Windows then reports it far off at
// about -32000 and tiny), only its own size and place.
fun keepsSpot(minimized: Boolean, filled: Boolean, x: Float, y: Float): Boolean =
    !minimized && !filled && x > OFF_SCREEN && y > OFF_SCREEN

private const val OFF_SCREEN = -10_000f
