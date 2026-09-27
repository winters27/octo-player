package app.winters.octo.player.immersive

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// The size, in pixels, of the square the background is drawn at before it
// is blurred and stretched over the screen.
const val WashSize = 512

// One copy of the cover in the layered composite. Its offset from the
// centre is x0 + xAmp sin(xSpeed t + xPhase) across and y0 + yAmp
// cos(ySpeed t + yPhase) down; its turn is spin R + tilt + wobble
// sin(wobbleSpeed t + wobblePhase). Copies that breathe are magnified by
// the breathing scale too.
data class WashCopy(
    val name: String,
    val opacity: Float,
    val zoom: Float,
    val breathes: Boolean,
    val x0: Float = 0f,
    val xAmp: Float = 0f,
    val xSpeed: Float = 0f,
    val xPhase: Float = 0f,
    val y0: Float = 0f,
    val yAmp: Float = 0f,
    val ySpeed: Float = 0f,
    val yPhase: Float = 0f,
    val spin: Float = 0f,
    val tilt: Float = 0f,
    val wobble: Float = 0f,
    val wobbleSpeed: Float = 0f,
    val wobblePhase: Float = 0f,
) {
    fun offsetX(t: Float): Float = x0 + xAmp * sin(xSpeed * t + xPhase)
    fun offsetY(t: Float): Float = y0 + yAmp * cos(ySpeed * t + yPhase)
    fun angle(t: Float, r: Float): Float = spin * r + tilt + wobble * sin(wobbleSpeed * t + wobblePhase)
    fun magnification(b: Float): Float = if (breathes) zoom * b else zoom
}

// The parallax copies step round by the golden angle, so the three never
// line up.
const val GoldenAngle = 2.39996f

// How fast the copies float and how much they wobble as they turn.
private const val FloatSpeed = 0.5f
private const val Wobble = 0.02f

// The eight copies, drawn in this order, each over the result so far at its
// opacity, starting from black.
val WashCopies: List<WashCopy> = listOf(
    WashCopy("Base", opacity = 1.0f, zoom = 3.0f, breathes = false),
    WashCopy(
        "Parallax 1", opacity = 0.60f, zoom = 3.0f, breathes = true,
        x0 = 0.10f, xAmp = 0.08f, xSpeed = 0.25f, y0 = 0.10f, yAmp = 0.06f, ySpeed = 0.25f,
        spin = 0.5f,
    ),
    WashCopy(
        "Parallax 2", opacity = 0.30f, zoom = 3.8f, breathes = true,
        x0 = 0.25f, xAmp = 0.08f, xSpeed = 0.25f, xPhase = GoldenAngle,
        y0 = 0.25f, yAmp = 0.06f, ySpeed = 0.25f, yPhase = GoldenAngle,
        spin = 0.5f, tilt = 0.15f,
    ),
    WashCopy(
        "Parallax 3", opacity = 0.20f, zoom = 4.6f, breathes = true,
        x0 = 0.40f, xAmp = 0.08f, xSpeed = 0.25f, xPhase = 2 * GoldenAngle,
        y0 = 0.40f, yAmp = 0.06f, ySpeed = 0.25f, yPhase = 2 * GoldenAngle,
        spin = 0.5f, tilt = 0.30f,
    ),
    WashCopy(
        "Primary", opacity = 0.85f, zoom = 2.4f, breathes = true,
        xAmp = 0.05f, xSpeed = FloatSpeed, yAmp = 0.04f, ySpeed = FloatSpeed,
        spin = 1f, wobble = Wobble, wobbleSpeed = FloatSpeed,
    ),
    WashCopy(
        "Secondary", opacity = 0.70f, zoom = 2.0f, breathes = true,
        x0 = 0.30f, xAmp = 0.06f, xSpeed = FloatSpeed, xPhase = 1f,
        y0 = 0.30f, yAmp = 0.05f, ySpeed = FloatSpeed, yPhase = 1f,
        spin = -0.5f, wobble = Wobble, wobbleSpeed = 0.7f, wobblePhase = 1f,
    ),
    WashCopy(
        "Tertiary", opacity = 0.55f, zoom = 1.8f, breathes = true,
        x0 = -0.20f, xAmp = 0.07f, xSpeed = 0.4f,
        y0 = 0.40f, yAmp = 0.05f, ySpeed = 0.3f,
        spin = 0.8f, wobble = Wobble, wobbleSpeed = 0.8f, wobblePhase = 2f,
    ),
    WashCopy(
        "Fourth", opacity = 0.40f, zoom = 2.2f, breathes = true,
        x0 = 0.40f, xAmp = 0.08f, xSpeed = 0.35f, xPhase = 3f,
        y0 = -0.10f, yAmp = 0.06f, ySpeed = 0.25f, yPhase = 3f,
        spin = 1.5f, wobble = Wobble, wobbleSpeed = 0.6f, wobblePhase = 3f,
    ),
)

// Where one copy samples the cover, per frame: a 2 x 2 matrix taking a
// point (relative to the centre, after the offset) into the cover's space,
// which holds both the zoom and the turn, and the offset itself.
fun copyPlacement(copy: WashCopy, t: Float, r: Float, b: Float, out: FloatArray, at: Int) {
    val scale = 1f / copy.magnification(b)
    val a = copy.angle(t, r)
    val c = cos(a) * scale
    val s = sin(a) * scale
    out[at] = c
    out[at + 1] = -s
    out[at + 2] = s
    out[at + 3] = c
    out[at + 4] = copy.offsetX(t)
    out[at + 5] = copy.offsetY(t)
}

// Where a point of the square samples one copy's cover, both 0 to 1 across
// the square (the cover repeats outside that range): the offset first, then
// the zoom and turn, all about the centre.
fun copySample(copy: WashCopy, t: Float, r: Float, b: Float, u: Float, v: Float): Pair<Float, Float> {
    val m = FloatArray(6)
    copyPlacement(copy, t, r, b, m, 0)
    val px = u - 0.5f + m[4]
    val py = v - 0.5f + m[5]
    return (m[0] * px + m[1] * py + 0.5f) to (m[2] * px + m[3] * py + 0.5f)
}

// The warp: three upright dividers at a quarter, a half and three quarters
// across swing sideways on sine waves that change down the picture, and
// the four bands between them stretch or squeeze to fill.
val WarpRest = floatArrayOf(0.25f, 0.5f, 0.75f)
const val WarpAmplitude = 60f / 512f * 1.5f
val WarpScales = floatArrayOf(0.35f * 0.05f, 0.35f * 0.03f, 0.35f * 0.06f)
const val WarpMinBand = 0.01f

// Each divider swings on the shared warp phase a third of a turn apart
// from the next, so they never move in step.
val WarpPhaseSteps = floatArrayOf(0f, (2 * PI / 3).toFloat(), (4 * PI / 3).toFloat())

// Where the dividers are on one row (y in pixels down the 512 square),
// kept in order with every band at least 0.01 wide.
fun warpDividers(yPx: Float, r: Float, phase: Float, out: FloatArray = FloatArray(3)): FloatArray {
    for (k in 0..2) {
        out[k] = WarpRest[k] + WarpAmplitude * sin(phase + WarpPhaseSteps[k] + yPx * 0.1f * r * WarpScales[k] * 10f)
    }
    // Left to right, each at least a band past the one before; then back
    // from the right edge, in case the last was pushed too far.
    out[0] = out[0].coerceIn(WarpMinBand, 1f - 3 * WarpMinBand)
    out[1] = out[1].coerceAtLeast(out[0] + WarpMinBand)
    out[2] = out[2].coerceAtLeast(out[1] + WarpMinBand).coerceAtMost(1f - WarpMinBand)
    out[1] = out[1].coerceAtMost(out[2] - WarpMinBand)
    out[0] = out[0].coerceAtMost(out[1] - WarpMinBand)
    return out
}

// The piecewise-linear remap: a point in the band between two moved
// dividers samples the matching quarter of the picture.
fun warpX(x: Float, dividers: FloatArray): Float {
    val left: Float
    val right: Float
    val band: Int
    when {
        x < dividers[0] -> { left = 0f; right = dividers[0]; band = 0 }
        x < dividers[1] -> { left = dividers[0]; right = dividers[1]; band = 1 }
        x < dividers[2] -> { left = dividers[1]; right = dividers[2]; band = 2 }
        else -> { left = dividers[2]; right = 1f; band = 3 }
    }
    return (band + (x - left) / (right - left)) * 0.25f
}
