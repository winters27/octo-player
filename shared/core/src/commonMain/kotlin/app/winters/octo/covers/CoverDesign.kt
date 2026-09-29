package app.winters.octo.covers

import app.winters.octo.player.immersive.contrastRatio
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// A playlist's cover: soft fields of colour, and white words at the top
// left (its name large, a light line under it saying what it is) with a
// small line at the foot. The numbers and the gradients are in
// cover-design.json (CoverBook); the colours come from the list's own
// music, or from the gradient as it is written when it has no covers.

// What a cover is made from: whose it is (its id), the name, the light line
// under it ("Playlist", "Live list"), the small line at the foot ("12
// songs", "By sam"), and its colours.
data class CoverSpec(
    val id: String,
    val name: String,
    val line: String?,
    val footer: String?,
    val palette: CoverPalette,
)

// Which of the book's gradients a playlist gets: always the same one, from
// its id, so a long list of them varies while each keeps its look.
fun coverGradientOf(id: String, book: CoverBook = CoverBook.Default): Int =
    ((coverHash(id) ushr 7) % book.gradients.size).toInt()

// A colour at a point of a gradient, 0 to 1 along it; the colour may be
// partly see-through.
data class Stop(val at: Float, val argb: Int)

// What a cover is painted with, bottom first, in pixels.
sealed interface CoverLayer {
    data class Linear(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val stops: List<Stop>) : CoverLayer

    data class Radial(val cx: Float, val cy: Float, val radius: Float, val stops: List<Stop>) : CoverLayer
}

enum class CoverAlign { Left, Right }

// Words on a cover: what, how they are set, the box they are set in, how
// they sit in it, their colour (white, perhaps partly see-through), and
// what they measured.
data class CoverWords(
    val text: String,
    val type: CoverType,
    val left: Float,
    val top: Float,
    val width: Float,
    val align: CoverAlign,
    val ink: Int,
    val measured: Measured,
) {
    val height: Float get() = measured.height

    // Where the letters themselves are, as left, top, right, bottom.
    val inked: FloatArray
        get() {
            val w = measured.width.coerceAtMost(width)
            val x = if (align == CoverAlign.Left) left else left + width - w
            return floatArrayOf(x, top, x + w, top + measured.height)
        }
}

// A cover ready to paint: its side in pixels, its layers and its words.
data class CoverArt(val side: Int, val layers: List<CoverLayer>, val words: List<CoverWords>)

private const val White = 0xFFFFFFFF.toInt()
private const val Black = 0xFF000000.toInt()

// The gradient's colours for this palette: as written, or turned round the
// colour wheel so its fold colour takes the music's first hue, keeping the
// gradient's own lightness and spread of hues, its chroma met halfway by
// the music's.
// The light's colour comes last, turned the same way.
fun gradientColours(gradient: CoverGradient, palette: CoverPalette, book: CoverBook = CoverBook.Default): List<Int> {
    val written = gradient.colours.map(::hexColour) + listOfNotNull(gradient.light?.colour?.let(::hexColour))
    if (!palette.fromMusic) return written
    val anchor = gradient.folds.firstOrNull()?.colour?.coerceIn(0, gradient.colours.lastIndex) ?: gradient.colours.lastIndex
    val turn = palette.hue - toLch(written[anchor]).h
    val rule = book.music
    return written.map { argb ->
        val own = toLch(argb)
        val chroma = ((own.c + palette.chroma) / 2).coerceIn(rule.minChroma, rule.maxChroma)
        lchToArgb(own.l, chroma, notOlive(own.h + turn, own.l, towardRed = palette.hue !in 98..300))
    }
}

// Yellow and orange turn to olive and brown in the dark, so a deep colour
// of those hues moves to red, or to green for music that is green or blue
// (the same way for every colour of a cover, so no gradient runs from red
// to green through mud).
internal fun notOlive(hue: Double, lightness: Double, towardRed: Boolean): Double {
    val h = ((hue % 360) + 360) % 360
    return when {
        lightness < 0.45 && towardRed && h in 40.0..170.0 -> 20.0
        lightness < 0.45 && h in 40.0..125.0 -> 145.0
        lightness < 0.62 && towardRed && h in 70.0..160.0 -> 55.0
        lightness < 0.62 && h in 70.0..125.0 -> 140.0
        else -> h
    }
}

// The background's layers for a cover `side` pixels square.
fun gradientLayers(gradient: CoverGradient, colours: List<Int>, side: Int): List<CoverLayer> {
    val s = side.toFloat()
    fun colour(i: Int) = colours[i.coerceIn(0, gradient.colours.lastIndex)]
    val base = gradient.base
    val layers = mutableListOf<CoverLayer>(
        CoverLayer.Linear(
            base.from[0] * s, base.from[1] * s, base.to[0] * s, base.to[1] * s,
            base.stops.map { Stop(it[0], colour(it[1].roundToInt())) },
        ),
    )
    for (fold in gradient.folds) {
        val c = colour(fold.colour)
        layers += CoverLayer.Radial(
            fold.centre[0] * s, fold.centre[1] * s, fold.radius * s,
            listOf(Stop(0f, c), Stop((1f - fold.soft).coerceIn(0f, 1f), c), Stop(1f, alpha(c, 0f))),
        )
    }
    gradient.light?.let { light ->
        val c = colours.getOrElse(gradient.colours.size) { hexColour(light.colour) }
        layers += CoverLayer.Radial(light.centre[0] * s, light.centre[1] * s, light.radius * s, listOf(Stop(0f, alpha(c, light.opacity)), Stop(1f, alpha(c, 0f))))
    }
    return layers
}

// The whole design of a cover `side` pixels square, the words measured by
// the app's own text engine.
fun composeCover(spec: CoverSpec, side: Int, setter: CoverTypesetter, book: CoverBook = CoverBook.Default): CoverArt {
    val gradient = book.gradients[coverGradientOf(spec.id, book)]
    val layers = gradientLayers(gradient, gradientColours(gradient, spec.palette, book), side)
    val words = coverWords(spec, side, setter, book)
    val (placed, painted) = readableWords(layers, words, side, book.layout.contrast)
    return CoverArt(side, painted, placed)
}

// Where the words go, all white for now.
fun coverWords(spec: CoverSpec, side: Int, setter: CoverTypesetter, book: CoverBook = CoverBook.Default): List<CoverWords> {
    val layout = book.layout
    val s = side.toFloat()
    val rtl = isRightToLeft(spec.name)
    val align = if (rtl) CoverAlign.Right else CoverAlign.Left
    val name = spec.name.trim()
    if (side < layout.tinyBelowPx) {
        if (name.isEmpty()) return emptyList()
        val margin = max(2f, (s * layout.tinyMargin).roundToInt().toFloat())
        val letter = monogram(name)
        val look = CoverType(0f, layout.title.weight, 0f, coverLineHeight(coverScript(letter), 1f), 1)
        val fit = fitCoverText(letter, s - 2 * margin, s - 2 * margin, 1, max(8f, s * 0.3f), s * layout.tinySize, look, setter)
        return listOf(CoverWords(letter, fit.type, margin, margin, s - 2 * margin, align, White, fit.measured))
    }
    val margin = (s * layout.margin).roundToInt().toFloat()
    val width = s - 2 * margin
    val out = mutableListOf<CoverWords>()
    var foot: CoverWords? = null

    // The foot line first, so the name knows how far down it may go.
    var floor = s - margin
    val footerText = spec.footer?.trim()?.takeIf { it.isNotEmpty() && side >= layout.footer.showFromPx }
    if (footerText != null) {
        val f = layout.footer
        val look = CoverType(0f, f.weight, f.tracking, coverLineHeight(coverScript(footerText), f.lineHeight), 1)
        val size = max(f.minPx, s * f.size)
        val fit = fitCoverText(footerText, width, s * 0.2f, 1, f.minPx, size, look, setter)
        val top = s - s * f.bottom - fit.measured.height
        foot = CoverWords(footerText, fit.type, margin, top, width, align, alpha(White, f.opacity), fit.measured)
        floor = top - s * 0.04f
    }
    if (name.isEmpty()) return listOfNotNull(foot)

    val t = layout.title
    val top = s * t.top
    val lineText = spec.line?.trim()?.takeIf { it.isNotEmpty() && side >= layout.line.showFromPx }
    val lineRoom = if (lineText == null) 0f else max(s * t.wrapSize, t.wrapMinPx) * layout.line.shareOfTitle * layout.line.lineHeight
    val room = floor - top - lineRoom
    val look = CoverType(0f, t.weight, t.tracking, coverLineHeight(coverScript(name), t.lineHeight), 1)
    val minPx = max(t.minPx, s * t.minSize)
    val oneLineLeast = max(minPx, max(s * t.oneLineDownTo, t.oneLineMinPx))
    val wrapMost = max(minPx, max(s * t.wrapSize, min(t.wrapMinPx, s * t.size)))
    val title = (if (oneLineLeast <= s * t.size) fitOrNull(name, width, room, 1, oneLineLeast, s * t.size, look, setter) else null)
        ?: fitCoverText(name, width, room, t.maxLines, minPx, wrapMost, look, setter)
    out += CoverWords(name, title.type, margin, top, width, align, White, title.measured)

    if (lineText != null) {
        val l = layout.line
        val lineTop = top + title.measured.height
        val most = max(l.minPx, title.type.sizePx * l.shareOfTitle)
        val lineLook = CoverType(0f, l.weight, l.tracking, coverLineHeight(coverScript(lineText), l.lineHeight), 1)
        val fit = fitCoverText(lineText, width, s, 1, min(l.minPx, most), most, lineLook, setter)
        if (lineTop + fit.measured.height <= floor) out += CoverWords(lineText, fit.type, margin, lineTop, width, align, White, fit.measured)
    }
    return out + listOfNotNull(foot)
}

// The colour with this opacity.
internal fun alpha(argb: Int, a: Float): Int = ((a.coerceIn(0f, 1f) * 255).roundToInt() shl 24) or (argb and 0xFFFFFF)

// One colour laid over another at the first one's opacity.
internal fun over(top: Int, under: Int): Int {
    val a = (top ushr 24 and 0xFF) / 255f
    if (a >= 1f) return top or (0xFF shl 24)
    fun channel(shift: Int): Int {
        val t = top shr shift and 0xFF
        val u = under shr shift and 0xFF
        return (u + (t - u) * a).roundToInt().coerceIn(0, 255)
    }
    return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}

// A gradient's colour `t` of the way along, each channel (and the opacity)
// mixed between the stops either side, as the screen draws gradients.
internal fun colourAlong(stops: List<Stop>, t: Float): Int {
    if (t <= stops.first().at) return stops.first().argb
    if (t >= stops.last().at) return stops.last().argb
    val after = stops.indexOfFirst { it.at >= t }
    val a = stops[after - 1]
    val b = stops[after]
    val f = if (b.at == a.at) 1f else (t - a.at) / (b.at - a.at)
    fun channel(shift: Int): Int {
        val x = a.argb ushr shift and 0xFF
        val y = b.argb ushr shift and 0xFF
        return (x + (y - x) * f).roundToInt().coerceIn(0, 255)
    }
    return (channel(24) shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}

// The colour the layers paint at a point.
fun colourAt(layers: List<CoverLayer>, x: Float, y: Float): Int {
    var colour = Black
    for (layer in layers) {
        colour = when (layer) {
            is CoverLayer.Linear -> {
                val dx = layer.x1 - layer.x0
                val dy = layer.y1 - layer.y0
                val length = dx * dx + dy * dy
                val t = if (length == 0f) 0f else ((x - layer.x0) * dx + (y - layer.y0) * dy) / length
                over(colourAlong(layer.stops, t.coerceIn(0f, 1f)), colour)
            }
            is CoverLayer.Radial -> {
                val t = hypot(x - layer.cx, y - layer.cy) / layer.radius
                over(colourAlong(layer.stops, t.coerceIn(0f, 1f)), colour)
            }
        }
    }
    return colour
}

// The colours under a box of words, on a grid over it and a little round it.
internal fun coloursUnder(layers: List<CoverLayer>, box: FloatArray, side: Int, grid: Int = 9): List<Int> {
    val pad = side * 0.015f
    val left = box[0] - pad
    val top = box[1] - pad
    val w = box[2] - box[0] + 2 * pad
    val h = box[3] - box[1] + 2 * pad
    return buildList {
        for (i in 0 until grid) for (j in 0 until grid) {
            val x = (left + w * i / (grid - 1)).coerceIn(0f, side - 1f)
            val y = (top + h * j / (grid - 1)).coerceIn(0f, side - 1f)
            add(colourAt(layers, x, y))
        }
    }
}

// The lowest contrast words in `ink` (perhaps partly see-through) get over
// any part of the colour under `box`.
fun worstContrast(layers: List<CoverLayer>, box: FloatArray, side: Int, ink: Int): Double =
    coloursUnder(layers, box, side).minOf { contrastRatio(over(ink, it), it) }

// A darkening under a box of words, `strength` at its fullest: from the
// foot up for words low on the cover, from the top down for words high on it.
internal fun shadeUnder(box: FloatArray, side: Int, strength: Float): CoverLayer {
    val s = side.toFloat()
    val full = alpha(Black, strength)
    val none = alpha(Black, 0f)
    return if ((box[1] + box[3]) / 2 > s / 2) {
        CoverLayer.Linear(0f, box[1] - s * 0.24f, 0f, box[1], listOf(Stop(0f, none), Stop(1f, full)))
    } else {
        CoverLayer.Linear(0f, box[3] + s * 0.24f, 0f, box[3], listOf(Stop(0f, none), Stop(1f, full)))
    }
}

// The words with the colour each needs to read, and the layers with any
// darkening added: each block of words keeps its own opacity where that
// reaches `contrast` over every part of the colour under it; else it goes
// more opaque, up to solid white; else the colour under it is darkened just
// enough. Never dark words: white reads on anything once darkened.
fun readableWords(layers: List<CoverLayer>, words: List<CoverWords>, side: Int, contrast: Double): Pair<List<CoverWords>, List<CoverLayer>> {
    val goal = contrast + 0.05
    var current = layers
    val placed = words.map { word ->
        val box = word.inked
        val own = (word.ink ushr 24 and 0xFF) / 255f
        if (worstContrast(current, box, side, word.ink) >= goal) return@map word
        if (worstContrast(current, box, side, White) >= goal) {
            var low = own
            var high = 1f
            repeat(12) {
                val mid = (low + high) / 2
                if (worstContrast(current, box, side, alpha(White, mid)) >= goal) high = mid else low = mid
            }
            return@map word.copy(ink = alpha(White, high))
        }
        var low = 0f
        var high = 0.95f
        repeat(16) {
            val mid = (low + high) / 2
            if (worstContrast(current + shadeUnder(box, side, mid), box, side, White) >= goal) high = mid else low = mid
        }
        current = current + shadeUnder(box, side, high)
        word.copy(ink = White)
    }
    return placed to current
}
