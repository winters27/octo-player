package app.winters.octo.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.composed
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeSourceSelection
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur

// How brightly a glazed surface is lit. Every glazed surface is the same
// material; they differ only in these numbers.
@Immutable
class GlazeLight(
    val ring: Float,
    val inner: Float,
    val spec: Float,
    val specSoft: Float,
    val lift: Float,
) {
    companion object {
        // At rest: a cluster, a selected tab, a control nobody is touching.
        val Rest = GlazeLight(ring = 0.10f, inner = 0.07f, spec = 0.34f, specSoft = 0.14f, lift = 0f)

        // A control under the finger.
        val Lifted = GlazeLight(ring = 0.17f, inner = 0.10f, spec = 0.46f, specSoft = 0.19f, lift = 0.07f)
    }
}

// The film: clear glass with the faintest dim, so whatever is behind (the
// page, or a song's colours) shows through in its own colour without
// glaring when it is bright. The bar can lay a trace of the song's colour
// over it.
val GlazeTint: Color @Composable @ReadOnlyComposable get() = glassFilm(0.16f)

// The film for glass over one smooth field of colour, like the player's
// background: none at all. With no detail behind to blur, a dim reads as a
// darker patch and a wash as a brighter one; bare glass is just its rim.
val GlazeClearFilm = Color.Transparent

// The darker pill that marks the chosen item inside a glaze, like the
// selected tab: a plain shade, with none of the glaze's lighting.
private val GlazeSelectedFill: Color @Composable @ReadOnlyComposable get() = glassFilm(0.72f)

// The specular fades out toward both ends. A rim bright all the way round
// reads as a drawn border; one bright only where light would catch reads as
// a curved surface.
private val SpecularMask = Brush.horizontalGradient(
    listOf(Color.Transparent, Color.Black, Color.Transparent),
)

// The frost the bar and the capsules use, as a CSS blur.
const val GlazeFrost = 4f

// How much the chrome glaze deepens the colours it frosts. Kept low on
// purpose: the glaze is clear glass, and bright artwork behind it should
// not glare.
const val GlazeSaturation = 1.1f

// Whether the window is the one in front. The desktop window gives it; a
// phone's app is always in front. Behind another window the glass loses
// its specular and its lit rim, as glass out of the light does.
val LocalWindowFocused = compositionLocalOf { true }

private fun glazeBlur(frost: Float, saturation: Float, page: Color, card: Color, mask: Brush? = null) = HazeBlurStyle {
    backgroundColor(page)
    blurRadius(backdropBlur(frost))
    noiseFactor(0f)
    colorEffects(listOf(HazeColorEffect.colorFilter(saturation(saturation))))
    fallbackColorEffect(HazeColorEffect.tint(card))
    if (mask != null) mask(mask)
}

// The status bar's frost, as a CSS blur: deep enough that a row's words
// under the clock blur away, where the chrome's lighter frost leaves them
// readable.
const val StatusFrost = 14f

// How far below the status bar its frost fades out.
val StatusFrostFade = 16.dp

// Where the status frost is full and where it fades, as fractions of its
// height: full down to the bar's bottom (`inset`), then gone `fade` below.
fun statusFrostStops(inset: Float, fade: Float): Pair<Float, Float> {
    val tall = inset + fade
    return if (tall <= 0f) 0f to 1f else inset / tall to 1f
}

// The status bar's own glass at the top of every page: what scrolls up under
// the clock is frosted through the bar's whole height, the frost fading out
// just below it, so rows never run through the clock and the page's own glow
// still shows through.
@Composable
fun StatusGlaze(backdrop: HazeState, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val inset = WindowInsets.statusBars.getTop(density).toFloat()
    val fade = with(density) { StatusFrostFade.toPx() }
    val (full, gone) = statusFrostStops(inset, fade)
    val page = OctoColors.Background
    val card = OctoColors.BackgroundTertiary
    val style = remember(page, card, full) {
        glazeBlur(StatusFrost, GlazeSaturation, page, card, mask = Brush.verticalGradient(0f to Color.Black, full to Color.Black, gone to Color.Transparent))
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(with(density) { (inset + fade).toDp() })
            .hazeBlur(input = HazeInput.Backdrop(backdrop), style = style),
    )
}

@Composable
private fun GlazeBlur(): HazeBlurStyle {
    val page = OctoColors.Background
    val card = OctoColors.BackgroundTertiary
    return remember(page, card) { glazeBlur(GlazeFrost, GlazeSaturation, page, card) }
}

private fun saturation(amount: Float) =
    ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(amount) })

// The light lit into the glass's rim: the glass's accent on a dark theme; on
// a light one the theme's edge color, since an accent this faint on a pale
// page reads as a tint of its own (terracotta turns pink).
@Composable
private fun accent(alpha: Float): Color =
    if (LocalOctoSurfaces.current.light) OctoColors.Edge.copy(alpha = alpha) else OctoColors.GlassAccent.copy(alpha = alpha)

// What lies behind a glass when it is a picture the glass can draw itself
// (the player's moving background, already far softer than the glass's
// frost): the glass draws that picture under it, deepened as the frost
// deepens colours, instead of blurring it again on every frame.
interface GlassBackdrop {
    // Whether there is a picture to draw yet.
    val ready: Boolean

    // Draws, under the glass laid out at `glass`, what lies behind it,
    // `saturation` deepening its colours as the frost would.
    fun DrawScope.drawBehind(glass: LayoutCoordinates, saturation: Float)
}

// The backdrop the glasses over the player's background draw from, while
// it is a picture.
val LocalGlassBackdrop = staticCompositionLocalOf<GlassBackdrop?> { null }

// The glaze: a lit glass capsule. Pass a backdrop when it floats over varied
// content, so it frosts what is behind it. Leave it null when it sits on an
// already-frosted or solid surface; blurring that again changes nothing.
// `frost` is how hard the backdrop blurs, as a CSS blur: text behind a menu
// needs more than the page behind the bar. `saturation` is how much the
// frosted colours deepen. `focused` false (a desktop window in the
// background) turns off the specular and the lit rim.
@Composable
fun Glaze(
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    light: GlazeLight = GlazeLight.Rest,
    backdrop: HazeState? = null,
    film: Color = GlazeTint,
    frost: Float = GlazeFrost,
    saturation: Float = GlazeSaturation,
    focused: Boolean = LocalWindowFocused.current,
    // Frost every source on `backdrop`, not only the one behind by default:
    // glass floating over a page that is a source of its own.
    seesAll: Boolean = false,
    // Drawn instead of the frost while it has its picture: see GlassBackdrop.
    picture: GlassBackdrop? = null,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val page = OctoColors.Background
    val card = OctoColors.BackgroundTertiary
    val blur = if (frost == GlazeFrost && saturation == GlazeSaturation) {
        GlazeBlur()
    } else {
        remember(frost, saturation, page, card) { glazeBlur(frost, saturation, page, card) }
    }
    Box(
        modifier
            // A half-pixel dark contour, so the capsule separates from
            // anything bright behind it.
            .dropShadow(shape, Shadow(radius = 0.dp, spread = 0.5.dp, color = shadowColor(0.38f)))
            // A contact line and a small drop: an object resting on the
            // surface, not a hole cut through it.
            .dropShadow(shape, Shadow(radius = 0.dp, color = shadowColor(0.05f), offset = DpOffset(0.dp, 1.dp)))
            .dropShadow(shape, Shadow(radius = shadowBlur(3f), color = shadowColor(0.10f), offset = DpOffset(0.dp, 1.dp))),
        contentAlignment = Alignment.Center,
    ) {
        // Fill and edge.
        Box(
            Modifier
                .matchParentSize()
                .clip(shape)
                .then(
                    when {
                        picture != null && picture.ready -> Modifier.drawsBehind(picture, saturation)
                        backdrop != null -> Modifier.hazeBlur(input = if (seesAll) HazeInput.Sources(backdrop, HazeSourceSelection.All) else HazeInput.Backdrop(backdrop), style = blur)
                        else -> Modifier
                    },
                )
                .background(film)
                // A lifted glaze takes a hint of the accent; on a light theme
                // at most 6%, so a lit disc stays glass.
                .background(accent(if (LocalOctoSurfaces.current.light) light.lift.coerceAtMost(0.06f) else light.lift))
                .then(if (focused) Modifier.litRim(shape, light) else Modifier),
        )
        // Specular: a brighter rim, masked to the middle.
        if (focused) Specular(shape, accent(light.spec), accent(light.specSoft))
        content()
    }
}

// Draws `picture` under the glass, from where the glass is laid out.
private fun Modifier.drawsBehind(picture: GlassBackdrop, saturation: Float): Modifier = composed {
    var glass by remember { mutableStateOf<LayoutCoordinates?>(null) }
    // In a layer of its own, so a new picture redraws only the glass.
    graphicsLayer().onGloballyPositioned { glass = it }.drawBehind {
        val at = glass ?: return@drawBehind
        if (at.isAttached) with(picture) { drawBehind(at, saturation) }
    }
}

// The lit edges of the glaze.
@Composable
private fun Modifier.litRim(shape: Shape, light: GlazeLight): Modifier = this
    // Light along the top edge only.
    .innerShadow(shape, Shadow(radius = 0.dp, color = accent(light.ring), offset = DpOffset(0.dp, 1.dp)))
    // A soft inner glow that gives the edge thickness.
    .innerShadow(shape, Shadow(radius = shadowBlur(6f), spread = 1.dp, color = accent(light.inner)))
    // Light thrown back up along the bottom inside edge. It is most of what
    // stops a translucent shape reading as a hole.
    .innerShadow(shape, Shadow(radius = 0.dp, color = glassRim(0.10f), offset = DpOffset(0.dp, (-0.5).dp)))

// A brighter rim, bright only across the middle of the shape: a curved
// surface catching the light, not a drawn border.
@Composable
internal fun BoxScope.Specular(shape: Shape, sharp: Color, soft: Color, strength: Float = 1f) {
    Box(
        Modifier
            .matchParentSize()
            .graphicsLayer {
                compositingStrategy = CompositingStrategy.Offscreen
                alpha = strength
            }
            .drawWithContent {
                drawContent()
                drawRect(SpecularMask, blendMode = BlendMode.DstIn)
            }
            .innerShadow(shape, Shadow(radius = shadowBlur(1.5f), spread = 1.dp, color = sharp))
            .innerShadow(shape, Shadow(radius = shadowBlur(3f), spread = 1.dp, color = soft)),
    )
}

// A glaze floating over the page, like the bottom bar: the lit glass,
// frosting what scrolls behind it, with a soft shadow pooled underneath.
// The content sits on top; place it with the box's alignment. `halo` gives
// it the menu plate's edges as well, for words over busy content: an even
// dark halo all round and a faint light hairline just inside the rim.
@Composable
fun FloatingGlaze(
    backdrop: HazeState,
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    film: Color = GlazeTint,
    frost: Float = GlazeFrost,
    saturation: Float = GlazeSaturation,
    halo: Boolean = false,
    seesAll: Boolean = false,
    picture: GlassBackdrop? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier) {
        if (halo) {
            Box(Modifier.matchParentSize().dropShadow(shape, Shadow(radius = shadowBlur(20f), color = shadowColor(0.5f))))
        }
        // The shadow is cast by a shape 12dp smaller on every side, so it
        // pools under the glass instead of haloing around it.
        Box(
            Modifier
                .matchParentSize()
                .padding(12.dp)
                .dropShadow(
                    shape,
                    Shadow(radius = shadowBlur(24f), color = shadowColor(0.45f), offset = DpOffset(0.dp, 8.dp)),
                ),
        )
        // On a light theme a plate's edge is the theme's hairline alone, with
        // no accent-lit rim.
        val plain = halo && LocalOctoSurfaces.current.light
        Glaze(
            Modifier.matchParentSize(), shape = shape, backdrop = backdrop, film = film, frost = frost, saturation = saturation, seesAll = seesAll, picture = picture,
            focused = LocalWindowFocused.current && !plain,
        )
        if (halo) {
            Box(Modifier.matchParentSize().innerShadow(shape, Shadow(radius = 0.dp, spread = 1.dp, color = if (plain) OctoColors.Edge else glassRim(0.06f))))
        }
        content()
    }
}

// The chosen item inside a glaze: a darker pill set into the glass, unlit,
// so only the glaze around it carries the light. On a light theme the
// theme's selected row color with an edge of the accent, as a chosen row in
// a list shows; words on it read through SelectedFill.
@Composable
fun GlazeSelected(modifier: Modifier = Modifier, shape: Shape = CircleShape) {
    if (LocalOctoSurfaces.current.light) {
        Box(modifier.clip(shape).background(OctoColors.AccentSelected).border(1.dp, OctoColors.Accent.copy(alpha = 0.25f), shape))
    } else {
        Box(modifier.clip(shape).background(GlazeSelectedFill))
    }
}

// A chosen row in a list: the same darker pill, lightly tinted with the
// accent, with a light line along its top and a shade along its bottom, so
// white words read on it.
@Composable
fun SelectedRow(modifier: Modifier = Modifier, shape: Shape = OctoShapes.Row) {
    Box(
        modifier
            .clip(shape)
            .background(OctoColors.AccentSelected)
            .innerShadow(shape, Shadow(radius = 0.dp, color = glassRim(0.28f), offset = DpOffset(0.dp, 1.dp)))
            .innerShadow(shape, Shadow(radius = 0.dp, color = glassRim(0.12f), offset = DpOffset(0.dp, 2.dp)))
            .innerShadow(shape, Shadow(radius = 0.dp, color = shadowColor(0.14f), offset = DpOffset(0.dp, (-1).dp))),
    )
}
