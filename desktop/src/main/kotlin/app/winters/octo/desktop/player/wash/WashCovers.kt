package app.winters.octo.desktop.player.wash

import app.winters.octo.player.immersive.WashSize
import app.winters.octo.player.immersive.WashTuning
import app.winters.octo.player.immersive.applyColorMatrix
import app.winters.octo.player.immersive.capBrightness
import app.winters.octo.player.immersive.washColorMatrix
import app.winters.octo.player.immersive.WashRange
import app.winters.octo.player.immersive.washRange
import app.winters.octo.player.immersive.washPeak
import app.winters.octo.subsonic.SubsonicClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect

// A cover made ready for the background: the 512 square the wash draws
// from, the darkest and brightest patches of it (`washRange`), from which
// the words over it take their colour, its main colour as ARGB, which
// tints the app's glass while it plays, and the brightest the wash over it
// gets (`washPeak`), for words on the pages.
class WashCover(
    val key: String,
    val square: Image,
    val range: WashRange,
    val main: Int = 0,
    val peak: Int = 0xFF000000.toInt(),
)

// The pixels of a 512 square, as ARGB, and back.
private val SquareInfo = ImageInfo(WashSize, WashSize, ColorType.BGRA_8888, ColorAlphaType.PREMUL)

// Prepares covers for the background off the main thread, as the phone
// does: cropped to the middle square and brought to 512, then contrast and
// saturation through the colour matrix and the brightness cap, pixel by
// pixel, with the shared maths. The last few are kept, so going back a
// song is instant.
class WashCovers(private val http: OkHttpClient) {
    private val kept = object : LinkedHashMap<String, WashCover>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, WashCover>?) = size > 4
    }

    suspend fun prepare(client: SubsonicClient?, coverId: String?, tuning: WashTuning): WashCover {
        val key = "${client?.primaryUrl?.host}|$coverId|${tuning.contrast}|${tuning.saturation}|${tuning.brightnessCap}"
        synchronized(kept) { kept[key] }?.let { return it }
        val bytes = if (client != null && coverId != null) {
            withContext(Dispatchers.IO) {
                runCatching {
                    http.newCall(Request.Builder().url(client.coverArtUrl(coverId, 600)).build()).execute().use { r ->
                        if (r.isSuccessful) r.body.bytes() else null
                    }
                }.getOrNull()
            }
        } else {
            null
        }
        val cover = withContext(Dispatchers.Default) { prepared(key, bytes?.let { runCatching { Image.makeFromEncoded(it) }.getOrNull() }, tuning) }
        synchronized(kept) { kept[key] = cover }
        return cover
    }

    companion object {
        // A cover image through every step; with none, a soft dark blend.
        fun prepared(key: String, source: Image?, tuning: WashTuning): WashCover {
            val bitmap = Bitmap()
            bitmap.allocPixels(SquareInfo)
            val canvas = Canvas(bitmap)
            if (source != null) {
                val side = minOf(source.width, source.height).toFloat()
                val crop = Rect.makeXYWH((source.width - side) / 2f, (source.height - side) / 2f, side, side)
                canvas.drawImageRect(
                    source,
                    crop,
                    Rect.makeWH(WashSize.toFloat(), WashSize.toFloat()),
                    FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR),
                    Paint(),
                    true,
                )
            }
            val bytes = bitmap.readPixels(SquareInfo, WashSize * 4, 0, 0) ?: ByteArray(WashSize * WashSize * 4)
            val pixels = if (source == null) {
                blend()
            } else {
                IntArray(WashSize * WashSize) { i ->
                    val o = i * 4
                    // BGRA in memory.
                    (0xFF shl 24) or ((bytes[o + 2].toInt() and 0xFF) shl 16) or ((bytes[o + 1].toInt() and 0xFF) shl 8) or (bytes[o].toInt() and 0xFF)
                }
            }
            val dominant = dominantColour(pixels)
            val matrix = washColorMatrix(tuning)
            for (i in pixels.indices) pixels[i] = capBrightness(applyColorMatrix(pixels[i], matrix), tuning.brightnessCap)
            for (i in pixels.indices) {
                val p = pixels[i]
                val o = i * 4
                bytes[o] = (p and 0xFF).toByte()
                bytes[o + 1] = (p shr 8 and 0xFF).toByte()
                bytes[o + 2] = (p shr 16 and 0xFF).toByte()
                bytes[o + 3] = 0xFF.toByte()
            }
            val out = Bitmap()
            out.allocPixels(SquareInfo)
            out.installPixels(SquareInfo, bytes, WashSize * 4)
            out.setImmutable()
            return WashCover(key, Image.makeFromBitmap(out), washRange(pixels, WashSize), dominant, washPeak(pixels))
        }

        // For music without a cover: three quiet colours, blended corner to
        // corner.
        private fun blend(): IntArray {
            val stops = intArrayOf(0xFF2A2F45.toInt(), 0xFF3C2533.toInt(), 0xFF1C3A3A.toInt())
            fun mix(a: Int, b: Int, t: Float): Int {
                fun ch(shift: Int) = ((a shr shift and 0xFF) + ((b shr shift and 0xFF) - (a shr shift and 0xFF)) * t).toInt()
                return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
            }
            return IntArray(WashSize * WashSize) { i ->
                val t = ((i % WashSize) + (i / WashSize)) / (2f * (WashSize - 1))
                if (t < 0.5f) mix(stops[0], stops[1], t * 2) else mix(stops[1], stops[2], t * 2 - 1)
            }
        }

        // The cover's main colour, as a palette's dominant swatch picks it
        // (shared with the playlist covers).
        fun dominantColour(pixels: IntArray): Int = app.winters.octo.covers.dominantColour(pixels)
    }
}
