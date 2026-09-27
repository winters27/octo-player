package app.winters.octo.player.immersive

// How long one cover takes to become the next, and the quicker fade used
// when covers change within a moment of each other (skipping through).
const val CoverFadeMs = 500L
const val QuickCoverFadeMs = 300L
const val QuickChangeWindowMs = 1_500L

// The fade for a cover arriving now: none for the very first, quick when
// the last change was under 1.5 s ago, otherwise the full one.
fun coverFadeMs(lastChangeMs: Long?, nowMs: Long): Long = when {
    lastChangeMs == null -> 0L
    nowMs - lastChangeMs < QuickChangeWindowMs -> QuickCoverFadeMs
    else -> CoverFadeMs
}

// Slow at both ends, quick through the middle.
fun easeInOutCubic(x: Float): Float {
    val t = x.coerceIn(0f, 1f)
    return if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).let { it * it * it } / 2f
}

// The fade between the old cover and the new one, run by the frame clock.
class CoverFade {
    private var durationMs = 0L
    private var elapsedMs = 0f
    private var lastChangeMs: Long? = null

    // How far the new cover has come in, 0 to 1, before easing.
    val progress: Float get() = if (durationMs <= 0L) 1f else (elapsedMs / durationMs).coerceIn(0f, 1f)

    // The mix of old and new the drawing uses.
    val mix: Float get() = easeInOutCubic(progress)

    val running: Boolean get() = progress < 1f

    // A new cover has arrived at this moment; returns the fade's length.
    fun start(nowMs: Long): Long {
        durationMs = coverFadeMs(lastChangeMs, nowMs)
        elapsedMs = 0f
        lastChangeMs = nowMs
        return durationMs
    }

    fun advance(ms: Float) {
        elapsedMs += ms
    }
}
