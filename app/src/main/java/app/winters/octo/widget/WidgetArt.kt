package app.winters.octo.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.graphics.createBitmap
import app.winters.octo.playback.artworkBitmap

// A widget's picture travels to the launcher inside the update, which has a
// size limit, so it is never more than this many pixels across.
const val WIDGET_ART_PX = 300

// The song's cover as a small square with rounded corners, ready for a
// widget. Null when there is none. Blocks, so never call it on the main
// thread.
fun widgetArt(context: Context, ref: String?): Bitmap? =
    artworkBitmap(context, ref, WIDGET_ART_PX)?.let(::roundedSquare)

// Crops the middle square out of a picture, scales it down to at most
// WIDGET_ART_PX, and rounds its corners, since a widget cannot clip a picture
// on older phones.
private fun roundedSquare(source: Bitmap): Bitmap {
    val side = minOf(source.width, source.height)
    val px = minOf(side, WIDGET_ART_PX)
    val scale = px.toFloat() / side
    val matrix = Matrix().apply {
        setTranslate(-(source.width - side) / 2f, -(source.height - side) / 2f)
        postScale(scale, scale)
    }
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(matrix) }
    }
    val out = createBitmap(px, px)
    val radius = px * 0.1f
    Canvas(out).drawRoundRect(RectF(0f, 0f, px.toFloat(), px.toFloat()), radius, radius, paint)
    return out
}
