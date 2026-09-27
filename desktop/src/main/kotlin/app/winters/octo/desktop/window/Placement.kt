package app.winters.octo.desktop.window

import app.winters.octo.desktop.settings.WindowSpot

// The smallest the window may be, and its size the first time.
const val MIN_WIDTH = 960f
const val MIN_HEIGHT = 600f
const val FIRST_WIDTH = 1280f
const val FIRST_HEIGHT = 800f

// The part of a screen a window may use (without the taskbar or menu bar),
// in the same units as WindowSpot.
data class ScreenArea(val x: Float, val y: Float, val width: Float, val height: Float) {
    val right get() = x + width
    val bottom get() = y + height
}

// Where the window opens: where it was last time, if enough of its top
// edge is still on a screen to grab it by; otherwise the size it had,
// centred on the first screen. Never smaller than the minimum, never larger
// than the screen it lands on.
fun placeWindow(saved: WindowSpot?, screens: List<ScreenArea>): WindowSpot {
    val primary = screens.firstOrNull() ?: ScreenArea(0f, 0f, FIRST_WIDTH, FIRST_HEIGHT)
    fun fit(width: Float, height: Float, area: ScreenArea) =
        maxOf(MIN_WIDTH, minOf(width, area.width)) to maxOf(MIN_HEIGHT, minOf(height, area.height))
    fun centred(width: Float, height: Float, maximized: Boolean): WindowSpot {
        val (w, h) = fit(width, height, primary)
        return WindowSpot(primary.x + (primary.width - w) / 2, primary.y + (primary.height - h) / 2, w, h, maximized)
    }
    if (saved == null) return centred(FIRST_WIDTH, FIRST_HEIGHT, maximized = false)
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
