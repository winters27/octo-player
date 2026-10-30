package app.winters.octo.wayland

import app.winters.octo.desktop.settings.WindowSpot

// A maximized configure reports the screen size, not the last normal bounds.
// Fullscreen configures are excluded by the caller, including their max flag.
internal fun windowSpot(saved: WindowSpot?, width: Int, height: Int, maximized: Boolean, mini: Boolean): WindowSpot {
    val normal = if (maximized) saved ?: WindowSpot(0f, 0f, if (mini) 520f else 1100f, if (mini) 160f else 760f)
        else WindowSpot(0f, 0f, width.toFloat(), height.toFloat())
    return normal.copy(maximized = maximized)
}
