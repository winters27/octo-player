package app.winters.octo.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// Icons drawn on whole pixels. Below 2x, an icon drawn at one of the icon
// sizes (IconSize.Fitted) is drawn from its outlines fitted to that many
// pixels, when the fit is kept (see pixelOutlines); at any other size, at
// 2x and above, or before its fit is ready, it is drawn exactly as its file
// says. Fits are worked out off the drawing thread, by one worker, and the
// icon redraws when its fit lands.

// How a vector was made from its file: so it can be fitted in the file's
// own square, then turned and mirrored as the vector is.
class IconRecipe(val source: IconSource, val rotation: Float, val mirrored: Boolean, val shiftY: Float) {
    val fits: Boolean
        get() = source.stroke != null && source.viewportWidth == source.viewportHeight && source.widthDp == source.heightDp
}

// Every vector made from an icon file, by the vector itself.
object IconRecipes {
    private val recipes: MutableMap<ImageVector, IconRecipe> = Collections.synchronizedMap(IdentityHashMap<ImageVector, IconRecipe>())

    fun record(vector: ImageVector, recipe: IconRecipe) {
        recipes[vector] = recipe
    }

    fun of(vector: ImageVector): IconRecipe? = recipes[vector]

    val size: Int get() = recipes.size

    // Every vector that can be fitted, with its recipe.
    fun fittable(): List<Pair<ImageVector, IconRecipe>> = synchronized(recipes) { recipes.entries.filter { it.value.fits }.map { it.key to it.value } }
}

// A fitted icon at one pixel size: its paths, in pixels, ready to draw.
class FittedIcon(val paths: List<Path>)

// The icon fitted to `px` pixels, or null when it is drawn as written.
fun fitIcon(recipe: IconRecipe, px: Int): FittedIcon? =
    fittedOutlines(recipe, px)?.let { kept -> FittedIcon(kept.map { it.toComposePath(px / recipe.source.viewportWidth) }) }

// The kept fit's outlines in the file's square: fitted where the file
// draws them (after the move down), then turned and mirrored.
fun fittedOutlines(recipe: IconRecipe, px: Int): List<Outline>? {
    if (!recipe.fits) return null
    val source = recipe.source
    val shifted = outlinesOf(source).map { outline -> outline.mapValues { i, v -> if (i % 2 == 1) v + recipe.shiftY else v } }
    val fitted = fitOutlines(shifted, source.viewportWidth, px, source.stroke ?: return null)
    val kept = judged(shifted, fitted, source.viewportWidth, px, source.stroke, IconList.lined(source.name)) ?: return null
    return kept.map { it.turned(recipe, source.viewportWidth / 2, source.viewportHeight / 2) }
}

// The outline turned by the recipe's rotation and mirrored when it is,
// about the square's middle, as the vector's group turns it. Quarter turns
// are exact, so whole pixels stay whole.
private fun Outline.turned(recipe: IconRecipe, pivotX: Float, pivotY: Float): Outline {
    if (recipe.rotation == 0f && !recipe.mirrored) return this
    val (c, s) = when (((recipe.rotation % 360f) + 360f) % 360f) {
        0f -> 1.0 to 0.0
        90f -> 0.0 to 1.0
        180f -> -1.0 to 0.0
        270f -> 0.0 to -1.0
        else -> Math.toRadians(recipe.rotation.toDouble()).let { cos(it) to sin(it) }
    }
    val sx = if (recipe.mirrored) -1.0 else 1.0
    return Outline(
        steps.map { step ->
            val v = step.values
            val out = DoubleArray(v.size)
            for (i in 0 until v.size step 2) {
                val x = (v[i] - pivotX) * sx
                val y = v[i + 1] - pivotY
                out[i] = c * x - s * y + pivotX
                out[i + 1] = s * x + c * y + pivotY
            }
            OutlineStep(step.kind, out)
        },
        evenOdd,
    )
}

// Whether `px` pixels is one of the icon sizes at this screen scale.
fun isIconSizePx(px: Int, density: Float): Boolean = IconSize.Fitted.any { (it.value * density).roundToInt() == px }

// Every icon size, in pixels at this screen scale.
fun iconSizePxs(density: Float): List<Int> = IconSize.Fitted.map { (it.value * density).roundToInt() }.distinct()

// The fits worked out so far, by vector and pixel size. One worker works
// them out and alone adds and drops them (the oldest first, past `cap`);
// drawing only reads. `decide` is the fitting itself.
@OptIn(ExperimentalCoroutinesApi::class)
class IconFits(private val cap: Int = 4096, private val decide: (IconRecipe, Int) -> FittedIcon? = ::fitIcon) {
    private class Key(val vector: ImageVector, val px: Int) {
        override fun equals(other: Any?) = other is Key && other.vector === vector && other.px == px
        override fun hashCode() = System.identityHashCode(vector) * 31 + px
    }

    // Kept for an icon drawn as written at that size.
    private object AsWritten

    private val worker = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + worker)
    private val fits = ConcurrentHashMap<Key, Any>()
    private val asked: MutableSet<Key> = ConcurrentHashMap.newKeySet()
    private val pending = AtomicInteger()
    // Only the worker reads and writes this.
    private val order = ArrayDeque<Key>()
    private val landedState = mutableIntStateOf(0)

    // Counts up each time the worker runs out of fits to work out, so
    // icons drawn as written while theirs was missing redraw.
    val landed: State<Int> get() = landedState

    val size: Int get() = fits.size

    // Whether the fit for the vector at `px` has been worked out.
    fun known(vector: ImageVector, px: Int): Boolean = fits.containsKey(Key(vector, px))

    // The fitted icon, or null when it is drawn as written or not known yet.
    fun fitted(vector: ImageVector, px: Int): FittedIcon? = fits[Key(vector, px)] as? FittedIcon

    // Has the worker work out the fit, unless it is known or on its way.
    fun ask(vector: ImageVector, recipe: IconRecipe, px: Int): Job? {
        val key = Key(vector, px)
        if (fits.containsKey(key) || !asked.add(key)) return null
        pending.incrementAndGet()
        return scope.launch {
            try {
                if (!fits.containsKey(key)) store(key, decide(recipe, px) ?: AsWritten)
            } finally {
                asked.remove(key)
                if (pending.decrementAndGet() == 0) landedState.intValue++
            }
        }
    }

    private fun store(key: Key, fit: Any) {
        fits[key] = fit
        order.addLast(key)
        while (order.size > cap) fits.remove(order.removeFirst())
    }

    // Drops every fit, on the worker, so a test can start from none.
    internal fun forget(): Job = scope.launch {
        fits.clear()
        order.clear()
    }

    // Works out every fittable icon at every icon size for this screen
    // scale. The job ends when they are all known.
    fun warm(density: Float): Job = scope.launch {
        // Every named icon made, so all of them are known.
        allIcons.forEach { (_, icon) -> icon() }
        if (density >= HIGH_DENSITY) return@launch
        val pxs = iconSizePxs(density)
        IconRecipes.fittable().flatMap { (vector, recipe) -> pxs.mapNotNull { px -> ask(vector, recipe, px) } }.joinAll()
    }

    // The fit to draw for `vector` filling `size` at `density`: null to
    // draw it as written. A missing fit is asked for, and the read of
    // `landed` redraws the icon when it is known.
    fun forDrawing(vector: ImageVector, size: Size, density: Float): FittedIcon? {
        if (density >= HIGH_DENSITY || size.width != size.height) return null
        val px = size.width.roundToInt()
        if (abs(size.width - px) > 0.01f || !isIconSizePx(px, density)) return null
        val found = fits[Key(vector, px)]
        if (found != null) return found as? FittedIcon
        val recipe = IconRecipes.of(vector)?.takeIf { it.fits } ?: return null
        landed.value
        ask(vector, recipe, px)
        return null
    }

    companion object {
        // From this screen scale up icons are drawn exactly: their edges
        // are already fine.
        const val HIGH_DENSITY = 2f

        // The app's fits.
        val shared = IconFits()
    }
}

// Draws `vector` fitted when its fit is known, else through `plain` (the
// vector as written).
class CrispPainter(private val vector: ImageVector, private val plain: Painter, private val fits: IconFits = IconFits.shared) : Painter() {
    private var alpha = 1f
    private var filter: ColorFilter? = null

    override val intrinsicSize: Size get() = plain.intrinsicSize

    override fun applyAlpha(alpha: Float): Boolean {
        this.alpha = alpha
        return true
    }

    override fun applyColorFilter(colorFilter: ColorFilter?): Boolean {
        filter = colorFilter
        return true
    }

    override fun DrawScope.onDraw() {
        val fitted = fits.forDrawing(vector, size, density)
        if (fitted == null) {
            with(plain) { draw(size, alpha, filter) }
            return
        }
        if (alpha < 1f && fitted.paths.size > 1) {
            // Overlapping paths fade as one, as the vector's do.
            val canvas = drawContext.canvas
            canvas.saveLayer(Rect(Offset.Zero, size), Paint().also { it.alpha = alpha })
            fitted.paths.forEach { drawPath(it, Color.White, colorFilter = filter) }
            canvas.restore()
        } else {
            fitted.paths.forEach { drawPath(it, Color.White, alpha = alpha, colorFilter = filter) }
        }
    }
}

// The painter every desktop icon is drawn with: on whole pixels at the icon
// sizes below 2x (see the top of this file). A vector not made from an icon
// file with a stroke width (one built in code) is drawn as written.
@Composable
fun crispPainter(icon: ImageVector): Painter {
    val plain = rememberVectorPainter(icon)
    if (IconRecipes.of(icon)?.fits != true) return plain
    return remember(icon, plain) { CrispPainter(icon, plain) }
}

// The painter for an icon drawn exactly as written at any size: a brand's
// mark, whose exact shape is the brand, and icons drawn at sizes outside
// the icon sizes.
@Composable
fun exactPainter(icon: ImageVector): Painter = rememberVectorPainter(icon)

// The painter the shared controls draw their icons with; on the desktop,
// on whole pixels.
@Composable
fun rememberIconPainter(icon: ImageVector): Painter = crispPainter(icon)

// Works out the icons' fits for this window's screen scale as it opens and
// whenever the scale changes, so icons are crisp from their first frames.
@Composable
fun WarmCrispIcons() {
    val density = LocalDensity.current.density
    LaunchedEffect(density) { IconFits.shared.warm(density) }
}
