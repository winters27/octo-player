package app.winters.octo.player.immersive

import java.util.Locale

// The blur behind the background, as its own maths gives it: a 9-tap
// weighting from the centre out, taps 0.03 x strength texels apart across
// and 0.04 x strength down, run across and down twice.
val BlurTaps = floatArrayOf(0.1027f, 0.0984f, 0.0867f, 0.0702f, 0.0523f, 0.0358f, 0.0225f, 0.0130f, 0.0069f)
const val BlurStrength = 30f
const val BlurStepAcross = 0.03f * BlurStrength
const val BlurStepDown = 0.04f * BlurStrength

// Softer than the taps alone give: on a phone the wash read as too sharp, so
// the platform blur is widened by this much.
const val WashBlurBoost = 2.5f

// The taps add up to a little under 1, so each of the four passes dims the
// picture by that much.
val BlurPassGain: Float get() = BlurTaps[0] + 2f * BlurTaps.drop(1).sum()

// The spread (standard deviation, in taps) of the bell curve the weights
// follow, from the fall-off between the centre and the last tap.
val BlurSigmaTaps: Float get() = kotlin.math.sqrt(64f / (2f * kotlin.math.ln(BlurTaps[0] / BlurTaps[8])))

// The whole blur's spread in texels of the 512 square: one pass's spread
// times the tap spacing, and two passes add up as the square root of two.
fun blurSigmaTexels(step: Float): Float = BlurSigmaTaps * step * kotlin.math.sqrt(2f)

// The radius to ask the platform's blur for, for a spread: it turns a
// radius r into a spread of 0.57735 r + 0.5.
fun blurRadiusFor(sigma: Float): Float = ((sigma - 0.5f) / 0.57735f).coerceAtLeast(0f)

// The final pass brightens by 1.5. It also carries the four blur passes'
// dimming, which the platform's blur (whose weights add to exactly 1)
// leaves out.
val FinalGain: Float get() = 1.5f * BlurPassGain.let { it * it * it * it }

private fun f(value: Float): String = String.format(Locale.ROOT, "%.5f", value)

// The layered composite with the warp, drawn over the 512 square. The
// cover's old and new pictures repeat at their edges; each copy samples
// both and mixes them by the fade. Each copy's placement comes in per
// frame as a 2 x 2 matrix (zoom and turn) and an offset; the opacities are
// written in from the copies table.
val CompositeShader: String = buildString {
    appendLine(
        """
        uniform shader oldCover;
        uniform shader newCover;
        uniform float coverSize;
        uniform float size;
        uniform float rotation;
        uniform float warp;
        uniform float fade;
        """.trimIndent(),
    )
    WashCopies.indices.forEach { appendLine("uniform float4 m$it;\nuniform float2 o$it;") }
    appendLine(
        """
        half3 cover(float2 uv) {
            float2 p = uv * coverSize;
            return mix(oldCover.eval(p).rgb, newCover.eval(p).rgb, half(fade));
        }

        half3 copy(float2 uv, float4 m, float2 o) {
            float2 p = uv - 0.5 + o;
            return cover(float2(m.x * p.x + m.y * p.y, m.z * p.x + m.w * p.y) + 0.5);
        }

        float divider(float rest, float lag, float scale, float y) {
            return rest + ${f(WarpAmplitude)} * sin(warp + lag + y * 0.1 * rotation * scale * 10.0);
        }

        float warpX(float x, float y) {
            float d0 = divider(${f(WarpRest[0])}, ${f(WarpPhaseSteps[0])}, ${f(WarpScales[0])}, y);
            float d1 = divider(${f(WarpRest[1])}, ${f(WarpPhaseSteps[1])}, ${f(WarpScales[1])}, y);
            float d2 = divider(${f(WarpRest[2])}, ${f(WarpPhaseSteps[2])}, ${f(WarpScales[2])}, y);
            float band = ${f(WarpMinBand)};
            d0 = clamp(d0, band, 1.0 - 3.0 * band);
            d1 = max(d1, d0 + band);
            d2 = min(max(d2, d1 + band), 1.0 - band);
            d1 = min(d1, d2 - band);
            d0 = min(d0, d1 - band);
            if (x < d0) return (x / d0) * 0.25;
            if (x < d1) return (1.0 + (x - d0) / (d1 - d0)) * 0.25;
            if (x < d2) return (2.0 + (x - d1) / (d2 - d1)) * 0.25;
            return (3.0 + (x - d2) / (1.0 - d2)) * 0.25;
        }

        half4 main(float2 coord) {
            float2 uv = coord / size;
            uv.x = warpX(uv.x, uv.y * ${WashSize}.0);
            half3 c = half3(0.0);
        """.trimIndent(),
    )
    WashCopies.forEachIndexed { i, copy ->
        appendLine("    c = mix(c, copy(uv, m$i, o$i), ${f(copy.opacity)});")
    }
    appendLine("    return half4(c, 1.0);\n}")
}

// The last pass, over the whole screen: the blurred, turned and magnified
// wash brightened, with a new speck of noise each frame (plus or minus
// 1/255, triangular: the sum of two even random values) so the dark
// gradients never band.
val FinalShader: String = """
    uniform shader wash;
    uniform float gain;
    uniform float seed;

    float grain(float2 p) {
        return fract(sin(dot(p, float2(12.9898, 78.233))) * 43758.5453);
    }

    half4 main(float2 coord) {
        half3 c = wash.eval(coord).rgb * half(gain);
        float n = grain(coord + seed) + grain(coord.yx + seed * 1.61803 + 17.0) - 1.0;
        c += half(n / 255.0);
        return half4(clamp(c, half3(0.0), half3(1.0)), 1.0);
    }
""".trimIndent()
