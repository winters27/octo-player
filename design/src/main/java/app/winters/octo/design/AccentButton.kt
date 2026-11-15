package app.winters.octo.design

import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import app.winters.octo.theme.alphaArgb
import app.winters.octo.theme.contrastOn
import app.winters.octo.theme.overArgb

// The filled button's color: the accent in the form the theme gives it, so
// the words on it stay readable.
val AccentFill: Color @Composable @ReadOnlyComposable get() = LocalOctoAccent.current.fill

// The one filled button on a surface. Its words are black or white,
// whichever reads on `fill`; pass `OctoColors.AccentTonal` for the quieter
// tonal button. Pressed it shrinks and darkens, under the pointer it rises
// and lightens, and the keyboard's focus rings it in the accent. Not
// `enabled`, it is drawn off (OffButtonLook), the same way on every theme;
// `loading` keeps its fill under the spinner, since it is at work.
@Composable
fun AccentButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    size: ButtonSize = ButtonSize.Large,
    fill: Color = AccentFill,
    icon: Painter? = null,
    dense: Boolean = false,
    interactionSource: MutableInteractionSource? = null,
) {
    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    val look = rememberButtonLook(interaction)
    val surfaces = LocalOctoSurfaces.current
    val offLook = remember(surfaces) { offButtonLook(surfaces) }
    val off = !enabled && !loading
    val words = when {
        off -> offLook.words
        fill == AccentFill -> OctoColors.OnAccentFill
        else -> contentColorFor(fill)
    }
    GlazeInset(
        fill = if (off) offLook.fill else fill,
        shape = CircleShape,
        modifier = modifier
            .height(if (dense) size.dense else size.height)
            .buttonMotion(look, CircleShape, OctoColors.FocusRing)
            .hoverable(interaction, enabled = enabled && !loading)
            .pointerHoverIcon(if (enabled && !loading) PointerIcon.Hand else PointerIcon.Default)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled && !loading,
                role = Role.Button,
                onClick = onClick,
            ),
        flat = off,
    ) {
        ButtonShades(look, CircleShape)
        ButtonLabel(text, words, size, icon = icon, loading = loading)
    }
}

// A filled button that cannot be used yet, alike on every theme so it never
// reads as ready: a flat pill of the theme's own ink at a tenth over whatever
// it sits on, and its words in that ink at the least strength that still
// reads 3:1 on the pill over each of the theme's surfaces (page, card, raised
// and sheet). Where the ready button carries the accent and stands well off
// its surface with words at 4.5:1 or more, the off one is neutral, a faint
// step off its surface, and its words a step quieter than any that can be
// used.
@Immutable
class OffButtonLook(val fill: Color, val words: Color)

fun offButtonLook(surfaces: OctoSurfaces): OffButtonLook {
    val ink = surfaces.ink.toArgb()
    val fill = alphaArgb(ink, OFF_FILL)
    val page = surfaces.page.toArgb()
    val layers = listOf(surfaces.page, surfaces.card, surfaces.raised, surfaces.sheet).map { overArgb(it.toArgb(), page) }
    val strength = (OFF_WORDS_LEAST..1000 step 10).firstOrNull { words ->
        layers.all { layer -> contrastOn(alphaArgb(ink, words), overArgb(fill, layer)) >= OFF_WORDS_CONTRAST }
    } ?: 1000
    return OffButtonLook(Color(fill), Color(alphaArgb(ink, strength)))
}

// In thousandths of the ink: the off pill's share, and the least its words
// may take.
private const val OFF_FILL = 100
private const val OFF_WORDS_LEAST = 300

// What an off button's words read at on their pill: 3:1, with a little room
// for a glass that lets more light through than the theme's surfaces do.
private const val OFF_WORDS_CONTRAST = 3.2
