package app.winters.octo.design

import android.animation.ValueAnimator
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize

// Where a pop-up is among its pages: the pages opened, the first at the
// bottom, and whether the last move went deeper or came back, which sets
// the way the pages slide. `opening` counts the times the pop-up started
// over, so a page opened afresh starts fresh even when it is the same page.
@Immutable
data class PageTrail<T>(val pages: List<T>, val deeper: Boolean = true, val opening: Int = 0) {
    init {
        require(pages.isNotEmpty()) { "A pop-up always shows a page" }
    }

    val current: T get() = pages.last()
    val canGoBack: Boolean get() = pages.size > 1

    // A page opened from this one, with a way back.
    fun open(page: T): PageTrail<T> = copy(pages = pages + page, deeper = true)

    // Back to the page before; on the first page nothing changes.
    fun back(): PageTrail<T> = if (canGoBack) copy(pages = pages.dropLast(1), deeper = false) else this

    // This page swapped for another at the same depth, as when a question
    // is answered and the answer takes its place.
    fun replace(page: T): PageTrail<T> = copy(pages = pages.dropLast(1) + page, deeper = true)

    // Back at the start with `first`, as the pop-up opens again.
    fun restart(first: T): PageTrail<T> = PageTrail(listOf(first), deeper = true, opening = opening + 1)

    companion object {
        fun <T> of(first: T): PageTrail<T> = PageTrail(listOf(first))
    }
}

// The pages of one pop-up. Opening it again starts at its first page.
@Stable
class PopupPages<T>(first: T) {
    var trail by mutableStateOf(PageTrail.of(first))
        private set

    val current: T get() = trail.current
    val canGoBack: Boolean get() = trail.canGoBack

    fun open(page: T) {
        trail = trail.open(page)
    }

    fun replace(page: T) {
        trail = trail.replace(page)
    }

    // Answers whether there was a page to go back to, so back closes the
    // pop-up only from its first page.
    fun back(): Boolean {
        val had = trail.canGoBack
        trail = trail.back()
        return had
    }

    fun reset(first: T) {
        trail = trail.restart(first)
    }
}

@Composable
fun <T> rememberPopupPages(first: T): PopupPages<T> = remember { PopupPages(first) }

// Shows the pop-up's current page. A new page slides in from the side it
// is going and the card eases to its height; back slides the other way.
// With motion reduced the pages only fade, and the card takes its new size
// at once. Each page is told whether it has a page to go back to.
@Composable
fun <T> PopupPager(pages: PopupPages<T>, modifier: Modifier = Modifier, content: @Composable (page: T, canGoBack: Boolean) -> Unit) {
    val animatorsOff = remember { !ValueAnimator.areAnimatorsEnabled() }
    val still = LocalReduceMotion.current || animatorsOff
    val motion = motionScale()
    AnimatedContent(
        targetState = pages.trail,
        modifier = modifier,
        contentKey = { it.opening to it.pages },
        transitionSpec = {
            if (still) {
                fadeIn(octoTween(motion, OctoDuration.Swap)) togetherWith fadeOut(octoTween(motion, OctoDuration.Press)) using SizeTransform(clip = true) { _, _ -> snap() }
            } else {
                val way = if (targetState.deeper) 1 else -1
                val enter = slideInHorizontally(OctoMotion.calm()) { width -> way * width / 3 } + fadeIn(tween(motion.ms(OctoDuration.Fill), delayMillis = 40, easing = OctoEasing.Spring))
                val exit = slideOutHorizontally(OctoMotion.calm()) { width -> -way * width / 3 } + fadeOut(octoTween(motion, OctoDuration.Press))
                enter togetherWith exit using SizeTransform(clip = true) { _, _ -> OctoMotion.calm<IntSize>() }
            }
        },
        label = "popup pages",
    ) { trail ->
        // A page that changes its own height, as a list loading in, eases too.
        Box(Modifier.animateContentSize(if (still) snap() else OctoMotion.calm())) { content(trail.current, trail.canGoBack) }
    }
}
