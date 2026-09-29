package app.winters.octo.covers

// One colour of a picture and how much of it that colour covers, 0 to 1.
data class Swatch(val argb: Int, val share: Float)

// Pixels counted in coarse buckets (4 bits a channel), every `step`th one:
// how many fell in each, and their channels added up.
private class Bucket {
    var count = 0
    var r = 0L
    var g = 0L
    var b = 0L

    val mean: Int get() = (0xFF shl 24) or ((r / count).toInt() shl 16) or ((g / count).toInt() shl 8) or (b / count).toInt()
}

private fun buckets(pixels: IntArray, step: Int): Collection<Bucket> {
    val counts = HashMap<Int, Bucket>()
    var i = 0
    while (i < pixels.size) {
        val p = pixels[i]
        val key = (p shr 20 and 0xF shl 8) or (p shr 12 and 0xF shl 4) or (p shr 4 and 0xF)
        val bucket = counts.getOrPut(key) { Bucket() }
        bucket.count++
        bucket.r += p shr 16 and 0xFF
        bucket.g += p shr 8 and 0xFF
        bucket.b += p and 0xFF
        i += step
    }
    return counts.values
}

// A picture's main colour: the most common of its colours, roughly, as a
// palette's dominant swatch picks it, averaged within its bucket. The
// full player tints the app's glass with it.
fun dominantColour(pixels: IntArray, step: Int = 7): Int =
    buckets(pixels, step).maxByOrNull { it.count }?.mean ?: 0xFF202020.toInt()

// Colours closer than this count as one.
private const val SameColour = 0.09

// A picture's main colours, most of the picture first, up to `most`: its
// buckets from the most common down, each joining the first colour it
// looks like, or starting one of its own.
fun coverSwatches(pixels: IntArray, step: Int = 3, most: Int = 6): List<Swatch> {
    if (pixels.isEmpty()) return emptyList()
    val found = buckets(pixels, step).sortedByDescending { it.count }
    val total = found.sumOf { it.count }.toFloat()
    // Each colour is compared by where its first bucket sat; specks of
    // a colour (under 0.2% of the picture) are left out.
    val groups = mutableListOf<Pair<Lch, Bucket>>()
    for (bucket in found) {
        if (bucket.count < total * 0.002f) break
        val seen = toLch(bucket.mean)
        val near = groups.firstOrNull { distance(it.first, seen) < SameColour }?.second
        if (near != null) {
            near.count += bucket.count
            near.r += bucket.r
            near.g += bucket.g
            near.b += bucket.b
        } else if (groups.size < most * 3) {
            groups += seen to Bucket().also {
                it.count = bucket.count
                it.r = bucket.r
                it.g = bucket.g
                it.b = bucket.b
            }
        }
    }
    return groups.map { it.second }.sortedByDescending { it.count }.take(most).map { Swatch(it.mean, it.count / total) }
}

// The main colours of each quarter of a square picture, top left first. A
// server draws a playlist's picture from the covers of its first four
// albums, a quarter each, so each quarter is one album's colours.
fun quarterSwatches(pixels: IntArray, width: Int, most: Int = 3): List<List<Swatch>> {
    val height = if (width == 0) 0 else pixels.size / width
    if (width < 2 || height < 2) return listOf(coverSwatches(pixels, most = most))
    val halfW = width / 2
    val halfH = height / 2
    return (0 until 4).map { q ->
        val x0 = (q % 2) * halfW
        val y0 = (q / 2) * halfH
        val part = IntArray(halfW * halfH) { i -> pixels[(y0 + i / halfW) * width + x0 + i % halfW] }
        coverSwatches(part, step = 2, most = most)
    }
}
