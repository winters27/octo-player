package app.winters.octo.player.immersive

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.os.Build
import android.util.LruCache
import androidx.compose.ui.graphics.toArgb
import app.winters.octo.player.PlayerColors
import app.winters.octo.playback.artworkBitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale

// A cover made ready for the background: the 512 square the wash and the
// blurred artwork draw from, and on phones older than Android 12, which
// cannot blur as they draw, a small copy blurred once here instead.
class WashCover(val key: String, val square: Bitmap, val stillBlurred: Bitmap?)

// Prepares covers for the background, off the main thread, and keeps the
// last few so going back a song is instant.
@Singleton
class WashArtwork @Inject constructor(@ApplicationContext private val context: Context) {
    private val cache = LruCache<String, WashCover>(4)

    // The cover for a song's artwork with these adjustments. A song with
    // no artwork gets a soft blend of its colours instead.
    suspend fun prepare(ref: String?, colors: PlayerColors, tuning: WashTuning): WashCover {
        val key = "$ref|${tuning.contrast}|${tuning.saturation}|${tuning.brightnessCap}"
        cache.get(key)?.let { return it }
        val source = ref?.let { withContext(Dispatchers.IO) { artworkBitmap(context, it, WashSize) } }
        val cover = withContext(Dispatchers.Default) {
            val square = processed(source ?: blend(colors), tuning)
            WashCover(key, square, if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) stillBlur(square) else null)
        }
        cache.put(key, cover)
        return cover
    }

    // Cropped to its middle square and brought to 512, then contrast and
    // saturation through a colour matrix, then the brightness cap pixel by
    // pixel.
    private fun processed(source: Bitmap, tuning: WashTuning): Bitmap {
        val out = createBitmap(WashSize, WashSize)
        val side = minOf(source.width, source.height)
        val crop = Rect((source.width - side) / 2, (source.height - side) / 2, (source.width + side) / 2, (source.height + side) / 2)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix(washColorMatrix(tuning)))
        }
        val readable = if (source.config == Bitmap.Config.HARDWARE) source.copy(Bitmap.Config.ARGB_8888, false) else source
        Canvas(out).drawBitmap(readable, crop, Rect(0, 0, WashSize, WashSize), paint)
        val pixels = IntArray(WashSize * WashSize)
        out.getPixels(pixels, 0, WashSize, 0, 0, WashSize, WashSize)
        capBrightness(pixels, tuning.brightnessCap)
        out.setPixels(pixels, 0, WashSize, 0, 0, WashSize, WashSize)
        return out
    }

    // For music without artwork: its colours, blended corner to corner.
    private fun blend(colors: PlayerColors): Bitmap {
        val out = createBitmap(WashSize, WashSize)
        val paint = Paint().apply {
            shader = LinearGradient(
                0f, 0f, WashSize.toFloat(), WashSize.toFloat(),
                colors.mesh.map { it.toArgb() }.toIntArray(), null, Shader.TileMode.CLAMP,
            )
        }
        Canvas(out).drawRect(0f, 0f, WashSize.toFloat(), WashSize.toFloat(), paint)
        return out
    }

    // A small copy, box-blurred three times over (close to a bell-curve
    // blur), which draws smoothly when stretched over the screen.
    private fun stillBlur(square: Bitmap): Bitmap {
        val small = square.scale(StillSize, StillSize)
        val pixels = IntArray(StillSize * StillSize)
        small.getPixels(pixels, 0, StillSize, 0, 0, StillSize, StillSize)
        repeat(3) { boxBlur(pixels, StillSize, StillSize, StillRadius) }
        small.setPixels(pixels, 0, StillSize, 0, 0, StillSize, StillSize)
        return small
    }

    private companion object {
        const val StillSize = 64
        const val StillRadius = 3
    }
}

// One box blur over a picture's pixels, across then down, in place. Edges
// repeat their last pixel.
fun boxBlur(pixels: IntArray, width: Int, height: Int, radius: Int) {
    val line = IntArray(maxOf(width, height))
    fun pass(count: Int, length: Int, index: (Int, Int) -> Int) {
        val span = 2 * radius + 1
        for (n in 0 until count) {
            var a = 0
            var r = 0
            var g = 0
            var b = 0
            for (i in -radius..radius) {
                val p = pixels[index(n, i.coerceIn(0, length - 1))]
                a += p ushr 24 and 0xFF; r += p shr 16 and 0xFF; g += p shr 8 and 0xFF; b += p and 0xFF
            }
            for (i in 0 until length) {
                line[i] = ((a / span) shl 24) or ((r / span) shl 16) or ((g / span) shl 8) or (b / span)
                val out = pixels[index(n, (i - radius).coerceIn(0, length - 1))]
                val next = pixels[index(n, (i + radius + 1).coerceIn(0, length - 1))]
                a += (next ushr 24 and 0xFF) - (out ushr 24 and 0xFF)
                r += (next shr 16 and 0xFF) - (out shr 16 and 0xFF)
                g += (next shr 8 and 0xFF) - (out shr 8 and 0xFF)
                b += (next and 0xFF) - (out and 0xFF)
            }
            for (i in 0 until length) pixels[index(n, i)] = line[i]
        }
    }
    pass(height, width) { row, x -> row * width + x }
    pass(width, height) { column, y -> y * width + column }
}
