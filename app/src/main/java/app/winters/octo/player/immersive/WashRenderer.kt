package app.winters.octo.player.immersive

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import kotlin.random.Random

// Draws the moving background, one frame at a time, in three steps:
//  1. the composite with its warp, into a 512 square,
//  2. that square blurred, by a blur set on it once,
//  3. the square turned and magnified over the screen, then brightened
//     with a speck of noise by the final shader.
// Everything runs on the GPU; per frame the work on this side is setting a
// few numbers and re-recording two one-line drawings.
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class WashRenderer {
    private val composite = RuntimeShader(CompositeShader)
    private val final = RuntimeShader(FinalShader)
    private val paint = Paint().apply { shader = composite }
    private val square = RenderNode("wash square").apply {
        setPosition(0, 0, WashSize, WashSize)
        setRenderEffect(
            RenderEffect.createBlurEffect(
                blurRadiusFor(blurSigmaTexels(BlurStepAcross)),
                blurRadiusFor(blurSigmaTexels(BlurStepDown)),
                Shader.TileMode.MIRROR,
            ),
        )
    }
    private val screen = RenderNode("wash screen")
    private val placement = FloatArray(6 * WashCopies.size)
    private val matrix = Matrix()
    private var oldCover: Bitmap? = null
    private var newCover: Bitmap? = null

    init {
        composite.setFloatUniform("coverSize", WashSize.toFloat())
        composite.setFloatUniform("size", WashSize.toFloat())
        final.setFloatUniform("gain", FinalGain)
    }

    // The two covers the copies fade between. Both are 512 squares that
    // repeat at their edges.
    fun setCovers(old: Bitmap, new: Bitmap) {
        if (old !== oldCover) composite.setInputShader("oldCover", coverShader(old))
        if (new !== newCover) composite.setInputShader("newCover", coverShader(new))
        oldCover = old
        newCover = new
    }

    // Draws one frame over a width x height screen. `zoom` scales the final
    // magnification, for the way in and out.
    fun draw(canvas: Canvas, width: Int, height: Int, motion: WashMotion, fade: Float, zoom: Float, alpha: Float): Boolean {
        if (newCover == null || width <= 0 || height <= 0 || !canvas.isHardwareAccelerated) return false

        // Step 1 and 2: the composite, into the blurred square.
        val b = motion.breathing
        WashCopies.forEachIndexed { i, copy -> copyPlacement(copy, motion.time, motion.rotation, b, placement, i * 6) }
        for (i in WashCopies.indices) {
            val at = i * 6
            composite.setFloatUniform("m$i", placement[at], placement[at + 1], placement[at + 2], placement[at + 3])
            composite.setFloatUniform("o$i", placement[at + 4], placement[at + 5])
        }
        composite.setFloatUniform("rotation", motion.rotation)
        composite.setFloatUniform("warp", motion.warpPhase)
        composite.setFloatUniform("fade", fade)
        val squareCanvas = square.beginRecording()
        squareCanvas.drawRect(0f, 0f, WashSize.toFloat(), WashSize.toFloat(), paint)
        square.endRecording()

        // Step 3: the square stretched over the screen, magnified 2b about
        // its centre and turned in its own space, then the final shader.
        if (screen.width != width || screen.height != height) screen.setPosition(0, 0, width, height)
        val magnify = 2f * b * zoom
        matrix.reset()
        matrix.postTranslate(-WashSize / 2f, -WashSize / 2f)
        matrix.postRotate(Math.toDegrees(motion.turn.toDouble()).toFloat())
        matrix.postScale(magnify * width / WashSize, magnify * height / WashSize)
        matrix.postTranslate(width / 2f, height / 2f)
        val screenCanvas = screen.beginRecording()
        screenCanvas.concat(matrix)
        screenCanvas.drawRenderNode(square)
        screen.endRecording()
        final.setFloatUniform("seed", Random.nextFloat() * 64f)
        screen.setRenderEffect(RenderEffect.createRuntimeShaderEffect(final, "wash"))
        screen.setAlpha(alpha)
        canvas.drawRenderNode(screen)
        return true
    }

    // Lets go of the drawings' memory once the background is gone.
    fun release() {
        square.discardDisplayList()
        screen.discardDisplayList()
    }

    private fun coverShader(bitmap: Bitmap) = BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply {
        filterMode = BitmapShader.FILTER_MODE_LINEAR
    }
}
